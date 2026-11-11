package com.fadcam.media;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.media.MediaCodec;
import android.media.MediaFormat;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.container.Mp4OrientationData;
import androidx.media3.common.util.Consumer;
import androidx.media3.muxer.FragmentedMp4Muxer;
import androidx.media3.muxer.MuxerException;
import androidx.media3.muxer.ProcessedSegment;

import com.fadcam.streaming.RemoteStreamManager;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Wrapper around Media3's FragmentedMp4Muxer that provides a MediaMuxer-compatible API.
 * Fragmented MP4 files are crash-safe because they write data incrementally with moov fragments,
 * unlike regular MP4 which writes the moov atom at the end.
 */
@OptIn(markerClass = UnstableApi.class)
public class FragmentedMp4MuxerWrapper {

    private static final String TAG = "FragmentedMp4MuxerWrap";

    private final FragmentedMp4Muxer muxer;
    // Non-final: hot-swapped to a fresh stream if the FUSE fd dies mid-recording (#332).
    private FileOutputStream fileOutputStream;
    private final Object muxerLock = new Object();
    // Guards writer-thread callbacks vs release()'s stream close. WITHOUT this, a
    // handleProcessedSegment() that passed the `released` check can race release()
    // closing the stream — writes hit an invalid fd and are silently DROPPED, which
    // is exactly the "FileDescriptor is INVALID — skipping write" flood and the
    // 0-byte split segments on some devices (Samsung SAF especially).
    private final Object ioLock = new Object();
    private boolean started = false;
    private boolean released = false;
    private int orientationHint = 0;
    
    // Track indices
    private int videoTrackIndex = -1;
    private int audioTrackIndex = -1;
    // Writer callback records validated payloads; read after closeWriter joins the writer.
    private boolean processedVideoSamples;
    private boolean processedAudioSamples;
    
    // Expected track count for choosing a common fragment origin
    private int trackCount = 0;
    
    // Fragment tracking for live streaming
    // CRITICAL VLC FIX: Start from 0, not 1 (VLC expects 0-based fragment sequence numbers)
    private int nextFragmentNumber = 0;
    private boolean initSegmentSent = false;
    
    // Each encoder supplies timestamps on the same capture clock. The final Media3 moov
    // computes edit lists from that clock; only the fragment writer needs the missing origin.
    private final java.util.concurrent.ConcurrentHashMap<Integer, Long> firstTrackPtsUs =
            new java.util.concurrent.ConcurrentHashMap<>();
    private long fragmentLowerBoundUs;
    private long fragmentOriginUs = -1; // writer thread only

    // Cached streaming manager reference to avoid repeated getInstance() calls
    private RemoteStreamManager cachedStreamManager;
    private boolean streamManagerChecked = false;

    // --- mvhd duration patching ---
    // Gallery apps (Instagram, etc.) read mvhd.duration from the moov atom
    // at the beginning of the file.  fMP4 spec sets this to 0 because the
    // duration is determined by fragments, but consumer apps don't parse
    // fragments — they see 0 and show 00:00 or refuse to share.
    // We track the file byte offset of the 4-byte mvhd.duration field
    // and patch it with the correct value after recording completes.
    private byte[] initSegmentData = null;
    private long initSegmentFilePosition = -1;
    private long cumulativeDurationUs = 0;
    // Diagnostic: where the output lives (null for pure-fd/SAF constructions).
    private String outputPath;

    // Hybrid MP4 finalization: track fragment positions and per-track sample
    // counts so we can build correct stco/stsc entries in the final moov.
    private final java.util.List<Long> fragmentPositions = new java.util.ArrayList<>();
    private final java.util.List<Integer> fragmentAudioCounts = new java.util.ArrayList<>();
    private final java.util.List<Integer> fragmentVideoCounts = new java.util.ArrayList<>();
    private final java.util.List<Integer> fragmentAudioOffsets = new java.util.ArrayList<>();
    private final java.util.List<Integer> fragmentVideoOffsets = new java.util.ArrayList<>();

    /** Last hybrid-finalization outcome (also reported via ungated critical log). */
    private String lastFinalizeOutcome = "never ran";

    /** Bounded AVC corruption tracing (first fragment only, non-flooding). */
    private long committedOutputEnd;
    private android.system.StructStat outputIdentity;
    private volatile IOException outputFailure;
    private boolean writerClosed;

    private static android.system.StructStat stat(FileOutputStream stream) {
        try { return android.system.Os.fstat(stream.getFD()); }
        catch (Exception failure) { return null; }
    }

    private boolean ownsOutput(FileOutputStream stream) {
        try { return ownsOutput(stream.getFD()); }
        catch (IOException failure) { return false; }
    }

    private boolean ownsOutput(FileDescriptor fd) {
        android.system.StructStat candidate;
        try { candidate = android.system.Os.fstat(fd); }
        catch (Exception failure) { return false; }
        return outputIdentity != null && candidate != null && outputIdentity.st_ino != 0
                && candidate.st_ino == outputIdentity.st_ino
                && candidate.st_dev == outputIdentity.st_dev;
    }

    private void closeWriter() throws MuxerException {
        if (!writerClosed) {
            writerClosed = true;
            muxer.close();
        }
    }

    private boolean firstMediaFragmentLogged = false;

    /**
     * Creates a FragmentedMp4MuxerWrapper with a file path.
     *
     * @param path The path to the output file.
     * @throws IOException If the file cannot be created.
     */
    public FragmentedMp4MuxerWrapper(@NonNull String path) throws IOException {
        this.fileOutputStream = new FileOutputStream(path);
        outputIdentity = stat(fileOutputStream);
        this.outputPath = path;
        
        // Create callback consumer for live streaming integration
        Consumer<ProcessedSegment> segmentConsumer = segment -> {
            handleProcessedSegment(segment);
        };
        
        // CRITICAL FIX for fMP4 seeking (GitHub issue #6704):
        // 1. Use 2000ms (2 second) fragments for stable live streaming
        // 2. Fragments will be automatically keyframe-aligned by Media3
        // 3. This ensures proper tfdt timestamps and fragment boundaries
        // 4. NEW: Callback-based architecture for real-time streaming
        // 5. 2-second segments reduce upload frequency by 50%, improving stability on slow networks
        this.muxer = new FragmentedMp4Muxer.Builder(segmentConsumer)
                .setFragmentDurationMs(2000) // 2 seconds per fragment for stable streaming
                .build();
    }

    /**
     * Creates a FragmentedMp4MuxerWrapper with a FileDescriptor.
     *
     * @param fd The FileDescriptor for the output file.
     * @throws IOException If the output stream cannot be created.
     */
    public FragmentedMp4MuxerWrapper(@NonNull FileDescriptor fd) throws IOException {
        this.fileOutputStream = new FileOutputStream(fd);
        outputIdentity = stat(fileOutputStream);
        // Verify the fd is valid immediately after wrapping it. If this logs a warning, the
        // ParcelFileDescriptor was already invalid when the new muxer was constructed, which
        // means the old PFD was closed too late (or the SAF provider returned a bad fd).
        FLog.d(TAG, "FragmentedMp4MuxerWrapper(fd): fd.valid()=" + fd.valid());
        
        // Create callback consumer for live streaming integration
        Consumer<ProcessedSegment> segmentConsumer = segment -> {
            handleProcessedSegment(segment);
        };
        
        // CRITICAL FIX for fMP4 seeking (GitHub issue #6704):
        // Use 2000ms (2 second) fragments for stable live streaming
        // NEW: Callback-based architecture for real-time streaming
        // 2-second segments reduce upload frequency by 50%, improving stability on slow networks
        this.muxer = new FragmentedMp4Muxer.Builder(segmentConsumer)
                .setFragmentDurationMs(2000) // 2 seconds per fragment for stable streaming
                .build();
    }

    /**
     * Adds a track to the muxer.
     *
     * @param format The MediaFormat describing the track.
     * @return The track index.
     * @throws IllegalStateException If the muxer has already been started.
     */
    public int addTrack(@NonNull MediaFormat format) {
        synchronized (muxerLock) {
            if (started) {
                throw new IllegalStateException("Cannot add track after muxer has started");
            }
            if (released) {
                throw new IllegalStateException("Muxer has been released");
            }

            // If this is a video track and we have an orientation hint, add it to the format
            String mimeType = format.getString(MediaFormat.KEY_MIME);
            boolean isVideo = mimeType != null && mimeType.startsWith("video/");
            if (isVideo && orientationHint != 0) {
                format.setInteger(MediaFormat.KEY_ROTATION, orientationHint);
            }

            Format media3Format = convertToMedia3Format(format);

            try {
                int trackId = muxer.addTrack(media3Format);
                
                // Track which index is audio/video for proper MP4 offset handling
                if (isVideo) {
                    videoTrackIndex = trackId;
                    FLog.i(TAG, "✅ [MEDIA3-FIX] VIDEO track registered at index " + trackId + 
                               " - tfhd.DEFAULT_BASE_IS_MOOF enabled for proper moof-relative offsets");
                } else if (mimeType != null && mimeType.startsWith("audio/")) {
                    audioTrackIndex = trackId;
                    FLog.i(TAG, "✅ [MEDIA3-FIX] AUDIO track registered at index " + trackId + 
                               " - tfhd.DEFAULT_BASE_IS_MOOF enabled for VLC compatibility");
                }

                trackCount++;

                return trackId;
            } catch (Exception e) {
                FLog.e(TAG, "Failed to add track", e);
                throw new RuntimeException("Failed to add track: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Starts the muxer. Must be called after all tracks have been added.
     */
    public void start() {
        synchronized (muxerLock) {
            if (started) {
                FLog.w(TAG, "Muxer already started");
                return;
            }
            if (released) {
                throw new IllegalStateException("Muxer has been released");
            }

            // Add orientation metadata if set
            if (orientationHint != 0) {
                try {
                    muxer.addMetadataEntry(new Mp4OrientationData(orientationHint));
                } catch (Exception e) {
                    FLog.w(TAG, "Failed to add orientation metadata", e);
                }
            }

            // Add timestamp metadata for creation/modification times
            // FIX: Mp4TimestampData expects MP4 epoch (seconds since Jan 1, 1904), not Unix epoch
            // Use the static helper method to properly convert Unix time to MP4 time
            try {
                long mp4TimeSeconds = androidx.media3.container.Mp4TimestampData.unixTimeToMp4TimeSeconds(
                    System.currentTimeMillis());
                muxer.addMetadataEntry(new androidx.media3.container.Mp4TimestampData(
                    mp4TimeSeconds, mp4TimeSeconds));
            } catch (Exception e) {
                FLog.w(TAG, "Failed to add timestamp metadata", e);
            }

            started = true;

            // CRITICAL: Force flush to disk so moov atom is written immediately
            // This makes the file streamable right away
            try {
                fileOutputStream.flush();
                FLog.i(TAG, "✅ [MEDIA3-FIX] Muxer initialized with " + trackCount + 
                           " tracks. ALL fragments will use tfhd.DEFAULT_BASE_IS_MOOF for proper offset calculation");
            } catch (IOException e) {
                FLog.w(TAG, "Failed to flush after start", e);
            }
        }
    }

    /**
     * Writes sample data for the specified track.
     *
     * @param trackIndex The track index returned by addTrack.
     * @param byteBuf    The encoded sample data.
     * @param bufferInfo The buffer info containing presentation time, size, and flags.
     */
    public void writeSampleData(int trackIndex, @NonNull ByteBuffer byteBuf,
                                 @NonNull MediaCodec.BufferInfo bufferInfo) {
        synchronized (muxerLock) {
            IOException failure = outputFailure;
            if (failure != null) throw new IllegalStateException("녹화 출력에 쓰지 못했습니다.", failure);
            if (!started) {
                throw new IllegalStateException("Muxer has not been started");
            }
            if (released) {
                throw new IllegalStateException("Muxer has been released");
            }

            try {
            long ptsUs = bufferInfo.presentationTimeUs;
            if (ptsUs < 0) throw new IllegalArgumentException("Negative capture presentation timestamp");
            int flags = convertFlags(bufferInfo.flags, trackIndex);
            androidx.media3.muxer.BufferInfo media3BufferInfo =
                    new androidx.media3.muxer.BufferInfo(ptsUs, bufferInfo.size, flags);

            // Position the buffer correctly for the muxer.
            // IMPORTANT: The caller (GLRecordingPipeline) pre-positions the buffer:
            //   - Sets position = bufferInfo.offset
            //   - Sets limit = bufferInfo.offset + bufferInfo.size
            // This means the buffer's remaining() exactly equals bufferInfo.size.
            // We must NOT re-apply the offset/size, just use the buffer as-is.
            ByteBuffer data = byteBuf.duplicate();
            
            // The buffer should already be properly positioned by the caller.
            // Just ensure the slice is correct and pass to muxer.
            if (data.remaining() != bufferInfo.size) {
                // Unexpected: caller didn't pre-position correctly
                // Try to fix it by slicing
                FLog.w(TAG, String.format("Buffer not properly positioned: remaining=%d, size=%d. Attempting correction.",
                    data.remaining(), bufferInfo.size));
                int start = bufferInfo.offset;
                int end = bufferInfo.offset + bufferInfo.size;
                if (start < 0 || end < 0 || end > data.capacity()) {
                    throw new IllegalArgumentException(
                        "Invalid buffer range: offset=" + bufferInfo.offset +
                            ", size=" + bufferInfo.size +
                            ", capacity=" + data.capacity());
                }
                data.position(start);
                data.limit(end);
            }
            // Else: buffer is already correctly positioned by caller, use as-is

            // Match Media3's initial video keyframe gate and exclude empty codec EOS samples.
            if (bufferInfo.size > 0 && (trackIndex != videoTrackIndex
                    || (flags & C.BUFFER_FLAG_KEY_FRAME) != 0)) {
                firstTrackPtsUs.putIfAbsent(trackIndex + 1, ptsUs);
            }
            muxer.writeSampleData(trackIndex, data, media3BufferInfo);
            } catch (MuxerException e) {
                FLog.e(TAG, "Failed to write sample data", e);
                throw new RuntimeException("Failed to write sample data: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Stops the muxer and finalizes the output file.
     * Writes end-of-stream samples to ensure proper duration calculation.
     */
    public void stop() {
        synchronized (muxerLock) {
            if (!started) {
                FLog.w(TAG, "Muxer was not started, nothing to stop");
                return;
            }
            if (released) {
                FLog.w(TAG, "Muxer already released");
                return;
            }

            try {
                closeWriter();
                if (outputFailure != null) throw outputFailure;
                performHybridFinalization();
                // The final moov omits empty tracks. Keep the recoverable file, but a
                // segment missing a registered track must never be reported as saved.
                if ((videoTrackIndex >= 0 && !processedVideoSamples)
                        || (audioTrackIndex >= 0 && !processedAudioSamples)) {
                    lastFinalizeOutcome = "INCOMPLETE (available samples preserved)";
                    throw new IOException(videoTrackIndex >= 0 && !processedVideoSamples
                            ? "영상 샘플을 저장하지 못했습니다." : "오디오 샘플을 저장하지 못했습니다.");
                }
            } catch (IOException | MuxerException e) {
                throw new IllegalStateException("녹화 파일을 완전히 저장하지 못했습니다.", e);
            } finally {
                started = false;
            }
        }
    }
    
    /**
     * Writes end-of-stream samples for all tracks to finalize duration.
     * This is required for FragmentedMp4Muxer to calculate proper duration.
     */
    /**
     * Releases resources used by the muxer.
     */
    public void release() {
        synchronized (muxerLock) {
            if (released) {
                return;
            }
            try {
                try {
                    closeWriter();
                } catch (MuxerException | RuntimeException e) {
                    FLog.e(TAG, "Error closing muxer", e);
                } finally {
                    started = false;
                }
                // CRITICAL: Flush BEFORE closing to ensure all data (including moov box) is written to disk.
                // Under ioLock: waits for any in-flight writer-thread callback to finish its write,
                // then closes — no write can ever hit an invalid fd.
                synchronized (ioLock) {
                    released = true;
                    try {
                        fileOutputStream.flush();
                        FLog.d(TAG, "FileOutputStream flushed successfully");
                    } catch (IOException e) {
                        FLog.w(TAG, "Failed to flush FileOutputStream", e);
                    }
                    fileOutputStream.close();
                    FLog.d(TAG, "Muxer released and file closed");
                }
            } catch (IOException e) {
                FLog.e(TAG, "Error releasing muxer", e);
            }
        }
    }

    /**
     * Sets the orientation hint for video playback.
     *
     * @param degrees The orientation in degrees (0, 90, 180, or 270).
     */
    /** Lets the pipeline record the output path even for fd-based constructions. */
    public void setOutputPath(String path) { this.outputPath = path; }

    /** Earliest possible packet time in this output, on the common encoder clock. */
    public void setFragmentLowerBoundUs(long earliestUs) {
        synchronized (muxerLock) {
            if (started || earliestUs < 0) throw new IllegalArgumentException("Invalid fragment clock lower bound");
            fragmentLowerBoundUs = earliestUs;
        }
    }

    public void setOrientationHint(int degrees) {
        if (started) {
            FLog.w(TAG, "Cannot set orientation after muxer started");
            return;
        }
        this.orientationHint = degrees;
        FLog.d(TAG, "Orientation hint set to: " + degrees);
    }

    /**
     * Sets the geographical location for the recorded media.
     * Note: Location metadata support depends on Media3 version.
     *
     * @param latitude  Latitude in degrees (-90 to 90).
     * @param longitude Longitude in degrees (-180 to 180).
     */
    public void setLocation(float latitude, float longitude) {
        if (started) {
            FLog.w(TAG, "Cannot set location after muxer started");
            return;
        }
        try {
            muxer.addMetadataEntry(new androidx.media3.container.Mp4LocationData(latitude, longitude));
            FLog.d(TAG, "Location metadata set: " + latitude + ", " + longitude);
        } catch (Exception e) {
            FLog.w(TAG, "Failed to set location metadata (may not be supported)", e);
        }
    }

    /**
     * Converts Android MediaFormat to Media3 Format.
     */
    private Format convertToMedia3Format(@NonNull MediaFormat mediaFormat) {
        String mimeType = mediaFormat.getString(MediaFormat.KEY_MIME);
        Format.Builder builder = new Format.Builder()
            .setSampleMimeType(mimeType);

        // Handle video format
        if (mimeType != null && mimeType.startsWith("video/")) {
            if (mediaFormat.containsKey(MediaFormat.KEY_WIDTH)) {
                builder.setWidth(mediaFormat.getInteger(MediaFormat.KEY_WIDTH));
            }
            if (mediaFormat.containsKey(MediaFormat.KEY_HEIGHT)) {
                builder.setHeight(mediaFormat.getInteger(MediaFormat.KEY_HEIGHT));
            }
            if (mediaFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                try {
                    builder.setFrameRate(mediaFormat.getFloat(MediaFormat.KEY_FRAME_RATE));
                } catch (ClassCastException e) {
                    builder.setFrameRate(mediaFormat.getInteger(MediaFormat.KEY_FRAME_RATE));
                }
            }
            if (mediaFormat.containsKey(MediaFormat.KEY_BIT_RATE)) {
                builder.setAverageBitrate(mediaFormat.getInteger(MediaFormat.KEY_BIT_RATE));
            }
            
            // CRITICAL FIX for VLC playback: Extract and set color metadata from MediaFormat
            // VLC needs explicit color information to properly decode and display HEVC video
            // Without this, VLC shows black video even though the frames are encoded correctly
            if (mediaFormat.containsKey(MediaFormat.KEY_COLOR_STANDARD) ||
                mediaFormat.containsKey(MediaFormat.KEY_COLOR_RANGE) ||
                mediaFormat.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                
                // Build ColorInfo from MediaFormat
                androidx.media3.common.ColorInfo.Builder colorBuilder = new androidx.media3.common.ColorInfo.Builder();
                
                // Extract color space from MediaFormat
                if (mediaFormat.containsKey(MediaFormat.KEY_COLOR_STANDARD)) {
                    int standard = mediaFormat.getInteger(MediaFormat.KEY_COLOR_STANDARD);
                    // MediaFormat.COLOR_STANDARD_BT709 = 1
                    if (standard == 1) {
                        colorBuilder.setColorSpace(androidx.media3.common.C.COLOR_SPACE_BT709);
                    } else if (standard == 2) { // BT601
                        colorBuilder.setColorSpace(androidx.media3.common.C.COLOR_SPACE_BT601);
                    } else if (standard == 6) { // BT2020
                        colorBuilder.setColorSpace(androidx.media3.common.C.COLOR_SPACE_BT2020);
                    }
                }
                
                // Extract color range from MediaFormat
                if (mediaFormat.containsKey(MediaFormat.KEY_COLOR_RANGE)) {
                    int range = mediaFormat.getInteger(MediaFormat.KEY_COLOR_RANGE);
                    // MediaFormat.COLOR_RANGE_LIMITED = 2, COLOR_RANGE_FULL = 1
                    if (range == 1) {
                        colorBuilder.setColorRange(androidx.media3.common.C.COLOR_RANGE_FULL);
                    } else {
                        colorBuilder.setColorRange(androidx.media3.common.C.COLOR_RANGE_LIMITED);
                    }
                }
                
                // Extract color transfer from MediaFormat
                if (mediaFormat.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                    int transfer = mediaFormat.getInteger(MediaFormat.KEY_COLOR_TRANSFER);
                    // MediaFormat.COLOR_TRANSFER_SDR_VIDEO = 3 (SMPTE 170M)
                    if (transfer == 3) {
                        colorBuilder.setColorTransfer(androidx.media3.common.C.COLOR_TRANSFER_SDR);
                    } else if (transfer == 6) { // ST2084 (HDR10)
                        colorBuilder.setColorTransfer(androidx.media3.common.C.COLOR_TRANSFER_ST2084);
                    } else if (transfer == 7) { // HLG
                        colorBuilder.setColorTransfer(androidx.media3.common.C.COLOR_TRANSFER_HLG);
                    }
                }
                
                androidx.media3.common.ColorInfo colorInfo = colorBuilder.build();
                builder.setColorInfo(colorInfo);
}
            
            if (mediaFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                builder.setRotationDegrees(mediaFormat.getInteger(MediaFormat.KEY_ROTATION));
            }

            // Handle CSD (Codec Specific Data)
            List<byte[]> initData = new ArrayList<>();
            if (mediaFormat.containsKey("csd-0")) {
                ByteBuffer csd0 = mediaFormat.getByteBuffer("csd-0");
                if (csd0 != null) {
                    byte[] csd0Bytes = new byte[csd0.remaining()];
                    csd0.get(csd0Bytes);
                    csd0.rewind();
                    initData.add(csd0Bytes);
                }
            } else if (MimeTypes.VIDEO_H265.equals(mimeType)) {
                FLog.w(TAG, "[HEVC-CSD] HEVC video format missing csd-0 — MP4 header will fail without it");
            }
            if (mediaFormat.containsKey("csd-1")) {
                ByteBuffer csd1 = mediaFormat.getByteBuffer("csd-1");
                if (csd1 != null) {
                    byte[] csd1Bytes = new byte[csd1.remaining()];
                    csd1.get(csd1Bytes);
                    csd1.rewind();
                    initData.add(csd1Bytes);
                }
            }
            if (!initData.isEmpty()) {
                builder.setInitializationData(initData);
            }
            
            // Set codec string for video - REQUIRED by Media3 for proper MP4 codec box
            // For fragmented MP4, Media3 uses this string in the codec descriptor
            if (mimeType != null) {
                if (mimeType.equals("video/hevc")) {
                    // HEVC: Infer profile from CSD data
                    // Most Android devices use Main profile (1), Level 5 (150 = level 5.0)
                    builder.setCodecs("hev1.1.6.L150.B0"); // HEVC Main profile, Level 5.0
                } else if (mimeType.equals("video/avc")) {
                    // AVC: Common profile used by Android encoders
                    builder.setCodecs("avc1.42001E"); // AVC Baseline profile, Level 30
                }
            }
        }

        // Handle audio format
        if (mimeType != null && mimeType.startsWith("audio/")) {
            FLog.d(TAG, "Converting audio format: " + mimeType);
            
            // Extract sample rate and channel count for AAC config generation
            int sampleRate = 48000; // default
            int channels = 2; // default
            
            if (mediaFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                channels = mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                builder.setChannelCount(channels);
            } else {
                FLog.w(TAG, "  WARNING: No channel count in audio format!");
            }
            if (mediaFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                sampleRate = mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                builder.setSampleRate(sampleRate);
            } else {
                FLog.w(TAG, "  WARNING: No sample rate in audio format!");
            }
            if (mediaFormat.containsKey(MediaFormat.KEY_BIT_RATE)) {
                int bitrate = mediaFormat.getInteger(MediaFormat.KEY_BIT_RATE);
                builder.setAverageBitrate(bitrate);
            }

            // CRITICAL VLC FIX: Generate proper AAC AudioSpecificConfig for ESDS box
            // The encoder's CSD-0 is often malformed. We generate a clean config based on
            // the actual sample rate and channel count for VLC compatibility.
            // AAC-LC (profile=2), with proper sampling frequency index
            if (mimeType != null && (mimeType.equals(MimeTypes.AUDIO_AAC) || mimeType.contains("mp4a"))) {
                byte[] aacConfig = generateAacAudioSpecificConfig(sampleRate, channels);
                List<byte[]> audioInitData = new ArrayList<>();
                audioInitData.add(aacConfig);
                builder.setInitializationData(audioInitData);
                
                // Set explicit codec string for MP4 descriptor box - Media3 REQUIRES this for proper ESDS
                // The codec string tells the MP4 parser (VLC) what audio codec profile/level to expect
                builder.setCodecs("mp4a.40.2"); // AAC-LC
            }
        }

        return builder.build();
    }

    /**
     * Converts MediaCodec buffer flags to Media3 buffer flags.
     */
    private int convertFlags(int mediaCodecFlags, int trackIndex) {
        int flags = 0;

        if ((mediaCodecFlags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
            flags |= C.BUFFER_FLAG_KEY_FRAME;
        }
        
        // DO NOT force audio keyframes
        // AAC samples should NOT be marked as keyframes in fragmented MP4
        // Forcing keyframes corrupts the trun sample flags, causing VLC to read from wrong byte offsets
        // Audio-specific sync sample marking is handled by MP4 spec via stss atom separately
        
        if ((mediaCodecFlags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            flags |= C.BUFFER_FLAG_END_OF_STREAM;
        }

        return flags;
    }
    
    /**
     * Helper to convert bytes to hex string for logging.
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(bytes.length, 16); i++) {
            sb.append(String.format("%02X ", bytes[i]));
        }
        if (bytes.length > 16) {
            sb.append("...");
        }
        return sb.toString().trim();
    }
    
    /**
    }

    /**
     * Patches mvhd.duration in the init segment at the beginning of the file.
     * fMP4 spec says mvhd.duration=0 is valid (fragments determine duration),
     * but gallery apps / Instagram read mvhd.duration directly and show 00:00.
     */
    private void patchMvhdDuration(java.nio.channels.FileChannel channel) throws IOException {
        if (initSegmentData == null || initSegmentFilePosition < 0) return;
        // Init segment layout: [ftyp][free(16)][moov[mvhd]]. Locate the mvhd
        // box by WALKING the boxes instead of a hardcoded offset — a hardcoded
        // ftypSize+32 landed inside mvhd.creation_time (byte-audited), leaving
        // the real duration field 0 and corrupting creation_time.
        int ftypSize = ((initSegmentData[0] & 0xFF) << 24)
                     | ((initSegmentData[1] & 0xFF) << 16)
                     | ((initSegmentData[2] & 0xFF) << 8)
                     |  (initSegmentData[3] & 0xFF);
        int pos = ftypSize;
        int end = initSegmentData.length;
        while (pos + 8 <= end) {
            int s = readIntBE(initSegmentData, pos);
            int t = readIntBE(initSegmentData, pos + 4);
            if (s < 8 || pos + s > end) break;
            if (t == 0x6D6F6F76) { // 'moov'
                int mvhdPos = pos + 8;
                if (mvhdPos + 12 > pos + s || readIntBE(initSegmentData, mvhdPos + 4) != 0x6d766864) {
                    throw new IOException("영상 길이 정보를 찾을 수 없습니다.");
                }
                int ver = initSegmentData[mvhdPos + 8] & 0xFF;
                int fieldOffset = ver == 1 ? 32 : 24;
                int fieldSize = ver == 1 ? 8 : 4;
                if (ver > 1 || mvhdPos + fieldOffset + fieldSize > pos + s
                        || readIntBE(initSegmentData, mvhdPos) < fieldOffset + fieldSize) {
                    throw new IOException("영상 길이 정보가 올바르지 않습니다.");
                }
                long durationOffset = initSegmentFilePosition + mvhdPos + fieldOffset;
                long scale = Integer.toUnsignedLong(readIntBE(initSegmentData, mvhdPos + fieldOffset - 4));
                long duration = cumulativeDurationUs / 1_000_000L * scale
                        + cumulativeDurationUs % 1_000_000L * scale / 1_000_000L;
                ByteBuffer patch = ByteBuffer.allocate(fieldSize);
                if (ver == 1) patch.putLong(duration);
                else patch.putInt((int) Math.min(duration, 0xffff_ffffL));
                patch.flip();
                channel.position(durationOffset);
                writeFully(channel, patch);
                FLog.i(TAG, "Patched mvhd.duration at offset " + durationOffset
                        + " (ver=" + ver + ") to " + cumulativeDurationUs + "us ("
                        + (cumulativeDurationUs / 1000000.0) + "s)");
                return;
            }
            pos += s;
        }
        FLog.w(TAG, "mvhd duration patch skipped: moov not found in init segment");
    }

    /**
     * Extracts per-track sample counts and trun data offsets from a fragment's
     * moof box, needed to build stco/stsc entries in the final moov.
     */
    private static final class FragmentLayout {
        int audioCount, videoCount, audioOffset, videoOffset;
        long audioEnd, videoEnd, endUs;
    }

    private FragmentLayout parseFragmentForFinalization(byte[] data, boolean applyTimeline) throws IOException {
        int moofSize = boxSize(data, 0, data.length);
        if (readIntBE(data, 4) != 0x6d6f6f66 || moofSize < 20) throw invalidFragment();
        int mdatSize = boxSize(data, moofSize, data.length);
        if (readIntBE(data, moofSize + 4) != 0x6d646174 || moofSize + mdatSize != data.length) {
            throw invalidFragment();
        }
        int mfhdSize = boxSize(data, 8, moofSize);
        if (readIntBE(data, 12) != 0x6d666864 || mfhdSize < 16) throw invalidFragment();
        FragmentLayout layout = new FragmentLayout();
        for (int pos = 8 + mfhdSize; pos < moofSize;) {
            int size = boxSize(data, pos, moofSize);
            if (readIntBE(data, pos + 4) == 0x74726166) {
                parseTrackRun(data, pos + 8, pos + size, moofSize + 8, layout, applyTimeline);
            }
            pos += size;
        }
        if (layout.audioCount == 0 && layout.videoCount == 0) throw invalidFragment();
        if (layout.audioCount != 0 && layout.videoCount != 0) {
            if (layout.audioOffset < layout.videoOffset) {
                if (layout.audioOffset != moofSize + 8 || layout.audioEnd != layout.videoOffset
                        || layout.videoEnd != data.length) throw invalidFragment();
            } else if (layout.videoOffset != moofSize + 8 || layout.videoEnd != layout.audioOffset
                    || layout.audioEnd != data.length) throw invalidFragment();
        } else if (layout.audioCount != 0) {
            if (layout.audioOffset != moofSize + 8 || layout.audioEnd != data.length) throw invalidFragment();
        } else if (layout.videoOffset != moofSize + 8 || layout.videoEnd != data.length) {
            throw invalidFragment();
        }
        return layout;
    }

    private void parseTrackRun(byte[] data, int first, int end, int mdatPayload,
                               FragmentLayout layout, boolean applyTimeline) throws IOException {
        int trackId = -1;
        int trun = -1;
        int trunSize = 0;
        int tfdt = -1;
        for (int pos = first; pos < end;) {
            int size = boxSize(data, pos, end);
            int type = readIntBE(data, pos + 4);
            if (type == 0x74666864) { // tfhd
                if (size < 16 || trackId != -1) throw invalidFragment();
                trackId = readIntBE(data, pos + 12);
            } else if (type == 0x74666474 && applyTimeline) { // tfdt
                // The pinned Media3 writer emits 64-bit decode times.
                if (size != 20 || tfdt != -1 || readIntBE(data, pos + 8) != 0x01000000) {
                    throw invalidFragment();
                }
                tfdt = pos + 12;
            } else if (type == 0x7472756e) { // trun
                if (size < 20 || trun != -1) throw invalidFragment();
                trun = pos;
                trunSize = size;
            }
            pos += size;
        }
        if (trun == -1 || trackId <= 0) throw invalidFragment();
        int flags = readIntBE(data, trun + 8) & 0x00ff_ffff;
        int sampleCount = readIntBE(data, trun + 12);
        int dataOffset = readIntBE(data, trun + 16);
        // The fixed Media3 writer emits explicit duration/size/flags and data offset,
        // optionally one composition offset for B frames; no default sample sizes.
        if ((flags != 0x701 && flags != 0xf01) || sampleCount <= 0 || dataOffset < mdatPayload) {
            throw invalidFragment();
        }
        int stride = flags == 0xf01 ? 16 : 12;
        if (20L + stride * (long) sampleCount != trunSize) throw invalidFragment();
        long sampleEnd = dataOffset;
        long durationVu = 0;
        for (int index = 0, sample = trun + 20; index < sampleCount; index++, sample += stride) {
            int size = readIntBE(data, sample + 4);
            if (applyTimeline) durationVu += Integer.toUnsignedLong(readIntBE(data, sample));
            if (size <= 0 || (sampleEnd += size) > data.length) throw invalidFragment();
        }
        if (videoTrackIndex >= 0 && trackId == videoTrackIndex + 1 && layout.videoCount == 0) {
            layout.videoCount = sampleCount;
            layout.videoOffset = dataOffset;
            layout.videoEnd = sampleEnd;
        } else if (audioTrackIndex >= 0 && trackId == audioTrackIndex + 1 && layout.audioCount == 0) {
            layout.audioCount = sampleCount;
            layout.audioOffset = dataOffset;
            layout.audioEnd = sampleEnd;
        } else {
            throw invalidFragment();
        }
        if (applyTimeline) {
            Long firstPtsUs = firstTrackPtsUs.get(trackId);
            if (tfdt < 0 || firstPtsUs == null) throw invalidFragment();
            if (fragmentOriginUs < 0) {
                // If one codec hasn't produced a packet yet, freeze the caller's known lower
                // bound. Do not infer a new origin later from out-of-order track delivery.
                fragmentOriginUs = fragmentLowerBoundUs;
                if (firstTrackPtsUs.size() == trackCount) {
                    fragmentOriginUs = java.util.Collections.min(firstTrackPtsUs.values());
                }
            }
            long offsetUs = firstPtsUs - fragmentOriginUs;
            if (offsetUs < 0) throw new IOException("Sample predates the shared fragment clock");
            // Same timebases as the pinned Media3 Track.videoUnitTimebase().
            long timescale = trackId == audioTrackIndex + 1 ? 48_000L : 90_000L;
            long offsetVu = (offsetUs / 1_000_000L) * timescale
                    + (offsetUs % 1_000_000L) * timescale / 1_000_000L;
            ByteBuffer fragment = ByteBuffer.wrap(data);
            long startVu = Math.addExact(fragment.getLong(tfdt), offsetVu);
            fragment.putLong(tfdt, startVu);
            long endVu = Math.addExact(startVu, durationVu);
            long endUs = Math.addExact(Math.multiplyExact(endVu / timescale, 1_000_000L),
                    (endVu % timescale) * 1_000_000L / timescale);
            layout.endUs = Math.max(layout.endUs, endUs);
        }
    }

    private static IOException invalidFragment() {
        return new IOException("MP4 조각의 샘플 표가 실제 데이터와 일치하지 않습니다.");
    }

    private static int boxSize(byte[] data, int position, int limit) throws IOException {
        if (position < 0 || position > limit - 8) throw invalidFragment();
        int size = readIntBE(data, position);
        if (size < 8 || size > limit - position) throw invalidFragment();
        return size;
    }

    private static int readIntBE(byte[] d, int off) {
        return ((d[off]&0xFF)<<24)|((d[off+1]&0xFF)<<16)|((d[off+2]&0xFF)<<8)|(d[off+3]&0xFF);
    }

    /**
     * Hybrid MP4 finalization: appends a complete moov box with sample
     * tables at the end of the file and overwrites the free placeholder
     * between ftyp and moov with an mdat header.  The file becomes a
     * standard MP4 without copying any media data.  On failure the
     * original fMP4 is left intact.
     *
     * <p>ROOT-CAUSE FIX (issue #332): the original FileOutputStream fd is
     * FUSE-backed on emulated storage (MediaProvider). On aggressive OEMs
     * (Xiaomi HyperOS etc.) the process is killed/frozen during long
     * screen-off recordings; after recovery the fd can go stale — buffered
     * writes still "succeed" but fstat (FileChannel.size()) throws
     * ENOENT ("No such file or directory"). If we rely on that fd, the moov
     * append never happens and the data is destroyed when the stream closes.
     * Fix: on any channel failure, re-open the file with a FRESH channel
     * (path or SAF re-opener) and redo the finalization. The media data is
     * already on disk — only the stale handle was broken.
     */
    private void performHybridFinalization() throws IOException {
        FLog.d(TAG, "performHybridFinalization START — fragments=" + fragmentPositions.size());
        if (fragmentPositions.isEmpty() || initSegmentData == null || fileOutputStream == null) {
            String msg = "SKIPPED — fragments=" + fragmentPositions.size()
                    + " initSegmentData=" + (initSegmentData != null)
                    + " stream=" + (fileOutputStream != null) + " | fMP4 left intact";
            FLog.w(TAG, "performHybridFinalization " + msg);
            lastFinalizeOutcome = msg;
            RemoteStreamManager stream = RemoteStreamManager.getInstance();
            boolean streamOnly = stream.isStreamingEnabled()
                    && stream.getStreamingMode() == RemoteStreamManager.StreamingMode.STREAM_ONLY;
            if (!streamOnly || committedOutputEnd != 0) {
                throw new IOException("저장할 수 있는 완전한 녹화 조각이 없습니다.");
            }
            return;
        }
        logFileState("before finalization");

        // Attempt 1: original (possibly stale) channel.
        try {
            finalizeWithChannel(fileOutputStream.getChannel());
            logFileState("after finalization (original channel)");
            FLog.i(TAG, "Hybrid MP4 finalization succeeded (original channel)");
            lastFinalizeOutcome = "OK (original channel)";
            // ── Post-finalize self-audit: read back the FIRST VIDEO sample of
            // each fragment from the file as written and report its framing.
            // This observes the actual file content (Annex-B vs AVCC vs
            // truncated), so a "successful" finalization with structurally
            // broken samples is visible in logs instead of guessed.
            // The original output channel is write-only ("w" fd), and
            // FileOutputStream.getChannel() is write-only by CONTRACT — use a
            // dedicated read channel (SAF "r" mode or RandomAccessFile).
            try {
                java.nio.channels.FileChannel auditChannel = reopenReadChannel();
                if (auditChannel == null) {
                    FLog.w(TAG, "[AVC-AUDIT] audit skipped: no readable channel");
                } else {
                    try {
                        auditChannel.position(0);
                        String audit = androidx.media3.muxer.FragmentedMp4Muxer
                                .auditFirstVideoSample(auditChannel);
                        FLog.i(TAG, "[AVC-AUDIT] first video sample in FILE: " + audit);
                    } finally {
                        closeQuietly(auditChannel);
                    }
                }
            } catch (Exception auditEx) {
                FLog.w(TAG, "[AVC-AUDIT] audit failed: " + auditEx);
            }
            return;
        } catch (Exception e) {
            FLog.w(TAG, "Hybrid finalization failed on original channel (" + e.getMessage()
                    + ") — re-opening with a fresh channel (stale FUSE fd after process churn)", e);
        }

        // Attempt 2: fresh channel — the file data is on disk; only the fd went stale.
        java.nio.channels.FileChannel fresh = reopenChannel();
        if (fresh != null) {
            try {
                finalizeWithChannel(fresh);
                logFileState("after finalization (re-opened channel)");
                FLog.i(TAG, "Hybrid MP4 finalization succeeded (re-opened channel) — data preserved");
                lastFinalizeOutcome = "OK (re-opened channel)";
            } catch (Exception e2) {
                lastFinalizeOutcome = "FAILED on re-opened channel: " + e2.getMessage();
                logFinalizationFailure(e2, "re-opened channel");
                throw new IOException("최종 저장 재시도에 실패했습니다.", e2);
            } finally {
                closeQuietly(fresh);
            }
        } else {
            lastFinalizeOutcome = "FAILED on original channel, re-open unavailable";
            logFinalizationFailure(null, "original channel (fresh re-open unavailable)");
            throw new IOException("녹화 출력을 다시 열 수 없습니다.");
        }
    }

    private static void fixMovieDurationForTrackEdits(ByteBuffer moov) throws IOException {
        int end = movieBoxEnd(moov, 0, moov.limit());
        if (end != moov.limit() || moov.getInt(4) != 0x6d6f6f76) throw invalidFragment();
        int mvhd = -1;
        long maxDuration = 0;
        for (int pos = 8; pos < end;) {
            int next = movieBoxEnd(moov, pos, end);
            int type = moov.getInt(pos + 4);
            if (type == 0x6d766864) { // mvhd
                if (next - pos < 28 || moov.get(pos + 8) != 0) throw invalidFragment();
                mvhd = pos;
            } else if (type == 0x7472616b) { // trak
                int tkhd = -1;
                long editDuration = 0;
                for (int child = pos + 8; child < next;) {
                    int childEnd = movieBoxEnd(moov, child, next);
                    int childType = moov.getInt(child + 4);
                    if (childType == 0x746b6864) { // tkhd
                        if (childEnd - child < 32 || moov.get(child + 8) != 0) throw invalidFragment();
                        tkhd = child;
                    } else if (childType == 0x65647473) { // edts
                        int elst = child + 8;
                        int editEnd = movieBoxEnd(moov, elst, childEnd);
                        if (moov.getInt(elst + 4) != 0x656c7374 || editEnd - elst < 16) throw invalidFragment();
                        if (moov.getInt(elst + 12) == 2) {
                            if (editEnd - elst < 56 || moov.get(elst + 8) != 1
                                    || moov.getLong(elst + 24) != -1) throw invalidFragment();
                            editDuration = Math.addExact(moov.getLong(elst + 16), moov.getLong(elst + 36));
                        }
                    }
                    child = childEnd;
                }
                if (tkhd < 0) throw invalidFragment();
                if (editDuration > 0) moov.putInt(tkhd + 28, (int) Math.min(editDuration, 0xffff_ffffL));
                maxDuration = Math.max(maxDuration, Integer.toUnsignedLong(moov.getInt(tkhd + 28)));
            }
            pos = next;
        }
        if (mvhd < 0) throw invalidFragment();
        moov.putInt(mvhd + 24, (int) maxDuration);
    }

    private static int movieBoxEnd(ByteBuffer data, int start, int limit) throws IOException {
        if (start > limit - 8) throw invalidFragment();
        int size = data.getInt(start);
        if (size < 8 || size > limit - start) throw invalidFragment();
        return start + size;
    }

    /** Performs the moov-append + mdat-header patch on the given channel. */
    private void finalizeWithChannel(java.nio.channels.FileChannel ch) throws IOException {
        if (ch.size() < committedOutputEnd) throw new IOException("녹화 파일이 짧습니다.");
        // Retry always writes the same moov location; never append a second copy.
        ch.truncate(committedOutputEnd);
        java.util.List<Long> aOff = new java.util.ArrayList<>();
        java.util.List<Integer> aCnt = new java.util.ArrayList<>();
        java.util.List<Long> vOff = new java.util.ArrayList<>();
        java.util.List<Integer> vCnt = new java.util.ArrayList<>();
        for (int i = 0; i < fragmentPositions.size(); i++) {
            long fragPos = fragmentPositions.get(i);
            int aoff = i < fragmentAudioOffsets.size() ? fragmentAudioOffsets.get(i) : 0;
            int voff = i < fragmentVideoOffsets.size() ? fragmentVideoOffsets.get(i) : 0;
            int acnt = i < fragmentAudioCounts.size() ? fragmentAudioCounts.get(i) : 0;
            int vcnt = i < fragmentVideoCounts.size() ? fragmentVideoCounts.get(i) : 0;
            if (acnt > 0 && aoff > 0) { aOff.add(fragPos + aoff); aCnt.add(acnt); }
            if (vcnt > 0 && voff > 0) { vOff.add(fragPos + voff); vCnt.add(vcnt); }
        }
        java.nio.ByteBuffer moov = muxer.buildFinalMoov(aOff, aCnt, vOff, vCnt);
        if (firstTrackPtsUs.size() > 1) {
            // The pinned writer encodes the empty edit but omits it from tkhd/mvhd durations.
            fixMovieDurationForTrackEdits(moov.slice());
        }
        long moovPos = committedOutputEnd;
        int moovSize = moov.remaining();
        // Replace the 16-byte free box with a 64-bit mdat header.
        int ftypSize = readIntBE(initSegmentData, 0);
        long freePos = initSegmentFilePosition + ftypSize;
        long mdatSize = moovPos - freePos;
        ByteBuffer hdr = ByteBuffer.allocate(16);
        hdr.putInt(1).putInt(0x6D646174).putLong(mdatSize).flip();
        try {
            ch.position(moovPos);
            writeFully(ch, moov);
            patchMvhdDuration(ch);
            ch.position(freePos);
            writeFully(ch, hdr);
        } catch (IOException failure) {
            try {
                // Restore original init bytes if the duration/header was partly patched.
                ch.position(initSegmentFilePosition);
                writeFully(ch, ByteBuffer.wrap(initSegmentData));
                ch.truncate(committedOutputEnd);
            } catch (IOException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
        FLog.i(TAG, "Hybrid MP4 finalized: " + fragmentPositions.size()
                + " fragments, moov=" + moovSize + "B, mdat(64bit)=" + mdatSize + "B");
    }

    static void writeFully(java.nio.channels.WritableByteChannel channel, ByteBuffer buffer)
            throws IOException {
        int stalled = 0;
        while (buffer.hasRemaining()) {
            int written = channel.write(buffer);
            if (written < 0 || (written == 0 && ++stalled == 16)) {
                throw new IOException("녹화 파일 쓰기가 진행되지 않습니다.");
            }
            if (written > 0) stalled = 0;
        }
    }

    /**
     * Supplies a FRESH stream to the output file, used both by hybrid
     * finalization and mid-recording hot-swap when the original fd went stale
     * (FUSE/MediaProvider churn after process death on aggressive OEMs).
     * Implementations must NOT truncate the file.
     */
    public interface FileReopener {
        java.io.FileOutputStream reopen() throws IOException;
        /** Readable stream to the SAME file — for the post-finalize audit. */
        java.io.FileInputStream reopenRead() throws IOException;
    }
    private FileReopener fileReopener;
    public void setFileReopener(FileReopener reopener) { this.fileReopener = reopener; }

    private java.io.FileOutputStream reopenStreamForWrite() throws IOException {
        // An O_APPEND descriptor cannot patch or retry at a committed offset.
        // Path outputs recover through the original channel; SAF can supply an rw FD.
        if (fileReopener == null) return null;
        java.io.FileOutputStream stream = fileReopener.reopen();
        if (stream == null) return null;
        if (!ownsOutput(stream)) {
            closeQuietlyStream(stream);
            throw new IOException("다시 열린 출력이 원래 파일과 다릅니다.");
        }
        return stream;
    }

    /** Fresh READABLE channel for the post-finalize audit. */
    private java.nio.channels.FileChannel reopenReadChannel() {
        // 1) Explicit re-opener with read access (SAF/fd mode).
        if (fileReopener != null) {
            try {
                java.io.FileInputStream in = fileReopener.reopenRead();
                if (in != null) {
                    if (ownsOutput(in.getFD())) return in.getChannel();
                    in.close();
                }
            } catch (Exception e) {
                FLog.w(TAG, "FileReopener (read) failed", e);
            }
        }
        // 2) Path-based read (internal storage mode).
        if (outputPath != null) {
            try {
                java.io.File f = new java.io.File(outputPath);
                if (f.exists()) {
                    java.io.RandomAccessFile file = new java.io.RandomAccessFile(f, "r");
                    if (ownsOutput(file.getFD())) return file.getChannel();
                    file.close();
                }
            } catch (Exception e) {
                FLog.w(TAG, "Read re-open by path failed", e);
            }
        }
        return null;
    }

    private java.nio.channels.FileChannel reopenChannel() {
        try {
            java.io.FileOutputStream stream = reopenStreamForWrite();
            if (stream != null) return stream.getChannel();
            if (outputPath != null && new java.io.File(outputPath).isFile()) {
                java.io.RandomAccessFile file = new java.io.RandomAccessFile(outputPath, "rw");
                boolean accepted = false;
                try {
                    accepted = ownsOutput(file.getFD());
                    if (accepted) return file.getChannel();
                } finally {
                    if (!accepted) file.close();
                }
            }
        } catch (Exception failure) {
            FLog.w(TAG, "출력 파일을 안전하게 다시 열지 못했습니다.", failure);
        }
        return null;
    }

    private static void closeQuietly(java.nio.channels.FileChannel ch) {
        try { if (ch != null) ch.close(); } catch (Exception ignored) {}
    }

    private static void closeQuietlyStream(java.io.FileOutputStream s) {
        try { if (s != null) s.close(); } catch (Exception ignored) {}
    }

    /** Logs existence/length/fd state of the output file at a given phase. */
    private void logFileState(String phase) {
        try {
            StringBuilder sb = new StringBuilder("File state " + phase + ":");
            if (outputPath != null) {
                java.io.File f = new java.io.File(outputPath);
                sb.append(" path=").append(outputPath)
                  .append(" exists=").append(f.exists())
                  .append(" length=").append(f.exists() ? f.length() : -1);
            } else {
                sb.append(" path=null");
            }
            try {
                sb.append(" | fdValid=").append(fileOutputStream != null ? fileOutputStream.getFD().valid() : false);
            } catch (Exception ignored) {}
            try {
                sb.append(" | channelOpen=").append(fileOutputStream != null ? fileOutputStream.getChannel().isOpen() : false);
            } catch (Exception ignored) {}
            FLog.i(TAG, sb.toString());
        } catch (Exception ignored) {}
    }

    /** DIAGNOSTIC (failure path only — zero steady-state cost): */
    private void logFinalizationFailure(Exception e, String channelDesc) {
        try {
            StringBuilder diag = new StringBuilder("Hybrid finalization failed — fMP4 left intact: ")
                    .append(e != null ? e.getMessage() : "unknown")
                    .append(" | channel=").append(channelDesc);
            if (outputPath != null) {
                java.io.File f = new java.io.File(outputPath);
                diag.append(" | path=").append(outputPath)
                    .append(" | exists=").append(f.exists())
                    .append(" | length=").append(f.exists() ? f.length() : -1);
            }
            try {
                diag.append(" | fdValid=").append(fileOutputStream != null ? fileOutputStream.getFD().valid() : false);
            } catch (Exception ignored) {}
            try {
                diag.append(" | channelOpen=").append(fileOutputStream != null ? fileOutputStream.getChannel().isOpen() : false);
            } catch (Exception ignored) {}
            FLog.w(TAG, diag.toString(), e != null ? e : new RuntimeException("finalization failed"));
        } catch (Exception logEx) {
            FLog.w(TAG, "Hybrid finalization failed — fMP4 left intact", e);
        }
    }

    /**
     * Handles processed segments from the patched Media3 muxer.
     * 
     * @param segment ProcessedSegment containing either init segment or media fragment
     */
    private void handleProcessedSegment(ProcessedSegment segment) {
        // No muxerLock here — this runs on the library's dedicated writer thread.
        // ioLock is shared with release(): either this callback completes its write
        // before the stream closes, or release() has already set `released` and we
        // skip. There is no window where a write can hit a closed fd.
        synchronized (ioLock) {
        if (released) {
            return;
        }
            try {
            // Extract bytes from Media3's payload buffer without mutating it.
            // Some Media3 versions leave the buffer in write-mode (position at end, limit=capacity),
            // while others provide it already ready for reading (position=0, limit=dataSize).
            ByteBuffer payload = segment.payload.duplicate();
            if (payload.limit() == payload.capacity() && payload.position() != 0) {
                payload.flip();
            }

            byte[] data = new byte[payload.remaining()];
            payload.get(data);
            
            // Lazy-init cached streaming state.  Callback runs on the library's
            // writer thread — never block it with heavy init.
            if (!streamManagerChecked && cachedStreamManager == null) {
                cachedStreamManager = RemoteStreamManager.getInstance();
                streamManagerChecked = true;
            }
            boolean serverActive = cachedStreamManager != null && cachedStreamManager.isStreamingEnabled();
            RemoteStreamManager.StreamingMode streamingMode = serverActive
                ? cachedStreamManager.getStreamingMode()
                : RemoteStreamManager.StreamingMode.STREAM_AND_SAVE;
            boolean shouldSaveToDisk = !serverActive
                || (streamingMode == RemoteStreamManager.StreamingMode.STREAM_AND_SAVE);
            
            if (segment.isInitSegment) {
                // Initialization segment (ftyp + moov)
                // SEGMENT init log removed
                
                // Send to RemoteStreamManager for HLS streaming ONLY when active
                if (serverActive && cachedStreamManager != null) {
                    cachedStreamManager.onInitializationSegment(data);
                }
                initSegmentSent = true;
                
                // Write to file — no per-fragment fsync; periodic flush handles durability.
                if (shouldSaveToDisk && fileOutputStream != null) {
                    writeSegmentWithRecovery(data, true);
                }
            } else {
                // Apply the same A/V start offset before publishing to HLS or disk.
                FragmentLayout layout = parseFragmentForFinalization(data, true);
                // Fragment info logged only at DEBUG level to avoid
                // flooding logcat during long recordings.
                
                // Send to RemoteStreamManager for HLS streaming ONLY when active
                if (serverActive && cachedStreamManager != null) {
                    if (initSegmentSent) {
                        cachedStreamManager.onFragmentComplete(segment.segmentNr, data, segment.durationMs);
                    } else {
                        FLog.w(TAG, "⚠️ Fragment #" + segment.segmentNr + 
                            " received before init segment - skipping stream upload");
                    }
                }
                
                // Write to file — per-fragment flush for SAF visibility.
                if (shouldSaveToDisk && fileOutputStream != null) {
                    writeSegmentWithRecovery(data, layout);
                }
                
                processedVideoSamples |= layout.videoCount > 0;
                processedAudioSamples |= layout.audioCount > 0;
                nextFragmentNumber++;
                // Track cumulative duration for mvhd patch
                cumulativeDurationUs = Math.max(cumulativeDurationUs, layout.endUs);
            }
            } catch (Exception e) {
                if (outputFailure == null) outputFailure = new IOException("조각 기록에 실패했습니다.", e);
                FLog.e(TAG, "❌ Error handling processed segment", e);
            }
        } // end synchronized(ioLock)
    }

    // ----- Stale-fd hot-swap recovery (FUSE/MediaProvider churn, issue #332) -----
    private int segmentWriteFailures = 0;
    private int streamReopenCount = 0;


    /**
     * Writes a processed segment. If the underlying FUSE fd died mid-recording
     * (writes start throwing ENOENT/EBADF after process churn), hot-swaps to a
     * FRESH stream (SAF re-opener or path re-open) and retries the segment —
     * instead of silently dropping fragments, which is what previously left
     * files "fragmented-only" or lost the tail of recordings.
     */
    private void writeSegmentWithRecovery(byte[] data, boolean isInit) throws IOException {
        if (outputFailure != null) throw outputFailure;
        final FragmentLayout layout;
        try {
            layout = isInit ? null : parseFragmentForFinalization(data, false);
        } catch (IOException invalid) {
            outputFailure = invalid;
            throw invalid;
        }
        writeSegmentWithRecovery(data, layout);
    }

    private void writeSegmentWithRecovery(byte[] data, FragmentLayout layout) throws IOException {
        if (outputFailure != null) throw outputFailure;
        try {
            writeSegmentBytes(data, layout);
        } catch (IOException first) {
            segmentWriteFailures++;
            try {
                try {
                    resetToCommittedBoundary(fileOutputStream.getChannel(), committedOutputEnd);
                } catch (IOException stale) {
                    java.io.FileOutputStream fresh = reopenStreamForWrite();
                    if (fresh == null) throw stale;
                    try {
                        resetToCommittedBoundary(fresh.getChannel(), committedOutputEnd);
                    } catch (IOException unsafe) {
                        closeQuietlyStream(fresh);
                        throw unsafe;
                    }
                    java.io.FileOutputStream old = fileOutputStream;
                    fileOutputStream = fresh;
                    closeQuietlyStream(old);
                    streamReopenCount++;
                }
                writeSegmentBytes(data, layout);
            } catch (IOException retry) {
                try {
                    resetToCommittedBoundary(fileOutputStream.getChannel(), committedOutputEnd);
                } catch (IOException rollback) {
                    if (rollback != retry) retry.addSuppressed(rollback);
                }
                if (first != retry) retry.addSuppressed(first);
                outputFailure = retry;
                throw retry;
            }
        }
    }

    static void resetToCommittedBoundary(java.nio.channels.FileChannel channel, long boundary)
            throws IOException {
        if (channel.size() < boundary) throw new IOException("확정된 녹화 데이터가 부족합니다.");
        channel.truncate(boundary);
        channel.position(boundary);
    }

    private void writeSegmentBytes(byte[] data, FragmentLayout layout) throws IOException {
        long pos = committedOutputEnd;
        if (fileOutputStream.getChannel().position() != pos) {
            throw new IOException("녹화 출력 위치가 일치하지 않습니다.");
        }
        fileOutputStream.write(data);
        fileOutputStream.flush();
        committedOutputEnd += data.length;
        if (layout == null) {
            initSegmentData = data;
            initSegmentFilePosition = pos;
        } else {
            fragmentPositions.add(pos);
            fragmentAudioCounts.add(layout.audioCount);
            fragmentAudioOffsets.add(layout.audioOffset);
            fragmentVideoCounts.add(layout.videoCount);
            fragmentVideoOffsets.add(layout.videoOffset);
            logFirstVideoSampleInFragment(data);
        }
    }

    /**
     * Bounded AVC corruption trace: logs the head of the FIRST video sample of the
     * FIRST fragment only (first 3 fragments max). Valid AVCC starts with a 4-byte
     * NAL length (e.g. 00 00 00 16 67...); Annex-B leakage shows 00 00 00 01.
     */
    private void logFirstVideoSampleInFragment(byte[] data) {
        try {
            if (firstMediaFragmentLogged || data.length < 16) return;
            int p = 8;
            while (p + 8 <= data.length) {
                int s = ((data[p] & 0xFF) << 24) | ((data[p + 1] & 0xFF) << 16)
                        | ((data[p + 2] & 0xFF) << 8) | (data[p + 3] & 0xFF);
                int t = ((data[p + 4] & 0xFF) << 24) | ((data[p + 5] & 0xFF) << 16)
                        | ((data[p + 6] & 0xFF) << 8) | (data[p + 7] & 0xFF);
                if (s < 8 || p + s > data.length) return;
                if (t == 0x6D6F6F66) { // 'moof'
                    int moofSize = s;
                    int q = p + 8;
                    int moofEnd = p + moofSize;
                    int sampleStart = -1;
                    int cnt = 0;
                    while (q + 8 <= moofEnd) {
                        int s2 = ((data[q] & 0xFF) << 24) | ((data[q + 1] & 0xFF) << 16)
                                | ((data[q + 2] & 0xFF) << 8) | (data[q + 3] & 0xFF);
                        int t2 = ((data[q + 4] & 0xFF) << 24) | ((data[q + 5] & 0xFF) << 16)
                                | ((data[q + 6] & 0xFF) << 8) | (data[q + 7] & 0xFF);
                        if (s2 < 8 || q + s2 > moofEnd) break;
                        if (t2 == 0x7472756E) { // 'trun' — video traf comes after audio
                            int sampleCount = ((data[q + 12] & 0xFF) << 24)
                                    | ((data[q + 13] & 0xFF) << 16)
                                    | ((data[q + 14] & 0xFF) << 8) | (data[q + 15] & 0xFF);
                            int flags = ((data[q + 9] & 0xFF) << 16)
                                    | ((data[q + 10] & 0xFF) << 8) | (data[q + 11] & 0xFF);
                            if ((flags & 0x1) != 0) { // data-offset-present
                                int dataOffset = ((data[q + 16] & 0xFF) << 24)
                                        | ((data[q + 17] & 0xFF) << 16)
                                        | ((data[q + 18] & 0xFF) << 8) | (data[q + 19] & 0xFF);
                                if (dataOffset > 0 && p + moofSize + dataOffset + 8 <= data.length) {
                                    sampleStart = p + moofSize + dataOffset;
                                    cnt = sampleCount;
                                }
                            }
                        }
                        q += s2;
                    }
                    if (sampleStart > 0) {
                        StringBuilder hex = new StringBuilder();
                        int shown = Math.min(16, data.length - sampleStart);
                        for (int i = 0; i < shown; i++) {
                            hex.append(String.format("%02X ", data[sampleStart + i]));
                        }
                        FLog.i(TAG, "[AVC-DIAG] First fragment: moof=" + moofSize + "B"
                                + " | video samples=" + cnt
                                + " | first video sample @ " + sampleStart
                                + " head=" + hex.toString().trim());
                        firstMediaFragmentLogged = true;
                    }
                    return;
                }
                p += s;
            }
        } catch (Exception e) {
            FLog.w(TAG, "[AVC-DIAG] Failed to locate first video sample in fragment", e);
        }
    }

    /**
     * Generates a proper AAC AudioSpecificConfig for the ESDS box.
     * This ensures VLC compatibility by creating clean configuration data.
     * 
     * Format: 5 bits audioObjectType (2=AAC-LC) + 4 bits samplingFrequencyIndex + 4 bits channelConfiguration
     * 
     * @param sampleRate Sample rate in Hz (e.g., 48000)
     * @param channels Number of audio channels (1 or 2)
     * @return 2-byte AAC AudioSpecificConfig
     */
    private byte[] generateAacAudioSpecificConfig(int sampleRate, int channels) {
        // AAC-LC profile = 2
        int audioObjectType = 2;
        
        // Sampling frequency index for AAC
        int samplingFrequencyIndex;
        switch (sampleRate) {
            case 96000: samplingFrequencyIndex = 0; break;
            case 88200: samplingFrequencyIndex = 1; break;
            case 64000: samplingFrequencyIndex = 2; break;
            case 48000: samplingFrequencyIndex = 3; break;
            case 44100: samplingFrequencyIndex = 4; break;
            case 32000: samplingFrequencyIndex = 5; break;
            case 24000: samplingFrequencyIndex = 6; break;
            case 22050: samplingFrequencyIndex = 7; break;
            case 16000: samplingFrequencyIndex = 8; break;
            case 12000: samplingFrequencyIndex = 9; break;
            case 11025: samplingFrequencyIndex = 10; break;
            case 8000: samplingFrequencyIndex = 11; break;
            case 7350: samplingFrequencyIndex = 12; break;
            default:
                FLog.w(TAG, "Unsupported sample rate: " + sampleRate + ", using 48000 Hz");
                samplingFrequencyIndex = 3; // 48000 Hz
                break;
        }
        
        // Channel configuration (1=mono, 2=stereo)
        int channelConfig = Math.min(channels, 2);
        
        // Build AudioSpecificConfig: 5 bits audioObjectType + 4 bits samplingFrequencyIndex + 4 bits channelConfiguration
        // Byte 1: xxxxx xxx (5 bits audioObjectType + 3 upper bits of samplingFrequencyIndex)
        // Byte 2: x xxxx xxx (1 lower bit of samplingFrequencyIndex + 4 bits channelConfiguration + 3 bits padding)
        
        int byte1 = (audioObjectType << 3) | (samplingFrequencyIndex >> 1);
        int byte2 = ((samplingFrequencyIndex & 0x1) << 7) | (channelConfig << 3);
        
        return new byte[] { (byte) byte1, (byte) byte2 };
    }
}

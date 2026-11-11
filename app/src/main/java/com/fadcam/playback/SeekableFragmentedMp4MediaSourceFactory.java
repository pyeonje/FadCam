package com.fadcam.playback;

import com.fadcam.FLog;
import android.content.Context;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.FileDataSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.extractor.ChunkIndex;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.SniffFailure;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.mp4.FragmentedMp4Extractor;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;

import com.google.common.collect.ImmutableList;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * Factory for creating MediaSources for fragmented MP4 files with proper seeking support.
 * 
 * <p>This factory pre-scans fMP4 files to build a fragment index, then wraps the standard
 * FragmentedMp4Extractor to provide a seekable SeekMap. This is the VLC-like approach
 * that enables seeking without requiring sidx boxes in the MP4 file.
 * 
 * <p>Usage:
 * <pre>
 * SeekableFragmentedMp4MediaSourceFactory factory = 
 *     new SeekableFragmentedMp4MediaSourceFactory(context);
 * MediaSource source = factory.createMediaSource(MediaItem.fromUri(uri));
 * player.setMediaSource(source);
 * </pre>
 */
@OptIn(markerClass = UnstableApi.class)
public class SeekableFragmentedMp4MediaSourceFactory {

    private static final String TAG = "SeekableFmp4Factory";

    private final Context context;
    private final FragmentedMp4IndexBuilder indexBuilder;

    public SeekableFragmentedMp4MediaSourceFactory(@NonNull Context context) {
        this.context = context.getApplicationContext();
        this.indexBuilder = new FragmentedMp4IndexBuilder();
    }

    /**
     * Creates a MediaSource for the given MediaItem with proper fMP4 seeking support.
     *
     * <p>For {@code file://} URIs, builds a full fragment index using
     * {@link FragmentedMp4IndexBuilder} for precise seeking. For {@code content://} URIs
     * (SAF / custom storage), reads the index from the provider if it supports seeks.
     * If not, use progressive playback and let the extractor consume the available bytes.
     *
     * @param mediaItem The MediaItem to create a source for.
     * @return A MediaSource with proper seeking for fMP4 files.
     */
    @NonNull
    public MediaSource createMediaSource(@NonNull MediaItem mediaItem) {
        Uri uri = mediaItem.localConfiguration != null ? 
                  mediaItem.localConfiguration.uri : null;
        
        if (uri == null) {
            throw new IllegalArgumentException("MediaItem has no URI");
        }

        String scheme = uri.getScheme();

        if ("content".equals(scheme)) {
            return createContentUriMediaSource(mediaItem, uri);
        }

        // Build the fragment index for file:// URIs
        FragmentedMp4IndexBuilder.FragmentIndex fragmentIndex = null;
        if ("file".equals(scheme)) {
            String path = uri.getPath();
            if (path != null) {
                File file = new File(path);
                if (file.exists()) {
                    long startTime = System.currentTimeMillis();
                    fragmentIndex = indexBuilder.buildIndex(file);
                    long scanTime = System.currentTimeMillis() - startTime;
                    FLog.d(TAG, "Index built in " + scanTime + "ms: " + 
                          fragmentIndex.fragments.size() + " fragments, " +
                          "duration=" + (fragmentIndex.durationUs / 1000000.0) + "s, " +
                          "seekable=" + fragmentIndex.isSeekable);
                }
            }
        }

        // Create the ExtractorsFactory — use standard Mp4Extractor for
        // non-fragmented files, our FragmentedMp4Extractor wrapper only for fMP4.
        final FragmentedMp4IndexBuilder.FragmentIndex index = fragmentIndex;
        ExtractorsFactory extractorsFactory = extractorsFactoryFor(index);

        // Create ProgressiveMediaSource with our custom extractor
        DataSource.Factory dataSourceFactory = new FileDataSource.Factory();
        
        return new ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
                .createMediaSource(mediaItem);
    }

    /** Reads the index from the original provider. Playback opens its own descriptor. */
    @NonNull
    private MediaSource createContentUriMediaSource(@NonNull MediaItem mediaItem, @NonNull Uri uri) {
        FragmentedMp4IndexBuilder.FragmentIndex index = scanContentIndex(uri);
        if (index != null && index.isSeekable && !index.fragments.isEmpty()) {
            return new ProgressiveMediaSource.Factory(
                    new DefaultDataSource.Factory(context), extractorsFactoryFor(index))
                    .createMediaSource(mediaItem);
        }

        // An unindexed recording can have a stale mvhd duration while its moof/mdat
        // still contain valid samples. Never clip this source to metadata alone.
        DataSource.Factory dataSource = new DefaultDataSource.Factory(context);
        if (!Thread.currentThread().isInterrupted() && isFragmentedMp4ContentUri(uri)) {
            return new ProgressiveMediaSource.Factory(dataSource,
                    () -> new Extractor[] { new SeekMapInjectingExtractor(null) })
                    .createMediaSource(mediaItem);
        }
        return new ProgressiveMediaSource.Factory(dataSource).createMediaSource(mediaItem);
    }

    @Nullable
    FragmentedMp4IndexBuilder.FragmentIndex scanContentIndex(Uri uri) {
        try (android.os.ParcelFileDescriptor pfd = openScanDescriptor(uri)) {
            if (pfd == null || Thread.currentThread().isInterrupted()) return null;
            return scanDescriptor(pfd);
        } catch (Exception e) {
            FLog.w(TAG, "Provider does not support descriptor indexing", e);
            return null;
        }
    }

    android.os.ParcelFileDescriptor openScanDescriptor(Uri uri) throws IOException {
        return context.getContentResolver().openFileDescriptor(uri, "r");
    }

    FragmentedMp4IndexBuilder.FragmentIndex scanDescriptor(android.os.ParcelFileDescriptor pfd) {
        return indexBuilder.buildIndex(new File("/proc/self/fd/" + pfd.getFd()));
    }

    /**
     * Returns an {@link ExtractorsFactory} appropriate for the given fragment index.
     * <p>
     * For fMP4 (index with fragments): uses {@link SeekMapInjectingExtractor} which
     * extends {@link FragmentedMp4Extractor} for proper moof/mdat parsing.
     * <p>
     * For standard MP4 (empty or null index): uses {@link DefaultExtractorsFactory}
     * which provides {@link Mp4Extractor} — handles stbl-based moov natively.
     */
    @NonNull
    private ExtractorsFactory extractorsFactoryFor(
            @Nullable FragmentedMp4IndexBuilder.FragmentIndex index) {
        if (index != null && !index.fragments.isEmpty()) {
            final FragmentedMp4IndexBuilder.FragmentIndex idx = index;
            return () -> new Extractor[] { new SeekMapInjectingExtractor(idx) };
        }
        return new DefaultExtractorsFactory();
    }

    /**
     * Checks if a URI points to a fragmented MP4 that needs our special handling.
     * Supports both {@code file://} and {@code content://} (SAF / custom storage) URIs.
     */
    public boolean isFragmentedMp4(@NonNull Uri uri) {
        String scheme = uri.getScheme();
        if ("file".equals(scheme)) {
            String path = uri.getPath();
            if (path == null) return false;
            File file = new File(path);
            return isFragmentedMp4File(file);
        } else if ("content".equals(scheme)) {
            return isFragmentedMp4ContentUri(uri);
        }
        return false;
    }

    /**
     * Checks if a {@code content://} URI points to a fragmented MP4 by reading
     * the first &leq;1&thinsp;MB through ContentResolver and looking for
     * {@code ftyp} + {@code moof} box types.
     */
    private boolean isFragmentedMp4ContentUri(@NonNull Uri uri) {
        InputStream is = null;
        try {
            is = context.getContentResolver().openInputStream(uri);
            if (is == null) return false;

            // Read up to 1MB (enough to find ftyp near the start and the first moof)
            byte[] data = new byte[1024 * 1024];
            int totalRead = 0;
            while (totalRead < data.length && !Thread.currentThread().isInterrupted()) {
                int n = is.read(data, totalRead, data.length - totalRead);
                if (n <= 0) break;
                totalRead += n;
            }

            boolean foundFtyp = false;
            boolean foundMoof = false;
            int position = 0;

            while (position + 8 <= totalRead) {
                long size = ((long)(data[position] & 0xFF) << 24) |
                            ((long)(data[position + 1] & 0xFF) << 16) |
                            ((long)(data[position + 2] & 0xFF) << 8) |
                            ((long)(data[position + 3] & 0xFF));
                int type = ((data[position + 4] & 0xFF) << 24) |
                           ((data[position + 5] & 0xFF) << 16) |
                           ((data[position + 6] & 0xFF) << 8) |
                           (data[position + 7] & 0xFF);

                // Handle extended size (size == 1)
                if (size == 1 && position + 16 <= totalRead) {
                    size = ((long)(data[position + 8] & 0xFF) << 56) |
                           ((long)(data[position + 9] & 0xFF) << 48) |
                           ((long)(data[position + 10] & 0xFF) << 40) |
                           ((long)(data[position + 11] & 0xFF) << 32) |
                           ((long)(data[position + 12] & 0xFF) << 24) |
                           ((long)(data[position + 13] & 0xFF) << 16) |
                           ((long)(data[position + 14] & 0xFF) << 8) |
                           ((long)(data[position + 15] & 0xFF));
                } else if (size == 0) {
                    // Size 0 means box extends to EOF — impossible to skip, stop scanning
                    break;
                }

                if (size < 8) break; // Invalid box

                if (type == 0x66747970) {        // 'ftyp'
                    foundFtyp = true;
                } else if (type == 0x6D6F6F66) { // 'moof'
                    foundMoof = true;
                    break;
                }

                if (size > totalRead - position) break; // Box extends past buffer
                position += (int) size;
            }

            boolean result = foundFtyp && foundMoof;
            FLog.d(TAG, "isFragmentedMp4ContentUri: " + result +
                  " (ftyp=" + foundFtyp + ", moof=" + foundMoof +
                  ", scanned " + totalRead + " bytes) for " + uri.getLastPathSegment());
            return result;

        } catch (Exception e) {
            FLog.w(TAG, "Error checking content URI for fMP4: " + uri, e);
            return false;
        } finally {
            if (is != null) {
                try { is.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Checks if a file is a fragmented MP4 by looking for moof boxes.
     */
    private boolean isFragmentedMp4File(@NonNull File file) {
        if (!file.exists() || !file.canRead()) {
            return false;
        }
        
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
            byte[] header = new byte[8];
            long position = 0;
            long fileLength = raf.length();
            
            // Scan first few boxes to check for ftyp and moof
            boolean foundFtyp = false;
            boolean foundMoof = false;
            
            while (position < Math.min(fileLength, 1024 * 1024)) { // Scan first 1MB
                raf.seek(position);
                if (raf.read(header, 0, 8) != 8) {
                    break;
                }
                
                long size = ((long)(header[0] & 0xFF) << 24) | 
                           ((long)(header[1] & 0xFF) << 16) | 
                           ((long)(header[2] & 0xFF) << 8) | 
                           ((long)(header[3] & 0xFF));
                int type = ((header[4] & 0xFF) << 24) | 
                          ((header[5] & 0xFF) << 16) | 
                          ((header[6] & 0xFF) << 8) | 
                          (header[7] & 0xFF);
                
                if (size == 1) {
                    // Extended size
                    if (raf.read(header, 0, 8) != 8) {
                        break;
                    }
                    size = ((long)(header[0] & 0xFF) << 56) |
                           ((long)(header[1] & 0xFF) << 48) |
                           ((long)(header[2] & 0xFF) << 40) |
                           ((long)(header[3] & 0xFF) << 32) |
                           ((long)(header[4] & 0xFF) << 24) |
                           ((long)(header[5] & 0xFF) << 16) |
                           ((long)(header[6] & 0xFF) << 8) |
                           ((long)(header[7] & 0xFF));
                } else if (size == 0) {
                    size = fileLength - position;
                }
                
                if (size < 8) {
                    break;
                }
                
                // Check box type
                if (type == 0x66747970) { // 'ftyp'
                    foundFtyp = true;
                } else if (type == 0x6D6F6F66) { // 'moof'
                    foundMoof = true;
                    break; // Found what we need
                }
                
                position += size;
            }
            
            return foundFtyp && foundMoof;
            
        } catch (IOException e) {
            FLog.w(TAG, "Error checking file type", e);
            return false;
        }
    }

    /**
     * Extractor that wraps FragmentedMp4Extractor and injects a pre-built SeekMap.
     */
    private static class SeekMapInjectingExtractor implements Extractor {
        
        private static final String TAG_EXTRACTOR = "SeekMapInjExtractor";
        
        private final FragmentedMp4Extractor delegate;
        @Nullable
        private final FragmentedMp4IndexBuilder.FragmentIndex fragmentIndex;
        @Nullable
        private ChunkIndex seekableChunkIndex;
        private ExtractorOutput wrappedOutput;

        SeekMapInjectingExtractor(@Nullable FragmentedMp4IndexBuilder.FragmentIndex fragmentIndex) {
            this.delegate = new FragmentedMp4Extractor(new DefaultSubtitleParserFactory());
            this.fragmentIndex = fragmentIndex;
            
            // Build ChunkIndex from fragment index
            if (fragmentIndex != null && fragmentIndex.isSeekable) {
                int count = fragmentIndex.fragments.size();
                int[] sizes = fragmentIndex.getSizes();
                long[] offsets = fragmentIndex.getOffsets();
                long[] durationsUs = fragmentIndex.getDurationsUs();
                long[] timesUs = fragmentIndex.getTimesUs();
                
                this.seekableChunkIndex = new ChunkIndex(sizes, offsets, durationsUs, timesUs);
                FLog.i(TAG_EXTRACTOR, "Created ChunkIndex with " + count + " fragments, " +
                      "duration=" + (seekableChunkIndex.getDurationUs() / 1000000.0) + "s");
            } else {
                FLog.w(TAG_EXTRACTOR, "No fragment index available, seeking will not work");
            }
        }

        @Override
        public boolean sniff(@NonNull ExtractorInput input) throws IOException {
            return delegate.sniff(input);
        }

        @NonNull
        @Override
        public ImmutableList<SniffFailure> getSniffFailureDetails() {
            return delegate.getSniffFailureDetails();
        }

        @Override
        public void init(@NonNull ExtractorOutput output) {
            FLog.d(TAG_EXTRACTOR, "init() called, wrapping output to intercept seekMap");
            // Wrap the output to intercept seekMap calls
            this.wrappedOutput = new SeekMapInterceptingOutput(output, seekableChunkIndex);
            delegate.init(wrappedOutput);
        }

        @Override
        public int read(@NonNull ExtractorInput input, @NonNull PositionHolder seekPosition) 
                throws IOException {
            return delegate.read(input, seekPosition);
        }

        @Override
        public void seek(long position, long timeUs) {
            FLog.d(TAG_EXTRACTOR, "seek() called: position=" + position + ", timeUs=" + timeUs + 
                  " (" + (timeUs / 1000000.0) + "s)");
            delegate.seek(position, timeUs);
        }

        @Override
        public void release() {
            delegate.release();
        }
    }

    /**
     * ExtractorOutput wrapper that intercepts seekMap() to provide our pre-built index.
     */
    private static class SeekMapInterceptingOutput implements ExtractorOutput {
        
        private static final String TAG_OUTPUT = "SeekMapIntercept";
        
        private final ExtractorOutput delegate;
        @Nullable
        private final ChunkIndex seekableChunkIndex;

        SeekMapInterceptingOutput(@NonNull ExtractorOutput delegate, 
                                   @Nullable ChunkIndex seekableChunkIndex) {
            this.delegate = delegate;
            this.seekableChunkIndex = seekableChunkIndex;
        }

        @NonNull
        @Override
        public TrackOutput track(int id, int type) {
            return delegate.track(id, type);
        }

        @Override
        public void endTracks() {
            delegate.endTracks();
        }

        @Override
        public void seekMap(@NonNull SeekMap seekMap) {
            FLog.i(TAG_OUTPUT, "seekMap() called: isSeekable=" + seekMap.isSeekable() + 
                  ", duration=" + (seekMap.getDurationUs() / 1000000.0) + "s");
            
            if (seekableChunkIndex != null) {
                // Always use our pre-built ChunkIndex — the delegate extractor's
                // SeekMap may be "seekable" but broken (all seeks → position 0,
                // especially for Faditor-converted standard MP4 files).
                if (!seekMap.isSeekable()) {
                    FLog.i(TAG_OUTPUT, "*** REPLACING UNSEEKABLE SeekMap with pre-built ChunkIndex ***");
                } else {
                    FLog.w(TAG_OUTPUT, "*** REPLACING delegate's (potentially broken) SeekMap with pre-built ChunkIndex ***");
                }
                FLog.i(TAG_OUTPUT, "ChunkIndex: duration=" + (seekableChunkIndex.getDurationUs() / 1000000.0) + "s");
                delegate.seekMap(seekableChunkIndex);
            } else if (seekMap.isSeekable()) {
                FLog.d(TAG_OUTPUT, "Using delegate's seekable SeekMap (file has sidx)");
                delegate.seekMap(seekMap);
            } else {
                FLog.w(TAG_OUTPUT, "No pre-built index, using original UNSEEKABLE SeekMap");
                delegate.seekMap(seekMap);
            }
        }
    }
}

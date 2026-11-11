package com.fadcam.opengl;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import com.fadcam.VideoCodec;
import com.fadcam.media.FragmentedMp4MuxerWrapper;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class AudioFormatDeadlineTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void audioFormatAtZeroStartsBothTracks() { verifyTimeline(0, true, true); }
    @Test public void audioFormatAt4900StartsBothTracks() { verifyTimeline(4_900, true, true); }
    @Test public void audioFormatAt5100KeepsVideoButFailsRecording() { verifyTimeline(5_100, true, false); }
    @Test public void audioFormatAt10000KeepsVideoButFailsRecording() { verifyTimeline(10_000, true, false); }
    @Test public void noAudioFormatKeepsVideoButFailsRecording() { verifyTimeline(-1, true, false); }
    @Test public void deliberatelySilentRecordingFinalizesSuccessfully() { verifyTimeline(-1, false, false); }

    @Test public void stopBeforeDeadlineKeepsVideoButFailsIfAudioIsMissing() {
        verifyTimeline(-1, true, false, 200, 0);
    }

    @Test public void audioSamplesArrivingBeforeVideoAreReleasedAndWrittenOnce() {
        verifyTimeline(0, true, true, 10_001, 250);
    }

    @Test public void formatWithoutAnyAudioSamplesIsNotSuccessful() {
        verifyTimeline(0, true, true, 10_001, 0, null, false);
    }

    @Test public void timedOutAudioStillLeavesAFinalizedVideoOnlyMp4() throws Exception {
        File output = folder.newFile("verified-recovery.mp4");
        verifyTimeline(-1, true, false, 10_001, 0, output, false);
        byte[] payload = Files.readAllBytes(output.toPath());
        String boxes = new String(payload, StandardCharsets.ISO_8859_1);
        assertTrue("physical recovery has an initialized video track", boxes.contains("moov") && boxes.contains("vide"));
        assertTrue("physical recovery kept video samples and an index", boxes.contains("mdat") && boxes.contains("stsz"));
        assertFalse("recovery video must not advertise audio", boxes.contains("soun"));
    }

    @Test public void pauseDoesNotDisarmDeadlineOrStopTheDrainOwner() {
        verifyTimeline(5_100, true, false, 10_001, 0, null, true, true);
    }

    private static ByteBuffer bytes(String hex) {
        byte[] data = new byte[hex.length() / 2];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return ByteBuffer.wrap(data);
    }

    private void verifyTimeline(long formatDelayMs, boolean requestedAudio, boolean hasAudioTrack) {
        verifyTimeline(formatDelayMs, requestedAudio, hasAudioTrack, 10_001, 0);
    }

    private void verifyTimeline(long formatDelayMs, boolean requestedAudio, boolean hasAudioTrack,
            long stopAtMs, long videoDelayMs) {
        verifyTimeline(formatDelayMs, requestedAudio, hasAudioTrack, stopAtMs, videoDelayMs, null, true);
    }

    private void verifyTimeline(long formatDelayMs, boolean requestedAudio, boolean hasAudioTrack,
            long stopAtMs, long videoDelayMs, File realOutput, boolean provideAudioSample) {
        verifyTimeline(formatDelayMs, requestedAudio, hasAudioTrack, stopAtMs, videoDelayMs,
                realOutput, provideAudioSample, false);
    }

    private void verifyTimeline(long formatDelayMs, boolean requestedAudio, boolean hasAudioTrack,
            long stopAtMs, long videoDelayMs, File realOutput, boolean provideAudioSample, boolean pause) {
        GLRecordingPipeline pipeline = new GLRecordingPipeline(RuntimeEnvironment.getApplication(), null,
                640, 480, 30, new File(folder.getRoot(), "recovery.mp4").getPath(), 0, 1,
                null, "portrait", 0, VideoCodec.AVC, null, null);
        final FragmentedMp4MuxerWrapper muxer;
        if (realOutput == null) {
            muxer = mock(FragmentedMp4MuxerWrapper.class);
        } else {
            try {
                muxer = new FragmentedMp4MuxerWrapper(realOutput.getPath());
            } catch (java.io.IOException failure) {
                throw new AssertionError(failure);
            }
        }
        MediaCodec video = mock(MediaCodec.class);
        MediaCodec audio = mock(MediaCodec.class);
        MediaFormat videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 640, 480);
        // Annex B parameter sets from the synthetic H.264 test fixture.
        videoFormat.setByteBuffer("csd-0", bytes("000000016764000aace47b011000000300100000030080f1225120"));
        videoFormat.setByteBuffer("csd-1", bytes("0000000168ebe3cb22c0"));
        MediaFormat audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48_000, 2);
        audioFormat.setByteBuffer("csd-0", ByteBuffer.wrap(new byte[] {0x11, (byte) 0x90}));
        if (realOutput == null) {
            when(muxer.addTrack(videoFormat)).thenReturn(0);
            when(muxer.addTrack(audioFormat)).thenReturn(1);
        }
        when(video.getOutputFormat()).thenReturn(videoFormat);
        when(audio.getOutputFormat()).thenReturn(audioFormat);
        AtomicInteger videoStep = new AtomicInteger();
        AtomicBoolean videoAvailable = new AtomicBoolean(videoDelayMs == 0);
        when(video.dequeueOutputBuffer(any(MediaCodec.BufferInfo.class), eq(0L))).thenAnswer(call -> {
            if (!videoAvailable.get()) return MediaCodec.INFO_TRY_AGAIN_LATER;
            int step = videoStep.getAndIncrement();
            if (step == 0) return MediaCodec.INFO_OUTPUT_FORMAT_CHANGED;
            if (step > (realOutput == null ? 1 : 3)) return MediaCodec.INFO_TRY_AGAIN_LATER;
            call.getArgument(0, MediaCodec.BufferInfo.class).set(0, 9, (step - 1) * 100_000L,
                    MediaCodec.BUFFER_FLAG_KEY_FRAME);
            return 0;
        });
        when(video.getOutputBuffer(0)).thenAnswer(call -> bytes("000000016588840102"));
        AtomicInteger audioStep = new AtomicInteger();
        AtomicBoolean audioAvailable = new AtomicBoolean();
        when(audio.dequeueOutputBuffer(any(MediaCodec.BufferInfo.class), eq(0L))).thenAnswer(call -> {
            if (!audioAvailable.get()) return MediaCodec.INFO_TRY_AGAIN_LATER;
            int step = audioStep.getAndIncrement();
            if (step == 0) return MediaCodec.INFO_OUTPUT_FORMAT_CHANGED;
            if (step != 1 || !provideAudioSample) return MediaCodec.INFO_TRY_AGAIN_LATER;
            call.getArgument(0, MediaCodec.BufferInfo.class).set(0, 1, formatDelayMs * 1000, 0);
            return 0;
        });
        when(audio.getOutputBuffer(0)).thenAnswer(call -> ByteBuffer.wrap(new byte[] {3}));
        when(video.dequeueOutputBuffer(any(MediaCodec.BufferInfo.class), eq(10_000L))).thenAnswer(call -> {
            call.getArgument(0, MediaCodec.BufferInfo.class).set(0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            return 1;
        });
        when(audio.dequeueOutputBuffer(any(MediaCodec.BufferInfo.class), eq(10_000L))).thenAnswer(call -> {
            call.getArgument(0, MediaCodec.BufferInfo.class).set(0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            return 1;
        });
        ReflectionHelpers.setField(pipeline, "recordingStarted", true);
        ReflectionHelpers.setField(pipeline, "isRecording", true);
        ReflectionHelpers.setField(pipeline, "videoEncoder", video);
        ReflectionHelpers.setField(pipeline, "audioRecordingEnabled", requestedAudio);
        if (requestedAudio) {
            ReflectionHelpers.setField(pipeline, "audioEncoderStarted", true);
            ReflectionHelpers.setField(pipeline, "audioEncoder", audio);
        }
        ReflectionHelpers.setField(pipeline, "mediaMuxer", muxer);
        Handler drainHandler = mock(Handler.class);
        ReflectionHelpers.setField(pipeline, "drainHandler", drainHandler);
        Runnable drain = ReflectionHelpers.getField(pipeline, "drainTask");
        Handler timeline = new Handler(Looper.getMainLooper());
        if (requestedAudio) {
            timeline.postDelayed(() -> ReflectionHelpers.callInstanceMethod(
                    pipeline, "startMuxerWithoutLateAudio"), 5_000);
        }
        if (requestedAudio && formatDelayMs >= 0) {
            timeline.postDelayed(() -> {
                audioAvailable.set(true);
                drain.run();
            }, formatDelayMs);
        }
        if (videoDelayMs != 0) {
            timeline.postDelayed(() -> {
                videoAvailable.set(true);
                drain.run();
            }, videoDelayMs);
        }
        if (pause) {
            timeline.postDelayed(pipeline::pauseRecording, 4_000);
            timeline.postDelayed(() -> {
                pipeline.resumeRecording();
                drain.run();
            }, 5_500);
        }
        drain.run();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(stopAtMs));
        if (pause) verify(drainHandler).postDelayed(eq(drain), eq(25L));
        timeline.removeCallbacksAndMessages(null);

        if (requestedAudio && (!hasAudioTrack || !provideAudioSample)) {
            IllegalStateException failure = assertThrows(IllegalStateException.class, pipeline::stopRecording);
            assertTrue(failure.getMessage().contains("오디오"));
            if (realOutput != null) assertArrayEquals("no writer or cleanup failure", new Throwable[0],
                    failure.getSuppressed());
        } else {
            pipeline.stopRecording();
        }
        if (hasAudioTrack && provideAudioSample) verify(audio).releaseOutputBuffer(0, false);
        if (realOutput == null) {
            verify(muxer).start();
            verify(muxer).stop();
            verify(muxer).writeSampleData(eq(0), any(), any());
            verify(muxer, hasAudioTrack ? times(1) : never()).addTrack(audioFormat);
            verify(muxer, hasAudioTrack && provideAudioSample ? times(1) : never()).writeSampleData(eq(1), any(), any());
        }
    }
}

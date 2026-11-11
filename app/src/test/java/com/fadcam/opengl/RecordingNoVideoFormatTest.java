package com.fadcam.opengl;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.app.Application;
import android.media.MediaCodec;
import com.fadcam.VideoCodec;
import com.fadcam.media.FragmentedMp4MuxerWrapper;
import java.io.File;
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
public class RecordingNoVideoFormatTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();

    @Test public void silentRecordingEosBeforeVideoFormatReportsSaveFailure() throws Exception {
        File target = files.newFile("no-format.mp4");
        GLRecordingPipeline pipeline = pipeline(target);
        pipeline.setAudioEnabledForSession(false);
        MediaCodec codec = mock(MediaCodec.class);
        when(codec.dequeueOutputBuffer(any(MediaCodec.BufferInfo.class), anyLong())).thenAnswer(call -> {
            call.getArgument(0, MediaCodec.BufferInfo.class)
                    .set(0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            return 0;
        });
        ReflectionHelpers.setField(pipeline, "recordingStarted", true);
        ReflectionHelpers.setField(pipeline, "isRecording", true);
        ReflectionHelpers.setField(pipeline, "videoEncoder", codec);
        FragmentedMp4MuxerWrapper realWriter = new FragmentedMp4MuxerWrapper(target.getPath());
        ReflectionHelpers.setField(pipeline, "mediaMuxer", realWriter);
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class, pipeline::stopRecording);
            assertTrue("unexpected save failure: " + failure + "; cause: " + failure.getCause(), failure.getMessage().contains("영상"));
            assertTrue("EOS was received, but no output was produced",
                    ReflectionHelpers.<Boolean>getField(pipeline, "videoEosReceived"));
            verify(codec).releaseOutputBuffer(0, false);
            verify(codec).release();
            assertTrue(target.isFile());
            assertEquals(0L, target.length());
        } finally {
            realWriter.release();
        }
    }

    @Test public void releasingPreviewWithoutRecordingDoesNotReportSaveFailure() throws Exception {
        pipeline(files.newFile("preview-placeholder.mp4")).stopRecording();
    }

    private GLRecordingPipeline pipeline(File output) {
        return new GLRecordingPipeline(RuntimeEnvironment.getApplication(), null, 640, 480, 30,
                output.getPath(), 0, 1, null, "portrait", 0, VideoCodec.AVC, null, null);
    }
}

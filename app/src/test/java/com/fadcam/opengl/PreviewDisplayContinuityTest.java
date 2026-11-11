package com.fadcam.opengl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import com.fadcam.VideoCodec;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public class PreviewDisplayContinuityTest {
    private GLRecordingPipeline pipeline(GLWatermarkRenderer renderer) {
        GLRecordingPipeline pipeline=new GLRecordingPipeline(RuntimeEnvironment.getApplication(),null,1920,1080,30,
                "unused-preview-display-test.mp4",0L,1,null,"portrait",90,VideoCodec.AVC,null,null);
        ReflectionHelpers.setField(pipeline,"glRenderer",renderer);
        ReflectionHelpers.setField(pipeline,"previewOnlyRendering",true);
        ReflectionHelpers.setField(pipeline,"handler",new Handler(Looper.getMainLooper()));
        return pipeline;
    }

    @Test public void displayResizeIsQueuedAndDoesNotReconfigureEncoderOrReplaceSurfaces() {
        GLWatermarkRenderer renderer=mock(GLWatermarkRenderer.class);
        GLRecordingPipeline pipeline=pipeline(renderer);
        ReflectionHelpers.setField(pipeline,"encoderWidth",1080);
        ReflectionHelpers.setField(pipeline,"encoderHeight",1920);
        pipeline.updatePreviewOnlyDisplay(Surface.ROTATION_90,1440,640);
        verifyNoInteractions(renderer);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        verify(renderer).setPreviewOnlyDisplay(Surface.ROTATION_90,1440,640);
        verifyNoMoreInteractions(renderer);
        assertEquals(1080,(int)ReflectionHelpers.getField(pipeline,"encoderWidth"));
        assertEquals(1920,(int)ReflectionHelpers.getField(pipeline,"encoderHeight"));
        assertEquals("portrait",pipeline.getOrientation());
    }

    @Test public void queuedResizeIsDiscardedIfRecordingHandoffOrCleanupOvertakesIt() {
        GLWatermarkRenderer renderer=mock(GLWatermarkRenderer.class);
        GLRecordingPipeline pipeline=pipeline(renderer);
        pipeline.updatePreviewOnlyDisplay(Surface.ROTATION_90,1440,640);
        ReflectionHelpers.setField(pipeline,"previewOnlyRendering",false);
        ReflectionHelpers.setField(pipeline,"isRecording",true);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        verifyNoInteractions(renderer);
        pipeline.updatePreviewOnlyDisplay(Surface.ROTATION_270,1440,640);
        Shadows.shadowOf(Looper.getMainLooper()).idle();verifyNoInteractions(renderer);
        ReflectionHelpers.setField(pipeline,"isRecording",false);
        ReflectionHelpers.setField(pipeline,"previewOnlyRendering",true);
        pipeline.updatePreviewOnlyDisplay(Surface.ROTATION_270,1440,640);
        ReflectionHelpers.setField(pipeline,"released",true);
        Shadows.shadowOf(Looper.getMainLooper()).idle();verifyNoInteractions(renderer);
    }

    @Test public void previewAspectFollowsBothDisplaySidesWithoutChangingEncoderDimensions() {
        GLWatermarkRenderer renderer=new GLWatermarkRenderer(RuntimeEnvironment.getApplication(),null,"portrait",90,1920,1080,false);
        renderer.setEncoderDimensions(1080,1920);
        for(int rotation:new int[]{Surface.ROTATION_0,Surface.ROTATION_90,Surface.ROTATION_270,Surface.ROTATION_180}) {
            boolean landscape=rotation==Surface.ROTATION_90 || rotation==Surface.ROTATION_270;
            renderer.setPreviewOnlyDisplay(rotation,landscape?1440:640,landscape?640:1440);
            assertEquals(landscape?1920:1080,(int)ReflectionHelpers.getField(renderer,"previewContentWidth"));
            assertEquals(landscape?1080:1920,(int)ReflectionHelpers.getField(renderer,"previewContentHeight"));
            assertEquals(landscape?"landscape":"portrait",ReflectionHelpers.getField(renderer,"userOrientationSetting"));
            assertEquals(rotation,(int)ReflectionHelpers.getField(renderer,"deviceOrientation"));
            assertEquals(1080,(int)ReflectionHelpers.getField(renderer,"encoderWidth"));
            assertEquals(1920,(int)ReflectionHelpers.getField(renderer,"encoderHeight"));
        }
    }

    @Test public void invalidOrUnavailableDisplayRequestsDoNotQueueRendererWork() {
        GLWatermarkRenderer renderer=mock(GLWatermarkRenderer.class);
        GLRecordingPipeline pipeline=pipeline(renderer);
        pipeline.updatePreviewOnlyDisplay(-1,1440,640);
        pipeline.updatePreviewOnlyDisplay(Surface.ROTATION_90,0,640);
        ReflectionHelpers.setField(pipeline,"handler",null);
        pipeline.updatePreviewOnlyDisplay(Surface.ROTATION_90,1440,640);
        Shadows.shadowOf(Looper.getMainLooper()).idle();verifyNoInteractions(renderer);
    }
}

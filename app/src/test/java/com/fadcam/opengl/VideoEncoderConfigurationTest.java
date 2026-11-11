package com.fadcam.opengl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import android.app.Application;
import android.media.MediaCodec;
import android.media.MediaFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public class VideoEncoderConfigurationTest {
    private MediaFormat format(int prepend) {
        MediaFormat f=MediaFormat.createVideoFormat("video/avc",1080,1920);
        f.setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES,prepend);
        f.setInteger(MediaFormat.KEY_BIT_RATE,7000000);
        return f;
    }
    @Test public void acceptedConfigurationRetainsHeaderAndDoesNotReset() {
        MediaCodec codec=mock(MediaCodec.class); MediaFormat f=format(1);
        GLRecordingPipeline.configureVideoEncoder(codec,f);
        assertEquals(1,f.getInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES));
        verify(codec).configure(f,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
        verifyNoMoreInteractions(codec);
    }
    @Test public void rejectedOptionalPrependRetriesOncePreservingFormat() {
        MediaCodec codec=mock(MediaCodec.class); MediaFormat f=format(1);
        List<Integer> attempts=new ArrayList<>();
        doAnswer(call->{
            int value=f.getInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES);
            attempts.add(value);
            if(value==1)throw new IllegalArgumentException("prepend unsupported");
            return null;
        }).when(codec).configure(f,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
        GLRecordingPipeline.configureVideoEncoder(codec,f);
        assertEquals(Arrays.asList(1,0),attempts);
        assertEquals(1080,f.getInteger(MediaFormat.KEY_WIDTH));
        assertEquals(1920,f.getInteger(MediaFormat.KEY_HEIGHT));
        assertEquals(7000000,f.getInteger(MediaFormat.KEY_BIT_RATE));
        verify(codec).reset();
    }
    @Test public void persistentFailureStopsAfterOneRetry() {
        MediaCodec codec=mock(MediaCodec.class); MediaFormat f=format(1);
        doThrow(new IllegalArgumentException("size unsupported")).when(codec).configure(f,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
        assertThrows(IllegalArgumentException.class,()->GLRecordingPipeline.configureVideoEncoder(codec,f));
        verify(codec,times(2)).configure(f,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
        verify(codec).reset();
    }
    @Test public void existingDisabledPrependPolicyNeverRetries() {
        MediaCodec codec=mock(MediaCodec.class); MediaFormat f=format(0);
        doThrow(new IllegalArgumentException("rejected")).when(codec).configure(f,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
        assertThrows(IllegalArgumentException.class,()->GLRecordingPipeline.configureVideoEncoder(codec,f));
        verify(codec).configure(f,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
        verify(codec,never()).reset();
    }
}

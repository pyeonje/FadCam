package com.fadcam.opengl;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import android.app.Application;
import android.media.AudioRecord;
import android.media.MediaCodec;
import com.fadcam.VideoCodec;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public class AudioEosTimelineTest {
    @Test public void stopWhilePausedDoesNotExtendEosPastLastQueuedPcm() throws Exception {
        GLRecordingPipeline pipeline=new GLRecordingPipeline(RuntimeEnvironment.getApplication(),null,1920,1080,30,
                "unused-eos-test.mp4",0L,1,null,"landscape",90,VideoCodec.AVC,null,null);
        AudioRecord record=mock(AudioRecord.class);MediaCodec encoder=mock(MediaCodec.class);
        ReflectionHelpers.setField(pipeline,"audioRecordingEnabled",true);
        ReflectionHelpers.setField(pipeline,"audioRecord",record);
        ReflectionHelpers.setField(pipeline,"audioEncoder",encoder);
        ReflectionHelpers.setField(pipeline,"audioSampleRate",48000);
        ReflectionHelpers.setField(pipeline,"audioChannelCount",2);
        ReflectionHelpers.setField(pipeline,"recordingStartSystemTimeNanos",System.nanoTime());
        when(record.getRecordingState()).thenReturn(AudioRecord.RECORDSTATE_RECORDING);
        when(record.read(any(byte[].class),eq(0),anyInt())).thenReturn(1024);
        when(encoder.dequeueInputBuffer(anyLong())).thenReturn(0);
        when(encoder.getInputBuffer(0)).thenAnswer(call->ByteBuffer.allocate(1024));
        CountDownLatch pcm=new CountDownLatch(1),eos=new CountDownLatch(1);
        AtomicLong pcmPts=new AtomicLong(-1),eosPts=new AtomicLong(-1);
        doAnswer(call->{
            long timestamp=call.getArgument(3);int flags=call.getArgument(4);
            if((flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0){eosPts.set(timestamp);eos.countDown();}
            else {
                pcmPts.set(timestamp);ReflectionHelpers.setField(pipeline,"isPaused",true);pcm.countDown();
            }
            return null;
        }).when(encoder).queueInputBuffer(anyInt(),eq(0),anyInt(),anyLong(),anyInt());
        try {
            ReflectionHelpers.callInstanceMethod(pipeline,"startAudioThread");
            assertTrue("PCM queued",pcm.await(3,TimeUnit.SECONDS));
            // Represents paused time and codec-drain delay, which are not media.
            Thread.sleep(100);
            ReflectionHelpers.setField(pipeline,"audioThreadRunning",false);
            assertTrue("EOS queued",eos.await(3,TimeUnit.SECONDS));
            Thread worker=ReflectionHelpers.getField(pipeline,"audioThread");worker.join(1000);assertFalse(worker.isAlive());
            // 1024 bytes / (stereo*16-bit) =256PCMframes at48kHz.
            assertEquals("EOS must end captured PCM, not the later stop wall clock",pcmPts.get()+5333,eosPts.get());
        } finally {
            ReflectionHelpers.setField(pipeline,"audioThreadRunning",false);
            Thread worker=ReflectionHelpers.getField(pipeline,"audioThread");if(worker!=null)worker.join(1000);
        }
    }
}

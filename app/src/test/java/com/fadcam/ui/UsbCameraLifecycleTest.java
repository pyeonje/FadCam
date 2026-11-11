package com.fadcam.ui;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.Manifest;
import android.app.Application;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Looper;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import com.fadcam.R;
import com.mikimn.droiduvc.StreamFormat;
import com.mikimn.droiduvc.StreamMode;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

/** Real activity requests and callbacks with an explicitly advanced USB worker. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class UsbCameraLifecycleTest {
    private static final StreamMode A=new StreamMode(StreamFormat.MJPEG,1280,720,30);
    private static final StreamMode B=new StreamMode(StreamFormat.YUYV,640,480,30);
    private static final StreamMode C=new StreamMode(StreamFormat.MJPEG,1024,576,24);
    private static final UsbCameraBackend.FocusState MF=new UsbCameraBackend.FocusState(true,true,false,200);
    private ActivityController<ControlledActivity> controller;
    private ControlledActivity activity;
    private UsbManager usb;
    private UsbDevice first,second;
    private SurfaceTexture texture;
    private TextureView preview;

    public static class ControlledActivity extends UsbCameraActivity {
        final ArrayDeque<Runnable> queue=new ArrayDeque<>();
        final ArrayDeque<UsbCameraBackend> backends=new ArrayDeque<>();
        final ArrayDeque<Surface> surfaces=new ArrayDeque<>();
        Runnable insideOpen=()->{};
        boolean quit, reject;
        UsbManager openedManager;
        UsbDevice openedDevice;

        @Override public void onCreate(Bundle state){setTheme(R.style.Base_Theme_FadCam);super.onCreate(state);}
        @Override void startWorker(){}
        @Override boolean postWorker(Runnable work){return !quit&&!reject&&queue.offer(work);}
        @Override void quitWorker(){quit=true;}
        @Override Surface createSurface(SurfaceTexture texture){return surfaces.remove();}
        @Override UsbCameraBackend openBackend(UsbManager manager,UsbDevice device)throws IOException{
            openedManager=manager;
            openedDevice=device;
            insideOpen.run();
            if(backends.isEmpty())throw new IOException("no test backend");
            return backends.remove();
        }

        void drain(){
            for(int n=0;n<30;n++){
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                if(queue.isEmpty())return;
                queue.remove().run();
            }
            fail("USB worker queue did not settle");
        }
    }

    @Before public void setup(){
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.CAMERA);
        controller=Robolectric.buildActivity(ControlledActivity.class).setup();
        activity=controller.get();
        usb=mock(UsbManager.class);
        ReflectionHelpers.setField(activity,"usb",usb);
        first=device("usb-one");second=device("usb-two");
        texture=mock(SurfaceTexture.class);
        preview=mock(TextureView.class);
        when(preview.getWidth()).thenReturn(640);
        when(preview.getHeight()).thenReturn(480);
        ReflectionHelpers.setField(activity,"preview",preview);
        activity.selectDevice(first);
        activity.drain();
    }

    @After public void teardown(){
        destroy();
        activity.drain();
        activity.finishWorker();
    }

    private void destroy(){if(controller!=null){controller.pause().stop().destroy();controller=null;}}

    private static UsbDevice device(String name){
        UsbDevice device=mock(UsbDevice.class);
        when(device.getDeviceName()).thenReturn(name);
        when(device.getProductName()).thenReturn(name);
        return device;
    }

    private UsbCameraBackend backend(Surface surface,StreamMode negotiated){
        UsbCameraBackend backend=mock(UsbCameraBackend.class);
        when(backend.modes()).thenReturn(Arrays.asList(A,B));
        when(backend.start(eq(surface),any(StreamMode.class))).thenReturn(negotiated);
        when(backend.focusState()).thenReturn(MF);
        activity.backends.add(backend);
        activity.surfaces.add(surface);
        return backend;
    }

    private void attach(){
        when(usb.hasPermission(first)).thenReturn(true);
        activity.onSurfaceTextureAvailable(texture,640,480);
    }

    private Button button(String field){return ReflectionHelpers.getField(activity,field);}
    private String status(){TextView view=ReflectionHelpers.getField(activity,"status");return view.getText().toString();}
    private SeekBar focus(){return ReflectionHelpers.getField(activity,"focus");}
    private SeekBar.OnSeekBarChangeListener focusListener(){return ReflectionHelpers.getField(focus(),"mOnSeekBarChangeListener");}
    private void manual(int progress){
        SeekBar slider=focus();
        focusListener().onProgressChanged(slider,progress,true);
        focusListener().onStopTrackingTouch(slider);
    }

    @Test public void failedStartKeepsModesAndExplicitRetryUsesCapturedModeAndNegotiatedAspect(){
        Surface a=mock(Surface.class),b=mock(Surface.class);
        UsbCameraBackend failed=backend(a,A),working=backend(b,C);
        when(failed.start(a,A)).thenThrow(new IllegalStateException("unsupported mode"));
        attach();
        activity.drain();

        assertTrue(button("modesButton").isEnabled());
        assertFalse(button("photo").isEnabled());
        assertEquals(activity.getString(R.string.usb_camera_failed),status());
        InOrder failedOwnership=inOrder(failed,a);
        failedOwnership.verify(failed).close();
        failedOwnership.verify(a).release();

        activity.selectMode(first,B);
        ReflectionHelpers.setField(activity,"requested",A); // queued request must retain its own mode
        activity.drain();

        verify(working).start(b,B);
        assertTrue(status().contains(UsbCameraActivity.modeLabel(C)));
        ArgumentCaptor<Matrix> transform=ArgumentCaptor.forClass(Matrix.class);
        verify(preview).setTransform(transform.capture());
        float[] values=new float[9];
        transform.getValue().getValues(values);
        assertEquals(1f,values[Matrix.MSCALE_X],.001f);
        assertEquals(.75f,values[Matrix.MSCALE_Y],.001f);
        assertFalse(button("photo").isEnabled());
        activity.onSurfaceTextureUpdated(mock(SurfaceTexture.class));
        assertFalse(button("photo").isEnabled());
        when(texture.getTimestamp()).thenReturn(1L);
        activity.onSurfaceTextureUpdated(texture);
        assertTrue(button("photo").isEnabled());

        activity.selectDevice(second);
        assertFalse(button("modesButton").isEnabled());
        activity.selectMode(first,B);
        activity.drain();
        assertFalse(button("photo").isEnabled());
    }

    @Test public void stopWhileOpeningSkipsModesStartAndFocusAndReleasesOnlyOwnedSurface(){
        Surface surface=mock(Surface.class);
        UsbCameraBackend abandoned=backend(surface,A);
        activity.insideOpen=activity::stopSession;
        attach();
        activity.drain();

        verify(abandoned,never()).modes();
        verify(abandoned,never()).start(any(),any());
        verify(abandoned,never()).focusState();
        verify(abandoned).close();
        verify(surface).release();
        verifyNoInteractions(texture);
        assertFalse(button("photo").isEnabled());
        assertFalse(button("modesButton").isEnabled());
    }

    @Test public void deviceChangeDuringModeEnumerationSkipsOldStartAndDropsModeList(){
        Surface surface=mock(Surface.class);
        UsbCameraBackend abandoned=backend(surface,A);
        when(abandoned.modes()).thenAnswer(call->{activity.selectDevice(second);return Arrays.asList(A,B);});
        attach();
        activity.drain();

        assertSame(first,activity.openedDevice);
        assertSame(usb,activity.openedManager);
        verify(abandoned,never()).start(any(),any());
        verify(abandoned,never()).focusState();
        verify(abandoned).close();
        verify(surface).release();
        assertFalse(button("modesButton").isEnabled());
        assertFalse(button("photo").isEnabled());
    }

    @Test public void permissionResultMustMatchRequestIdDeviceGrantedFlagAndCurrentSystemPermission(){
        ArgumentCaptor<PendingIntent> replies=ArgumentCaptor.forClass(PendingIntent.class);
        activity.requestUsbPermission();
        verify(usb).requestPermission(eq(first),replies.capture());
        Intent firstReply=Shadows.shadowOf(replies.getValue()).getSavedIntent();
        activity.selectDevice(second);
        activity.requestUsbPermission();
        verify(usb).requestPermission(eq(second),replies.capture());
        Intent secondReply=Shadows.shadowOf(replies.getValue()).getSavedIntent();
        BroadcastReceiver receiver=ReflectionHelpers.getField(activity,"receiver");
        TextView message=ReflectionHelpers.getField(activity,"status");
        message.setText("current-device");
        receiver.onReceive(activity,reply(firstReply,first,true));
        receiver.onReceive(activity,reply(firstReply,first,false));
        receiver.onReceive(activity,reply(secondReply,first,false));
        assertEquals("current-device",status());

        receiver.onReceive(activity,reply(secondReply,second,true)); // manager still denies
        assertEquals(activity.getString(R.string.usb_camera_denied),status());
        verify(usb,never()).openDevice(any());

        activity.requestUsbPermission();
        verify(usb,times(2)).requestPermission(eq(second),replies.capture());
        Intent latest=Shadows.shadowOf(replies.getValue()).getSavedIntent();
        when(usb.hasPermission(second)).thenReturn(true);
        receiver.onReceive(activity,reply(latest,second,false)); // result itself denied
        assertEquals(activity.getString(R.string.usb_camera_denied),status());
        verify(usb,never()).openDevice(any());

        Surface surface=mock(Surface.class);
        UsbCameraBackend current=backend(surface,C);
        when(usb.hasPermission(second)).thenReturn(false);
        activity.onSurfaceTextureAvailable(texture,640,480);
        activity.requestUsbPermission();
        verify(usb,times(3)).requestPermission(eq(second),replies.capture());
        Intent currentReply=Shadows.shadowOf(replies.getValue()).getSavedIntent();
        when(usb.hasPermission(second)).thenReturn(true);
        receiver.onReceive(activity,reply(latest,second,true)); // same device, obsolete request ID
        activity.drain();
        assertNull(activity.openedDevice);
        verify(current,never()).start(any(),any());

        receiver.onReceive(activity,reply(currentReply,second,true));
        receiver.onReceive(activity,reply(currentReply,second,true));
        activity.drain();
        assertSame(second,activity.openedDevice);
        verify(current).start(surface,A);
    }

    private static Intent reply(Intent request,UsbDevice device,boolean granted){
        return new Intent(request).putExtra(UsbManager.EXTRA_DEVICE,device)
                .putExtra(UsbManager.EXTRA_PERMISSION_GRANTED,granted);
    }

    @Test public void returningWithoutCameraPermissionClearsModesAndShowsCameraPermissionRequirement(){
        Surface surface=mock(Surface.class);
        UsbCameraBackend running=backend(surface,A);
        android.hardware.usb.UsbInterface video=mock(android.hardware.usb.UsbInterface.class);
        when(video.getInterfaceClass()).thenReturn(android.hardware.usb.UsbConstants.USB_CLASS_VIDEO);
        when(first.getInterfaceCount()).thenReturn(1);
        when(first.getInterface(0)).thenReturn(video);
        java.util.HashMap<String,UsbDevice> devices=new java.util.HashMap<>();
        devices.put(first.getDeviceName(),first);
        when(usb.getDeviceList()).thenReturn(devices);
        attach();
        activity.drain();
        assertTrue(button("modesButton").isEnabled());

        Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.CAMERA);
        activity.refreshDevices();
        activity.drain();
        assertEquals(activity.getString(R.string.usb_camera_camera_required),status());
        assertFalse(button("modesButton").isEnabled());
        verify(running).close();
        verify(surface).release();
    }

    @Test public void surfaceDestroyedBeforeActivityReleasesNativeThenSurfaceThenTextureExactlyOnce(){
        Surface surface=mock(Surface.class);
        UsbCameraBackend running=backend(surface,A);
        attach();
        activity.drain();
        assertFalse(activity.onSurfaceTextureDestroyed(texture));
        assertFalse(activity.onSurfaceTextureDestroyed(texture));
        destroy();
        verify(running,never()).close();
        verify(texture,never()).release();

        activity.drain();
        activity.finishWorker();
        assertOrderedRelease(running,surface);
    }

    @Test public void surfaceDestroyedAfterActivityWithRejectedPostIsOwnedByWorkerFinalizer(){
        Surface surface=mock(Surface.class);
        UsbCameraBackend running=backend(surface,A);
        attach();
        activity.drain();
        activity.reject=true;
        destroy();
        assertTrue(activity.quit);
        assertFalse(activity.onSurfaceTextureDestroyed(texture));
        verify(running,never()).close();
        verify(texture,never()).release();

        activity.finishWorker();
        assertOrderedRelease(running,surface);
        assertFalse(activity.onSurfaceTextureDestroyed(texture));
    }

    @Test public void cancelDuringStartAndDestroyBeforeNativeReturnSkipsFocusAndLateUi(){
        Surface surface=mock(Surface.class);
        UsbCameraBackend abandoned=backend(surface,A);
        when(abandoned.start(surface,A)).thenAnswer(call->{
            destroy();
            assertFalse(activity.onSurfaceTextureDestroyed(texture));
            verify(abandoned,never()).close();
            verify(texture,never()).release();
            return A;
        });
        attach();
        activity.drain();
        activity.finishWorker();

        verify(abandoned,never()).focusState();
        assertOrderedRelease(abandoned,surface);
        assertFalse(button("photo").isEnabled());
        assertFalse(status().contains(UsbCameraActivity.modeLabel(A)));
    }

    @Test public void finishedWorkerWithoutASurfaceLeavesAnyLaterTextureForFramework(){
        destroy();
        activity.drain();
        activity.finishWorker();
        assertTrue(activity.onSurfaceTextureDestroyed(texture));
        verifyNoInteractions(texture);
    }

    private void assertOrderedRelease(UsbCameraBackend backend,Surface surface){
        InOrder order=inOrder(backend,surface,texture);
        order.verify(backend).close();
        order.verify(surface).release();
        order.verify(texture).release();
        verify(backend,times(1)).close();
        verify(surface,times(1)).release();
        verify(texture,times(1)).release();
    }

    @Test public void manualInputAfterRunningAutoFailureWinsAndOldCompletionDoesNotOverwriteUi(){
        Surface surface=mock(Surface.class);
        UsbCameraBackend running=backend(surface,A);
        when(running.setAutoFocus(true)).thenAnswer(call->{
            manual(740);
            assertEquals("MF",button("auto").getText().toString());
            throw new IllegalStateException("device rejected AF");
        });
        when(running.setFocus(740)).thenReturn(new UsbCameraBackend.FocusState(true,true,false,740));
        attach();
        activity.drain();

        button("auto").performClick();
        activity.drain();

        InOrder commands=inOrder(running);
        commands.verify(running).setAutoFocus(true);
        commands.verify(running).setFocus(740);
        assertEquals("MF",button("auto").getText().toString());
        assertEquals(740,focus().getProgress());
        assertTrue(status().contains(UsbCameraActivity.modeLabel(A)));
    }

    @Test public void sessionChangeDropsQueuedFocusAndOldCompletion(){
        Surface firstSurface=mock(Surface.class),secondSurface=mock(Surface.class);
        UsbCameraBackend old=backend(firstSurface,A),fresh=backend(secondSurface,C);
        when(old.setFocus(420)).thenAnswer(call->{
            manual(730);
            activity.selectDevice(second);
            when(usb.hasPermission(second)).thenReturn(true);
            activity.openSelected();
            return new UsbCameraBackend.FocusState(true,true,false,420);
        });
        when(fresh.focusState()).thenReturn(new UsbCameraBackend.FocusState(true,true,true,81));
        attach();
        activity.drain();

        manual(420);
        activity.drain();

        verify(old,never()).setFocus(730);
        verify(fresh,never()).setFocus(anyInt());
        assertEquals("AF",button("auto").getText().toString());
        assertEquals(81,focus().getProgress());
        assertTrue(status().contains(UsbCameraActivity.modeLabel(C)));
    }

    @Test public void reusedTextureLayoutCallbackCannotEnablePhotoUntilNewStreamFrameArrives(){
        Surface oldSurface=mock(Surface.class),newSurface=mock(Surface.class);
        backend(oldSurface,A);
        backend(newSurface,C);
        when(texture.getTimestamp()).thenReturn(10L);
        attach();
        activity.drain();
        when(texture.getTimestamp()).thenReturn(20L);
        activity.onSurfaceTextureUpdated(texture);
        assertTrue(button("photo").isEnabled());

        activity.selectMode(first,B);
        activity.drain();
        assertFalse(button("photo").isEnabled());
        activity.onSurfaceTextureUpdated(texture); // resize can update TextureView without another camera buffer
        assertFalse(button("photo").isEnabled());
        when(texture.getTimestamp()).thenReturn(21L);
        activity.onSurfaceTextureUpdated(texture);
        assertTrue(button("photo").isEnabled());
    }
}

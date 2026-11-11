package com.fadcam.ui;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import android.Manifest;
import android.app.Application;
import android.content.*;
import android.hardware.usb.*;
import android.os.*;
import com.fadcam.*;
import com.mikimn.droiduvc.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public class UsbCameraIntegrationTest {
    @Test public void onlyUsbVideoInterfacesAreAccepted(){
        UsbDevice d=mock(UsbDevice.class);UsbInterface generic=mock(UsbInterface.class),video=mock(UsbInterface.class);when(d.getInterfaceCount()).thenReturn(2);when(d.getInterface(0)).thenReturn(generic);when(d.getInterface(1)).thenReturn(video);when(generic.getInterfaceClass()).thenReturn(UsbConstants.USB_CLASS_MASS_STORAGE);when(video.getInterfaceClass()).thenReturn(UsbConstants.USB_CLASS_VIDEO);assertTrue(UsbCameraBackend.isVideoDevice(d));when(video.getInterfaceClass()).thenReturn(UsbConstants.USB_CLASS_VENDOR_SPEC);assertFalse(UsbCameraBackend.isVideoDevice(d));assertFalse(UsbCameraBackend.isVideoDevice(null));
    }
    @Test public void rangeMappingPreservesDeviceStepAndClampsExtremes(){
        ControlRange range=new ControlRange(10,90,4,50);assertEquals(10,UsbCameraBackend.valueFor(-1,range));assertEquals(90,UsbCameraBackend.valueFor(1001,range));assertEquals(50,UsbCameraBackend.valueFor(500,range));assertEquals(500,UsbCameraBackend.progressFor(50,range));assertEquals(0,UsbCameraBackend.progressFor(0,range));assertEquals(1000,UsbCameraBackend.progressFor(500,range));
    }
    @Test public void defaultModeIsAdvertisedRatherThanInventedFiveMegapixels(){
        StreamMode small=new StreamMode(StreamFormat.YUYV,640,480,30),hd=new StreamMode(StreamFormat.MJPEG,1280,720,30),large=new StreamMode(StreamFormat.MJPEG,2592,1944,5);assertSame(hd,UsbCameraActivity.initialMode(Arrays.asList(small,hd,large)));assertSame(small,UsbCameraActivity.initialMode(Collections.singletonList(small)));
    }
    @Test public void integratedEmptyScreenDoesNotStartBuiltInCameraOrModifyItsPreset(){
        Application app=RuntimeEnvironment.getApplication();ReflectionHelpers.setStaticField(SharedPreferencesManager.class,"instance",null);SharedPreferencesManager p=SharedPreferencesManager.getInstance(app);p.setSavedAfMode(1);p.setSpecificZoomRatio(CameraType.BACK,2.5f);Map<String,?> before=new HashMap<>(p.sharedPreferences.getAll());
        ActivityController<UsbCameraActivity> c=Robolectric.buildActivity(UsbCameraActivity.class);c.setup();UsbCameraActivity a=c.get();assertTrue(a.devices().isEmpty());assertNull(Shadows.shadowOf(app).getNextStartedService());c.pause().stop().destroy();assertEquals(before,p.sharedPreferences.getAll());
    }
    @Test public void usbPermissionIsPerDeviceAndMissingCameraPermissionDoesNotOpen(){
        Application app=RuntimeEnvironment.getApplication();Shadows.shadowOf(app).grantPermissions(Manifest.permission.CAMERA);ActivityController<UsbCameraActivity> c=Robolectric.buildActivity(UsbCameraActivity.class).setup();UsbCameraActivity a=c.get();UsbManager manager=mock(UsbManager.class);UsbDevice d=mock(UsbDevice.class);ReflectionHelpers.setField(a,"usb",manager);ReflectionHelpers.setField(a,"selected",d);when(manager.hasPermission(d)).thenReturn(false);a.requestUsbPermission();verify(manager).requestPermission(eq(d),any(android.app.PendingIntent.class));verify(manager,never()).openDevice(any());Shadows.shadowOf(app).denyPermissions(Manifest.permission.CAMERA);a.openSelected();verify(manager,never()).openDevice(any());c.pause().stop().destroy();
    }
    @Test public void unsupportedFocusIsDisabledAndLeavingInvalidatesQueuedWrites(){
        ActivityController<UsbCameraActivity> c=Robolectric.buildActivity(UsbCameraActivity.class).setup();UsbCameraActivity a=c.get();ReflectionHelpers.setField(a,"streaming",true);a.bindFocus(new UsbCameraBackend.FocusState(false,false,false,0));android.widget.SeekBar focus=ReflectionHelpers.getField(a,"focus");android.widget.Button auto=ReflectionHelpers.getField(a,"auto");assertFalse(focus.isEnabled());assertFalse(auto.isEnabled());int before=ReflectionHelpers.getField(a,"generation");a.stopSession();assertTrue((int)ReflectionHelpers.getField(a,"generation")>before);assertFalse((boolean)ReflectionHelpers.getField(a,"streaming"));c.pause().stop().destroy();
    }
    @Test public void obsoleteUsbPermissionResultDoesNotReplaceCurrentDeviceStatus(){
        ActivityController<UsbCameraActivity> c=Robolectric.buildActivity(UsbCameraActivity.class).setup();
        UsbCameraActivity a=c.get();
        try {
            UsbDevice current=mock(UsbDevice.class), old=mock(UsbDevice.class);
            when(current.getDeviceName()).thenReturn("current");when(old.getDeviceName()).thenReturn("old");
            UsbManager manager=mock(UsbManager.class);ReflectionHelpers.setField(a,"usb",manager);ReflectionHelpers.setField(a,"selected",current);
            android.widget.TextView status=ReflectionHelpers.getField(a,"status");status.setText("current-device-status");
            BroadcastReceiver receiver=ReflectionHelpers.getField(a,"receiver");
            receiver.onReceive(a,new Intent(a.getPackageName()+".USB_CAMERA_PERMISSION")
                    .putExtra(UsbManager.EXTRA_DEVICE,old).putExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false));
            assertEquals("current-device-status",status.getText().toString());
            verify(manager,never()).openDevice(any());
        } finally { c.pause().stop().destroy(); }
    }
    @Test public void lastAutoFocusCancelsManualWriteWaitingBehindInFlightManual(){
        ActivityController<UsbCameraActivity> c=Robolectric.buildActivity(UsbCameraActivity.class).setup();
        UsbCameraActivity a=c.get();
        try {
            ArrayDeque<Runnable> queued=new ArrayDeque<>();
            Handler dispatcher=mock(Handler.class);when(dispatcher.post(any(Runnable.class))).thenAnswer(call->queued.offer(call.getArgument(0)));
            UsbCameraBackend backend=mock(UsbCameraBackend.class);
            when(backend.setFocus(anyInt())).thenAnswer(call->new UsbCameraBackend.FocusState(true,true,false,call.getArgument(0)));
            when(backend.setAutoFocus(true)).thenReturn(new UsbCameraBackend.FocusState(true,true,true,200));
            ReflectionHelpers.setField(a,"worker",dispatcher);ReflectionHelpers.setField(a,"backend",backend);
            ReflectionHelpers.setField(a,"streaming",true);a.bindFocus(new UsbCameraBackend.FocusState(true,true,false,200));
            android.widget.SeekBar focus=ReflectionHelpers.getField(a,"focus");
            android.widget.SeekBar.OnSeekBarChangeListener listener=ReflectionHelpers.getField(focus,"mOnSeekBarChangeListener");
            android.widget.Button auto=ReflectionHelpers.getField(a,"auto");
            when(backend.setFocus(400)).thenAnswer(call->{
                listener.onProgressChanged(focus,700,true);
                auto.performClick();
                return new UsbCameraBackend.FocusState(true,true,false,400);
            });
            listener.onProgressChanged(focus,400,true);listener.onStopTrackingTouch(focus);
            for(int n=0;!queued.isEmpty()&&n<10;n++){
                queued.remove().run();Shadows.shadowOf(Looper.getMainLooper()).idle();
            }
            verify(backend).setFocus(400);verify(backend).setAutoFocus(true);verify(backend,never()).setFocus(700);
            assertEquals("AF",auto.getText().toString());
        } finally { c.pause().stop().destroy(); }
    }
}

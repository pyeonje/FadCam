package com.fadcam.ui;

import android.hardware.usb.*;
import android.view.Surface;
import com.mikimn.droiduvc.*;
import java.util.*;

/** Blocking UVC operations. Call only from the USB worker; never touches built-in camera preferences. */
interface UsbCameraBackend extends AutoCloseable {
    List<StreamMode> modes();
    StreamMode start(Surface surface, StreamMode mode);
    FocusState focusState();
    FocusState setFocus(int progress);
    FocusState setAutoFocus(boolean enabled);
    void close();

    final class FocusState {
        final boolean manualSupported, autoSupported, automatic;
        final int progress;
        FocusState(boolean manual,boolean auto,boolean automatic,int progress){this.manualSupported=manual;this.autoSupported=auto;this.automatic=automatic;this.progress=progress;}
    }
    static boolean isVideoDevice(UsbDevice device){
        if(device==null)return false;
        for(int n=0;n<device.getInterfaceCount();n++)if(device.getInterface(n).getInterfaceClass()==UsbConstants.USB_CLASS_VIDEO)return true;
        return false;
    }
    static int progressFor(int value,ControlRange r){return r==null||r.getMax()<=r.getMin()?0:(int)Math.round(1000d*Math.max(0d,Math.min(1d,(value-(double)r.getMin())/(r.getMax()-(double)r.getMin()))));}
    static int valueFor(int progress,ControlRange r){
        if(r==null||r.getMax()<r.getMin())throw new IllegalArgumentException("Invalid focus range");
        long step=Math.max(1,r.getStep()),span=(long)r.getMax()-r.getMin();
        long ticks=Math.round(Math.max(0,Math.min(1000,progress))/1000d*span/step);
        return (int)Math.min(r.getMax(),r.getMin()+ticks*step);
    }
    final class Native implements UsbCameraBackend {
        private UsbDeviceConnection connection;
        private UvcCamera camera;
        Native(UsbManager usb,UsbDevice device) throws Exception {
            connection=usb.openDevice(device);
            if(connection==null)throw new java.io.IOException("USB open denied");
            try{camera=UvcCamera.Companion.open(connection.getFileDescriptor());}catch(Exception|LinkageError e){connection.close();connection=null;throw e;}
        }
        public List<StreamMode> modes(){return camera.supportedModes();}
        public StreamMode start(Surface surface,StreamMode mode){return camera.start(surface,mode);}
        public FocusState focusState(){
            CameraControls c=camera.getControls();Control focus=c.getFocus(),automatic=c.getAutoFocus();
            boolean autoSupported=false,autoValue=false,usable=false;int progress=0;
            if(automatic!=null)try{autoValue=automatic.getValue()!=0;autoSupported=true;}catch(Exception unavailable){}
            if(focus!=null)try{ControlRange range=focus.getRange();usable=range!=null&&range.getMax()>range.getMin();if(usable)progress=progressFor(focus.getValue(),range);}catch(Exception unavailable){usable=false;}
            return new FocusState(usable,autoSupported,autoValue,progress);
        }
        public FocusState setFocus(int progress){
            CameraControls c=camera.getControls();Control focus=c.getFocus();
            if(focus==null)throw new IllegalStateException("Focus not supported");
            if(c.getAutoFocus()!=null)c.getAutoFocus().set(0);
            focus.set(valueFor(progress,focus.getRange()));return focusState();
        }
        public FocusState setAutoFocus(boolean enabled){
            Control control=camera.getControls().getAutoFocus();if(control==null)throw new IllegalStateException("AF not supported");
            control.set(enabled?1:0);return focusState();
        }
        public void close(){try{if(camera!=null){try{camera.stop();}finally{camera.close();}}}finally{camera=null;if(connection!=null)connection.close();connection=null;}}
    }
}

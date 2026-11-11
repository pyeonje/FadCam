package com.fadcam.utils;

import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import com.fadcam.CameraType;

/** Latest real capture result. Missing values remain unknown; never fabricate AUTO readings. */
public final class PersonalCameraTelemetry {
    private PersonalCameraTelemetry() { }
    public static final class Snapshot {
        public final CameraType camera;
        public final String id;
        public final Integer iso;
        public final Long exposure;
        public final Float focus;
        public final android.hardware.camera2.params.ColorSpaceTransform colorTransform;
        public final boolean autoWhiteBalance;
        Snapshot(CameraType camera,String id,TotalCaptureResult result) {
            this.camera=camera;this.id=id;
            iso=result.get(CaptureResult.SENSOR_SENSITIVITY);
            exposure=result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            focus=result.get(CaptureResult.LENS_FOCUS_DISTANCE);
            colorTransform=result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM);
            autoWhiteBalance=Integer.valueOf(CaptureRequest.CONTROL_AWB_MODE_AUTO).equals(result.get(CaptureResult.CONTROL_AWB_MODE));
        }
    }
    private static volatile Snapshot latest;
    public static Snapshot current(CameraType camera,String id) {
        Snapshot value=latest;
        return value!=null && value.camera==camera && value.id.equals(id)?value:null;
    }
    public static CameraCaptureSession.CaptureCallback callback(CameraType camera,String id) {
        latest=null;
        return new CameraCaptureSession.CaptureCallback() {
            @Override public void onCaptureCompleted(CameraCaptureSession session,CaptureRequest request,TotalCaptureResult result) {
                latest=new Snapshot(camera,id,result);
            }
        };
    }
}

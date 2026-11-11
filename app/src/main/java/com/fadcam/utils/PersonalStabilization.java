package com.fadcam.utils;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;

/** Camera2 recommends avoiding simultaneous optical and digital stabilization. */
public final class PersonalStabilization {
    private PersonalStabilization() { }
    public static void apply(CaptureRequest.Builder builder, CameraCharacteristics c, boolean enabled) {
        boolean optical = enabled && c != null && PersonalProSettings.contains(
                c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION),
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
        boolean digital = enabled && !optical && c != null && PersonalProSettings.contains(
                c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES),
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);
        builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, optical
                ? CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON : CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
        builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, digital
                ? CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON : CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
    }
}

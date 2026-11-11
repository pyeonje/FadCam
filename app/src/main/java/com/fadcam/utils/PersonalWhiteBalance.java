package com.fadcam.utils;

import android.hardware.camera2.params.RggbChannelVector;

/**
 * Kelvin-to-RGGB conversion adapted from Open Camera / OW Camera contributors.
 * GPL-3.0-or-later, matching this application's license.
 * Source: https://github.com/jamsinclair/ow-camera-android/blob/development/app/src/main/java/net/sourceforge/opencamera/cameracontroller/CameraController2.java
 * Functions convertTemperatureToRggb and RGBtoGain. This generic conversion is
 * independent of Samsung's proprietary color processing.
 */
public final class PersonalWhiteBalance {
    private PersonalWhiteBalance() { }
    public static RggbChannelVector gains(int kelvin) {
        double temperature=Math.max(2300,Math.min(10000,kelvin))/100.0;
        double red=temperature<=66?255:329.698727446*Math.pow(temperature-60,-.1332047592);
        double green=temperature<=66?99.4708025861*Math.log(temperature)-161.1195681661
                :288.1221695283*Math.pow(temperature-60,-.0755148492);
        double blue=temperature>=66?255:138.5177312231*Math.log(temperature-10)-305.0447927307;
        float r=gain(red/255),g=gain(green/255)/2,b=gain(blue/255);
        return new RggbChannelVector(r,g,g,b);
    }
    private static float gain(double value) {
        value=Math.max(0,Math.min(1,value));
        return (float)(value<1e-5?10:Math.min(10,1/value));
    }
}

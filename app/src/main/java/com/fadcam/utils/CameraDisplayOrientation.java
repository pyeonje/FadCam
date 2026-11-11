package com.fadcam.utils;

import android.content.Context;
import android.view.Surface;
import android.view.WindowManager;
import com.fadcam.SharedPreferencesManager;

/** Snapshot the starting display direction; an active file never changes it. */
public final class CameraDisplayOrientation {
    private CameraDisplayOrientation() { }
    @SuppressWarnings("deprecation")
    public static int currentRotation(Context context) {
        WindowManager windows = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        return windows != null && windows.getDefaultDisplay() != null
                ? windows.getDefaultDisplay().getRotation() : Surface.ROTATION_0;
    }
    public static int resolve(Context context, String savedOrientation) {
        boolean landscape = SharedPreferencesManager.ORIENTATION_LANDSCAPE.equals(savedOrientation);
        int fallback = landscape ? Surface.ROTATION_90 : Surface.ROTATION_0;
        int rotation = currentRotation(context);
        boolean displayLandscape = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270;
        // A background quick launch honors its saved aspect even when the launcher
        // is portrait. Foreground camera capture uses either matching sensor side.
        return landscape == displayLandscape ? rotation : fallback;
    }
}

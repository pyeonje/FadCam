package com.fadcam.utils;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import androidx.core.content.ContextCompat;

/** Camera access is required for both silent and audible recording. */
public final class RecordingPermissions {
    private RecordingPermissions() {}

    public static boolean canRecord(Context context, boolean audioEnabled) {
        return context != null
                && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
                && (!audioEnabled || ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED);
    }
}

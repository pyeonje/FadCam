package com.fadcam;

import com.fadcam.FLog;
import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;
import androidx.core.content.ContextCompat;
import com.fadcam.dualcam.service.DualCameraRecordingService;
import com.fadcam.services.RecordingService;

public class RecordingStartActivity extends Activity {
    private static final String TAG = "RecordingStartActivity";
    public static final String EXTRA_SHORTCUT_CAMERA_MODE = "shortcut_camera_mode";
    public static final String CAMERA_MODE_BACK = "back";
    public static final String CAMERA_MODE_FRONT = "front";
    public static final String CAMERA_MODE_CURRENT = "current";
    public static final String CAMERA_MODE_DUAL = "dual";
    private boolean openingSetup;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        try {
            SharedPreferencesManager sharedPreferencesManager = SharedPreferencesManager.getInstance(this);
            // Both permissions are required by the upstream recording engine, even for muted video.
            boolean missingPermissions = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED
                    || ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED;
            if (missingPermissions || !sharedPreferencesManager.sharedPreferences
                    .getBoolean(Constants.COMPLETED_ONBOARDING_KEY, false)) {
                openingSetup = true;
                if (missingPermissions) {
                    sharedPreferencesManager.setShowOnboarding(true);
                    Toast.makeText(this, R.string.toast_permissions_required, Toast.LENGTH_LONG).show();
                }
                startActivity(new Intent(this, MainActivity.class));
                return;
            }
            // Check if recording is already in progress
            if (sharedPreferencesManager.isRecordingInProgress()) {
                // Utils.showQuickToast(this, R.string.video_recording_started);
                Utils.showQuickToast(this, R.string.video_recording_started);

                finish();
                return;
            }

            String mode = getIntent() != null
                    ? getIntent().getStringExtra(EXTRA_SHORTCUT_CAMERA_MODE)
                    : null;
            if (mode == null) {
                mode = CAMERA_MODE_BACK;
            }
            if (CAMERA_MODE_FRONT.equals(mode)) {
                sharedPreferencesManager.sharedPreferences.edit()
                        .putString(Constants.PREF_CAMERA_SELECTION, CameraType.FRONT.name())
                        .apply();
            } else if (CAMERA_MODE_BACK.equals(mode)) {
                sharedPreferencesManager.sharedPreferences.edit()
                        .putString(Constants.PREF_CAMERA_SELECTION, CameraType.BACK.name())
                        .apply();
            } else if (CAMERA_MODE_DUAL.equals(mode)) {
                sharedPreferencesManager.sharedPreferences.edit()
                        .putString(Constants.PREF_CAMERA_SELECTION, CameraType.DUAL_PIP.name())
                        .apply();
            }

            CameraType selectedCamera = sharedPreferencesManager.getCameraSelection();
            boolean shouldStartDual = CAMERA_MODE_DUAL.equals(mode)
                    || (CAMERA_MODE_CURRENT.equals(mode)
                    && selectedCamera != null
                    && selectedCamera.isDual());

            if (shouldStartDual) {
                Intent startDualIntent = new Intent(this, DualCameraRecordingService.class);
                startDualIntent.setAction(Constants.INTENT_ACTION_START_DUAL_RECORDING);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(this, startDualIntent);
                } else {
                    startService(startDualIntent);
                }
            } else {
                // Use the same intent as the main app's start recording
                Intent startIntent = new Intent(this, RecordingService.class);
                startIntent.setAction(Constants.INTENT_ACTION_START_RECORDING);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(this, startIntent);
                } else {
                    startService(startIntent);
                }
            }

        } catch (Exception e) {
            FLog.e(TAG, "Error starting recording via shortcut", e);
            Toast.makeText(this, R.string.personal_home_recording_failed, Toast.LENGTH_SHORT).show();
        } finally {
            // Prevent app from coming to foreground
            if (!openingSetup) {
                moveTaskToBack(true);
            }
            finish();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        finish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!openingSetup) {
            moveTaskToBack(true);
        }
    }
}

package com.fadcam;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import com.fadcam.dualcam.service.DualCameraRecordingService;
import com.fadcam.services.RecordingService;

/**
 * No-UI entry point that routes a launcher shortcut to the existing recording
 * start or stop activity.
 */
public class RecordingToggleActivity extends Activity {
    private static final String TAG = "RecordingToggleActivity";
    private static final RecordingToggleGuard TOGGLE_GUARD = new RecordingToggleGuard();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            if (!TOGGLE_GUARD.tryAcquire(SystemClock.elapsedRealtime())) {
                FLog.w(TAG, "Ignoring duplicate recording toggle request");
                return;
            }

            SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(this);
            boolean dualActive = DualCameraRecordingService.hasActiveSession();
            boolean singleActive = RecordingService.hasActiveSession();

            Intent controlIntent;
            if (dualActive || singleActive) {
                FLog.i(TAG, "Stopping the active " + (dualActive ? "dual" : "single") + " recording service");
                controlIntent = dualActive
                        ? new Intent(this, DualCameraRecordingService.class).setAction(Constants.INTENT_ACTION_STOP_DUAL_RECORDING)
                        : new Intent(this, RecordingService.class).setAction(Constants.INTENT_ACTION_STOP_RECORDING);
                startService(controlIntent);
            } else {
                // Process death can leave persisted flags/timestamps behind. They
                // are recovery metadata, not evidence that a recording still exists.
                prefs.setRecordingInProgress(false);
                prefs.sharedPreferences.edit()
                        .remove(Constants.PREF_RECORDING_START_TIME)
                        .remove(Constants.PREF_RECORDING_PAUSE_STARTED_AT)
                        .remove(Constants.PREF_RECORDING_ACCUMULATED_PAUSED_DURATION).apply();
                FLog.i(TAG, "Routing toggle request to recording start");
                controlIntent = new Intent(this, RecordingStartActivity.class)
                        .putExtra(
                                RecordingStartActivity.EXTRA_SHORTCUT_CAMERA_MODE,
                                RecordingStartActivity.CAMERA_MODE_CURRENT);
                controlIntent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                startActivity(controlIntent);
            }
        } catch (Exception e) {
            FLog.e(TAG, "Error toggling recording via shortcut", e);
        } finally {
            finishWithoutUi();
        }
    }

    void finishWithoutUi() {
        // Do NOT moveTaskToBack() here: it backgrounds the whole task in the same
        // synchronous block that launched the chained start/stop activity, so that
        // activity's onCreate (which dispatches the service command) gets aborted
        // and the press does nothing. The chained activity backgrounds the task
        // itself AFTER dispatching, via its own moveTaskToBack() in finally.
        finish();
    }
}

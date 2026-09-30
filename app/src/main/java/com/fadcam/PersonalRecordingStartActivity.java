package com.fadcam;

import android.os.Bundle;

/** Launcher entry that records with the camera and video settings saved in the main app. */
public class PersonalRecordingStartActivity extends RecordingStartActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        getIntent().putExtra(EXTRA_SHORTCUT_CAMERA_MODE, CAMERA_MODE_CURRENT);
        super.onCreate(savedInstanceState);
    }
}

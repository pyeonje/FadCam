package com.fadcam;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.fadcam.ui.WatchMainActivity;
import com.fadcam.utils.RuntimeCompat;

/**
 * Simple splash screen activity showing the FadSecLab flag centered.
 * Uses a short delay, then routes to the appropriate main activity:
 * - Wear OS: {@link WatchMainActivity} (minimal watch UI)
 * - Phone/tablet: {@link MainActivity}
 */
public class SplashActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Class<?> destination = RuntimeCompat.shouldUseWatchUi(this)
                ? WatchMainActivity.class : MainActivity.class;
        startActivity(new Intent(this, destination));
        finish();
    }
}

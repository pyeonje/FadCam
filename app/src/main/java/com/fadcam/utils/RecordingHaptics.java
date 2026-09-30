package com.fadcam.utils;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import com.fadcam.FLog;
import java.util.function.Consumer;

/** Service-owned confirmation of actual recording transitions, independent of UI touch feedback. */
public final class RecordingHaptics {
    private enum Phase { IDLE, ARMED, ACTIVE, FINISHED }
    private Phase phase = Phase.IDLE;
    private final Consumer<Context> startFeedback, stopFeedback;

    public RecordingHaptics() {
        this(context -> play(context, new long[]{0, 120}),
                context -> play(context, new long[]{0, 100, 80, 180}));
    }

    RecordingHaptics(Consumer<Context> startFeedback, Consumer<Context> stopFeedback) {
        this.startFeedback = startFeedback;
        this.stopFeedback = stopFeedback;
    }

    /** Called only after the service accepts a new recording session. */
    public synchronized void beginSession() {
        phase = Phase.ARMED;
    }

    /** A short pulse after the encoder and camera session successfully start. */
    public synchronized void onRecordingStarted(Context context) {
        if (phase != Phase.ARMED) return;
        phase = Phase.ACTIVE;
        startFeedback.accept(context);
    }

    /** Two pulses when a successfully started session ends; startup failures stay silent. */
    public synchronized void onRecordingStopped(Context context) {
        boolean wasActive = phase == Phase.ACTIVE;
        phase = Phase.FINISHED;
        if (wasActive) stopFeedback.accept(context);
    }

    @SuppressWarnings("deprecation")
    private static void play(Context context, long[] pattern) {
        try {
            Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else {
                vibrator.vibrate(pattern, -1);
            }
        } catch (RuntimeException e) {
            // Feedback failure must never interrupt the recording or stop operation.
            FLog.w("RecordingHaptics", "Recording confirmation vibration unavailable", e);
        }
    }
}

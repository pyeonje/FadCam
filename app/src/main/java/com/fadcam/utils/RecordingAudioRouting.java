package com.fadcam.utils;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioRecord;
import com.fadcam.FLog;
import com.fadcam.SharedPreferencesManager;

/** Per-recorder input routing. Never takes playback focus or changes global output/call mode. */
public final class RecordingAudioRouting {
    private static final String TAG = "RecordingAudioRouting";

    private RecordingAudioRouting() {}

    public static AudioDeviceInfo resolveInput(Context context, SharedPreferencesManager prefs) {
        if (SharedPreferencesManager.AUDIO_INPUT_SOURCE_WIRED.equals(prefs.getAudioInputSource())) {
            return AudioDeviceResolver.resolvePreferredInput(context, prefs.getAudioInputSource(),
                    prefs.getAudioInputDeviceType(), prefs.getAudioInputDeviceName());
        }
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        AudioDeviceInfo[] inputs = manager == null ? null : manager.getDevices(AudioManager.GET_DEVICES_INPUTS);
        if (inputs != null) {
            for (AudioDeviceInfo device : inputs) {
                if (device != null && device.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                    return device;
                }
            }
        }
        FLog.w(TAG, "Phone microphone not enumerated; input routing remains system-controlled");
        return null;
    }

    public static void configureInput(AudioRecord record, AudioDeviceInfo preferred) {
        try {
            if (preferred != null) {
                boolean accepted = record.setPreferredDevice(preferred);
                FLog.i(TAG, "Microphone preference type=" + preferred.getType() + " accepted=" + accepted
                        + " (actual route is checked after capture starts)");
            }
            record.addOnRoutingChangedListener(router -> logActualInput(record, preferred), null);
        } catch (RuntimeException e) {
            FLog.w(TAG, "Microphone routing preference unavailable; system controls the actual route", e);
        }
    }

    public static void logActualInput(AudioRecord record, AudioDeviceInfo preferred) {
        AudioDeviceInfo actual = record.getRoutedDevice();
        if (actual == null) {
            FLog.w(TAG, "Actual microphone route not available yet");
        } else if (preferred != null && actual.getId() != preferred.getId()) {
            FLog.w(TAG, "System selected a different microphone: requested=" + preferred.getType()
                    + ", actual=" + actual.getType());
        } else {
            FLog.i(TAG, "Actual microphone type=" + actual.getType() + " name=" + actual.getProductName());
        }
    }
}

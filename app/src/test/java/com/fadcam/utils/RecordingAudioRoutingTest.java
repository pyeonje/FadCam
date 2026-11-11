package com.fadcam.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaCodec;
import com.fadcam.Constants;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.VideoCodec;
import com.fadcam.opengl.GLRecordingPipeline;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class RecordingAudioRoutingTest {
    private Context context;
    private AudioManager manager;
    private SharedPreferencesManager prefs;
    private AudioDeviceInfo phone, buds;

    @Before public void setup() {
        Context app = RuntimeEnvironment.getApplication();
        ReflectionHelpers.setStaticField(SharedPreferencesManager.class, "instance", null);
        app.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit();
        prefs = SharedPreferencesManager.getInstance(app);
        context = spy(app);
        manager = mock(AudioManager.class);
        doReturn(manager).when(context).getSystemService(Context.AUDIO_SERVICE);
        phone = device(1, AudioDeviceInfo.TYPE_BUILTIN_MIC, "Phone");
        buds = device(2, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Buds");
        when(manager.getDevices(AudioManager.GET_DEVICES_INPUTS))
                .thenReturn(new AudioDeviceInfo[]{buds, phone});
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO);
    }

    private AudioDeviceInfo device(int id, int type, String name) {
        AudioDeviceInfo device = mock(AudioDeviceInfo.class);
        when(device.getId()).thenReturn(id);
        when(device.getType()).thenReturn(type);
        when(device.getProductName()).thenReturn(name);
        return device;
    }

    @Test public void phoneInputWinsEvenWithBudsConnectedAndOldExternalDeviceSaved() {
        prefs.setAudioInputSource(SharedPreferencesManager.AUDIO_INPUT_SOURCE_PHONE);
        prefs.setAudioInputDeviceType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
        assertSame(phone, RecordingAudioRouting.resolveInput(context, prefs));
        verify(manager).getDevices(AudioManager.GET_DEVICES_INPUTS);
        verifyNoMoreInteractions(manager);
    }

    @Test public void explicitExternalMicrophoneSelectionStillWorksWithoutGlobalRouting() {
        prefs.setAudioInputSource(SharedPreferencesManager.AUDIO_INPUT_SOURCE_WIRED);
        prefs.setAudioInputDeviceType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
        prefs.setAudioInputDeviceName("Buds");
        assertSame(buds, RecordingAudioRouting.resolveInput(context, prefs));
        verify(manager).getDevices(AudioManager.GET_DEVICES_INPUTS);
        verifyNoMoreInteractions(manager);
    }

    @Test public void absentPhoneInputDoesNotSilentlyPickBuds() {
        when(manager.getDevices(AudioManager.GET_DEVICES_INPUTS)).thenReturn(new AudioDeviceInfo[]{buds});
        assertNull(RecordingAudioRouting.resolveInput(context, prefs));
    }

    @Test public void realRecordingAudioSetupDoesNotInterruptMediaOrChangeOutput() throws Exception {
        prefs.setRecordAudioEnabled(true);
        AudioRecord record = mock(AudioRecord.class);
        when(record.getState()).thenReturn(AudioRecord.STATE_INITIALIZED);
        when(record.setPreferredDevice(phone)).thenReturn(true);
        MediaCodec codec = mock(MediaCodec.class);
        try (MockedStatic<MediaCodec> codecs = mockStatic(MediaCodec.class);
             MockedStatic<AudioRecord> records = mockStatic(AudioRecord.class);
             MockedConstruction<AudioRecord.Builder> builders = mockConstruction(AudioRecord.Builder.class,
                     withSettings().defaultAnswer(RETURNS_SELF),
                     (builder, mocked) -> when(builder.build()).thenReturn(record))) {
            codecs.when(() -> MediaCodec.createEncoderByType("audio/mp4a-latm")).thenReturn(codec);
            GLRecordingPipeline pipeline = new GLRecordingPipeline(context, null, 1920, 1080, 30,
                    "unused-test-output.mp4", 0L, 1, null, "portrait", 90, VideoCodec.AVC, null, null);
            clearInvocations(manager);
            ReflectionHelpers.callInstanceMethod(pipeline, "setupAudio");
            assertTrue(ReflectionHelpers.getField(pipeline, "audioRecordingEnabled"));
            verify(record).setPreferredDevice(phone);
            verify(codec).start();
            // Covers the actual production setup method, not just a routing helper.
            // No focus, output, speakerphone, communication-device or SCO mutations.
            verifyNoInteractions(manager);
            verify(record, never()).startRecording(); // setup itself does not start capture.
        }
    }
}

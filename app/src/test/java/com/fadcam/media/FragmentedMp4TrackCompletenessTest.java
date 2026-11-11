package com.fadcam.media;

import static org.junit.Assert.*;

import android.app.Application;
import android.media.MediaCodec;
import android.media.MediaFormat;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class FragmentedMp4TrackCompletenessTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();

    @Test public void registeredAudioWithoutPacketsFailsButKeepsVideoSamples() throws Exception {
        assertOutput(true, false, true);
    }

    @Test public void registeredVideoWithoutPacketsFailsButKeepsAudioSamples() throws Exception {
        assertOutput(true, true, false);
    }

    @Test public void explicitlyVideoOnlyOutputStillSucceeds() throws Exception {
        assertOutput(false, false, true);
    }

    private void assertOutput(boolean audioRegistered, boolean sendAudio, boolean sendVideo) throws Exception {
        File destination = files.newFile();
        FragmentedMp4MuxerWrapper wrapper = new FragmentedMp4MuxerWrapper(destination.getPath());
        try {
            MediaFormat video = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 16, 16);
            video.setByteBuffer("csd-0", bytes("000000016764000aace47b011000000300100000030080f1225120"));
            video.setByteBuffer("csd-1", bytes("0000000168ebe3cb22c0"));
            int videoTrack = wrapper.addTrack(video);
            int audioTrack = -1;
            if (audioRegistered) {
                MediaFormat audio = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 2);
                audio.setByteBuffer("csd-0", bytes("1190"));
                audioTrack = wrapper.addTrack(audio);
            }
            wrapper.start();
            for (int i = 0; i < 3; i++) {
                if (sendVideo) write(wrapper, videoTrack, bytes("000000016588840102"), i, true);
                if (sendAudio) write(wrapper, audioTrack, bytes("211004608c"), i, false);
            }
            if (audioRegistered) {
                assertThrows("registered media with no payload must not count as saved",
                        IllegalStateException.class, wrapper::stop);
            } else {
                wrapper.stop();
            }
        } finally {
            wrapper.release();
        }
        ByteBuffer output = ByteBuffer.wrap(Files.readAllBytes(destination.toPath()));
        int outerMdat = output.getInt(0);
        assertEquals("mdat", fourcc(output, outerMdat + 4));
        int finalMoov = Math.toIntExact(outerMdat + output.getLong(outerMdat + 8));
        assertEquals("moov", fourcc(output, finalMoov + 4));
        String finalizedIndex = new String(output.array(), finalMoov, output.limit() - finalMoov,
                StandardCharsets.ISO_8859_1);
        assertEquals(sendAudio, finalizedIndex.contains("soun"));
        assertEquals(sendVideo, finalizedIndex.contains("vide"));
        int stsz = finalizedIndex.indexOf("stsz");
        assertTrue(stsz >= 0);
        assertEquals("three supplied samples remain recoverable", 3, output.getInt(finalMoov + stsz + 12));
    }

    private static void write(FragmentedMp4MuxerWrapper wrapper, int track, ByteBuffer payload,
            int position, boolean video) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        info.set(0, payload.remaining(), position * 100_000L, video ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
        wrapper.writeSampleData(track, payload, info);
    }

    private static String fourcc(ByteBuffer bytes, int position) {
        return new String(bytes.array(), position, 4, StandardCharsets.US_ASCII);
    }

    private static ByteBuffer bytes(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return ByteBuffer.wrap(bytes);
    }
}

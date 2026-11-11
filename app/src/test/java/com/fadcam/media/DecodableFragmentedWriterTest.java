package com.fadcam.media;

import static org.junit.Assert.*;

import android.app.Application;
import android.media.MediaCodec;
import android.media.MediaFormat;
import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class DecodableFragmentedWriterTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void actualAvcAndAacPacketsSurviveHybridFinalization() throws Exception {
        Properties source = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/playback/longrun-decodable.properties")) {
            assertNotNull(in);
            source.load(in);
        }
        MediaFormat video = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 16, 16);
        video.setByteBuffer("csd-0", ByteBuffer.wrap(decode(source.getProperty("video.sps"))));
        video.setByteBuffer("csd-1", ByteBuffer.wrap(decode(source.getProperty("video.pps"))));
        MediaFormat audio = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48_000, 1);
        audio.setByteBuffer("csd-0", ByteBuffer.wrap(decode(source.getProperty("audio.asc"))));
        File output = folder.newFile("decodable.mp4");
        FragmentedMp4MuxerWrapper writer = new FragmentedMp4MuxerWrapper(output.getAbsolutePath());
        try {
            List<Sample> samples = new ArrayList<>();
            addSamples(source, samples, "video", writer.addTrack(video), 0, MediaCodec.BUFFER_FLAG_KEY_FRAME);
            addSamples(source, samples, "audio", writer.addTrack(audio), 100_000, 0);
            samples.sort(Comparator.comparingLong(s -> s.pts));
            writer.start();
            for (Sample sample : samples) {
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                info.set(0, sample.data.length, sample.pts, sample.flags);
                writer.writeSampleData(sample.track, ByteBuffer.wrap(sample.data), info);
            }
            writer.stop();
        } finally {
            writer.release();
        }
        ByteBuffer mp4 = ByteBuffer.wrap(Files.readAllBytes(output.toPath()));
        int ftypEnd = mp4.getInt(0);
        assertEquals(0x6d646174, mp4.getInt(ftypEnd + 4)); // mdat
        int moov = Math.toIntExact(ftypEnd + mp4.getLong(ftypEnd + 8));
        assertEquals(0x6d6f6f76, mp4.getInt(moov + 4)); // moov
        assertEquals(mp4.capacity(), moov + mp4.getInt(moov));
        // Opt-in host decode evidence; never uses a device or external media.
        if ("1".equals(System.getenv("FADCAM_EXPORT_SYNTHETIC_QA"))) {
            File generated = new File("build/longrun-synthetic");
            Files.createDirectories(generated.toPath());
            Files.copy(output.toPath(), new File(generated, "writer-output.mp4").toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void addSamples(Properties p, List<Sample> samples, String key, int track, long offset, int flags) {
        for (int i = 0; i < Integer.parseInt(p.getProperty(key + ".count")); i++) {
            String[] entry = p.getProperty(key + "." + i).split(",", 2);
            samples.add(new Sample(track, offset + Long.parseLong(entry[0]), flags, decode(entry[1])));
        }
    }

    private static byte[] decode(String b64) { return Base64.getDecoder().decode(b64); }

    private static final class Sample {
        final int track, flags;
        final long pts;
        final byte[] data;
        Sample(int track, long pts, int flags, byte[] data) {
            this.track = track;
            this.pts = pts;
            this.flags = flags;
            this.data = data;
        }
    }
}

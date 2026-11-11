package com.fadcam.media;

import static org.junit.Assert.*;

import android.app.Application;
import android.media.MediaCodec;
import android.media.MediaFormat;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class FragmentedMp4SharedTimelineTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();

    @Test public void audioFirstKeepsZeroHundredFiveHundredAndTwoSecondOffsets() throws Exception {
        for (long delayUs : new long[]{0, 100_000, 500_000, 2_000_000}) {
            assertFileTimeline(writeFile(0, delayUs), 0, delayUs);
        }
    }

    @Test public void videoFirstKeepsZeroHundredFiveHundredAndTwoSecondOffsets() throws Exception {
        for (long delayUs : new long[]{0, 100_000, 500_000, 2_000_000}) {
            assertFileTimeline(writeFile(delayUs, 0), delayUs, 0);
        }
    }

    @Test public void newSegmentAndRestartDoNotKeepElapsedRecordingTimeAsFileDuration() throws Exception {
        assertFileTimeline(writeFile(3_600_500_000L, 3_600_000_000L), 500_000, 0);
        assertFileTimeline(writeFile(0, 100_000), 0, 100_000);
    }

    @Test public void lateEarlierPeerAfterFirstFragmentUsesKnownRolloverBound() throws Exception {
        long rollover = 3_600_000_000L;
        File dest = files.newFile();
        FragmentedMp4MuxerWrapper writer = new FragmentedMp4MuxerWrapper(dest.getPath());
        try {
            int video = writer.addTrack(videoFormat());
            int audio = writer.addTrack(audioFormat());
            writer.setFragmentLowerBoundUs(rollover);
            writer.start();
            for (int i = 0; i < 23; i++) sample(writer, video, rollover + 500_000, i, true);
            // The first 2-second fragment must reach disk before the peer delivers any packets.
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            boolean emitted = false;
            do {
                ByteBuffer prefix = ByteBuffer.wrap(Files.readAllBytes(dest.toPath()));
                if (prefix.limit() >= 8) {
                    int initMoov = prefix.getInt(0) + 16;
                    if (prefix.limit() >= initMoov + 8) {
                        int firstMoof = firstMoof(prefix);
                        emitted = prefix.limit() >= firstMoof + 8
                                && prefix.getInt(firstMoof + 4) == type("moof");
                    }
                }
                if (!emitted) Thread.sleep(5);
            } while (!emitted && System.nanoTime() < deadline);
            assertTrue("writer must publish the first fragment before the peer sample", emitted);
            for (int i = 0; i < 3; i++) sample(writer, audio, rollover, i, false);
            writer.stop();
        } finally {
            writer.release();
        }
        ByteBuffer mp4 = ByteBuffer.wrap(Files.readAllBytes(dest.toPath()));
        int first = firstMoof(mp4);
        int finalMoov = finalMoov(mp4);
        int second = first + mp4.getInt(first);
        assertEquals(type("mdat"), mp4.getInt(second + 4));
        second += mp4.getInt(second);
        assertEquals(type("moof"), mp4.getInt(second + 4));
        assertEquals(45_000L, decodeStart(mp4, fragmentTrack(mp4, first, 1)));
        assertEquals(0L, decodeStart(mp4, fragmentTrack(mp4, second, 2)));
        assertTrue(decodeStart(mp4, fragmentTrack(mp4, second, 1)) > 45_000L);
        int mvhd = child(mp4, finalMoov, "mvhd");
        assertTrue(Integer.toUnsignedLong(mp4.getInt(mvhd + 24)) < 3L * mp4.getInt(mvhd + 20));
    }

    private ByteBuffer writeFile(long audioStart, long videoStart) throws Exception {
        File dest = files.newFile();
        FragmentedMp4MuxerWrapper writer = new FragmentedMp4MuxerWrapper(dest.getPath());
        try {
            int video = writer.addTrack(videoFormat());
            int audio = writer.addTrack(audioFormat());
            writer.start();
            // Write whichever codec delivers first by PTS, with a separate fixture below for out-of-order arrival.
            for (int i = 0; i < 3; i++) {
                if (audioStart < videoStart) sample(writer, audio, audioStart, i, false);
                sample(writer, video, videoStart, i, true);
                if (audioStart >= videoStart) sample(writer, audio, audioStart, i, false);
            }
            writer.stop();
        } finally {
            writer.release();
        }
        return ByteBuffer.wrap(Files.readAllBytes(dest.toPath()));
    }

    private static MediaFormat videoFormat() {
        MediaFormat videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 16, 16);
        // SPS/PPS are from the existing synthetic H.264 test fixture, as Annex B.
        videoFormat.setByteBuffer("csd-0", bytes("000000016764000aace47b011000000300100000030080f1225120"));
        videoFormat.setByteBuffer("csd-1", bytes("0000000168ebe3cb22c0"));
        return videoFormat;
    }

    private static MediaFormat audioFormat() {
        MediaFormat audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 2);
        audioFormat.setByteBuffer("csd-0", bytes("1190"));
        return audioFormat;
    }

    private static ByteBuffer bytes(String hex) {
        byte[] data = new byte[hex.length() / 2];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return ByteBuffer.wrap(data);
    }

    private static void sample(FragmentedMp4MuxerWrapper writer, int track, long start, int i, boolean video) {
        ByteBuffer payload = video ? bytes("000000016588840102") : bytes("211004608c");
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        info.set(0, payload.remaining(), start + i * 100_000L, video ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
        writer.writeSampleData(track, payload, info);
    }

    private static int finalMoov(ByteBuffer mp4) {
        int mdat = mp4.getInt(0);
        assertEquals(type("mdat"), mp4.getInt(mdat + 4));
        int finalMoov = Math.toIntExact(mdat + mp4.getLong(mdat + 8));
        assertEquals(type("moov"), mp4.getInt(finalMoov + 4));
        return finalMoov;
    }

    private static int firstMoof(ByteBuffer mp4) {
        int initMoov = mp4.getInt(0) + 16;
        assertEquals(type("moov"), mp4.getInt(initMoov + 4));
        return initMoov + mp4.getInt(initMoov);
    }

    private static void assertFileTimeline(ByteBuffer mp4, long expectedAudioUs, long expectedVideoUs) {
        int finalMoov = finalMoov(mp4);
        int firstMoof = firstMoof(mp4);
        assertEquals(type("moof"), mp4.getInt(firstMoof + 4));
        int movieHeader = child(mp4, finalMoov, "mvhd");
        long movieTimescale = mp4.getInt(movieHeader + 20);
        long movieDurationUs = mp4.getInt(movieHeader + 24) * 1_000_000L / movieTimescale;
        assertEquals("movie duration includes the initial A/V delay",
                Math.max(expectedAudioUs, expectedVideoUs) + 300_000L, movieDurationUs);
        long audioEdit = assertTrack(mp4, finalMoov, firstMoof, "soun", movieTimescale, expectedAudioUs);
        long videoEdit = assertTrack(mp4, finalMoov, firstMoof, "vide", movieTimescale, expectedVideoUs);
        assertEquals("relative final MP4 presentation offset", expectedAudioUs - expectedVideoUs, audioEdit - videoEdit);
    }

    private static long assertTrack(ByteBuffer mp4, int moov, int moof, String handler,
            long movieTimescale, long expectedUs) {
        int trak = childMatching(mp4, moov, "trak", pos ->
                mp4.getInt(child(mp4, child(mp4, pos, "mdia"), "hdlr") + 16) == type(handler));
        assertTrue("missing " + handler + " track", trak >= 0);
        int edts = childMatching(mp4, trak, "edts", pos -> true);
        long editStartUs = 0;
        if (edts >= 0) {
            int elst = child(mp4, edts, "elst");
            assertEquals(1, mp4.get(elst + 8));
            assertEquals(2, mp4.getInt(elst + 12));
            assertEquals(-1L, mp4.getLong(elst + 24));
            editStartUs = mp4.getLong(elst + 16) * 1_000_000L / movieTimescale;
        }
        assertEquals(handler + " first presentation time in final moov", expectedUs, editStartUs);
        int tkhd = child(mp4, trak, "tkhd");
        int trackId = mp4.getInt(tkhd + 20);
        int mdia = child(mp4, trak, "mdia");
        int mdhd = child(mp4, mdia, "mdhd");
        long trackTimescale = mp4.getInt(mdhd + 20);
        int traf = fragmentTrack(mp4, moof, trackId);
        long firstDecodeTime = decodeStart(mp4, traf);
        assertEquals(handler + " first decode time in fMP4", expectedUs, firstDecodeTime * 1_000_000L / trackTimescale);
        int trun = child(mp4, traf, "trun");
        int stbl = child(mp4, child(mp4, mdia, "minf"), "stbl");
        int stsz = child(mp4, stbl, "stsz");
        assertEquals(3, mp4.getInt(stsz + 16));
        assertEquals(3, mp4.getInt(trun + 12));
        int stride = (mp4.getInt(trun + 8) & 0x800) == 0 ? 12 : 16;
        for (int i = 0; i < 3; i++) {
            int trunRow = trun + 20 + stride * i;
            assertTrue("sample time advances", mp4.getInt(trunRow) > 0);
            assertEquals("trun and stsz sizes", mp4.getInt(stsz + 20 + 4 * i), mp4.getInt(trunRow + 4));
        }
        return editStartUs;
    }

    private static int fragmentTrack(ByteBuffer mp4, int moof, int trackId) {
        int traf = childMatching(mp4, moof, "traf", pos -> mp4.getInt(child(mp4, pos, "tfhd") + 12) == trackId);
        assertTrue("missing fragment for track " + trackId, traf >= 0);
        return traf;
    }

    private static long decodeStart(ByteBuffer mp4, int traf) {
        int tfdt = child(mp4, traf, "tfdt");
        return mp4.get(tfdt + 8) == 1 ? mp4.getLong(tfdt + 12) : Integer.toUnsignedLong(mp4.getInt(tfdt + 12));
    }

    private static int type(String type) {
        return ByteBuffer.wrap(type.getBytes(java.nio.charset.StandardCharsets.US_ASCII)).getInt();
    }

    private static int child(ByteBuffer mp4, int parent, String type) {
        int pos = childMatching(mp4, parent, type, ignored -> true);
        assertTrue("missing box: " + type, pos >= 0);
        return pos;
    }

    private static int childMatching(ByteBuffer mp4, int parent, String type, java.util.function.IntPredicate match) {
        int end = parent + mp4.getInt(parent);
        for (int pos = parent + 8; pos + 8 <= end;) {
            int size = mp4.getInt(pos);
            assertTrue("invalid box extent", size >= 8 && size <= end - pos);
            if (mp4.getInt(pos + 4) == type(type) && match.test(pos)) return pos;
            pos += size;
        }
        return -1;
    }
}

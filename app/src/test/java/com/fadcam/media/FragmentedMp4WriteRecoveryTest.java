package com.fadcam.media;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import androidx.media3.muxer.FragmentedMp4Muxer;
import java.lang.reflect.InvocationTargetException;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicBoolean;
import android.app.Application;
import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
public class FragmentedMp4WriteRecoveryTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void shortWritesConsumeDurationMoovAndMdatSizedBuffers() throws Exception {
        for (int length : new int[] {4, 35, 16}) {
            byte[] data = new byte[length];
            for (int i=0;i<length;i++) data[i]=(byte)i;
            ByteArrayOutputStream saved = new ByteArrayOutputStream();
            FragmentedMp4MuxerWrapper.writeFully(new WritableByteChannel() {
                public boolean isOpen() { return true; }
                public void close() {}
                public int write(ByteBuffer src) {
                    int count = Math.min(3, src.remaining());
                    for (int i=0;i<count;i++) saved.write(src.get());
                    return count;
                }
            }, ByteBuffer.wrap(data));
            assertArrayEquals(data, saved.toByteArray());
        }
    }

    @Test public void zeroProgressIsBoundedAndReported() {
        AtomicInteger attempts = new AtomicInteger();
        WritableByteChannel blocked = new WritableByteChannel() {
            public boolean isOpen() { return true; }
            public void close() {}
            public int write(ByteBuffer src) { attempts.incrementAndGet(); return 0; }
        };
        assertThrows(IOException.class, () ->
                FragmentedMp4MuxerWrapper.writeFully(blocked, ByteBuffer.allocate(16)));
        assertEquals(16, attempts.get());
    }

    @Test public void partialFragmentIsTruncatedBeforeRealWrapperRetries() throws Exception {
        File destination = folder.newFile();
        FragmentedMp4MuxerWrapper wrapper = new FragmentedMp4MuxerWrapper(destination.getPath());
        Field stream = FragmentedMp4MuxerWrapper.class.getDeclaredField("fileOutputStream");
        stream.setAccessible(true);
        ((FileOutputStream) stream.get(wrapper)).close();
        AtomicInteger writes = new AtomicInteger();
        stream.set(wrapper, new FileOutputStream(destination) {
            @Override public void write(byte[] bytes) throws IOException {
                if (writes.incrementAndGet() == 2) {
                    super.write(bytes, 0, 2);
                    throw new IOException("injected partial fragment");
                }
                super.write(bytes);
            }
        });
        Method append = FragmentedMp4MuxerWrapper.class.getDeclaredMethod(
                "writeSegmentWithRecovery", byte[].class, boolean.class);
        append.setAccessible(true);
        try {
            append.invoke(wrapper, new byte[] {1,2,3,4}, true);
            append.invoke(wrapper, new byte[] {5,6,7,8}, false);
            assertArrayEquals(new byte[] {1,2,3,4,5,6,7,8}, Files.readAllBytes(destination.toPath()));
            assertEquals(3, writes.get());
        } finally {
            wrapper.release();
        }
    }

    @Test public void releaseClosesActualUnstartedPatchedWriterExactlyOnce() throws Exception {
        File destination = folder.newFile();
        FragmentedMp4MuxerWrapper wrapper = new FragmentedMp4MuxerWrapper(destination.getPath());
        Object muxer = get(wrapper, "muxer");
        Thread writer = (Thread) get(get(muxer, "fragmentedMp4Writer"), "writerThread");
        assertTrue(writer.isAlive());
        try {
            wrapper.release();
            assertFalse(writer.isAlive());
            assertEquals(0L, destination.length());
        } finally {
            wrapper.release();
        }
    }

    @Test public void actualFinalizationCompletesEveryShortWriteAndDoesNotDuplicateMoov() throws Exception {
        assertFinalizationAt(-1);
    }

    @Test public void partialFinalizationAtEachBoundaryRestoresBytesAndCanRetry() throws Exception {
        for (int failingOffset : new int[] {64, 56, 8}) {
            assertFinalizationAt(failingOffset);
        }
    }

    @Test public void startedDiskMuxerWithoutCompleteFragmentsReportsSaveFailure() throws Exception {
        File destination = folder.newFile();
        FragmentedMp4MuxerWrapper wrapper = new FragmentedMp4MuxerWrapper(destination.getPath());
        try {
            wrapper.start();
            IllegalStateException failure = assertThrows(IllegalStateException.class, wrapper::stop);
            assertTrue(failure.getCause() instanceof IOException);
            assertEquals(0L, destination.length());
        } finally {
            wrapper.release();
        }
    }

    private void assertFinalizationAt(int failingOffset) throws Exception {
        File destination = folder.newFile();
        FragmentedMp4MuxerWrapper wrapper = new FragmentedMp4MuxerWrapper(destination.getPath());
        FragmentedMp4Muxer actualMuxer = (FragmentedMp4Muxer) get(wrapper, "muxer");
        FragmentedMp4Muxer fixture = mock(FragmentedMp4Muxer.class);
        byte[] tail = {0,0,0,12,'m','o','o','v',9,8,7,6};
        when(fixture.buildFinalMoov(anyList(), anyList(), anyList(), anyList()))
                .thenAnswer(ignored -> ByteBuffer.wrap(tail.clone()));
        ByteBuffer initial = ByteBuffer.allocate(64);
        initial.putInt(8).putInt(0x66747970); // ftyp
        initial.putInt(16).putInt(0x66726565).putLong(0); // free
        initial.putInt(36).putInt(0x6d6f6f76); // moov
        initial.putInt(28).putInt(0x6d766864); // mvhd version 0
        initial.putInt(0).putInt(0).putInt(0).putInt(10_000).putInt(0);
        initial.putInt(0x01020304); // Existing fragment payload fixture
        byte[] original = initial.array();
        set(wrapper, "muxer", fixture);
        set(wrapper, "initSegmentData", java.util.Arrays.copyOf(original, 60));
        set(wrapper, "initSegmentFilePosition", 0L);
        set(wrapper, "committedOutputEnd", 64L);
        set(wrapper, "cumulativeDurationUs", 250_000L);
        Method finalize = FragmentedMp4MuxerWrapper.class.getDeclaredMethod("finalizeWithChannel", FileChannel.class);
        finalize.setAccessible(true);
        try (RandomAccessFile file = new RandomAccessFile(destination, "rw")) {
            file.write(original);
            FileChannel actual = file.getChannel();
            FileChannel shortChannel = mock(FileChannel.class);
            AtomicBoolean fail = new AtomicBoolean(failingOffset >= 0);
            when(shortChannel.size()).thenAnswer(ignored -> actual.size());
            when(shortChannel.position(anyLong())).thenAnswer(invocation -> {
                actual.position(invocation.getArgument(0, Long.class));
                return shortChannel;
            });
            when(shortChannel.truncate(anyLong())).thenAnswer(invocation -> {
                actual.truncate(invocation.getArgument(0, Long.class));
                return shortChannel;
            });
            when(shortChannel.write(any(ByteBuffer.class))).thenAnswer(invocation -> {
                ByteBuffer source = invocation.getArgument(0);
                boolean inject = actual.position() == failingOffset && fail.compareAndSet(true, false);
                ByteBuffer prefix = source.duplicate();
                prefix.limit(prefix.position() + Math.min(inject ? 2 : 3, prefix.remaining()));
                int count = actual.write(prefix);
                source.position(source.position() + count);
                if (inject) throw new IOException("부분 쓰기 주입");
                return count;
            });
            if (failingOffset >= 0) {
                InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                        () -> finalize.invoke(wrapper, shortChannel));
                assertTrue(failure.getCause() instanceof IOException);
                assertArrayEquals(original, Files.readAllBytes(destination.toPath()));
            }
            finalize.invoke(wrapper, shortChannel);
            byte[] saved = Files.readAllBytes(destination.toPath());
            assertEquals(64 + tail.length, saved.length);
            ByteBuffer bytes = ByteBuffer.wrap(saved);
            assertEquals(1, bytes.getInt(8));
            assertEquals(0x6d646174, bytes.getInt(12));
            assertEquals(56L, bytes.getLong(16));
            assertEquals(2500, bytes.getInt(56));
            assertEquals(0x01020304, bytes.getInt(60));
            assertArrayEquals(tail, java.util.Arrays.copyOfRange(saved, 64, saved.length));
        } finally {
            set(wrapper, "muxer", actualMuxer);
            wrapper.release();
        }
    }

    private static void set(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static Object get(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }
}

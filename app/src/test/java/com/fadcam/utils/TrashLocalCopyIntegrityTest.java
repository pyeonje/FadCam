package com.fadcam.utils;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import com.fadcam.Constants;
import com.fadcam.model.TrashItem;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class TrashLocalCopyIntegrityTest {
    private static final byte[] MEDIA = {11, 22, 33, 44};
    private static final byte[] ALTERED = {44, 33, 22, 11};

    @Test public void shortWriteCloseFailureSameLengthMutationAndUnreadableCopyPreserveTrash() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File trash = TrashManager.getTrashDirectory(context);
        File target = context.getExternalFilesDir(null);
        for (String fault : List.of("short", "close", "mutate", "readback")) {
            File source = new File(trash, fault + ".mp4");
            File output = new File(target, fault + ".mp4");
            Files.write(source.toPath(), MEDIA);
            TrashItem item = item(source);
            assertThrows(fault, IOException.class, () -> TrashManager.restoreLocalFile(context, item, source, output,
                    () -> new FilterOutputStream(new FileOutputStream(output)) {
                        @Override public void write(byte[] b, int offset, int length) throws IOException {
                            out.write(b, offset, "short".equals(fault) ? length - 1 : length);
                        }
                        @Override public void close() throws IOException {
                            super.close();
                            if ("close".equals(fault)) throw new IOException("injected close failure");
                            if ("mutate".equals(fault)) Files.write(output.toPath(), ALTERED);
                        }
                    },
                    () -> {
                        if ("readback".equals(fault)) throw new IOException("injected reopen failure");
                        return new FileInputStream(output);
                    }));
            assertArrayEquals(fault, MEDIA, Files.readAllBytes(source.toPath()));
            assertFalse(fault, output.exists());
            assertFalse(new File(context.getFilesDir(), "trash-restore-journal").exists());
        }
    }

    @Test public void journalFailureAndThreadInterruptionKeepOnlyTheUnmodifiedSource() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File source = new File(TrashManager.getTrashDirectory(context), "verify-first.mp4");
        File output = new File(context.getExternalFilesDir(null), "new.mp4");
        Files.write(source.toPath(), MEDIA);
        File journal = new File(context.getFilesDir(), "trash-restore-journal");
        Files.write(journal.toPath(), ALTERED);
        assertFalse(restore(context, item(source), source, output));
        assertArrayEquals(ALTERED, Files.readAllBytes(journal.toPath()));
        assertArrayEquals(MEDIA, Files.readAllBytes(source.toPath()));
        assertFalse(output.exists());
        Files.delete(journal.toPath());

        assertThrows(java.io.InterruptedIOException.class, () -> TrashManager.restoreLocalFile(context, item(source),
                source, output, () -> new FilterOutputStream(new FileOutputStream(output)) {
                    @Override public void write(byte[] b, int offset, int length) throws IOException {
                        out.write(b, offset, length);
                        Thread.currentThread().interrupt();
                    }
                }, () -> new FileInputStream(output)));
        assertTrue(Thread.interrupted()); // Clear the synthetic interruption before leaving the test.
        assertArrayEquals(MEDIA, Files.readAllBytes(source.toPath()));
        assertFalse(output.exists());
        assertFalse(journal.exists());
    }

    @Test public void sourceDeleteFailureResumesFromVerifiedJournalWithoutAnotherCopy() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File firstSource = new File(TrashManager.getTrashDirectory(context), "retained.mp4") {
            @Override public boolean delete() { return false; }
        };
        Files.write(firstSource.toPath(), MEDIA);
        TrashItem item = item(firstSource);
        File output = new File(context.getExternalFilesDir(null), "restore.mp4");
        assertFalse(restore(context, item, firstSource, output));
        assertArrayEquals(MEDIA, Files.readAllBytes(firstSource.toPath()));
        assertArrayEquals(MEDIA, Files.readAllBytes(output.toPath()));
        File journal = new File(context.getFilesDir(), "trash-restore-journal");
        assertEquals(1, journal.list().length);

        File realSource = new File(firstSource.getAbsolutePath());
        File second = new File(context.getExternalFilesDir(null), "restore (1).mp4");
        assertTrue(TrashManager.restoreLocalFile(context, item, realSource, second,
                () -> { throw new AssertionError("must not duplicate a verified copy"); },
                () -> { throw new AssertionError("must use prior journal destination"); }));
        assertFalse(realSource.exists());
        assertFalse(second.exists());
        assertArrayEquals(MEDIA, Files.readAllBytes(output.toPath()));
        assertTrue(TrashManager.loadTrashMetadata(context).isEmpty());
        assertEquals(0, journal.list().length);
    }

    @Test public void recoveryNeverTreatsSameLengthCorruptionAsCompleted() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File source = new File(TrashManager.getTrashDirectory(context), "recovery.mp4");
        File output = new File(context.getExternalFilesDir(null), "destination.mp4");
        Files.write(source.toPath(), MEDIA);
        TrashItem item = item(source);
        assertTrue(TrashManager.saveTrashMetadata(context, List.of(item)));
        assertTrue(restore(context, item, source, output));
        assertFalse(source.exists());
        Files.write(output.toPath(), ALTERED);
        assertEquals(List.of(item), TrashManager.loadTrashMetadata(context));
        assertEquals(1, new File(context.getFilesDir(), "trash-restore-journal").list().length);
        assertArrayEquals(ALTERED, Files.readAllBytes(output.toPath()));
    }

    @Test public void watchRestoresOnlyInCameraTreeWithoutOverwritingExistingFile() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File trash = TrashManager.getTrashDirectory(context);
        File source = new File(trash, "watch.mp4");
        Files.write(source.toPath(), MEDIA);
        File outside = new File(context.getExternalFilesDir(null), "outside-watch.mp4");
        Files.write(outside.toPath(), ALTERED);
        TrashItem bad = new TrashItem(outside.toURI().toASCIIString(), "outside-watch.mp4", source.getName(), 3L, false);
        assertFalse(TrashManager.restoreWatchItem(context, bad));
        assertArrayEquals(ALTERED, Files.readAllBytes(outside.toPath()));
        assertArrayEquals(MEDIA, Files.readAllBytes(source.toPath()));

        File folder = new File(new File(context.getExternalFilesDir(null), Constants.RECORDING_DIRECTORY),
                Constants.RECORDING_SUBDIR_CAMERA);
        assertTrue(folder.isDirectory() || folder.mkdirs());
        File existing = new File(folder, "watch.mp4");
        Files.write(existing.toPath(), ALTERED);
        TrashItem good = new TrashItem(existing.toURI().toASCIIString(), "watch.mp4", source.getName(), 4L, false);
        assertTrue(TrashManager.restoreWatchItem(context, good));
        assertArrayEquals(ALTERED, Files.readAllBytes(existing.toPath()));
        assertArrayEquals(MEDIA, Files.readAllBytes(new File(folder, "watch (1).mp4").toPath()));
        assertFalse(source.exists());
    }

    private static boolean restore(Context context, TrashItem item, File source, File output) throws IOException {
        return TrashManager.restoreLocalFile(context, item, source, output,
                () -> new FileOutputStream(output), () -> new FileInputStream(output));
    }

    private static TrashItem item(File source) {
        return new TrashItem("file:///qa-original.mp4", source.getName(), source.getName(), 123L, false, MEDIA.length);
    }
}

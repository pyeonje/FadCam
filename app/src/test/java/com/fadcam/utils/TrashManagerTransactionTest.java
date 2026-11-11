package com.fadcam.utils;

import static org.junit.Assert.*;
import android.content.Context;
import android.net.Uri;
import com.fadcam.Constants;
import com.fadcam.SharedPreferencesManager;
import java.io.File;
import java.nio.file.Files;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = android.app.Application.class)
public class TrashManagerTransactionTest {
    @Test public void unknownSizeSafEarlyEofDoesNotAttemptToDeleteTheSource() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        SharedPreferencesManager.getInstance(app).setTrashAutoDeleteMinutes(-1);
        var resolver = org.mockito.Mockito.mock(android.content.ContentResolver.class);
        Context context = new android.content.ContextWrapper(app) {
            @Override public android.content.ContentResolver getContentResolver() { return resolver; }
        };
        Uri original = Uri.parse("content://fixture/document/early");
        org.mockito.Mockito.when(resolver.openInputStream(original))
                .thenReturn(new java.io.ByteArrayInputStream(new byte[]{1, 2}));
        assertFalse(TrashManager.moveToTrash(context, original, "fixture.mp4", true));
        assertTrue(TrashManager.loadTrashMetadata(context).isEmpty());
        assertFalse(org.mockito.Mockito.mockingDetails(resolver).getInvocations().stream()
                .anyMatch(i -> i.getMethod().getName().equals("call")));
    }

    @Test public void missingRestoreSourceDoesNotClaimSuccessOrDiscardMetadata() {
        Context context = RuntimeEnvironment.getApplication();
        var item = new com.fadcam.model.TrashItem("file:///original.mp4", "original.mp4",
                "unavailable.mp4", 123, false, 4);
        assertTrue(TrashManager.saveTrashMetadata(context, java.util.Collections.singletonList(item)));
        assertFalse(TrashManager.restoreItemsFromTrash(context, java.util.Collections.singletonList(item),
                TrashManager.RestoreDestination.INTERNAL_STORAGE));
        assertEquals(java.util.Collections.singletonList(item), TrashManager.loadTrashMetadata(context));
    }

    @Test public void shortenedSafRestoreWithUnknownSizeAndFailedCleanupPreservesTheTrashCopy() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        var resolver = org.mockito.Mockito.mock(android.content.ContentResolver.class);
        Context context = new android.content.ContextWrapper(app) {
            @Override public android.content.ContentResolver getContentResolver() { return resolver; }
        };
        File source = new File(TrashManager.getTrashDirectory(context), "original.mp4");
        byte[] bytes = {1, 2, 3, 4};
        Files.write(source.toPath(), bytes);
        var item = new com.fadcam.model.TrashItem("file:///original.mp4", "original.mp4", source.getName(),
                123L, false, bytes.length);
        assertTrue(TrashManager.saveTrashMetadata(context, java.util.Collections.singletonList(item)));
        Uri output = Uri.parse("content://fixture/document/restored");
        var doc = org.mockito.Mockito.mock(androidx.documentfile.provider.DocumentFile.class);
        org.mockito.Mockito.when(doc.getUri()).thenReturn(output);
        org.mockito.Mockito.when(resolver.openOutputStream(output)).thenReturn(new java.io.ByteArrayOutputStream());
        org.mockito.Mockito.when(resolver.openInputStream(output))
                .thenReturn(new java.io.ByteArrayInputStream(new byte[]{1, 2}));
        assertThrows(java.io.IOException.class, () -> TrashManager.restoreToSafDocument(context, source, item, doc));
        org.mockito.Mockito.verify(doc).delete(); // Provider returns false; it may leave the incomplete copy behind.
        assertArrayEquals(bytes, Files.readAllBytes(source.toPath()));
        assertEquals(java.util.Collections.singletonList(item), TrashManager.loadTrashMetadata(context));
    }

    @Test public void safRestoreWithUnknownSizeCanBeReconciledByIndependentReadback() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        var resolver = org.mockito.Mockito.mock(android.content.ContentResolver.class);
        Context context = new android.content.ContextWrapper(app) {
            @Override public android.content.ContentResolver getContentResolver() { return resolver; }
        };
        File source = new File(TrashManager.getTrashDirectory(context), "verified.mp4");
        byte[] bytes = {1, 2, 3, 4};
        Files.write(source.toPath(), bytes);
        var item = new com.fadcam.model.TrashItem("file:///verified.mp4", "verified.mp4", source.getName(),
                123L, false, bytes.length);
        assertTrue(TrashManager.saveTrashMetadata(context, java.util.Collections.singletonList(item)));
        Uri output = Uri.parse("content://fixture/document/verified");
        var doc = org.mockito.Mockito.mock(androidx.documentfile.provider.DocumentFile.class);
        org.mockito.Mockito.when(doc.getUri()).thenReturn(output);
        var written = new java.io.ByteArrayOutputStream();
        org.mockito.Mockito.when(resolver.openOutputStream(output)).thenReturn(written);
        org.mockito.Mockito.when(resolver.openInputStream(output))
                .thenAnswer(call -> new java.io.ByteArrayInputStream(written.toByteArray()));
        assertTrue(TrashManager.restoreToSafDocument(context, source, item, doc));
        assertFalse(source.exists());
        assertArrayEquals(bytes, written.toByteArray());
        // No restore-loop metadata write: reload the durable restore journal as after interruption.
        assertTrue(TrashManager.loadTrashMetadata(context).isEmpty());
        org.mockito.Mockito.verify(doc, org.mockito.Mockito.never()).delete();
    }

    @Test public void restoreJournalFailureOrSourceDeleteFailureNeverDiscardsTrashEntry() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        var resolver = org.mockito.Mockito.mock(android.content.ContentResolver.class);
        Context context = new android.content.ContextWrapper(app) {
            @Override public android.content.ContentResolver getContentResolver() { return resolver; }
        };
        File source = new File(TrashManager.getTrashDirectory(context), "retained.mp4") {
            @Override public boolean delete() { return false; }
        };
        byte[] bytes = {4, 3, 2, 1};
        Files.write(source.toPath(), bytes);
        var item = new com.fadcam.model.TrashItem("file:///retained.mp4", "retained.mp4", source.getName(),
                123L, false, bytes.length);
        assertTrue(TrashManager.saveTrashMetadata(context, java.util.Collections.singletonList(item)));
        Uri output = Uri.parse("content://fixture/document/retained");
        var doc = org.mockito.Mockito.mock(androidx.documentfile.provider.DocumentFile.class);
        org.mockito.Mockito.when(doc.getUri()).thenReturn(output);
        var written = new java.io.ByteArrayOutputStream();
        org.mockito.Mockito.when(resolver.openOutputStream(output)).thenReturn(written);
        org.mockito.Mockito.when(resolver.openInputStream(output))
                .thenAnswer(call -> new java.io.ByteArrayInputStream(written.toByteArray()));
        File journal = new File(context.getFilesDir(), "trash-restore-journal");
        Files.write(journal.toPath(), new byte[]{7}); // A file blocks journal directory creation.
        assertThrows(java.io.IOException.class, () -> TrashManager.restoreToSafDocument(context, source, item, doc));
        org.mockito.Mockito.verify(doc).delete();
        assertArrayEquals(bytes, Files.readAllBytes(source.toPath()));
        assertEquals(java.util.Collections.singletonList(item), TrashManager.loadTrashMetadata(context));

        Files.delete(journal.toPath());
        written.reset();
        assertFalse(TrashManager.restoreToSafDocument(context, source, item, doc));
        org.mockito.Mockito.verify(doc, org.mockito.Mockito.times(1)).delete();
        assertArrayEquals(bytes, written.toByteArray());
        assertArrayEquals(bytes, Files.readAllBytes(source.toPath()));
        assertEquals(java.util.Collections.singletonList(item), TrashManager.loadTrashMetadata(context));
    }


    @Test public void failedPermanentDeleteKeepsTheEntryAndItsBytes() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File blocked = new File(TrashManager.getTrashDirectory(context), "blocked");
        assertTrue(blocked.mkdir());
        File retained = new File(blocked, "retained.mp4");
        Files.write(retained.toPath(), new byte[]{1, 2, 3});
        var item = new com.fadcam.model.TrashItem("file:///original.mp4", "original.mp4", "blocked", 123, false, 3);
        assertTrue(TrashManager.saveTrashMetadata(context, java.util.Collections.singletonList(item)));
        assertFalse(TrashManager.permanentlyDeleteItems(context, java.util.Collections.singletonList(item)));
        assertEquals(java.util.Collections.singletonList(item), TrashManager.loadTrashMetadata(context));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(retained.toPath()));
    }

    @Test public void metadataFailureNeverDeletesTheRenamedFile() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferencesManager.getInstance(context).setTrashAutoDeleteMinutes(-1);
        File metadata = new File(context.getFilesDir(), Constants.TRASH_METADATA_FILENAME);
        assertTrue(metadata.mkdir());
        File source = File.createTempFile("source", ".mp4", context.getCacheDir());
        byte[] content = {1, 4, 9, 16};
        Files.write(source.toPath(), content);
        assertFalse(TrashManager.moveToTrash(context, Uri.fromFile(source), source.getName(), false));
        assertTrue(source.isFile());
        assertArrayEquals(content, Files.readAllBytes(source.toPath()));
    }

    @Test public void failureOnWriteFlushOrCloseNeverCompletesACopy() throws Exception {
        for (String failure : new String[]{"write", "flush", "close", "inputClose"}) {
            byte[] content = {1, 2, 3, 4};
            java.io.ByteArrayInputStream input = new java.io.ByteArrayInputStream(content) {
                @Override public void close() throws java.io.IOException {
                    if ("inputClose".equals(failure)) throw new java.io.IOException(failure);
                }
            };
            java.io.OutputStream output = new java.io.OutputStream() {
                @Override public void write(int value) throws java.io.IOException {
                    if ("write".equals(failure)) throw new java.io.IOException(failure);
                }
                @Override public void flush() throws java.io.IOException {
                    if ("flush".equals(failure)) throw new java.io.IOException(failure);
                }
                @Override public void close() throws java.io.IOException {
                    if ("close".equals(failure)) throw new java.io.IOException(failure);
                }
            };
            assertThrows(java.io.IOException.class, () ->
                    TrashManager.copyClosed(() -> input, () -> output, content.length, null));
        }
    }

    @Test public void failedRollbackLeavesVerifiedCopyDiscoverableEvenWithoutMetadata() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferencesManager.getInstance(context).setTrashAutoDeleteMinutes(-1);
        File metadata = new File(context.getFilesDir(), Constants.TRASH_METADATA_FILENAME);
        assertTrue(metadata.mkdir());
        File source = File.createTempFile("rollback", ".mp4", context.getCacheDir());
        byte[] content = {2, 7, 4};
        Files.write(source.toPath(), content);
        assertFalse(TrashManager.moveToTrash(context, Uri.fromFile(source), source.getName(), false, progress -> {
            try { Files.write(source.toPath(), new byte[]{99}); }
            catch (java.io.IOException e) { throw new AssertionError(e); }
            throw new java.util.concurrent.CancellationException();
        }));
        assertArrayEquals(new byte[]{99}, Files.readAllBytes(source.toPath()));
        var items = TrashManager.loadTrashMetadata(context);
        assertEquals(1, items.size());
        File recovered = new File(TrashManager.getTrashDirectory(context), items.get(0).getTrashFileName());
        assertArrayEquals(content, Files.readAllBytes(recovered.toPath()));
        assertTrue(metadata.delete());
        assertEquals(items, TrashManager.loadTrashMetadata(context));
        assertEquals(items, TrashManager.loadTrashMetadata(context));
    }
}

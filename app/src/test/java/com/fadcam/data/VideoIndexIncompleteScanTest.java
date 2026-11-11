package com.fadcam.data;

import android.content.ContentResolver;
import android.content.Context;
import android.content.UriPermission;
import android.database.MatrixCursor;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.content.ContextWrapper;
import android.net.Uri;
import com.fadcam.Constants;
import com.fadcam.SharedPreferencesManager;
import java.io.File;
import com.fadcam.data.dao.VideoIndexDao;
import com.fadcam.data.entity.VideoIndexEntity;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = android.app.Application.class)
public class VideoIndexIncompleteScanTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    @Test public void actualScannerDistinguishesBrokenInternalCategoryFromAnEmptyDirectory() throws Exception {
        File root = temp.newFolder();
        File records = new File(root, Constants.RECORDING_DIRECTORY);
        assertTrue(records.mkdir());
        File category = new File(records, Constants.RECORDING_SUBDIR_CAMERA);
        assertTrue(category.createNewFile()); // A category cannot be listed as a directory.
        Context context = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getExternalFilesDir(String type) { return root; }
        };
        FastFileScanner scanner = new FastFileScanner(context);
        SharedPreferencesManager preferences = mock(SharedPreferencesManager.class);
        String expected = Uri.fromFile(new File(category, "old.mp4")).toString();
        FastFileScanner.ScanResult partial = scanner.scanWithStatus(preferences);
        assertFalse(partial.failedScopes.isEmpty());
        assertFalse(partial.coversMissing(expected));
        assertTrue(category.delete());
        assertTrue(category.mkdir());
        FastFileScanner.ScanResult complete = scanner.scanWithStatus(preferences);
        assertTrue(complete.failedScopes.toString(), complete.failedScopes.isEmpty());
        assertTrue(complete.coversMissing(expected));
    }
    @Test public void incompleteScanRetainsMissingRowsThenCompleteScanRemovesOnlyItsOwnMissingRow() {
        Uri tree = Uri.parse("content://com.android.externalstorage.documents/tree/SD%3AMedia");
        String a = tree + "/document/SD%3AMedia%2Fa.mp4";
        String b = tree + "/document/SD%3AMedia%2Fb.mp4";
        String unrelated = "content://com.android.externalstorage.documents/tree/SD%3AOther/document/SD%3AOther%2Fb.mp4";
        VideoIndexDao dao = mock(VideoIndexDao.class);
        when(dao.getDeltaCheckRows()).thenReturn(Arrays.asList(row(a), row(b), row(unrelated)));
        when(dao.getAllNewestFirst()).thenReturn(Collections.emptyList());
        VideoIndexRepository repo = new VideoIndexRepository(RuntimeEnvironment.getApplication(), dao);
        VideoIndexEntity found = new VideoIndexEntity();
        found.uriString = a;
        repo.reconcile(new FastFileScanner.ScanResult(Collections.singletonList(found),
                Collections.singletonList("provider returned null cursor"), tree, Collections.emptySet()));
        verify(dao, never()).deleteByUris(anyList());
        repo.reconcile(new FastFileScanner.ScanResult(Collections.singletonList(found),
                Collections.emptyList(), tree, Collections.singleton("SD:Media")));
        verify(dao).deleteByUris(Collections.singletonList(b));
        verify(dao, never()).deleteAll();
    }

    @Test public void safProviderPartialAndCompleteQueriesReconcileWithoutLosingUnconfirmedRows() {
        Uri tree = Uri.parse("content://com.android.externalstorage.documents/tree/SD%3AMedia");
        Uri camera = DocumentsContract.buildDocumentUriUsingTree(tree, "SD:Media/Camera");
        Uri rootChildren = DocumentsContract.buildChildDocumentsUriUsingTree(tree, "SD:Media");
        Uri cameraChildren = DocumentsContract.buildChildDocumentsUriUsingTree(tree, "SD:Media/Camera");
        Uri a = DocumentsContract.buildDocumentUriUsingTree(tree, "SD:Media/Camera/a.mp4");
        Uri b = DocumentsContract.buildDocumentUriUsingTree(tree, "SD:Media/Camera/b.mp4");
        Uri other = DocumentsContract.buildDocumentUriUsingTree(tree, "SD:Media/Other/keep.mp4");
        ContentResolver resolver = mock(ContentResolver.class);
        Context context = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public ContentResolver getContentResolver() { return resolver; }
        };
        UriPermission grant = mock(UriPermission.class);
        SharedPreferencesManager preferences = mock(SharedPreferencesManager.class);
        when(preferences.getCustomStorageUri()).thenReturn(tree.toString());
        when(grant.getUri()).thenReturn(tree);
        when(grant.isReadPermission()).thenReturn(true);
        when(resolver.getPersistedUriPermissions()).thenReturn(Collections.singletonList(grant));
        when(resolver.query(eq(rootChildren), any(String[].class), isNull(), isNull(), isNull()))
                .thenAnswer(call -> {
                    String[] columns = call.getArgument(1);
                    MatrixCursor cursor = new MatrixCursor(columns);
                    Object[] row = new Object[columns.length];
                    row[0] = DocumentsContract.getDocumentId(camera);
                    row[1] = Constants.RECORDING_SUBDIR_CAMERA;
                    row[2] = DocumentsContract.Document.MIME_TYPE_DIR;
                    cursor.addRow(row);
                    Object[] untouchedFolder = new Object[columns.length];
                    untouchedFolder[0] = "SD:Media/Other";
                    untouchedFolder[1] = "Other";
                    untouchedFolder[2] = DocumentsContract.Document.MIME_TYPE_DIR;
                    cursor.addRow(untouchedFolder);
                    return cursor;
                });
        java.util.concurrent.atomic.AtomicInteger stage = new java.util.concurrent.atomic.AtomicInteger();
        when(resolver.query(eq(cameraChildren), any(String[].class), isNull(), isNull(), isNull()))
                .thenAnswer(call -> {
                    if (stage.get() == 0) return null;
                    MatrixCursor cursor = new MatrixCursor(call.getArgument(1));
                    if (stage.get() == 1) {
                        Bundle extras = new Bundle();
                        extras.putBoolean(DocumentsContract.EXTRA_LOADING, true);
                        cursor.setExtras(extras);
                    }
                    cursor.addRow(new Object[]{DocumentsContract.getDocumentId(a), "a.mp4", "video/mp4", 10L, 20L});
                    return cursor;
                });

        FastFileScanner scanner = new FastFileScanner(context);
        VideoIndexDao dao = mock(VideoIndexDao.class);
        when(dao.getDeltaCheckRows()).thenReturn(Arrays.asList(row(a.toString()), row(b.toString()), row(other.toString())));
        when(dao.getAllNewestFirst()).thenReturn(Collections.emptyList());
        VideoIndexRepository repo = new VideoIndexRepository(context, dao);
        for (int partialStage = 0; partialStage < 2; partialStage++) {
            stage.set(partialStage);
            FastFileScanner.ScanResult partial = scanner.scanWithStatus(preferences);
            assertFalse(partial.failedScopes.isEmpty());
            repo.reconcile(partial);
        }
        verify(dao, never()).deleteByUris(anyList());

        stage.set(2);
        FastFileScanner.ScanResult complete = scanner.scanWithStatus(preferences);
        assertTrue(complete.failedScopes.toString(), complete.failedScopes.isEmpty());
        assertEquals(a.toString(), complete.files.get(0).uriString);
        repo.reconcile(complete);
        verify(dao).deleteByUris(Collections.singletonList(b.toString()));
        verify(dao, never()).deleteAll();
    }

    @Test public void completeSafScanOfNestedRecordingRootCannotDeleteSiblingIndexRows() {
        Uri tree = Uri.parse("content://com.android.externalstorage.documents/tree/SD%3AMedia");
        String nestedId = "SD:Media/" + Constants.RECORDING_DIRECTORY;
        Uri outside = DocumentsContract.buildDocumentUriUsingTree(tree, "SD:Media/Camera/other.mp4");
        Uri inside = DocumentsContract.buildDocumentUriUsingTree(tree, nestedId + "/Camera/old.mp4");
        Uri rootChildren = DocumentsContract.buildChildDocumentsUriUsingTree(tree, "SD:Media");
        Uri nestedChildren = DocumentsContract.buildChildDocumentsUriUsingTree(tree, nestedId);
        Uri cameraChildren = DocumentsContract.buildChildDocumentsUriUsingTree(tree, nestedId + "/Camera");

        ContentResolver resolver = mock(ContentResolver.class);
        Context context = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public ContentResolver getContentResolver() { return resolver; }
        };
        UriPermission grant = mock(UriPermission.class);
        SharedPreferencesManager preferences = mock(SharedPreferencesManager.class);
        when(preferences.getCustomStorageUri()).thenReturn(tree.toString());
        when(grant.getUri()).thenReturn(tree);
        when(grant.isReadPermission()).thenReturn(true);
        when(resolver.getPersistedUriPermissions()).thenReturn(Collections.singletonList(grant));
        when(resolver.query(eq(rootChildren), any(String[].class), isNull(), isNull(), isNull()))
                .thenAnswer(call -> {
                    MatrixCursor cursor = new MatrixCursor(call.getArgument(1));
                    cursor.addRow(new Object[]{nestedId, Constants.RECORDING_DIRECTORY,
                            DocumentsContract.Document.MIME_TYPE_DIR});
                    return cursor;
                });
        when(resolver.query(eq(nestedChildren), any(String[].class), isNull(), isNull(), isNull()))
                .thenAnswer(call -> {
                    String[] columns = call.getArgument(1);
                    MatrixCursor cursor = new MatrixCursor(columns);
                    Object[] row = new Object[columns.length];
                    row[0] = nestedId + "/Camera";
                    row[1] = Constants.RECORDING_SUBDIR_CAMERA;
                    row[2] = DocumentsContract.Document.MIME_TYPE_DIR;
                    cursor.addRow(row);
                    return cursor;
                });
        when(resolver.query(eq(cameraChildren), any(String[].class), isNull(), isNull(), isNull()))
                .thenAnswer(call -> new MatrixCursor(call.getArgument(1)));

        FastFileScanner.ScanResult complete = new FastFileScanner(context).scanWithStatus(preferences);
        assertTrue(complete.failedScopes.toString(), complete.failedScopes.isEmpty());
        assertFalse(complete.coversMissing(outside.toString()));
        assertTrue(complete.coversMissing(inside.toString()));
    }

    private static VideoIndexDao.DeltaCheckRow row(String uri) {
        VideoIndexDao.DeltaCheckRow row = new VideoIndexDao.DeltaCheckRow();
        row.uri_string = uri;
        return row;
    }
}

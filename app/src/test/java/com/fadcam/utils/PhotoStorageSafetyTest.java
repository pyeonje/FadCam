package com.fadcam.utils;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import com.fadcam.SharedPreferencesManager;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PhotoStorageSafetyTest {
    private static final long SHUTTER_MS = 1_700_000_000_000L;
    private static final String AUTHORITY = "test.fadcam.photo.documents";
    private static final String DIR = DocumentsContract.Document.MIME_TYPE_DIR;
    private static final String PARENT = "drive/FadShot/Back";
    private static final String OLD = PARENT + "/existing.jpg";
    private static final byte[] ORIGINAL = {11, 22, 33, 44};

    @Rule public TemporaryFolder storage = new TemporaryFolder();
    private Context context;
    private File external;

    @Before public void setup() throws Exception {
        external = storage.newFolder();
        context = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public File getExternalFilesDir(String type) { return external; }
            @Override public int checkCallingOrSelfUriPermission(Uri uri, int mode) {
                return PackageManager.PERMISSION_GRANTED;
            }
        };
        SharedPreferencesManager.getInstance(context).setCustomStorageUri("");
    }

    @Test public void twoShotsAtTheSameSecondKeepDistinctUrisAndDecodedPixels() throws Exception {
        PhotoStorageHelper.SaveRequest request = request();
        Uri first = save(request, Color.RED);
        Uri second = save(request, Color.BLUE);
        assertNotNull(first);
        assertNotNull(second);
        assertNotEquals(first, second);
        assertPixel(first, true);
        assertPixel(second, false);
        assertTrue(new File(first.getPath()).getName().startsWith("FadShot_Back_"));
        assertTrue(new File(second.getPath()).getName().endsWith(".jpg"));
    }

    @Test public void concurrentSameSecondShotsBothRetainTheirPixels() throws Exception {
        PhotoStorageHelper.SaveRequest request = request();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Uri> a = workers.submit(() -> { start.await(); return save(request, Color.RED); });
            Future<Uri> b = workers.submit(() -> { start.await(); return save(request, Color.BLUE); });
            start.countDown();
            Uri first = a.get(10, TimeUnit.SECONDS);
            Uri second = b.get(10, TimeUnit.SECONDS);
            assertNotNull(first);
            assertNotNull(second);
            assertNotEquals(first, second);
            assertPixel(first, true);
            assertPixel(second, false);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test public void existingKoreanNameIsNeverTruncatedAndNewJpegDecodes() throws Exception {
        String fileName = "FadShot_Back_테스트.jpg";
        File dir = RecordingStoragePaths.getInternalShotSourceDir(context,
                RecordingStoragePaths.ShotSource.BACK, true);
        File existing = new File(dir, fileName);
        Files.write(existing.toPath(), ORIGINAL);
        Uri fresh = saveInternal(fileName, Color.BLUE);
        assertNotNull(fresh);
        assertNotEquals(Uri.fromFile(existing), fresh);
        assertArrayEquals(ORIGINAL, Files.readAllBytes(existing.toPath()));
        assertTrue(new File(fresh.getPath()).getName().contains("테스트"));
        assertPixel(fresh, false);
    }

    @Test public void secondEncodeFailureDoesNotTouchFirstJpeg() throws Exception {
        PhotoStorageHelper.SaveRequest request = request();
        Uri first = save(request, Color.RED);
        assertNotNull(first);
        byte[] bytes = Files.readAllBytes(new File(first.getPath()).toPath());
        Bitmap recycled = bitmap(Color.BLUE);
        recycled.recycle();
        assertNull(PhotoStorageHelper.saveJpegBitmap(context, recycled, request));
        assertArrayEquals(bytes, Files.readAllBytes(new File(first.getPath()).toPath()));
        assertEquals(1, new File(first.getPath()).getParentFile().list().length);
        assertPixel(first, true);
    }

    @Test public void internalCloseFailureKeepsFirstJpegAndRemovesOnlyNewCandidate() throws Exception {
        PhotoStorageHelper.SaveRequest request = request();
        Uri first = save(request, Color.RED);
        assertNotNull(first);
        File existing = new File(first.getPath());
        byte[] before = Files.readAllBytes(existing.toPath());
        Bitmap next = bitmap(Color.BLUE);
        try {
            Uri failed = PhotoStorageHelper.saveToInternal(context, existing.getName(), next,
                    PhotoStorageHelper.ShotSource.BACK, raw -> new java.io.FilterOutputStream(raw) {
                        @Override public void close() throws IOException {
                            super.close();
                            throw new IOException("fixture local close failed");
                        }
                    });
            assertNull(failed);
        } finally { next.recycle(); }
        assertArrayEquals(before, Files.readAllBytes(existing.toPath()));
        assertArrayEquals(new String[]{existing.getName()}, existing.getParentFile().list());
        assertPixel(first, true);
    }

    @Test public void internalCreationFailureDoesNotModifyBlockingFile() throws Exception {
        File shotRoot = RecordingStoragePaths.getInternalCategoryDir(context,
                RecordingStoragePaths.Category.SHOT, true);
        File blocker = new File(shotRoot, "Back");
        Files.write(blocker.toPath(), ORIGINAL);
        assertNull(save(request(), Color.RED));
        assertArrayEquals(ORIGINAL, Files.readAllBytes(blocker.toPath()));
    }

    @Test public void safFreshDocumentReturnsExactUriWithDecodableJpeg() throws Exception {
        Provider fixture = provider("normal");
        Uri saved = saveSaf(fixture, "FadShot_Back_fixture.jpg");
        assertEquals(fixture.newUri, saved);
        assertEquals(1, fixture.writes);
        assertEquals(0, fixture.deletes);
        assertTrue("SAF JPEG must be read back after close", fixture.readsAfterWrite > 0);
        assertPixel(Uri.fromFile(fixture.fresh), true);
    }

    @Test public void safDirectReuseDoesNotOpenOrDeleteExistingDocument() throws Exception {
        assertExistingUnaffected("reuse");
    }

    @Test public void safOmittedExistingDocumentWithContentIsNotOpenedOrDeleted() throws Exception {
        assertExistingUnaffected("hidden");
    }

    @Test public void safFalseZeroSizeDoesNotHideExistingDocumentBytes() throws Exception {
        assertExistingUnaffected("zero");
    }

    @Test public void safDeniedNewDocumentReadNeverOpensForWritingOrDeletes() throws Exception {
        Provider fixture = provider("deny-initial");
        assertNull(saveSaf(fixture, "FadShot_Back_fixture.jpg"));
        assertEquals(0, fixture.writes);
        assertEquals(0, fixture.deletes);
    }

    @Test public void safShortWriteIsRejectedAndOnlyConfirmedOutputIsCleaned() throws Exception {
        assertBadFresh("short");
    }

    @Test public void safAlteredSameSizeIsRejectedAndOnlyConfirmedOutputIsCleaned() throws Exception {
        assertBadFresh("alter");
    }

    @Test public void safDeniedReadbackIsRejectedAndOnlyConfirmedOutputIsCleaned() throws Exception {
        assertBadFresh("deny-readback");
    }

    @Test public void safCloseFailureIsRejectedAndOnlyConfirmedOutputIsCleaned() throws Exception {
        assertBadFresh("close");
    }

    @Test public void selectedSafFailureStillFallsBackToInternalStorage() throws Exception {
        Provider fixture = provider("reuse");
        SharedPreferencesManager.getInstance(context).setCustomStorageUri(fixture.tree.toString());
        Uri saved = save(request(), Color.RED);
        assertNotNull(saved);
        assertEquals("file", saved.getScheme());
        assertArrayEquals(ORIGINAL, Files.readAllBytes(fixture.existing.toPath()));
        assertEquals(0, fixture.writes);
        assertEquals(0, fixture.deletes);
        assertPixel(saved, true);
    }

    private void assertExistingUnaffected(String mode) throws Exception {
        Provider fixture = provider(mode);
        assertNull(saveSaf(fixture, "FadShot_Back_fixture.jpg"));
        assertArrayEquals(ORIGINAL, Files.readAllBytes(fixture.existing.toPath()));
        assertEquals(0, fixture.writes);
        assertEquals(0, fixture.deletes);
    }

    private void assertBadFresh(String mode) throws Exception {
        Provider fixture = provider(mode);
        assertNull(saveSaf(fixture, "FadShot_Back_fixture.jpg"));
        assertEquals(1, fixture.writes);
        assertEquals(1, fixture.deletes);
        assertEquals(fixture.newUri, fixture.deletedUri);
        assertFalse(fixture.fresh.exists());
        assertArrayEquals(ORIGINAL, Files.readAllBytes(fixture.existing.toPath()));
    }

    private PhotoStorageHelper.SaveRequest request() {
        return PhotoStorageHelper.snapshotSaveRequest(context, PhotoStorageHelper.ShotSource.BACK,
                false, null, SHUTTER_MS);
    }

    private Uri save(PhotoStorageHelper.SaveRequest request, int color) {
        Bitmap bitmap = bitmap(color);
        try { return PhotoStorageHelper.saveJpegBitmap(context, bitmap, request); }
        finally { bitmap.recycle(); }
    }

    private Uri saveInternal(String name, int color) {
        Bitmap bitmap = bitmap(color);
        try {
            return ReflectionHelpers.callStaticMethod(PhotoStorageHelper.class, "saveToInternal",
                    ReflectionHelpers.ClassParameter.from(Context.class, context),
                    ReflectionHelpers.ClassParameter.from(String.class, name),
                    ReflectionHelpers.ClassParameter.from(Bitmap.class, bitmap),
                    ReflectionHelpers.ClassParameter.from(PhotoStorageHelper.ShotSource.class, PhotoStorageHelper.ShotSource.BACK));
        } finally { bitmap.recycle(); }
    }

    private Uri saveSaf(Provider fixture, String name) {
        Bitmap bitmap = bitmap(Color.RED);
        try {
            return ReflectionHelpers.callStaticMethod(PhotoStorageHelper.class, "saveToSaf",
                    ReflectionHelpers.ClassParameter.from(Context.class, context),
                    ReflectionHelpers.ClassParameter.from(String.class, fixture.tree.toString()),
                    ReflectionHelpers.ClassParameter.from(String.class, name),
                    ReflectionHelpers.ClassParameter.from(Bitmap.class, bitmap),
                    ReflectionHelpers.ClassParameter.from(PhotoStorageHelper.ShotSource.class, PhotoStorageHelper.ShotSource.BACK));
        } finally { bitmap.recycle(); }
    }

    private static Bitmap bitmap(int color) {
        Bitmap result = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888);
        result.eraseColor(color);
        return result;
    }

    private static void assertPixel(Uri uri, boolean red) {
        assertNotNull(uri);
        Bitmap decoded = BitmapFactory.decodeFile(uri.getPath());
        assertNotNull("saved output is a decodable JPEG", decoded);
        try {
            assertEquals(16, decoded.getWidth());
            assertEquals(12, decoded.getHeight());
            int pixel = decoded.getPixel(7, 5);
            assertTrue(red ? Color.red(pixel) > 220 : Color.blue(pixel) > 220);
            assertTrue(red ? Color.blue(pixel) < 30 : Color.red(pixel) < 30);
        } finally { decoded.recycle(); }
    }

    private Provider provider(String mode) throws IOException {
        Provider fixture = new Provider(storage.newFolder(), mode);
        Application app = RuntimeEnvironment.getApplication();
        ProviderInfo info = new ProviderInfo();
        info.authority = AUTHORITY;
        fixture.attachInfo(app, info);
        ShadowContentResolver.registerProviderInternal(AUTHORITY, fixture);
        fixture.registerOutput(fixture.uri(OLD), fixture.existing);
        return fixture;
    }

    private static final class Provider extends ContentProvider {
        final Map<String, String> names = new LinkedHashMap<>();
        final Uri tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "drive");
        final File existing, fresh;
        final String mode;
        boolean created;
        int writes, deletes, readsAfterWrite;
        Uri newUri, deletedUri;

        Provider(File dir, String mode) throws IOException {
            this.mode = mode;
            existing = new File(dir, "old.jpg");
            Files.write(existing.toPath(), ORIGINAL);
            fresh = new File(dir, "fresh.jpg");
            names.put("drive", "drive");
            names.put("drive/FadShot", "FadShot");
            names.put(PARENT, "Back");
            names.put(OLD, "existing.jpg");
        }

        Uri uri(String id) { return DocumentsContract.buildDocumentUriUsingTree(tree, id); }
        boolean hidden() { return "hidden".equals(mode) || "zero".equals(mode); }
        boolean reuse() { return "reuse".equals(mode) || hidden(); }
        @Override public boolean onCreate() { return true; }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
        @Override public String getType(Uri uri) { return mime(DocumentsContract.getDocumentId(uri)); }
        private String mime(String id) {
            return id.equals(OLD) || (newUri != null && newUri.equals(uri(id))) ? "image/jpeg" : DIR;
        }

        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
            String id = DocumentsContract.getDocumentId(uri);
            MatrixCursor cursor = new MatrixCursor(projection);
            if ("children".equals(uri.getLastPathSegment())) {
                String prefix = id + "/";
                for (String child : names.keySet()) {
                    if (hidden() && !created && OLD.equals(child)) continue;
                    if (child.startsWith(prefix) && child.indexOf('/', prefix.length()) < 0) add(cursor, projection, child);
                }
            } else if (names.containsKey(id)) add(cursor, projection, id);
            return cursor;
        }

        private void add(MatrixCursor cursor, String[] projection, String id) {
            Object[] values = new Object[projection.length];
            for (int i = 0; i < projection.length; i++) {
                switch (projection[i]) {
                    case DocumentsContract.Document.COLUMN_DOCUMENT_ID: values[i] = id; break;
                    case DocumentsContract.Document.COLUMN_DISPLAY_NAME: values[i] = names.get(id); break;
                    case DocumentsContract.Document.COLUMN_MIME_TYPE: values[i] = mime(id); break;
                    case DocumentsContract.Document.COLUMN_SIZE:
                        values[i] = OLD.equals(id) ? ("zero".equals(mode) ? 0 : existing.length()) : fresh.length(); break;
                    case DocumentsContract.Document.COLUMN_FLAGS:
                        values[i] = DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                                | DocumentsContract.Document.FLAG_SUPPORTS_WRITE | DocumentsContract.Document.FLAG_SUPPORTS_DELETE;
                        break;
                    default: break;
                }
            }
            cursor.addRow(values);
        }

        @Override public Bundle call(String method, String argument, Bundle extras) {
            Uri target = extras.getParcelable("uri");
            Bundle result = new Bundle();
            if ("android:createDocument".equals(method)) {
                String name = extras.getString("_display_name");
                if (!"image/jpeg".equals(extras.getString("mime_type"))) return null;
                created = true;
                if (reuse()) {
                    if (hidden()) names.put(OLD, name);
                    result.putParcelable("uri", uri(OLD));
                } else {
                    String id = PARENT + "/new-id";
                    names.put(id, name);
                    newUri = uri(id);
                    try { assertTrue(fresh.createNewFile()); }
                    catch (IOException e) { throw new AssertionError(e); }
                    registerOutput(newUri, fresh);
                    result.putParcelable("uri", newUri);
                }
                return result;
            }
            if ("android:deleteDocument".equals(method)) {
                deletes++;
                deletedUri = target;
                File file = target.equals(newUri) ? fresh : existing;
                assertTrue(file.delete());
                names.remove(DocumentsContract.getDocumentId(target));
                return result;
            }
            return super.call(method, argument, extras);
        }

        void registerOutput(Uri uri, File file) {
            Shadows.shadowOf(getContext().getContentResolver()).registerOutputStream(uri, new ByteArrayOutputStream() {
                @Override public void close() throws IOException {
                    writes++;
                    byte[] bytes = toByteArray();
                    if ("short".equals(mode)) bytes = Arrays.copyOf(bytes, bytes.length / 2);
                    if ("alter".equals(mode)) bytes[bytes.length / 2] ^= 0x40;
                    Files.write(file.toPath(), bytes);
                    if ("close".equals(mode)) throw new IOException("fixture close failed");
                    super.close();
                }
            });
        }

        @Override public ParcelFileDescriptor openFile(Uri uri, String access) throws FileNotFoundException {
            boolean newDocument = uri.equals(newUri);
            if (newDocument && writes > 0) readsAfterWrite++;
            if (newDocument && ("deny-initial".equals(mode) || ("deny-readback".equals(mode) && writes > 0))) {
                throw new FileNotFoundException("fixture read denied");
            }
            File file = newDocument ? fresh : existing;
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(access));
        }
    }
}

package com.fadcam.data;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import com.fadcam.Constants;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.Utils;
import com.fadcam.data.entity.VideoIndexEntity;
import com.fadcam.ui.VideoItem;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * High-performance file scanner that replaces the slow DocumentFile.listFiles() approach.
 * <p>
 * For SAF (Storage Access Framework): Uses raw ContentResolver.query() which is 50-100x faster
 * than DocumentFile.listFiles() because it avoids per-file IPC round trips.
 * <p>
 * For internal storage: Uses File.listFiles() with parallel directory scanning.
 * <p>
 * Returns lightweight {@link VideoIndexEntity} objects ready for DB insertion —
 * no duration or thumbnail computed yet (those are done lazily in background).
 */
public class FastFileScanner {

    private static final String TAG = "FastFileScanner";

    /** Number of parallel threads for scanning subdirectories. */
    private static final int SCAN_PARALLELISM = 4;

    private final Context context;

    public FastFileScanner(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    // ════════════════════════════════════════════════════════════════
    // Public API
    // ════════════════════════════════════════════════════════════════

    /** A scan's rows and the exact direct parents enumerated successfully. */
    static final class ScanResult {
        final List<VideoIndexEntity> files;
        final List<String> failedScopes;
        final Uri tree;
        final Set<String> completedParents;

        ScanResult(List<VideoIndexEntity> files, List<String> failedScopes, Uri tree,
                Set<String> completedParents) {
            this.files = new ArrayList<>(files);
            this.failedScopes = new ArrayList<>(failedScopes);
            this.tree = tree;
            this.completedParents = new HashSet<>(completedParents);
        }

        boolean coversMissing(String uriString) {
            // No deletion inference from a partial scan, even if some parents completed.
            if (!failedScopes.isEmpty()) return false;
            try {
                Uri uri = Uri.parse(uriString);
                if (tree != null) {
                    if (!"content".equals(uri.getScheme())
                            || !java.util.Objects.equals(tree.getAuthority(), uri.getAuthority())
                            || !DocumentsContract.getTreeDocumentId(tree)
                                    .equals(DocumentsContract.getTreeDocumentId(uri))) return false;
                    // The external-storage provider defines IDs as volume:path. Opaque IDs
                    // don't establish a parent after deletion; keep the previous index row.
                    if (!"com.android.externalstorage.documents".equals(tree.getAuthority())) return false;
                    String documentId = DocumentsContract.getDocumentId(uri);
                    int volume = documentId.indexOf(':');
                    if (volume < 0) return false;
                    int lastSlash = documentId.lastIndexOf('/');
                    String parent = lastSlash > volume
                            ? documentId.substring(0, lastSlash) : documentId.substring(0, volume + 1);
                    return completedParents.contains(parent);
                }
                if (!"file".equals(uri.getScheme()) || uri.getPath() == null) return false;
                File parent = new File(uri.getPath()).getParentFile();
                return parent != null && completedParents.contains(parent.getCanonicalPath());
            } catch (IllegalArgumentException | java.io.IOException ignored) {
                return false;
            }
        }
    }

    @NonNull
    public List<VideoIndexEntity> scanAll(@NonNull SharedPreferencesManager prefs) {
        return scanWithStatus(prefs).files;
    }

    @NonNull
    ScanResult scanWithStatus(@NonNull SharedPreferencesManager prefs) {
        List<String> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        String safUriString = prefs.getCustomStorageUri();
        Uri tree = safUriString == null || safUriString.isEmpty() ? null : Uri.parse(safUriString);
        if (tree != null) {
            try {
                for (android.content.UriPermission p : context.getContentResolver().getPersistedUriPermissions()) {
                    if (p.getUri().equals(tree) && p.isReadPermission()) {
                        return scanSaf(tree, failures);
                    }
                }
            } catch (RuntimeException e) {
                FLog.w(TAG, "Cannot read configured storage grant", e);
            }
            // Existing internal fallback can discover files, but cannot attest to SAF deletions.
            failures.add(tree.toString());
        }
        try {
            File root = context.getExternalFilesDir(null);
            if (root == null || !root.isDirectory() || root.list() == null) {
                failures.add("internal-storage");
                return new ScanResult(java.util.Collections.emptyList(), failures, tree, java.util.Collections.emptySet());
            }
            File base = new File(root, Constants.RECORDING_DIRECTORY);
            Set<String> completedParents = java.util.concurrent.ConcurrentHashMap.newKeySet();
            return new ScanResult(scanInternal(base, failures, completedParents), failures, tree, completedParents);
        } catch (RuntimeException e) {
            FLog.w(TAG, "Internal storage unavailable", e);
            failures.add("internal-storage");
            return new ScanResult(java.util.Collections.emptyList(), failures, tree, java.util.Collections.emptySet());
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Internal Storage Scanning
    // ════════════════════════════════════════════════════════════════

    @NonNull
    private List<VideoIndexEntity> scanInternal(File baseDir, List<String> failures, Set<String> completedParents) {
        if (!baseDir.exists()) return new ArrayList<>();
        if (!baseDir.isDirectory()) {
            failures.add(baseDir.getPath());
            return new ArrayList<>();
        }

        // Parallel scan of all category directories
        CopyOnWriteArrayList<VideoIndexEntity> results = new CopyOnWriteArrayList<>();
        ExecutorService scanner = Executors.newFixedThreadPool(SCAN_PARALLELISM);
        CountDownLatch latch = new CountDownLatch(7); // 6 category submits + 1 extra countDown

        // Camera
        scanner.submit(() -> {
            try {
                scanInternalDirectory(results, completedParents, new File(baseDir, Constants.RECORDING_SUBDIR_CAMERA),
                        VideoItem.Category.CAMERA);
                // Legacy dual folder mapped to CAMERA
                scanInternalDirectory(results, completedParents, new File(baseDir, Constants.RECORDING_SUBDIR_DUAL),
                        VideoItem.Category.CAMERA);
            } catch (RuntimeException e) {
                failures.add(e.getMessage() == null ? baseDir.getPath() : e.getMessage());
            } finally {
                latch.countDown();
            }
        });

        // Screen
        scanner.submit(() -> {
            try {
                scanInternalDirectory(results, completedParents, new File(baseDir, Constants.RECORDING_SUBDIR_SCREEN),
                        VideoItem.Category.SCREEN);
            } catch (RuntimeException e) {
                failures.add(e.getMessage() == null ? baseDir.getPath() : e.getMessage());
            } finally {
                latch.countDown();
            }
        });

        // Faditor
        scanner.submit(() -> {
            try {
                scanInternalDirectory(results, completedParents, new File(baseDir, Constants.RECORDING_SUBDIR_FADITOR),
                        VideoItem.Category.FADITOR);
            } catch (RuntimeException e) {
                failures.add(e.getMessage() == null ? baseDir.getPath() : e.getMessage());
            } finally {
                latch.countDown();
            }
        });

        // Stream
        scanner.submit(() -> {
            try {
                scanInternalDirectory(results, completedParents, new File(baseDir, Constants.RECORDING_SUBDIR_STREAM),
                        VideoItem.Category.STREAM);
            } catch (RuntimeException e) {
                failures.add(e.getMessage() == null ? baseDir.getPath() : e.getMessage());
            } finally {
                latch.countDown();
            }
        });

        // Shot
        scanner.submit(() -> {
            try {
                scanInternalDirectory(results, completedParents, new File(baseDir, Constants.RECORDING_SUBDIR_SHOT),
                        VideoItem.Category.SHOT);
                scanInternalDirectory(results, completedParents, new File(baseDir, Constants.RECORDING_SUBDIR_MINIAPPS),
                        VideoItem.Category.MINIAPPS);
            } catch (RuntimeException e) {
                failures.add(e.getMessage() == null ? baseDir.getPath() : e.getMessage());
            } finally {
                latch.countDown();
            }
        });

        // Legacy root-level files
        scanner.submit(() -> {
            try {
                scanLegacyRoot(results, completedParents, baseDir);
            } catch (RuntimeException e) {
                failures.add(e.getMessage() == null ? baseDir.getPath() : e.getMessage());
            } finally {
                latch.countDown();
            }
        });

        // Extra latch count for the combined Camera+Dual task
        latch.countDown(); // Adjust — we used 7 but only 6 submits (Camera+Dual share one)

        try {
            if (!latch.await(30, TimeUnit.SECONDS)) failures.add(baseDir.getPath());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failures.add(baseDir.getPath());
            FLog.w(TAG, "Scan interrupted");
        }
        scanner.shutdown();

        return new ArrayList<>(results);
    }

    /**
     * Recursively scan a category directory (handles subdirectories like Camera/Back, Shot/Selfie, etc.)
     */
    private static void recordCompletedFolder(Set<String> completedParents, File folder) {
        try {
            completedParents.add(folder.getCanonicalPath());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(folder.getPath(), e);
        }
    }

    private void scanInternalDirectory(List<VideoIndexEntity> out, Set<String> completedParents, File directory, VideoItem.Category category) {
        if (!directory.exists()) return;
        if (!directory.isDirectory()) throw new IllegalStateException(directory.getPath());

        File[] children = directory.listFiles();
        if (children == null) throw new IllegalStateException(directory.getPath());

        for (File child : children) {
            if (child == null) continue;

            if (child.isDirectory()) {
                // Handle known subdirectories for category-specific subtypes
                scanInternalSubdirectory(out, completedParents, child, category);
            } else if (child.isFile()) {
                VideoIndexEntity entity = buildEntityFromFile(child, category,
                        VideoItem.ShotSubtype.UNKNOWN,
                        VideoItem.CameraSubtype.UNKNOWN,
                        VideoItem.FaditorSubtype.UNKNOWN);
                if (entity != null) out.add(entity);
            }
        }
        recordCompletedFolder(completedParents, directory);
    }

    private void scanInternalSubdirectory(List<VideoIndexEntity> out, Set<String> completedParents, File subdir, VideoItem.Category category) {
        String folderName = subdir.getName();

        VideoItem.ShotSubtype shotSubtype = VideoItem.ShotSubtype.UNKNOWN;
        VideoItem.CameraSubtype cameraSubtype = VideoItem.CameraSubtype.UNKNOWN;
        VideoItem.FaditorSubtype faditorSubtype = VideoItem.FaditorSubtype.UNKNOWN;

        if (category == VideoItem.Category.SHOT) {
            shotSubtype = inferShotSubtypeFromFolder(folderName);
        } else if (category == VideoItem.Category.CAMERA) {
            cameraSubtype = inferCameraSubtypeFromFolder(folderName);
        } else if (category == VideoItem.Category.FADITOR) {
            faditorSubtype = inferFaditorSubtypeFromFolder(folderName);
        }

        File[] files = subdir.listFiles();
        if (files == null) throw new IllegalStateException(subdir.getPath());

        for (File file : files) {
            if (file == null) continue;
            if (file.isDirectory()) {
                scanInternalSubdirectory(out, completedParents, file, category);
            } else if (file.isFile()) {
                VideoIndexEntity entity = buildEntityFromFile(file, category, shotSubtype, cameraSubtype, faditorSubtype);
                if (entity != null) out.add(entity);
            }
        }
        recordCompletedFolder(completedParents, subdir);
    }

    private void scanLegacyRoot(List<VideoIndexEntity> out, Set<String> completedParents, File baseDir) {
        File[] files = baseDir.listFiles();
        if (files == null) throw new IllegalStateException(baseDir.getPath());

        for (File file : files) {
            if (file == null || !file.isFile()) continue;
            String name = file.getName();

            String mediaType = inferMediaType(name);
            if (mediaType == null || name.startsWith("temp_")) continue;

            VideoItem.Category category = inferCategoryFromLegacyName(name);

            VideoIndexEntity entity = new VideoIndexEntity();
            entity.uriString = Uri.fromFile(file).toString();
            entity.displayName = name;
            entity.fileSize = file.length();

            long lastMod = file.lastModified();
            long fromName = Utils.parseTimestampFromFilename(name);
            entity.lastModified = lastMod > 0 ? lastMod : (fromName > 0 ? fromName : System.currentTimeMillis());

            entity.category = category.name();
            entity.mediaType = mediaType;
            entity.shotSubtype = (category == VideoItem.Category.SHOT
                    ? resolveShotSubtype(name) : VideoItem.ShotSubtype.UNKNOWN).name();
            entity.cameraSubtype = (category == VideoItem.Category.CAMERA
                    ? resolveCameraSubtype(name) : VideoItem.CameraSubtype.UNKNOWN).name();
            entity.faditorSubtype = (category == VideoItem.Category.FADITOR
                    ? resolveFaditorSubtype(name) : VideoItem.FaditorSubtype.UNKNOWN).name();
            entity.isTemporary = false;
            entity.durationResolved = false;
            entity.indexedAt = System.currentTimeMillis();

            out.add(entity);
        }
        recordCompletedFolder(completedParents, baseDir);
    }

    @Nullable
    private VideoIndexEntity buildEntityFromFile(
            @NonNull File file,
            @NonNull VideoItem.Category category,
            @NonNull VideoItem.ShotSubtype shotSubtype,
            @NonNull VideoItem.CameraSubtype cameraSubtype,
            @NonNull VideoItem.FaditorSubtype faditorSubtype
    ) {
        String name = file.getName();
        String mediaType = inferMediaType(name);
        if (mediaType == null) return null;

        // Skip temp_ files — OpenGL pipeline no longer uses them
        if (name.startsWith("temp_")) return null;

        VideoIndexEntity entity = new VideoIndexEntity();
        entity.uriString = Uri.fromFile(file).toString();
        entity.displayName = name;
        entity.fileSize = file.length();

        long lastMod = file.lastModified();
        long fromName = Utils.parseTimestampFromFilename(name);
        entity.lastModified = lastMod > 0 ? lastMod : (fromName > 0 ? fromName : System.currentTimeMillis());

        entity.category = category.name();
        entity.mediaType = mediaType;

        // Resolve subtypes — use folder-based first, then fall back to name-based
        entity.shotSubtype = (category == VideoItem.Category.SHOT
                ? resolveEffectiveShotSubtype(shotSubtype, name) : VideoItem.ShotSubtype.UNKNOWN).name();
        entity.cameraSubtype = (category == VideoItem.Category.CAMERA
                ? resolveEffectiveCameraSubtype(cameraSubtype, name) : VideoItem.CameraSubtype.UNKNOWN).name();
        entity.faditorSubtype = (category == VideoItem.Category.FADITOR
                ? resolveEffectiveFaditorSubtype(faditorSubtype, name) : VideoItem.FaditorSubtype.UNKNOWN).name();

        entity.isTemporary = false;
        entity.durationResolved = false;
        entity.indexedAt = System.currentTimeMillis();

        return entity;
    }

    // ════════════════════════════════════════════════════════════════
    // SAF Scanning (ContentResolver — fast path)
    // ════════════════════════════════════════════════════════════════

    @NonNull
    private ScanResult scanSaf(@NonNull Uri treeUri, List<String> failures) {
        List<VideoIndexEntity> results = new ArrayList<>();
        Set<String> completedParents = new HashSet<>();

        try {
            // The SAF tree URI points directly to the recording directory
            // (the user selected this folder via the SAF picker, and the app
            // creates Camera/, Screen/, etc. subdirectories directly inside it).
            DocumentFile treeDoc = DocumentFile.fromTreeUri(context, treeUri);
            if (treeDoc == null) {
                failures.add(treeUri.toString());
                return new ScanResult(results, failures, treeUri, completedParents);
            }

            // Use the tree root directly as the recording root.
            // Fallback: check if there's a "FadCam" subdirectory (some setups may
            // point to a parent directory containing a FadCam folder).
            Uri recordingRoot = treeDoc.getUri();
            java.util.Map<String, Uri> folders = listSafFolders(recordingRoot);
            Uri fadCamChild = folders.get(Constants.RECORDING_DIRECTORY);
            if (fadCamChild != null) {
                recordingRoot = fadCamChild;
                folders = listSafFolders(recordingRoot);
            }

            scanSafCategory(results, completedParents, folders.get(Constants.RECORDING_SUBDIR_CAMERA), VideoItem.Category.CAMERA);
            scanSafCategory(results, completedParents, folders.get(Constants.RECORDING_SUBDIR_DUAL), VideoItem.Category.CAMERA);
            scanSafCategory(results, completedParents, folders.get(Constants.RECORDING_SUBDIR_SCREEN), VideoItem.Category.SCREEN);
            scanSafCategory(results, completedParents, folders.get(Constants.RECORDING_SUBDIR_FADITOR), VideoItem.Category.FADITOR);
            scanSafCategory(results, completedParents, folders.get(Constants.RECORDING_SUBDIR_STREAM), VideoItem.Category.STREAM);
            scanSafCategory(results, completedParents, folders.get(Constants.RECORDING_SUBDIR_SHOT), VideoItem.Category.SHOT);
            scanSafCategory(results, completedParents, folders.get(Constants.RECORDING_SUBDIR_MINIAPPS), VideoItem.Category.MINIAPPS);

            // Root-level legacy files
            scanSafDirectoryFiles(results, completedParents, recordingRoot, VideoItem.Category.UNKNOWN,
                    VideoItem.ShotSubtype.UNKNOWN, VideoItem.CameraSubtype.UNKNOWN, VideoItem.FaditorSubtype.UNKNOWN,
                    true);

        } catch (Exception e) {
            failures.add(e.getMessage() == null ? treeUri.toString() : e.getMessage());
            FLog.e(TAG, "SAF scan error", e);
        }

        return new ScanResult(results, failures, treeUri, completedParents);
    }

    private java.util.Map<String, Uri> listSafFolders(Uri root) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(root, DocumentsContract.getDocumentId(root));
        java.util.Map<String, Uri> folders = new java.util.HashMap<>();
        try (Cursor cursor = context.getContentResolver().query(children, new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            requireCompleteCursor(cursor, children);
            while (cursor.moveToNext()) {
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))) {
                    folders.put(cursor.getString(1), DocumentsContract.buildDocumentUriUsingTree(root, cursor.getString(0)));
                }
            }
        }
        return folders;
    }

    private static void requireCompleteCursor(Cursor cursor, Uri scope) {
        if (cursor == null || (cursor.getExtras() != null &&
                (cursor.getExtras().getBoolean(DocumentsContract.EXTRA_LOADING)
                        || cursor.getExtras().containsKey(DocumentsContract.EXTRA_ERROR)))) {
            throw new IllegalStateException(scope.toString());
        }
    }

    private void scanSafCategory(List<VideoIndexEntity> out, Set<String> completedParents, Uri subdir, VideoItem.Category category) {
        if (subdir == null) return;
        if (category == VideoItem.Category.SHOT || category == VideoItem.Category.CAMERA
                || category == VideoItem.Category.FADITOR) {
            scanSafWithSubdirectories(out, completedParents, subdir, category);
        } else {
            scanSafDirectoryFiles(out, completedParents, subdir, category, VideoItem.ShotSubtype.UNKNOWN,
                    VideoItem.CameraSubtype.UNKNOWN, VideoItem.FaditorSubtype.UNKNOWN, false);
        }
    }

    private void scanSafWithSubdirectories(List<VideoIndexEntity> out, Set<String> completedParents, Uri categoryDir,
                                           VideoItem.Category category) {
        // Use ContentResolver to list children (both files and dirs)
        ContentResolver cr = context.getContentResolver();
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                categoryDir,
                DocumentsContract.getDocumentId(categoryDir));

        try (Cursor cursor = cr.query(childrenUri,
                new String[]{
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED
                },
                null, null, null)) {

            requireCompleteCursor(cursor, childrenUri);

            while (cursor.moveToNext()) {
                String docId = cursor.getString(0);
                String name = cursor.getString(1);
                String mimeType = cursor.getString(2);
                long size = cursor.isNull(3) ? 0 : cursor.getLong(3);
                long lastMod = cursor.isNull(4) ? 0 : cursor.getLong(4);

                if (mimeType != null && DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                    // It's a subdirectory — determine subtype from folder name
                    VideoItem.ShotSubtype shotSub = VideoItem.ShotSubtype.UNKNOWN;
                    VideoItem.CameraSubtype camSub = VideoItem.CameraSubtype.UNKNOWN;
                    VideoItem.FaditorSubtype fadSub = VideoItem.FaditorSubtype.UNKNOWN;

                    if (category == VideoItem.Category.SHOT) {
                        shotSub = inferShotSubtypeFromFolder(name);
                    } else if (category == VideoItem.Category.CAMERA) {
                        camSub = inferCameraSubtypeFromFolder(name);
                    } else if (category == VideoItem.Category.FADITOR) {
                        fadSub = inferFaditorSubtypeFromFolder(name);
                    }

                    Uri subdirUri = DocumentsContract.buildDocumentUriUsingTree(categoryDir, docId);
                    scanSafDirectoryFiles(out, completedParents, subdirUri, category, shotSub, camSub, fadSub, false);
                } else {
                    // It's a file at category root level
                    VideoIndexEntity entity = buildEntityFromSafCursor(
                            categoryDir, docId, name, mimeType, size, lastMod,
                            category, VideoItem.ShotSubtype.UNKNOWN,
                            VideoItem.CameraSubtype.UNKNOWN, VideoItem.FaditorSubtype.UNKNOWN);
                    if (entity != null) out.add(entity);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(childrenUri.toString(), e);
        }
        completedParents.add(DocumentsContract.getDocumentId(categoryDir));
    }

    /**
     * Fast SAF directory file listing using raw ContentResolver.query().
     * This is the key performance win — avoids DocumentFile's per-file IPC.
     *
     * @param legacyInfer If true, infer category from filename (for root-level legacy files)
     */
    private void scanSafDirectoryFiles(List<VideoIndexEntity> out, Set<String> completedParents, Uri directoryUri,
                                       VideoItem.Category category,
                                       VideoItem.ShotSubtype shotSubtype,
                                       VideoItem.CameraSubtype cameraSubtype,
                                       VideoItem.FaditorSubtype faditorSubtype,
                                       boolean legacyInfer) {
        ContentResolver cr = context.getContentResolver();
        String docId = DocumentsContract.getDocumentId(directoryUri);
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(directoryUri, docId);

        try (Cursor cursor = cr.query(childrenUri,
                new String[]{
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED
                },
                null, null, null)) {

            requireCompleteCursor(cursor, childrenUri);

            while (cursor.moveToNext()) {
                String childDocId = cursor.getString(0);
                String name = cursor.getString(1);
                String mimeType = cursor.getString(2);
                long size = cursor.isNull(3) ? 0 : cursor.getLong(3);
                long lastMod = cursor.isNull(4) ? 0 : cursor.getLong(4);

                // Skip directories in flat scans
                if (mimeType != null && DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                    continue;
                }

                // For legacy root files, infer category from name
                VideoItem.Category effectiveCategory = category;
                VideoItem.ShotSubtype effectiveShot = shotSubtype;
                VideoItem.CameraSubtype effectiveCam = cameraSubtype;
                VideoItem.FaditorSubtype effectiveFad = faditorSubtype;

                if (legacyInfer && category == VideoItem.Category.UNKNOWN && name != null) {
                    effectiveCategory = inferCategoryFromLegacyName(name);
                    if (effectiveCategory == VideoItem.Category.SHOT) {
                        effectiveShot = resolveShotSubtype(name);
                    } else if (effectiveCategory == VideoItem.Category.CAMERA) {
                        effectiveCam = resolveCameraSubtype(name);
                    } else if (effectiveCategory == VideoItem.Category.FADITOR) {
                        effectiveFad = resolveFaditorSubtype(name);
                    }
                }

                VideoIndexEntity entity = buildEntityFromSafCursor(
                        directoryUri, childDocId, name, mimeType, size, lastMod,
                        effectiveCategory, effectiveShot, effectiveCam, effectiveFad);
                if (entity != null) out.add(entity);
            }
        } catch (Exception e) {
            throw new IllegalStateException(childrenUri.toString(), e);
        }
        completedParents.add(docId);
    }

    @Nullable
    private VideoIndexEntity buildEntityFromSafCursor(
            Uri treeUri, String docId, String name, String mimeType,
            long size, long lastMod,
            VideoItem.Category category,
            VideoItem.ShotSubtype shotSubtype,
            VideoItem.CameraSubtype cameraSubtype,
            VideoItem.FaditorSubtype faditorSubtype) {

        if (name == null) return null;

        String mediaTypeStr = inferMediaType(name);
        if (mediaTypeStr == null) return null;

        // Skip temp_ files — OpenGL pipeline no longer uses them
        if (name.startsWith("temp_")) return null;

        Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);

        VideoIndexEntity entity = new VideoIndexEntity();
        entity.uriString = fileUri.toString();
        entity.displayName = name;
        entity.fileSize = size;
        entity.lastModified = lastMod > 0 ? lastMod : System.currentTimeMillis();
        entity.category = category.name();
        entity.mediaType = mediaTypeStr;
        entity.shotSubtype = resolveEffectiveShotSubtype(shotSubtype, name).name();
        entity.cameraSubtype = resolveEffectiveCameraSubtype(cameraSubtype, name).name();
        entity.faditorSubtype = resolveEffectiveFaditorSubtype(faditorSubtype, name).name();
        entity.isTemporary = false;
        entity.durationResolved = false;
        entity.indexedAt = System.currentTimeMillis();

        return entity;
    }

    // ════════════════════════════════════════════════════════════════
    // Category / Subtype inference helpers
    // ════════════════════════════════════════════════════════════════

    @Nullable
    private String inferMediaType(@Nullable String fileName) {
        if (fileName == null) return null;
        String lower = fileName.toLowerCase();
        String expectedExt = "." + Constants.RECORDING_FILE_EXTENSION.toLowerCase();
        if (lower.endsWith(expectedExt)) return "VIDEO";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp")) {
            return "IMAGE";
        }
        return null;
    }

    @NonNull
    private VideoItem.Category inferCategoryFromLegacyName(@Nullable String fileName) {
        if (fileName == null) return VideoItem.Category.UNKNOWN;
        if (fileName.startsWith(Constants.RECORDING_DIRECTORY + "_")) return VideoItem.Category.CAMERA;
        if (fileName.startsWith("DualCam_")) return VideoItem.Category.CAMERA;
        if (fileName.startsWith(Constants.RECORDING_FILE_PREFIX_FADREC)) return VideoItem.Category.SCREEN;
        if (fileName.startsWith("Faditor_")) return VideoItem.Category.FADITOR;
        if (fileName.startsWith("Stream_")) return VideoItem.Category.STREAM;
        if (fileName.startsWith(Constants.RECORDING_FILE_PREFIX_FADSHOT)) return VideoItem.Category.SHOT;
        return VideoItem.Category.UNKNOWN;
    }

    // -- Shot subtypes --
    @NonNull
    private VideoItem.ShotSubtype inferShotSubtypeFromFolder(@Nullable String folderName) {
        if (folderName == null) return VideoItem.ShotSubtype.UNKNOWN;
        if (Constants.RECORDING_SUBDIR_SHOT_SELFIE.equalsIgnoreCase(folderName)) return VideoItem.ShotSubtype.SELFIE;
        if (Constants.RECORDING_SUBDIR_SHOT_FADREC.equalsIgnoreCase(folderName)) return VideoItem.ShotSubtype.FADREC;
        if (Constants.RECORDING_SUBDIR_SHOT_BACK.equalsIgnoreCase(folderName)) return VideoItem.ShotSubtype.BACK;
        return VideoItem.ShotSubtype.UNKNOWN;
    }

    @NonNull
    private VideoItem.ShotSubtype resolveShotSubtype(@Nullable String name) {
        if (name == null) return VideoItem.ShotSubtype.BACK;
        if (name.startsWith(Constants.RECORDING_FILE_PREFIX_FADSHOT + "Selfie_")) return VideoItem.ShotSubtype.SELFIE;
        if (name.startsWith(Constants.RECORDING_FILE_PREFIX_FADSHOT + "FadRec_")) return VideoItem.ShotSubtype.FADREC;
        if (name.startsWith(Constants.RECORDING_FILE_PREFIX_FADSHOT + "Back_")) return VideoItem.ShotSubtype.BACK;
        return VideoItem.ShotSubtype.BACK;
    }

    @NonNull
    private VideoItem.ShotSubtype resolveEffectiveShotSubtype(@NonNull VideoItem.ShotSubtype explicit, @Nullable String name) {
        if (explicit != VideoItem.ShotSubtype.UNKNOWN) return explicit;
        return resolveShotSubtype(name);
    }

    // -- Camera subtypes --
    @NonNull
    private VideoItem.CameraSubtype inferCameraSubtypeFromFolder(@Nullable String folderName) {
        if (folderName == null) return VideoItem.CameraSubtype.UNKNOWN;
        if (Constants.RECORDING_SUBDIR_CAMERA_FRONT.equalsIgnoreCase(folderName)) return VideoItem.CameraSubtype.FRONT;
        if (Constants.RECORDING_SUBDIR_CAMERA_DUAL.equalsIgnoreCase(folderName)) return VideoItem.CameraSubtype.DUAL;
        if (Constants.RECORDING_SUBDIR_CAMERA_BACK.equalsIgnoreCase(folderName)) return VideoItem.CameraSubtype.BACK;
        return VideoItem.CameraSubtype.UNKNOWN;
    }

    @NonNull
    private VideoItem.CameraSubtype resolveCameraSubtype(@Nullable String name) {
        if (name == null) return VideoItem.CameraSubtype.BACK;
        if (name.startsWith("DualCam_")) return VideoItem.CameraSubtype.DUAL;
        return VideoItem.CameraSubtype.BACK;
    }

    @NonNull
    private VideoItem.CameraSubtype resolveEffectiveCameraSubtype(@NonNull VideoItem.CameraSubtype explicit, @Nullable String name) {
        if (explicit != VideoItem.CameraSubtype.UNKNOWN) return explicit;
        return resolveCameraSubtype(name);
    }

    // -- Faditor subtypes --
    @NonNull
    private VideoItem.FaditorSubtype inferFaditorSubtypeFromFolder(@Nullable String folderName) {
        if (folderName == null) return VideoItem.FaditorSubtype.UNKNOWN;
        if (Constants.RECORDING_SUBDIR_FADITOR_CONVERTED.equalsIgnoreCase(folderName)) return VideoItem.FaditorSubtype.CONVERTED;
        if (Constants.RECORDING_SUBDIR_FADITOR_MERGE.equalsIgnoreCase(folderName)) return VideoItem.FaditorSubtype.MERGE;
        return VideoItem.FaditorSubtype.UNKNOWN;
    }

    @NonNull
    private VideoItem.FaditorSubtype resolveFaditorSubtype(@Nullable String name) {
        if (name == null) return VideoItem.FaditorSubtype.OTHER;
        if (name.startsWith(Constants.RECORDING_FILE_PREFIX_FADITOR_STANDARD)) return VideoItem.FaditorSubtype.CONVERTED;
        if (name.startsWith(Constants.RECORDING_FILE_PREFIX_FADITOR_MERGE)) return VideoItem.FaditorSubtype.MERGE;
        return VideoItem.FaditorSubtype.OTHER;
    }

    @NonNull
    private VideoItem.FaditorSubtype resolveEffectiveFaditorSubtype(@NonNull VideoItem.FaditorSubtype explicit, @Nullable String name) {
        if (explicit != VideoItem.FaditorSubtype.UNKNOWN) return explicit;
        return resolveFaditorSubtype(name);
    }
}

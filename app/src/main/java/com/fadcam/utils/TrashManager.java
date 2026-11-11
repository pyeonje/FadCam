package com.fadcam.utils;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.AtomicFile;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import com.fadcam.Constants;
import com.fadcam.model.TrashItem;
import com.fadcam.Utils;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.fadcam.SharedPreferencesManager;

public class TrashManager {

    private static final String TAG = "TrashManager";
    private static final String METADATA_FILE_NAME = "trash_metadata.json";

    public interface MoveToTrashProgressListener {
        void onProgress(@NonNull MoveToTrashProgress progress);
    }

    public static class MoveToTrashProgress {
        public final long bytesCopied;
        public final long totalBytes;

        public MoveToTrashProgress(long bytesCopied, long totalBytes) {
            this.bytesCopied = Math.max(0L, bytesCopied);
            this.totalBytes = Math.max(0L, totalBytes);
        }
    }

    /**
     * Gets the File object for the trash directory within the app's external files
     * directory.
     * Creates the directory if it doesn't exist.
     * Path: Android/data/com.fadcam/files/Trash/
     *
     * @param context The application context.
     * @return The File object for the trash directory, or null if it cannot be
     *         created/accessed.
     */
    public static File getTrashDirectory(Context context) {
        if (context == null) {
            FLog.e(TAG, "Context is null, cannot get trash directory.");
            return null;
        }
        File externalFilesDir = context.getExternalFilesDir(null);
        if (externalFilesDir == null) {
            FLog.e(TAG, "External files directory is null, cannot get trash directory.");
            return null;
        }
        File trashDir = new File(externalFilesDir, Constants.TRASH_DIRECTORY_NAME);
        if (!trashDir.exists()) {
            if (trashDir.mkdirs()) {
                FLog.i(TAG, "Trash directory created at: " + trashDir.getAbsolutePath());
            } else {
                FLog.e(TAG, "Failed to create trash directory at: " + trashDir.getAbsolutePath());
                return null; // Failed to create
            }
        }
        return trashDir;
    }

    /**
     * Loads the list of TrashItem objects from the metadata JSON file.
     *
     * @param context The application context.
     * @return A List of TrashItem objects. Returns an empty list if the file
     *         doesn't exist or an error occurs.
     */
    public static synchronized List<TrashItem> loadTrashMetadata(Context context) {
        if (context == null) {
            FLog.e(TAG, "Context is null, cannot load trash metadata.");
            return new ArrayList<>();
        }
        File metadataFile = new File(context.getFilesDir(), Constants.TRASH_METADATA_FILENAME);
        List<TrashItem> items = new ArrayList<>();
        boolean readable = true;
        try {
            byte[] bytes = new AtomicFile(metadataFile).readFully();
            List<TrashItem> parsed = new Gson().fromJson(new String(bytes, StandardCharsets.UTF_8),
                    new TypeToken<ArrayList<TrashItem>>() {}.getType());
            if (parsed != null) items.addAll(parsed);
        } catch (IOException | RuntimeException e) {
            readable = !metadataFile.exists();
            if (!readable) FLog.e(TAG, "휴지통 목록을 읽을 수 없습니다.", e);
        }
        File journalDir = new File(context.getFilesDir(), "trash-move-journal");
        File[] records = journalDir.listFiles((dir, name) -> name.endsWith(".json"));
        File[] restores = new File(context.getFilesDir(), "trash-restore-journal")
                .listFiles((dir, name) -> name.endsWith(".json"));
        if ((records == null || records.length == 0) && (restores == null || restores.length == 0)) return items;
        File trashDir = getTrashDirectory(context);
        if (trashDir == null) return items;
        List<File> reconciled = new ArrayList<>();
        for (File record : records == null ? new File[0] : records) {
            try {
                TrashItem item = new Gson().fromJson(new String(new AtomicFile(record).readFully(),
                        StandardCharsets.UTF_8), TrashItem.class);
                if (item == null || item.getTrashFileName() == null) continue;
                File file = new File(trashDir, item.getTrashFileName());
                if (!file.isFile()) {
                    reconciled.add(record);
                    continue;
                }
                if (file.length() != item.getSizeBytes()) continue;
                if (!items.contains(item)) items.add(item);
                reconciled.add(record);
            } catch (IOException | RuntimeException e) {
                FLog.w(TAG, "휴지통 이동 복구 기록을 읽을 수 없습니다.", e);
            }
        }
        for (File record : restores == null ? new File[0] : restores) {
            try {
                RestoreRecord restore = new Gson().fromJson(new String(new AtomicFile(record).readFully(),
                        StandardCharsets.UTF_8), RestoreRecord.class);
                if (restore == null || restore.item == null || restore.destination == null) continue;
                File original = new File(trashDir, restore.item.getTrashFileName());
                // Keep the trash entry unless a completed destination can be identified.
                Uri destination = Uri.parse(restore.destination);
                if (!original.exists() && (restore.digest != null
                        ? CopyIntegrity.matches(context.getContentResolver().openInputStream(destination),
                                restore.bytes, restore.digest)
                        : getUriSize(context, destination) == restore.bytes)) {
                    items.remove(restore.item);
                    reconciled.add(record);
                }
            } catch (IOException | RuntimeException e) {
                FLog.w(TAG, "휴지통 복원 기록을 읽을 수 없습니다.", e);
            }
        }
        if (readable && !reconciled.isEmpty() && saveTrashMetadata(context, items)) {
            for (File record : reconciled) new AtomicFile(record).delete();
        }
        return items;
    }

    /**
     * Saves the list of TrashItem objects to the metadata JSON file.
     *
     * @param context The application context.
     * @param items   The list of TrashItem objects to save.
     * @return true if saving was successful, false otherwise.
     */
    public static synchronized boolean saveTrashMetadata(Context context, List<TrashItem> items) {
        if (context == null) {
            FLog.e(TAG, "Context is null, cannot save trash metadata.");
            return false;
        }
        if (items == null) {
            FLog.w(TAG, "Trash items list is null. Saving an empty list instead.");
            items = new ArrayList<>(); // Avoid NullPointerException by saving an empty list
        }

        File metadataFile = new File(context.getFilesDir(), Constants.TRASH_METADATA_FILENAME);
        if (metadataFile.isDirectory()) return false;
        // Refuse to replace a pre-existing but unreadable list.
        if (metadataFile.isFile()) {
            try {
                new Gson().fromJson(new String(new AtomicFile(metadataFile).readFully(), StandardCharsets.UTF_8),
                        new TypeToken<ArrayList<TrashItem>>() {}.getType());
            } catch (IOException | RuntimeException e) {
                return false;
            }
        }
        return writeAtomic(metadataFile, new Gson().toJson(items));
    }

    private static boolean writeAtomic(File file, String json) {
        AtomicFile atomic = new AtomicFile(file);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        FileOutputStream out = null;
        try {
            out = atomic.startWrite();
            out.write(bytes);
            out.getFD().sync();
            atomic.finishWrite(out);
            out = null;
            return java.util.Arrays.equals(bytes, atomic.readFully());
        } catch (IOException | RuntimeException e) {
            if (out != null) atomic.failWrite(out);
            FLog.e(TAG, "휴지통 기록을 저장할 수 없습니다.", e);
            return false;
        }
    }

    /**
     * Moves a video file to the trash directory and updates the trash metadata.
     *
     * @param context             The application context.
     * @param videoUri            The URI of the video to be trashed.
     * @param originalDisplayName The original display name of the video.
     * @param isSafSource         True if the videoUri is from SAF, false if it's a
     *                            direct file path.
     * @return True if the video was successfully moved to trash, false otherwise.
     */
    public static boolean moveToTrash(Context context, Uri videoUri, String originalDisplayName, boolean isSafSource) {
        return moveToTrash(
                context,
                videoUri,
                originalDisplayName,
                isSafSource,
                Constants.TRASH_SUBDIR_VIDEO_RECORDINGS,
                null
        );
    }

    public static boolean moveToTrash(
            Context context,
            Uri videoUri,
            String originalDisplayName,
            boolean isSafSource,
            String subDirectory
    ) {
        return moveToTrash(context, videoUri, originalDisplayName, isSafSource, subDirectory, null);
    }

    public static boolean moveToTrash(
            Context context,
            Uri videoUri,
            String originalDisplayName,
            boolean isSafSource,
            @Nullable MoveToTrashProgressListener progressListener
    ) {
        return moveToTrash(
                context,
                videoUri,
                originalDisplayName,
                isSafSource,
                Constants.TRASH_SUBDIR_VIDEO_RECORDINGS,
                progressListener
        );
    }

    public static synchronized boolean moveToTrash(
            Context context,
            Uri videoUri,
            String originalDisplayName,
            boolean isSafSource,
            String subDirectory,
            @Nullable MoveToTrashProgressListener progressListener
    ) {
        if (context == null || videoUri == null || originalDisplayName == null) {
            FLog.e(TAG, "moveToTrash: Invalid arguments (context, URI, or displayName is null).");
            return false;
        }

        File trashDir = getTrashDirectory(context);
        if (trashDir == null) {
            FLog.e(TAG, "moveToTrash: Failed to get or create trash directory.");
            return false;
        }

        String targetTrashFileName = getUniqueTrashFileName(trashDir, originalDisplayName, subDirectory);
        File targetTrashFile = new File(trashDir, targetTrashFileName);
        File targetParent = targetTrashFile.getParentFile();
        if (targetParent != null && !targetParent.exists() && !targetParent.mkdirs()) {
            FLog.e(TAG, "moveToTrash: Failed to create target parent folder " + targetParent.getAbsolutePath());
            return false;
        }

        File original = isSafSource || videoUri.getPath() == null ? null : new File(videoUri.getPath());
        if (!isSafSource && (original == null || !original.isFile())) return false;
        long expected = original != null ? original.length() : getUriSize(context, videoUri);
        // EOF alone does not establish that a provider supplied the entire original.
        if (isSafSource && expected < 0) return false;
        List<TrashItem> items = loadTrashMetadata(context);
        File journalDir = new File(context.getFilesDir(), "trash-move-journal");
        if (!journalDir.isDirectory() && !journalDir.mkdirs()) return false;
        File record = new File(journalDir, java.util.UUID.randomUUID() + ".json");
        TrashItem item = new TrashItem(videoUri.toString(), originalDisplayName, targetTrashFileName,
                System.currentTimeMillis(), isSafSource, expected);
        boolean renamed = false;
        boolean deletionAttempted = false;
        boolean targetCreated = false;
        boolean saved = false;
        try {
            if (original != null) {
                if (!writeAtomic(record, new Gson().toJson(item))) return false;
                renamed = !targetTrashFile.exists() && original.renameTo(targetTrashFile);
                if (renamed) dispatchProgress(progressListener, expected, expected);
            }
            if (!renamed) {
                // Do not expose an incomplete copy as a recoverable file.
                new AtomicFile(record).delete();
                if (!targetTrashFile.createNewFile()) return false;
                targetCreated = true;
                java.security.MessageDigest digest = CopyIntegrity.sha256();
                long copied = copyClosed(
                        () -> digesting(original != null ? new FileInputStream(original)
                                : context.getContentResolver().openInputStream(videoUri), digest),
                        () -> new FileOutputStream(targetTrashFile), expected,
                        progressListener);
                if (targetTrashFile.length() != copied
                        || !CopyIntegrity.matches(new FileInputStream(targetTrashFile), copied, digest.digest())) {
                    throw new IOException("휴지통 사본을 검증하지 못했습니다.");
                }
                item.setSizeBytes(copied);
                if (!writeAtomic(record, new Gson().toJson(item))) return false;
                deletionAttempted = true;
                boolean deleted = original != null ? original.delete()
                        : DocumentsContract.deleteDocument(context.getContentResolver(), videoUri);
                if (!deleted) return false;
            }
            items.add(item);
            saved = saveTrashMetadata(context, items);
            if (!saved) return false;
            if (SharedPreferencesManager.getInstance(context).getTrashAutoDeleteMinutes() == 0
                    && targetTrashFile.delete()) {
                items.remove(item);
                saveTrashMetadata(context, items);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            FLog.e(TAG, "휴지통 이동을 완료하지 못했습니다.", e);
            return false;
        } finally {
            if (saved || (renamed && rollbackLocalMove(targetTrashFile, original))) {
                new AtomicFile(record).delete();
            } else if (!renamed && !deletionAttempted) {
                if (targetCreated) targetTrashFile.delete();
                new AtomicFile(record).delete();
            }
            // On ambiguous deletion or failed rollback, retain the verified copy and journal.
        }
    }

    interface StreamFactory<T> { T open() throws IOException; }

    private static InputStream digesting(InputStream in, java.security.MessageDigest digest) {
        return in == null ? null : new java.security.DigestInputStream(in, digest);
    }

    private static boolean rollbackLocalMove(File trash, File original) {
        boolean created = false;
        boolean complete = false;
        try {
            // Reserving the name atomically prevents rollback from overwriting a new original.
            if (!original.createNewFile()) return false;
            created = true;
            long copied = copyClosed(() -> new FileInputStream(trash),
                    () -> new FileOutputStream(original), trash.length(), null);
            complete = original.length() == copied;
            return complete && trash.delete();
        } catch (IOException | RuntimeException e) {
            FLog.w(TAG, "휴지통 이동을 원위치로 되돌리지 못했습니다.", e);
            return false;
        } finally {
            if (created && !complete) original.delete();
        }
    }

    private static final class RestoreRecord {
        final TrashItem item;
        final String destination;
        final long bytes;
        final byte[] digest;
        RestoreRecord(TrashItem item, Uri destination, long bytes, byte[] digest) {
            this.item = item;
            this.destination = destination.toString();
            this.bytes = bytes;
            this.digest = digest;
        }
    }

    private static File recordRestore(Context context, TrashItem item, Uri destination, long bytes) {
        return recordRestore(context, item, destination, bytes, null);
    }

    private static File recordRestore(Context context, TrashItem item, Uri destination, long bytes, byte[] digest) {
        File folder = new File(context.getFilesDir(), "trash-restore-journal");
        if (!folder.isDirectory() && !folder.mkdirs()) return null;
        File journal = new File(folder, java.util.UUID.randomUUID() + ".json");
        return writeAtomic(journal, new Gson().toJson(new RestoreRecord(item, destination, bytes, digest))) ? journal : null;
    }

    static long copyClosed(StreamFactory<InputStream> input, StreamFactory<OutputStream> output,
            long expected, @Nullable MoveToTrashProgressListener progress) throws IOException {
        long copied = 0;
        try (InputStream in = input.open(); OutputStream out = output.open()) {
            if (in == null || out == null) throw new IOException("파일 스트림을 열 수 없습니다.");
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (n == 0) throw new IOException("복사가 진행되지 않았습니다.");
                out.write(buffer, 0, n);
                copied += n;
                dispatchProgress(progress, copied, expected);
            }
            out.flush();
        }
        if (expected >= 0 && expected != copied) throw new IOException("사본 크기가 원본과 다릅니다.");
        return copied;
    }

    private static void dispatchProgress(
            @Nullable MoveToTrashProgressListener progressListener,
            long copiedBytes,
            long totalBytes
    ) {
        if (progressListener == null) {
            return;
        }
        progressListener.onProgress(new MoveToTrashProgress(copiedBytes, totalBytes));
    }

    private static long sourceLengthForProgress(@NonNull Uri videoUri) {
        if (!"file".equals(videoUri.getScheme())) {
            return 0L;
        }
        try {
            File sourceFile = new File(videoUri.getPath());
            return sourceFile.exists() ? sourceFile.length() : 0L;
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static long getUriSize(@NonNull Context context, @NonNull Uri videoUri) {
        if ("file".equals(videoUri.getScheme())) {
            String path = videoUri.getPath();
            File source = path == null ? null : new File(path);
            return source != null && source.isFile() ? source.length() : -1;
        }
        try (android.database.Cursor cursor = context.getContentResolver()
                .query(videoUri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !cursor.isNull(idx)) {
                    return cursor.getLong(idx);
                }
            }
        } catch (Exception ignored) {
        }
        return -1L;
    }

    /**
     * Generates a unique filename for the trash directory, appending a counter if
     * needed.
     * e.g., video.mp4, video (1).mp4, video (2).mp4
     */
    private static String getUniqueTrashFileName(File trashDir, String originalDisplayName, String subDirectory) {
        String normalizedSubdir = (subDirectory == null || subDirectory.trim().isEmpty())
                ? Constants.TRASH_SUBDIR_VIDEO_RECORDINGS
                : subDirectory.trim();
        File scopedDir = new File(trashDir, normalizedSubdir);
        if (!scopedDir.exists() && !scopedDir.mkdirs()) {
            FLog.w(TAG, "getUniqueTrashFileName: Could not create scoped dir, falling back to root: " + scopedDir.getAbsolutePath());
            scopedDir = trashDir;
            normalizedSubdir = "";
        }
        File potentialFile = new File(scopedDir, originalDisplayName);
        if (!potentialFile.exists()) {
            return normalizedSubdir.isEmpty() ? originalDisplayName : (normalizedSubdir + "/" + originalDisplayName);
        }

        String nameWithoutExtension;
        String extension = "";
        int dotIndex = originalDisplayName.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < originalDisplayName.length() - 1) {
            nameWithoutExtension = originalDisplayName.substring(0, dotIndex);
            extension = originalDisplayName.substring(dotIndex);
        } else {
            nameWithoutExtension = originalDisplayName; // No extension or leading/trailing dot
        }

        int count = 1;
        while (true) {
            String newName = nameWithoutExtension + " (" + count + ")" + extension;
            potentialFile = new File(scopedDir, newName);
            if (!potentialFile.exists()) {
                FLog.d(TAG, "getUniqueTrashFileName: Generated unique name '" + newName + "' for original '"
                        + originalDisplayName + "'");
                return normalizedSubdir.isEmpty() ? newName : (normalizedSubdir + "/" + newName);
            }
            count++;
            if (count > 1000) { // Safety break to prevent infinite loop with extreme cases
                FLog.e(TAG, "getUniqueTrashFileName: Exceeded 1000 attempts to find unique name for "
                        + originalDisplayName + ". Using UUID based name.");
                String generated = java.util.UUID.randomUUID().toString() + extension;
                return normalizedSubdir.isEmpty() ? generated : (normalizedSubdir + "/" + generated);
            }
        }
    }

    /**
     * Permanently deletes a list of trash items from the file system and metadata.
     *
     * @param context       The application context.
     * @param itemsToDelete The list of TrashItem objects to permanently delete.
     * @return true if all specified items were successfully deleted (or didn't
     *         exist), false otherwise.
     */
    public static synchronized boolean permanentlyDeleteItems(Context context, List<TrashItem> itemsToDelete) {
        if (context == null || itemsToDelete == null || itemsToDelete.isEmpty()) {
            FLog.w(TAG, "permanentlyDeleteItems: Context is null, or no items specified for deletion.");
            return true; // Nothing to do, so technically successful.
        }

        File trashDir = getTrashDirectory(context);
        if (trashDir == null) {
            FLog.e(TAG, "permanentlyDeleteItems: Failed to get trash directory. Cannot delete files.");
            return false; // Cannot proceed without trash directory
        }

        List<TrashItem> currentMetadata = loadTrashMetadata(context);
        List<TrashItem> updatedMetadata = new ArrayList<>(currentMetadata);
        boolean allSucceeded = true;

        for (TrashItem item : itemsToDelete) {
            if (item == null || item.getTrashFileName() == null) {
                FLog.w(TAG, "permanentlyDeleteItems: Skipping null item or item with null trashFileName.");
                continue;
            }
            File fileToDelete = new File(trashDir, item.getTrashFileName());
            boolean fileDeleted = true;
            if (fileToDelete.exists()) {
                if (fileToDelete.delete()) {
                    FLog.i(TAG, "permanentlyDeleteItems: Successfully deleted file: " + fileToDelete.getAbsolutePath());
                } else {
                    FLog.e(TAG, "permanentlyDeleteItems: Failed to delete file: " + fileToDelete.getAbsolutePath());
                    fileDeleted = false;
                    allSucceeded = false; // Mark failure if any file deletion fails
                }
            } else {
                FLog.w(TAG, "permanentlyDeleteItems: File not found in trash, skipping deletion: "
                        + item.getTrashFileName());
            }

            // A failed physical delete must remain visible and retryable.
            if (!fileDeleted) continue;
            boolean removedFromMeta = updatedMetadata.remove(item);
            if (!removedFromMeta) {
                FLog.w(TAG, "permanentlyDeleteItems: Item " + item.getOriginalDisplayName()
                        + " not found in current metadata list during deletion process.");
            } else {
                FLog.d(TAG, "permanentlyDeleteItems: Item " + item.getOriginalDisplayName()
                        + " removed from metadata list.");
            }
        }

        if (!saveTrashMetadata(context, updatedMetadata)) {
            FLog.e(TAG, "permanentlyDeleteItems: Failed to save updated trash metadata after attempting deletions.");
            allSucceeded = false; // Saving metadata is crucial
        }

        return allSucceeded;
    }

    /**
     * Empties the entire trash: deletes all files in the trash directory and clears
     * the metadata.
     *
     * @param context The application context.
     * @return true if the trash was successfully emptied, false otherwise.
     */
    public static synchronized boolean emptyAllTrash(Context context) {
        if (context == null) {
            FLog.e(TAG, "emptyAllTrash: Context is null.");
            return false;
        }

        File trashDir = getTrashDirectory(context);
        if (trashDir == null || !trashDir.exists()) {
            FLog.w(TAG, "휴지통 저장소를 확인하지 못했습니다.");
            return false;
        }

        boolean allFilesDeleted = true;
        File[] files = trashDir.listFiles();
        if (files == null) return false;
        for (File file : files) {
            if (!deleteRecursively(file)) {
                FLog.e(TAG, "emptyAllTrash: Failed to delete path: " + file.getAbsolutePath());
                allFilesDeleted = false;
            }
        }

        if (!allFilesDeleted) return false;
        if (!saveTrashMetadata(context, new ArrayList<>())) {
            FLog.e(TAG, "emptyAllTrash: Failed to clear trash metadata after deleting files.");
            return false; // This is a more critical failure
        }

        return true;
    }

    private static boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return true;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursively(child)) {
                        return false;
                    }
                }
            }
        }
        return file.delete();
    }

    /**
     * Restore destination options
     */
    public enum RestoreDestination {
        DOWNLOADS_FOLDER, // Public Downloads/FadCam/ folder
        INTERNAL_STORAGE, // App's private external files directory
        SAF_STORAGE // Original SAF location (if applicable)
    }

    /**
     * Restores a list of trash items from the trash directory back to a public
     * visible location (Downloads/FadCam/) and removes them from metadata.
     *
     * @param context        The application context.
     * @param itemsToRestore The list of TrashItem objects to restore.
     * @return true if all specified items were successfully restored and metadata
     *         updated, false otherwise.
     */
    public static synchronized boolean restoreItemsFromTrash(Context context, List<TrashItem> itemsToRestore) {
        return restoreItemsFromTrash(context, itemsToRestore, RestoreDestination.DOWNLOADS_FOLDER);
    }

    /**
     * Restores a list of trash items from the trash directory back to the specified
     * destination
     * and removes them from metadata.
     *
     * @param context        The application context.
     * @param itemsToRestore The list of TrashItem objects to restore.
     * @param destination    The destination where files should be restored.
     * @return true if all specified items were successfully restored and metadata
     *         updated, false otherwise.
     */
    public static synchronized boolean restoreItemsFromTrash(Context context, List<TrashItem> itemsToRestore,
            RestoreDestination destination) {
        if (context == null || itemsToRestore == null || itemsToRestore.isEmpty()) {
            FLog.w(TAG, "restoreItemsFromTrash: Context is null, or no items specified for restoration.");
            return true; // Nothing to do.
        }

        // SAF restore uses a completely different code path (DocumentFile API)
        if (destination == RestoreDestination.SAF_STORAGE) {
            return restoreItemsToSaf(context, itemsToRestore);
        }

        File trashDir = getTrashDirectory(context);
        if (trashDir == null) {
            FLog.e(TAG, "restoreItemsFromTrash: Failed to get trash directory.");
            return false;
        }

        // Determine target directory based on destination choice
        File targetRestoreDir = getRestoreTargetDirectory(context, destination);
        if (targetRestoreDir == null) {
            FLog.e(TAG, "restoreItemsFromTrash: Failed to get target restore directory for destination: " + destination);
            return false;
        }

        if (!targetRestoreDir.exists()) {
            if (!targetRestoreDir.mkdirs()) {
                FLog.e(TAG, "restoreItemsFromTrash: Failed to create target restore directory: "
                        + targetRestoreDir.getAbsolutePath());
                if (destination == RestoreDestination.INTERNAL_STORAGE) {
                    return false;
                }
            }
        }

        List<TrashItem> currentMetadata = loadTrashMetadata(context);
        List<TrashItem> updatedMetadata = new ArrayList<>(currentMetadata);
        boolean allSucceeded = true;

        for (TrashItem item : itemsToRestore) {
            if (item == null || item.getTrashFileName() == null) {
                FLog.w(TAG, "restoreItemsFromTrash: Skipping null item or item with null trashFileName.");
                continue;
            }

            File fileInTrash = new File(trashDir, item.getTrashFileName());
            if (!fileInTrash.exists()) {
                FLog.w(TAG, "복원할 휴지통 파일을 확인하지 못했습니다.");
                allSucceeded = false;
                continue;
            }

            // Ensure unique name in target directory (using original display name as base)
            String restoredFileName = getUniqueFileNameInDirectory(targetRestoreDir, item.getOriginalDisplayName());
            File restoredFile = new File(targetRestoreDir, restoredFileName);

            boolean restoredSuccessfully = false;
            boolean created = false;
            boolean complete = false;
            try {
                created = restoredFile.createNewFile();
                if (created) {
                    long copied = copyClosed(() -> new FileInputStream(fileInTrash),
                            () -> new FileOutputStream(restoredFile), fileInTrash.length(), null);
                    if (restoredFile.length() == copied) {
                        File journal = recordRestore(context, item, Uri.fromFile(restoredFile), copied);
                        complete = journal != null;
                        if (complete) {
                            restoredSuccessfully = fileInTrash.delete();
                            if (!restoredSuccessfully) new AtomicFile(journal).delete();
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                FLog.e(TAG, "휴지통 파일을 복원하지 못했습니다.", e);
            } finally {
                if (created && !complete) restoredFile.delete();
            }

            if (restoredSuccessfully) {
                // Add to MediaStore so it appears in Gallery apps (only for public directories)
                if (destination == RestoreDestination.DOWNLOADS_FOLDER) {
                    Utils.scanFileWithMediaStore(context, restoredFile.getAbsolutePath());
                }
                boolean removedFromMeta = updatedMetadata.remove(item);
                if (!removedFromMeta) {
                    FLog.w(TAG, "restoreItemsFromTrash: Item " + item.getOriginalDisplayName()
                            + " not found in metadata during restoration.");
                } else {
                    FLog.d(TAG, "restoreItemsFromTrash: Item " + item.getOriginalDisplayName()
                            + " removed from trash metadata after restoration.");
                }
            } else {
                allSucceeded = false; // Mark as failed if this item couldn't be restored
            }
        }

        if (!saveTrashMetadata(context, updatedMetadata)) {
            FLog.e(TAG, "restoreItemsFromTrash: Failed to save updated trash metadata after restoration attempts.");
            allSucceeded = false; // This is a critical failure for consistency
        }

        return allSucceeded;
    }

    /**
     * Restores trash items back to the original SAF (custom storage) location using
     * DocumentFile API. Reads the SAF tree URI from SharedPreferencesManager and
     * creates files directly in the user's configured custom storage directory.
     *
     * @param context        The application context.
     * @param itemsToRestore The list of TrashItem objects to restore.
     * @return true if all items were successfully restored, false otherwise.
     */
    private static boolean restoreItemsToSaf(Context context, List<TrashItem> itemsToRestore) {
        File trashDir = getTrashDirectory(context);
        if (trashDir == null) {
            FLog.e(TAG, "restoreItemsToSaf: Failed to get trash directory.");
            return false;
        }

        // Get the SAF tree URI from preferences
        String safUriString = SharedPreferencesManager.getInstance(context).getCustomStorageUri();
        if (safUriString == null || safUriString.isEmpty()) {
            FLog.e(TAG, "restoreItemsToSaf: No custom storage URI configured. Cannot restore to SAF.");
            return false;
        }

        Uri safTreeUri = Uri.parse(safUriString);

        // Verify we still have write permission
        boolean hasPermission = false;
        for (android.content.UriPermission p : context.getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(safTreeUri) && p.isWritePermission()) {
                hasPermission = true;
                break;
            }
        }
        if (!hasPermission) {
            FLog.e(TAG, "restoreItemsToSaf: No write permission for SAF URI: " + safTreeUri);
            return false;
        }

        DocumentFile safDir = DocumentFile.fromTreeUri(context, safTreeUri);
        if (safDir == null || !safDir.exists() || !safDir.canWrite()) {
            FLog.e(TAG, "restoreItemsToSaf: SAF directory is null, doesn't exist, or not writable.");
            return false;
        }

        List<TrashItem> currentMetadata = loadTrashMetadata(context);
        List<TrashItem> updatedMetadata = new ArrayList<>(currentMetadata);
        boolean allSucceeded = true;

        for (TrashItem item : itemsToRestore) {
            if (item == null || item.getTrashFileName() == null) {
                FLog.w(TAG, "restoreItemsToSaf: Skipping null item or item with null trashFileName.");
                continue;
            }

            File fileInTrash = new File(trashDir, item.getTrashFileName());
            if (!fileInTrash.exists()) {
                FLog.w(TAG, "복원할 휴지통 파일을 확인하지 못했습니다.");
                allSucceeded = false;
                continue;
            }

            // Create a unique file name in the SAF directory
            String targetFileName = getUniqueSafFileName(safDir, item.getOriginalDisplayName());
            String mimeType = getMimeTypeForFile(targetFileName);

            try {
                DocumentFile newDoc = safDir.createFile(mimeType, targetFileName);
                if (newDoc != null && restoreToSafDocument(context, fileInTrash, item, newDoc)) {
                    updatedMetadata.remove(item);
                } else allSucceeded = false;
            } catch (IOException | RuntimeException e) {
                FLog.e(TAG, "휴지통 파일을 저장 위치에 복원하지 못했습니다.", e);
                allSucceeded = false;
            }
        }

        if (!saveTrashMetadata(context, updatedMetadata)) {
            FLog.e(TAG, "restoreItemsToSaf: Failed to save updated trash metadata after restoration attempts.");
            allSucceeded = false;
        }

        return allSucceeded;
    }

    static boolean restoreToSafDocument(Context context, File source, TrashItem item, DocumentFile newDoc)
            throws IOException {
        boolean complete = false;
        try {
            Uri target = newDoc.getUri();
            java.security.MessageDigest digest = CopyIntegrity.sha256();
            long copied = copyClosed(() -> digesting(new FileInputStream(source), digest),
                    () -> context.getContentResolver().openOutputStream(target), source.length(), null);
            long stored = getUriSize(context, target);
            byte[] expectedDigest = digest.digest();
            if ((stored >= 0 && stored != copied) || !CopyIntegrity.matches(
                    context.getContentResolver().openInputStream(target), copied, expectedDigest)) {
                throw new IOException("복원한 사본을 검증하지 못했습니다.");
            }
            File journal = recordRestore(context, item, target, copied, expectedDigest);
            if (journal == null) throw new IOException("복원 진행 기록을 저장하지 못했습니다.");
            complete = true;
            if (source.delete()) return true;
            new AtomicFile(journal).delete();
            return false;
        } finally {
            if (!complete) {
                try {
                    if (!newDoc.delete()) FLog.w(TAG, "불완전한 복원 사본이 저장 위치에 남았습니다.");
                } catch (RuntimeException e) {
                    FLog.w(TAG, "불완전한 복원 사본을 정리하지 못했습니다.", e);
                }
            }
        }
    }

    /**
     * Generates a unique filename within a SAF DocumentFile directory.
     * If a file with the given name already exists, appends (1), (2), etc.
     *
     * @param directory    The SAF DocumentFile directory.
     * @param originalName The desired file name.
     * @return A unique file name that doesn't conflict with existing files.
     */
    private static String getUniqueSafFileName(DocumentFile directory, String originalName) {
        // Check if a file with this name already exists
        DocumentFile existing = directory.findFile(originalName);
        if (existing == null) {
            return originalName;
        }

        String namePart = originalName;
        String extPart = "";
        int dotIndex = originalName.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < originalName.length() - 1) {
            namePart = originalName.substring(0, dotIndex);
            extPart = originalName.substring(dotIndex);
        }

        int count = 1;
        while (count <= 1000) {
            String newName = namePart + " (" + count + ")" + extPart;
            if (directory.findFile(newName) == null) {
                return newName;
            }
            count++;
        }

        // Fallback to UUID
        String generated = java.util.UUID.randomUUID().toString() + extPart;
        FLog.w(TAG, "getUniqueSafFileName: Exceeded 1000 attempts, using UUID: " + generated);
        return generated;
    }

    /**
     * Returns a MIME type for the given file name based on its extension.
     *
     * @param fileName The file name to determine MIME type for.
     * @return The MIME type string, defaults to "video/mp4" for video files.
     */
    private static String getMimeTypeForFile(String fileName) {
        if (fileName == null) return "video/mp4";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".3gp")) return "video/3gpp";
        if (lower.endsWith(".avi")) return "video/x-msvideo";
        if (lower.endsWith(".mov")) return "video/quicktime";
        return "video/mp4"; // Default for FadCam recordings
    }

    /**
     * Gets the available restore destinations for the given trash items.
     *
     * @param items The list of trash items to check.
     * @return A list of available restore destinations.
     */
    public static List<RestoreDestination> getAvailableRestoreDestinations(List<TrashItem> items) {
        List<RestoreDestination> destinations = new ArrayList<>();

        // Downloads folder is always available
        destinations.add(RestoreDestination.DOWNLOADS_FOLDER);

        // Internal storage is always available
        destinations.add(RestoreDestination.INTERNAL_STORAGE);

        // SAF storage is available if any item was originally from SAF
        boolean hasSafItems = false;
        if (items != null) {
            for (TrashItem item : items) {
                if (item != null && item.isFromSaf()) {
                    hasSafItems = true;
                    break;
                }
            }
        }

        if (hasSafItems) {
            destinations.add(RestoreDestination.SAF_STORAGE);
        }

        return destinations;
    }

    /**
     * Gets the target directory for restore based on the destination choice.
     *
     * @param context     The application context.
     * @param destination The restore destination.
     * @return The target directory File, or null if it cannot be determined.
     */
    private static File getRestoreTargetDirectory(Context context, RestoreDestination destination) {
        switch (destination) {
            case DOWNLOADS_FOLDER:
                // Public Downloads/FadCam/ folder
                File publicDownloadsDir = Environment
                        .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                return new File(publicDownloadsDir, Constants.RECORDING_DIRECTORY);

            case INTERNAL_STORAGE:
                // App's private external files directory
                File externalFilesDir = context.getExternalFilesDir(null);
                if (externalFilesDir == null) {
                    FLog.e(TAG, "getRestoreTargetDirectory: External files directory is null");
                    return null;
                }
                return new File(externalFilesDir, Constants.RECORDING_DIRECTORY);

            case SAF_STORAGE:
                // SAF restore is handled by restoreItemsToSaf() and should not reach here.
                // If it does somehow, return null to prevent incorrect restores.
                FLog.e(TAG, "getRestoreTargetDirectory: SAF_STORAGE should be handled by restoreItemsToSaf(), not here.");
                return null;

            default:
                FLog.e(TAG, "getRestoreTargetDirectory: Unknown destination: " + destination);
                return null;
        }
    }

    /**
     * Helper to get a unique filename in a target directory, similar to
     * getUniqueTrashFileName.
     */
    private static String getUniqueFileNameInDirectory(File directory, String originalName) {
        if (!directory.exists() && !directory.mkdirs()) {
            FLog.w(TAG, "getUniqueFileNameInDirectory: Target directory doesn't exist and couldn't be created: "
                    + directory.getAbsolutePath());
            // Fallback to original name, hoping for the best or that caller handles it
            return originalName;
        }
        File file = new File(directory, originalName);
        if (!file.exists()) {
            return originalName;
        }

        String namePart = originalName;
        String extPart = "";
        int dotIndex = originalName.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < originalName.length() - 1) {
            namePart = originalName.substring(0, dotIndex);
            extPart = originalName.substring(dotIndex);
        }

        int count = 1;
        while (true) {
            String newName = namePart + " (" + count + ")" + extPart;
            file = new File(directory, newName);
            if (!file.exists()) {
                return newName;
            }
            count++;
        }
    }

    /**
     * Automatically deletes items from trash that are older than the specified
     * number of minutes.
     * If autoDeleteMinutes is TRASH_AUTO_DELETE_NEVER, no items are deleted.
     *
     * @param context           Context
     * @param autoDeleteMinutes The maximum age in minutes for items to keep. Items
     *                          older than this will be deleted.
     *                          Use SharedPreferencesManager.TRASH_AUTO_DELETE_NEVER
     *                          to disable auto-deletion.
     * @return The number of items deleted.
     */
    public static synchronized int autoDeleteExpiredItems(Context context, int autoDeleteMinutes) {
        if (context == null) {
            FLog.e(TAG, "autoDeleteExpiredItems: Context is null.");
            return 0;
        }
        if (autoDeleteMinutes == 0) {
            // Immediate deletion: delete all items currently in trash
            FLog.i(TAG, "autoDeleteExpiredItems: Immediate mode selected. Deleting all items.");
            List<TrashItem> allTrashItems = loadTrashMetadata(context);
            if (allTrashItems.isEmpty())
                return 0;
            boolean success = permanentlyDeleteItemsInternal(context, new ArrayList<>(allTrashItems), allTrashItems);
            return success ? allTrashItems.size() : 0;
        }
        if (autoDeleteMinutes == SharedPreferencesManager.TRASH_AUTO_DELETE_NEVER) {
            FLog.i(TAG, "autoDeleteExpiredItems: Auto-deletion is set to NEVER. No items will be deleted.");
            return 0;
        }

        FLog.i(TAG, "autoDeleteExpiredItems: Checking for items older than " + autoDeleteMinutes + " minutes.");
        List<TrashItem> allTrashItems = loadTrashMetadata(context); // Load all current items
        if (allTrashItems.isEmpty()) {
            return 0;
        }

        List<TrashItem> itemsToExpire = new ArrayList<>();
        long currentTimeMillis = System.currentTimeMillis();
        long expiryMillis = TimeUnit.MINUTES.toMillis(autoDeleteMinutes);

        for (TrashItem item : allTrashItems) {
            if ((currentTimeMillis - item.getDateTrashed()) > expiryMillis) {
                itemsToExpire.add(item);
            }
        }

        if (itemsToExpire.isEmpty()) {
            FLog.d(TAG, "autoDeleteExpiredItems: No items found older than " + autoDeleteMinutes + " minutes.");
            return 0;
        }

        FLog.d(TAG, "autoDeleteExpiredItems: Found " + itemsToExpire.size() + " items to auto-delete.");
        boolean success = permanentlyDeleteItemsInternal(context, itemsToExpire, allTrashItems);
        return success ? itemsToExpire.size() : 0;
    }

    /**
     * Internal helper to delete item files and remove them from the provided list
     * of all trash items.
     * Saves the modified list to metadata if changes were made.
     * 
     * @param context       Context.
     * @param itemsToDelete List of TrashItem objects to delete.
     * @param allTrashItems The complete list of current trash items. This list WILL
     *                      BE MODIFIED.
     * @return true if all specified items were successfully deleted (or didn't
     *         exist on disk) and metadata was updated, false otherwise.
     */
    private static synchronized boolean permanentlyDeleteItemsInternal(Context context, List<TrashItem> itemsToDelete,
            List<TrashItem> allTrashItems) {
        if (context == null || itemsToDelete == null || itemsToDelete.isEmpty() || allTrashItems == null) {
            FLog.w(TAG, "permanentlyDeleteItemsInternal: Invalid arguments.");
            return false;
        }
        File trashDir = getTrashDirectory(context);
        if (trashDir == null) {
            FLog.e(TAG, "permanentlyDeleteItemsInternal: Failed to get trash directory.");
            return false;
        }

        int successfullyDeletedCount = 0;

        for (TrashItem item : new ArrayList<>(itemsToDelete)) { // Iterate over a copy of itemsToDelete
            File fileToDelete = new File(trashDir, item.getTrashFileName());
            boolean fileExistedAndDeleted = false; // Track if physical file was handled
            boolean metadataRemoved = false;

            if (fileToDelete.exists()) {
                if (fileToDelete.delete()) {
                    FLog.d(TAG, "Permanently deleted file: " + item.getTrashFileName());
                    fileExistedAndDeleted = true;
                } else {
                    FLog.e(TAG, "Failed to permanently delete file: " + item.getTrashFileName());
                    continue; // Preserve the entry for retry.
                }
            } else {
                FLog.w(TAG, "File to delete not found on disk: " + item.getTrashFileName()
                        + ". It will be removed from metadata.");
                fileExistedAndDeleted = true; // Consider it handled from disk perspective as it's not there
            }

            // Attempt to remove from the allTrashItems list passed in
            if (allTrashItems.remove(item)) { // Relies on TrashItem.equals() for proper removal
                metadataRemoved = true;
                FLog.d(TAG, "Removed item from metadata list: " + item.getOriginalDisplayName());
            } else {
                FLog.w(TAG, "Item " + item.getOriginalDisplayName() + " (file: " + item.getTrashFileName()
                        + ") not found in the provided allTrashItems list for metadata removal.");
            }

            if (fileExistedAndDeleted && metadataRemoved) {
                successfullyDeletedCount++;
            }
        }

        // Save the modified allTrashItems list if any items were actually removed from
        // it
        if (successfullyDeletedCount > 0 || itemsToDelete.stream().anyMatch(allTrashItems::contains)) {
            // The second condition handles cases where items were meant to be deleted
            // but perhaps file deletion failed, yet we still want to update metadata if
            // they were removed from the list.
            // More accurately, only save if the list passed (allTrashItems) has actually
            // changed.
            // A simple way is to compare sizes or check if any item in itemsToDelete is
            // still in allTrashItems.
            // However, since allTrashItems is modified directly, if
            // successfullyDeletedCount > 0, changes were made.
            if (!saveTrashMetadata(context, allTrashItems)) {
                FLog.e(TAG, "permanentlyDeleteItemsInternal: Failed to save updated metadata after deleting items.");
                return false; // Metadata save failure is critical
            }
        }
        // Return true if the number of items successfully handled (file deleted/not
        // found AND metadata removed) matches the number we intended to delete.
        return successfullyDeletedCount == itemsToDelete.size();
    }

    // More methods will be added here for restore, deletePermanently,
    // autoDeleteOldItems etc.

}

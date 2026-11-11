package com.fadcam.service;

import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.Build;
import android.os.IBinder;

import android.provider.DocumentsContract;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.fadcam.Constants;
import com.fadcam.FLog;
import com.fadcam.R;
import com.fadcam.Utils;
import com.fadcam.data.VideoIndexRepository;
import com.fadcam.utils.TrashManager;
import com.fadcam.utils.VideoStatsCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class RecordsDeletionService extends Service {

    private static final String TAG = "RecordsDeletionService";
    private static final int FOREGROUND_ID = 1308;

    public static final String ACTION_START_DELETE_SESSION = "com.fadcam.action.START_DELETE_SESSION";
    public static final String ACTION_APPEND_TO_DELETE_SESSION = "com.fadcam.action.APPEND_TO_DELETE_SESSION";
    public static final String ACTION_START_SAVE_SESSION = "com.fadcam.action.START_SAVE_SESSION";
    public static final String ACTION_ACKNOWLEDGE_DELETE_COMPLETION = "com.fadcam.action.ACKNOWLEDGE_DELETE_COMPLETION";
    public static final String ACTION_CANCEL_DELETE_SESSION = "com.fadcam.action.CANCEL_DELETE_SESSION";
    public static final String EXTRA_DELETE_ITEMS_JSON = "delete_items_json";
    public static final String EXTRA_SESSION_ID = "delete_session_id";
    public static final String EXTRA_OPERATION_KIND = "operation_kind";
    public static final String EXTRA_CUSTOM_TREE_URI = "custom_tree_uri";

    private static final String EXTRA_REQUEST_ID = "request_id";

    private final AtomicBoolean processing = new AtomicBoolean(false);
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private final AtomicBoolean executorShuttingDown = new AtomicBoolean(false);
    private int currentStartId = -1;
    private ExecutorService executor;
    private RecordsDeletionSessionStore sessionStore;
    private RecordsDeletionNotificationManager notificationManager;

    @Override
    public void onCreate() {
        super.onCreate();
        // Use a thread pool for concurrent processing instead of single thread
        executor = Executors.newFixedThreadPool(3); // Limit to 3 concurrent operations
        sessionStore = new RecordsDeletionSessionStore(this);
        notificationManager = new RecordsDeletionNotificationManager(this);
        RecordsDeletionSessionSnapshot existing = sessionStore.read();
        if (existing != null && existing.isFinished()) {
            notificationManager.cancelProgress();
        }
    }

    @Override
    public synchronized int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            maybeResumeProcessing(startId);
            return START_REDELIVER_INTENT;
        }

        String action = intent.getAction();
        if (ACTION_ACKNOWLEDGE_DELETE_COMPLETION.equals(action)) {
            acknowledgeCompletion(intent.getStringExtra(EXTRA_SESSION_ID));
            RecordsDeletionSessionSnapshot current = sessionStore.read();
            if (processing.get() || (current != null && current.isActive())) {
                // A stale notification can outlive the session it acknowledges.
                // The active worker still owns shutdown, including this latest start ID.
                currentStartId = startId;
            } else {
                stopSelfResult(startId);
            }
            return START_NOT_STICKY;
        }
        if (ACTION_CANCEL_DELETE_SESSION.equals(action)) {
            requestCancellation(intent.getStringExtra(EXTRA_SESSION_ID));
            maybeResumeProcessing(startId);
            return START_NOT_STICKY;
        }

        List<RecordsDeletionRequestItem> items = RecordsDeletionRequestItem.fromJson(
                intent.getStringExtra(EXTRA_DELETE_ITEMS_JSON));
        RecordsDeletionSessionSnapshot.OperationKind operationKind = parseOperationKind(
                intent.getStringExtra(EXTRA_OPERATION_KIND),
                action
        );
        String customTreeUri = intent.getStringExtra(EXTRA_CUSTOM_TREE_URI);

        String requestId = intent.getStringExtra(EXTRA_REQUEST_ID);
        if (!sessionStore.alreadyAccepted(requestId)) {
            startOrReplaceSession(items, operationKind, customTreeUri, requestId);
        }

        maybeResumeProcessing(startId);
        return START_REDELIVER_INTENT;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        executorShuttingDown.set(true);
        if (executor != null) {
            executor.shutdownNow();
        }
        super.onDestroy();
    }

    public static void startDeleteSession(
            @NonNull android.content.Context context,
            @NonNull List<RecordsDeletionRequestItem> items
    ) {
        startSession(context, items, RecordsDeletionSessionSnapshot.OperationKind.DELETE, null);
    }

    public static void startSaveSession(
            @NonNull android.content.Context context,
            @NonNull List<RecordsDeletionRequestItem> items,
            boolean moveToGallery
    ) {
        startSession(
                context,
                items,
                moveToGallery
                        ? RecordsDeletionSessionSnapshot.OperationKind.SAVE_MOVE_TO_GALLERY
                        : RecordsDeletionSessionSnapshot.OperationKind.SAVE_COPY_TO_GALLERY,
                null
        );
    }

    /**
     * Start an export session to a custom tree URI (from DocumentsProvider picker).
     * @param context The application context
     * @param items The files to export
     * @param customTreeUri The tree URI from OpenDocumentTree picker (e.g., content://com.android.externalstorage.documents/...)
     */
    public static boolean startExportSession(
            @NonNull android.content.Context context,
            @NonNull List<RecordsDeletionRequestItem> items,
            @NonNull android.net.Uri customTreeUri
    ) {
        return startSession(
                context,
                items,
                RecordsDeletionSessionSnapshot.OperationKind.SAVE_EXPORT_TO_CUSTOM_TREE,
                customTreeUri.toString()
        );
    }

    private static boolean startSession(
            @NonNull android.content.Context context,
            @NonNull List<RecordsDeletionRequestItem> items,
            @NonNull RecordsDeletionSessionSnapshot.OperationKind operationKind,
            @Nullable String customTreeUri
    ) {
        String action = ACTION_START_DELETE_SESSION;
        RecordsDeletionSessionSnapshot snapshot = new RecordsDeletionSessionStore(context).read();
        if (snapshot != null && snapshot.isActive()) {
            if (!snapshot.accepts(operationKind, customTreeUri)) return false;
            action = ACTION_APPEND_TO_DELETE_SESSION;
        }
        Intent intent = new Intent(context, RecordsDeletionService.class);
        intent.setAction(action);
        intent.putExtra(EXTRA_REQUEST_ID, java.util.UUID.randomUUID().toString());
        intent.putExtra(EXTRA_DELETE_ITEMS_JSON, RecordsDeletionRequestItem.toJson(items));
        intent.putExtra(EXTRA_OPERATION_KIND, operationKind.name());
        if (customTreeUri != null) {
            intent.putExtra(EXTRA_CUSTOM_TREE_URI, customTreeUri);
        }
        ContextCompat.startForegroundService(context, intent);
        return true;
    }

    public static void acknowledgeCompletion(
            @NonNull android.content.Context context,
            @NonNull String sessionId
    ) {
        Intent intent = new Intent(context, RecordsDeletionService.class);
        intent.setAction(ACTION_ACKNOWLEDGE_DELETE_COMPLETION);
        intent.putExtra(EXTRA_SESSION_ID, sessionId);
        context.startService(intent);
    }

    public static void cancelSession(
            @NonNull android.content.Context context,
            @NonNull String sessionId
    ) {
        Intent intent = new Intent(context, RecordsDeletionService.class);
        intent.setAction(ACTION_CANCEL_DELETE_SESSION);
        intent.putExtra(EXTRA_SESSION_ID, sessionId);
        context.startService(intent);
    }

    private void startOrReplaceSession(
            @NonNull List<RecordsDeletionRequestItem> items,
            @NonNull RecordsDeletionSessionSnapshot.OperationKind operationKind,
            @Nullable String customTreeUri, @Nullable String requestId
    ) {
        if (items.isEmpty()) {
            return;
        }
        RecordsDeletionSessionSnapshot existing = sessionStore.read();
        if (existing != null && existing.isActive()) {
            if (!existing.accepts(operationKind, customTreeUri)) {
                android.widget.Toast.makeText(this, R.string.records_operation_busy, android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            existing.appendItems(items);
            if (sessionStore.write(existing, requestId)) publishSnapshot(existing, false, false, false);
            return;
        }
        cancelRequested.set(false);
        RecordsDeletionSessionSnapshot snapshot = RecordsDeletionSessionSnapshot.create(items, operationKind);
        if (customTreeUri != null) {
            snapshot.customTreeUri = customTreeUri;
        }
        if (sessionStore.write(snapshot, requestId)) publishSnapshot(snapshot, false, false, false);
    }

    private void maybeResumeProcessing(int startId) {
        currentStartId = startId;
        if (processing.getAndSet(true)) {
            return;
        }
        executor.submit(() -> {
            synchronized (this) {
            try {
                RecordsDeletionSessionSnapshot snapshot = sessionStore.read();
                if (snapshot == null || snapshot.completionAcknowledged || snapshot.pendingItems.isEmpty() && !snapshot.isActive()) {
                    processing.set(false);
                    stopSelfResult(currentStartId);
                    return;
                }
                if (snapshot.isFinished()) {
                    startForeground(FOREGROUND_ID, notificationManager.buildForegroundNotification(snapshot));
                    notificationManager.showCompletion(snapshot);
                    notificationManager.cancelProgress();
                    processing.set(false);
                    stopSelfResult(currentStartId);
                    return;
                }

                reconcileInterruptedClaim(snapshot);
                cancelRequested.set(snapshot.cancellationRequested);
                snapshot.state = RecordsDeletionSessionSnapshot.State.RUNNING;
                snapshot.lastUpdatedAtMs = System.currentTimeMillis();
                startForeground(FOREGROUND_ID, notificationManager.buildForegroundNotification(snapshot));
                if (!sessionStore.write(snapshot)) throw new IllegalStateException("작업 상태를 저장하지 못했습니다.");
                publishSnapshot(snapshot, false, false, false);

                // Process items sequentially - don't return yet, let processItemsConcurrently handle finalization
                processItemsConcurrently(snapshot);
            } catch (Exception e) {
                FLog.e(TAG, "Error in maybeResumeProcessing", e);
                processing.set(false);
                com.fadcam.Utils.stopForegroundCompat(RecordsDeletionService.this, true);
                notificationManager.cancelProgress();
                stopSelfResult(currentStartId);
            }
            }
        });
    }

    private void reconcileInterruptedClaim(RecordsDeletionSessionSnapshot snapshot) {
        if (snapshot.inFlight == null) return;
        if (snapshot.inFlightOutputUri != null && snapshot.inFlightVerifiedBytes != null
                && snapshot.operationKind == RecordsDeletionSessionSnapshot.OperationKind.SAVE_EXPORT_TO_CUSTOM_TREE
                && snapshot.inFlightVerifiedBytes.equals(queryExportSize(Uri.parse(snapshot.inFlightOutputUri)))) {
            snapshot.finishRecoveredOperation(snapshot.inFlightVerifiedBytes);
            return;
        }
        Uri original = snapshot.inFlight.toUri();
        if (snapshot.operationKind == RecordsDeletionSessionSnapshot.OperationKind.DELETE
                && snapshot.inFlightStartedAtMs > 0 && original != null && "file".equals(original.getScheme())
                && original.getPath() != null && !new File(original.getPath()).exists()) {
            File trash = TrashManager.getTrashDirectory(this);
            if (trash != null) for (com.fadcam.model.TrashItem item : TrashManager.loadTrashMetadata(this)) {
                if (!snapshot.inFlight.uriString.equals(item.getOriginalUriString())
                        || item.getDateTrashed() < snapshot.inFlightStartedAtMs) continue;
                File copy = new File(trash, item.getTrashFileName());
                if (copy.isFile() && copy.length() == item.getSizeBytes()) {
                    snapshot.finishRecoveredOperation(copy.length());
                    return;
                }
            }
        }
        snapshot.recoverInterruptedClaim();
    }

    private void processItemsConcurrently(@NonNull RecordsDeletionSessionSnapshot snapshot) {
        if (cancelRequested.get() || snapshot.cancellationRequested) {
            finalizeCancelledSession(snapshot);
            return;
        }
        if (executorShuttingDown.get()) return;
        RecordsDeletionRequestItem item = snapshot.claimNext();
        if (item == null) {
            finalizeSession(snapshot);
            return;
        }
        snapshot.lastUpdatedAtMs = System.currentTimeMillis();
        if (!sessionStore.write(snapshot)) {
            processing.set(false);
            com.fadcam.Utils.stopForegroundCompat(this, true);
            stopSelfResult(currentStartId);
            return;
        }

        try {
            executor.submit(() -> {
                try {
                    ProcessResult result = processItemAsync(snapshot, item);

                    // Update progress after processing
                    synchronized (this) {
                        RecordsDeletionSessionSnapshot latest = sessionStore.read();
                        if (latest != null) {
                            latest.inFlight = null;
                            latest.inFlightOutputUri = null;
                            latest.inFlightVerifiedBytes = null;
                            latest.inFlightStartedAtMs = 0;
                            if (result.success) {
                                latest.completedItemCount++;
                                latest.processedBytes += result.item.sizeBytes;
                                latest.completedUriStrings.add(result.item.uriString);
                                latest.lastUpdatedAtMs = System.currentTimeMillis();
                                sessionStore.write(latest);

                                // Remove from index if deletion was successful
                                if (snapshot.operationKind == RecordsDeletionSessionSnapshot.OperationKind.DELETE
                                        || snapshot.operationKind == RecordsDeletionSessionSnapshot.OperationKind.SAVE_MOVE_TO_GALLERY) {
                                    try {
                                        VideoIndexRepository.getInstance(this).removeFromIndex(result.item.uriString);
                                    } catch (Exception e) {
                                        FLog.w(TAG, "Failed to remove deleted item from index: " + result.item.uriString, e);
                                    }
                                }

                                VideoStatsCache.invalidateStats(com.fadcam.SharedPreferencesManager.getInstance(this));
                                publishSnapshot(latest, true, false, false);
                            } else if (latest.cancellationRequested && result.cancelled) {
                                latest.cancelledItemCount++;
                            } else {
                                latest.failedItemCount++;
                                latest.failedUriStrings.add(result.item.uriString);
                                latest.errorSummaries.add(result.errorMessage != null
                                        ? result.errorMessage : getString(R.string.generic_save_failed));
                                latest.lastUpdatedAtMs = System.currentTimeMillis();
                                sessionStore.write(latest);
                                publishSnapshot(latest, false, false, false);
                            }

                            // Continue processing remaining items if not cancelled and executor not shutting down
                            if (!cancelRequested.get() && !executorShuttingDown.get() && !latest.pendingItems.isEmpty()) {
                                processItemsConcurrently(latest);
                            } else {
                                if (latest.cancellationRequested) finalizeCancelledSession(latest);
                                else if (!executorShuttingDown.get()) finalizeSession(latest);
                            }
                        }
                    }
                } catch (Exception e) {
                    FLog.e(TAG, "Error processing item " + item.displayName, e);
                    synchronized (this) {
                        RecordsDeletionSessionSnapshot latest = sessionStore.read();
                        if (latest != null) {
                            latest.inFlight = null;
                            if (latest.inFlightOutputUri != null) latest.residualOutputUris.add(latest.inFlightOutputUri);
                            latest.inFlightOutputUri = null;
                            latest.inFlightVerifiedBytes = null;
                            latest.inFlightStartedAtMs = 0;
                            latest.failedItemCount++;
                            latest.failedUriStrings.add(item.uriString);
                            latest.errorSummaries.add(getString(R.string.generic_save_failed));
                            latest.lastUpdatedAtMs = System.currentTimeMillis();
                            sessionStore.write(latest);
                            publishSnapshot(latest, false, false, false);

                            // Continue processing remaining items if not cancelled and executor not shutting down
                            if (!cancelRequested.get() && !executorShuttingDown.get() && !latest.pendingItems.isEmpty()) {
                                processItemsConcurrently(latest);
                            } else {
                                if (latest.cancellationRequested) finalizeCancelledSession(latest);
                                else if (!executorShuttingDown.get()) finalizeSession(latest);
                            }
                        }
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // Executor is shutting down, finalize gracefully
            FLog.w(TAG, "Executor rejected task, shutting down gracefully", e);
            processing.set(false); // Leave the durable claim for recovery.
        }
    }

    private static class ProcessResult {
        final RecordsDeletionRequestItem item;
        final boolean success;
        final String errorMessage;
        boolean cancelled;

        ProcessResult(RecordsDeletionRequestItem item, boolean success, String errorMessage) {
            this.item = item;
            this.success = success;
            this.errorMessage = errorMessage;
        }
    }

    private ProcessResult processItemAsync(
            @NonNull RecordsDeletionSessionSnapshot snapshot,
            @NonNull RecordsDeletionRequestItem item
    ) {
        if (cancelRequested.get()) {
            ProcessResult result = new ProcessResult(item, false, null);
            result.cancelled = true;
            return result;
        }

        try {
            if (snapshot.operationKind == RecordsDeletionSessionSnapshot.OperationKind.DELETE) {
                boolean success = processDeleteItemAsync(snapshot, item);
                return new ProcessResult(item, success, success ? null : getString(R.string.generic_delete_failed));
            } else if (snapshot.operationKind == RecordsDeletionSessionSnapshot.OperationKind.SAVE_EXPORT_TO_CUSTOM_TREE) {
                if (snapshot.customTreeUri == null) {
                    return new ProcessResult(item, false, getString(R.string.export_destination_none));
                }
                boolean success = processExportItemAsync(snapshot, item, snapshot.customTreeUri);
                return new ProcessResult(item, success, success ? null : getString(R.string.records_export_failed));
            } else {
                boolean success = processSaveItemAsync(snapshot, item,
                        snapshot.operationKind == RecordsDeletionSessionSnapshot.OperationKind.SAVE_MOVE_TO_GALLERY);
                return new ProcessResult(item, success, success ? null : getString(R.string.generic_save_failed));
            }
        } catch (Exception e) {
            ProcessResult result = new ProcessResult(item, false, getString(R.string.generic_save_failed));
            result.cancelled = e instanceof java.util.concurrent.CancellationException
                    || e instanceof android.os.OperationCanceledException || e instanceof CancellationSignalException;
            return result;
        }
    }

    private boolean processDeleteItemAsync(
            @NonNull RecordsDeletionSessionSnapshot snapshot,
            @NonNull RecordsDeletionRequestItem item
    ) {
        Uri uri = item.toUri();
        if (uri == null) {
            return false;
        }

        return TrashManager.moveToTrash(
                this,
                uri,
                item.displayName,
                item.safSource,
                progress -> {
                    // Progress updates are handled by the main thread
                    if (cancelRequested.get()) {
                        throw new CancellationSignalException();
                    }
                }
        );
    }

    private boolean processSaveItemAsync(
            @NonNull RecordsDeletionSessionSnapshot snapshot,
            @NonNull RecordsDeletionRequestItem item,
            boolean moveToGallery
    ) {
        Uri uri = item.toUri();
        if (uri == null) {
            return false;
        }

        try {
            GalleryMediaPublisher.checkCancelled(cancelRequested::get);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                GalleryMediaPublisher.publish(getContentResolver(), item, cancelRequested::get);
            } else if (!copyToLegacyGallery(item, uri)) return false;
            // A successful public copy/closed stream is required BEFORE deleting
            // the original. A late cancellation retains the original and copy.
            GalleryMediaPublisher.checkCancelled(cancelRequested::get);
            if (moveToGallery) {
                // For MOVE operations, deletion is required for success
                return deleteOriginalFile(uri);
            }
            // For COPY operations, copy succeeded even if we can't delete the original
            return true;
        } catch (Exception e) {
            FLog.w(TAG, "processSaveItemAsync failed for " + item.uriString, e);
            return false;
        }
    }

    private boolean copyToLegacyGallery(@NonNull RecordsDeletionRequestItem item,@NonNull Uri source) throws Exception {
        File destination=resolveUniqueGalleryDestination(GalleryMediaPublisher.displayName(item.displayName));
        if(destination==null||!destination.createNewFile())return false;
        boolean completed=false;
        try {
            long copied;
            try(InputStream in=getContentResolver().openInputStream(source);
                    OutputStream out=new FileOutputStream(destination)) {
                if(in==null)throw new java.io.IOException("Source stream unavailable");
                copied=GalleryMediaPublisher.copy(in,out,cancelRequested::get);
            }
            if(copied<=0||(item.sizeBytes>0&&copied!=item.sizeBytes)||destination.length()!=copied)
                throw new java.io.IOException("Gallery byte count mismatch");
            GalleryMediaPublisher.checkCancelled(cancelRequested::get);
            Utils.scanFileWithMediaStore(this,destination.getAbsolutePath());
            completed=true;return true;
        } finally {
            if(!completed&&!destination.delete())FLog.w(TAG,"Unable to clean owned partial Gallery copy");
        }
    }

    /**
     * Delete the original file after moving to gallery.
     * For file:// URIs, directly delete the file.
     * For SAF URIs (content://), use DocumentsContract.deleteDocument() which is the official API.
     *
     * @return true if deletion succeeded, false otherwise
     */
    private boolean deleteOriginalFile(@NonNull Uri uri) {
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            // Local file system - direct deletion
            File sourceFile = new File(uri.getPath());
            boolean deleted = !sourceFile.exists() || sourceFile.delete();
            if (!deleted) {
                FLog.w(TAG, "deleteOriginalFile: Failed to delete local file: " + uri.getPath());
            }
            return deleted;
        } else {
            // SAF URI (content://) - use official DocumentsContract.deleteDocument()
            // This is the proper API for deleting document provider URIs
            try {
                boolean deleted = DocumentsContract.deleteDocument(getContentResolver(), uri);
                if (deleted) {
                    FLog.d(TAG, "deleteOriginalFile: Successfully deleted SAF URI via DocumentsContract: " + uri);
                    return true;
                } else {
                    FLog.w(TAG, "deleteOriginalFile: DocumentsContract.deleteDocument returned false for: " + uri);
                    return false;
                }
            } catch (UnsupportedOperationException e) {
                // Some document providers don't support delete operation
                FLog.w(TAG, "deleteOriginalFile: Provider doesn't support delete for: " + uri, e);
                return false;
            } catch (SecurityException e) {
                // Insufficient permissions to delete
                FLog.w(TAG, "deleteOriginalFile: Permission denied to delete: " + uri, e);
                return false;
            } catch (Exception e) {
                // Other unexpected errors
                FLog.e(TAG, "deleteOriginalFile: Unexpected error deleting: " + uri, e);
                return false;
            }
        }
    }

    private boolean processExportItemAsync(
            @NonNull RecordsDeletionSessionSnapshot snapshot,
            @NonNull RecordsDeletionRequestItem item,
            @NonNull String customTreeUriString
    ) {
        Uri sourceUri = item.toUri();
        if (sourceUri == null) {
            return false;
        }

        androidx.documentfile.provider.DocumentFile targetFile = null;
        boolean complete = false;
        try {
            Uri treeUri = Uri.parse(customTreeUriString);
            androidx.documentfile.provider.DocumentFile targetDir =
                    androidx.documentfile.provider.DocumentFile.fromTreeUri(this, treeUri);
            if (targetDir == null || !targetDir.isDirectory() || !targetDir.canWrite()) return false;
            GalleryMediaPublisher.checkCancelled(cancelRequested::get);
            targetFile = createUniqueSafFile(targetDir, item.displayName);
            if (targetFile == null) return false;
            Uri output = targetFile.getUri();
            if (!recordExportOutput(snapshot.sessionId, output, false, null)) return false;
            Long sourceSize = queryExportSize(sourceUri);
            long expected = sourceSize != null ? sourceSize : item.sizeBytes > 0 ? item.sizeBytes : -1;
            long copied = copyExportPayload(
                    () -> getContentResolver().openInputStream(sourceUri),
                    () -> getContentResolver().openOutputStream(output, "w"), cancelRequested::get);
            Long outputSize = queryExportSize(output);
            if (!verifiedExportSize(expected, copied, outputSize)) return false;
            GalleryMediaPublisher.checkCancelled(cancelRequested::get);
            complete = recordExportOutput(snapshot.sessionId, output, false, copied);
            return complete;
        } catch (android.os.OperationCanceledException | java.util.concurrent.CancellationException e) {
            complete = false;
            throw e;
        } catch (Exception e) {
            complete = false;
            FLog.w(TAG, "파일을 내보내지 못했습니다.", e);
            return false;
        } finally {
            if (!complete && targetFile != null) {
                boolean removed = false;
                try { removed = targetFile.delete(); }
                catch (RuntimeException e) { FLog.w(TAG, "불완전한 사본을 정리하지 못했습니다.", e); }
                recordExportOutput(snapshot.sessionId, removed ? null : targetFile.getUri(), !removed, null);
            }
        }
    }

    static boolean verifiedExportSize(long expected, long copied, @Nullable Long stored) {
        return (expected < 0 || expected == copied) && (stored == null || stored == copied);
    }

    interface ExportStream<T> { T open() throws java.io.IOException; }

    static long copyExportPayload(ExportStream<InputStream> input, ExportStream<OutputStream> output,
            java.util.function.BooleanSupplier cancelled) throws java.io.IOException {
        try (InputStream in = input.open(); OutputStream out = output.open()) {
            if (in == null || out == null) throw new java.io.IOException("내보내기 스트림을 열 수 없습니다.");
            return GalleryMediaPublisher.copy(in, out, cancelled);
        }
    }

    private Long queryExportSize(Uri uri) {
        if ("file".equals(uri.getScheme())) {
            File file = new File(uri.getPath());
            return file.isFile() ? file.length() : null;
        }
        try (android.database.Cursor cursor = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                long size = cursor.getLong(0);
                return size >= 0 ? size : null;
            }
        } catch (RuntimeException ignored) {
            // Some providers cannot report sizes. Validate available sizes and actual stream EOF.
        }
        return null;
    }

    private synchronized boolean recordExportOutput(String sessionId, @Nullable Uri uri, boolean residual,
            @Nullable Long verifiedBytes) {
        RecordsDeletionSessionSnapshot latest = sessionStore.read();
        if (latest == null || !sessionId.equals(latest.sessionId) || latest.inFlight == null) return false;
        latest.inFlightOutputUri = uri == null ? null : uri.toString();
        latest.inFlightVerifiedBytes = verifiedBytes;
        if (residual && uri != null) {
            latest.residualOutputUris.add(uri.toString());
            latest.errorSummaries.add("불완전한 내보내기 사본이 남았습니다. 저장 위치를 확인하세요.");
        }
        return sessionStore.write(latest);
    }

    @Nullable
    private androidx.documentfile.provider.DocumentFile createUniqueSafFile(@NonNull androidx.documentfile.provider.DocumentFile targetDir, @NonNull String originalName) {
        String base = originalName;
        String ext = "";
        int dot = originalName.lastIndexOf('.');
        if (dot > 0) {
            base = originalName.substring(0, dot);
            ext = originalName.substring(dot);
        }

        // Try original name first
        androidx.documentfile.provider.DocumentFile file = targetDir.createFile("*/*", originalName);
        if (file != null) {
            return file;
        }

        // If exists, try with number suffix
        for (int i = 1; i <= 100; i++) {
            String uniqueName = base + "_" + i + ext;
            file = targetDir.createFile("*/*", uniqueName);
            if (file != null) {
                return file;
            }
        }

        return null;
    }

    private void markFailed(
            @NonNull RecordsDeletionSessionSnapshot snapshot,
            @NonNull RecordsDeletionRequestItem item,
            @NonNull String error
    ) {
        snapshot.failedItemCount++;
        snapshot.failedUriStrings.add(item.uriString);
        snapshot.errorSummaries.add(error);
        snapshot.lastUpdatedAtMs = System.currentTimeMillis();
        sessionStore.write(snapshot);
        publishSnapshot(snapshot, false, false, false);
    }

    private void finalizeSession(@Nullable RecordsDeletionSessionSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        snapshot.currentItemName = null;
        snapshot.currentItemIndex = Math.max(0, snapshot.completedItemCount + snapshot.failedItemCount);
        snapshot.finishedAtMs = System.currentTimeMillis();
        snapshot.lastUpdatedAtMs = snapshot.finishedAtMs;
        if (snapshot.failedItemCount == 0 && snapshot.completedItemCount > 0) {
            snapshot.state = RecordsDeletionSessionSnapshot.State.COMPLETED_SUCCESS;
        } else if (snapshot.completedItemCount > 0 && snapshot.failedItemCount > 0) {
            snapshot.state = RecordsDeletionSessionSnapshot.State.COMPLETED_PARTIAL;
        } else if (snapshot.failedItemCount > 0) {
            snapshot.state = RecordsDeletionSessionSnapshot.State.COMPLETED_FAILED;
        } else {
            snapshot.state = RecordsDeletionSessionSnapshot.State.COMPLETED_SUCCESS;
        }
        sessionStore.write(snapshot);
        publishSnapshot(snapshot, false, true, true);
        notificationManager.cancelProgress();
        notificationManager.showCompletion(snapshot);
        
        // Stop service now that session is finalized
        processing.set(false);
        com.fadcam.Utils.stopForegroundCompat(RecordsDeletionService.this, true);
        stopSelfResult(currentStartId);
    }

    private void finalizeCancelledSession(@Nullable RecordsDeletionSessionSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        snapshot.currentItemName = null;
        snapshot.currentItemIndex = Math.max(0, snapshot.completedItemCount + snapshot.failedItemCount);
        snapshot.finishedAtMs = System.currentTimeMillis();
        snapshot.lastUpdatedAtMs = snapshot.finishedAtMs;
        snapshot.unprocessedItemCount = snapshot.pendingItems.size();
        snapshot.state = RecordsDeletionSessionSnapshot.State.CANCELLED;
        sessionStore.write(snapshot);
        publishSnapshot(snapshot, false, true, true);
        notificationManager.cancelProgress();
        notificationManager.showCompletion(snapshot);
        
        // Stop service now that session is finalized
        processing.set(false);
        com.fadcam.Utils.stopForegroundCompat(RecordsDeletionService.this, true);
        stopSelfResult(currentStartId);
    }

    private void acknowledgeCompletion(@Nullable String sessionId) {
        RecordsDeletionSessionSnapshot snapshot = sessionStore.read();
        if (snapshot == null || !snapshot.isFinished() || !snapshot.sessionId.equals(sessionId)) {
            return;
        }
        snapshot.completionAcknowledged = true;
        sessionStore.clear();
        notificationManager.cancelProgress();
        notificationManager.cancelCompletion();
        publishSnapshot(snapshot, false, false, true);
    }

    private void requestCancellation(@Nullable String sessionId) {
        RecordsDeletionSessionSnapshot snapshot = sessionStore.read();
        if (snapshot == null) {
            return;
        }
        if (sessionId != null && !sessionId.equals(snapshot.sessionId)) {
            return;
        }
        cancelRequested.set(true);
        snapshot.cancellationRequested = true;
        snapshot.lastUpdatedAtMs = System.currentTimeMillis();
        sessionStore.write(snapshot);
    }

    @Nullable
    private File resolveUniqueGalleryDestination(@Nullable String displayName) {
        String safeName = (displayName == null || displayName.trim().isEmpty())
                ? ("FadCam_" + System.currentTimeMillis() + ".mp4")
                : displayName;
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File fadCamDir = new File(downloadsDir, Constants.RECORDING_DIRECTORY);
        if (!fadCamDir.exists() && !fadCamDir.mkdirs()) {
            return null;
        }
        File destination = new File(fadCamDir, safeName);
        if (!destination.exists()) {
            return destination;
        }

        String base = safeName;
        String ext = "";
        int dot = safeName.lastIndexOf('.');
        if (dot > 0 && dot < safeName.length() - 1) {
            base = safeName.substring(0, dot);
            ext = safeName.substring(dot);
        }
        int suffix = 1;
        while (destination.exists()) {
            destination = new File(fadCamDir, base + " (" + suffix + ")" + ext);
            suffix++;
        }
        return destination;
    }

    @NonNull
    private RecordsDeletionSessionSnapshot.OperationKind parseOperationKind(
            @Nullable String kindRaw,
            @Nullable String action
    ) {
        if (ACTION_START_SAVE_SESSION.equals(action)) {
            if (RecordsDeletionSessionSnapshot.OperationKind.SAVE_MOVE_TO_GALLERY.name().equals(kindRaw)) {
                return RecordsDeletionSessionSnapshot.OperationKind.SAVE_MOVE_TO_GALLERY;
            }
            return RecordsDeletionSessionSnapshot.OperationKind.SAVE_COPY_TO_GALLERY;
        }
        if (kindRaw == null || kindRaw.trim().isEmpty()) {
            return RecordsDeletionSessionSnapshot.OperationKind.DELETE;
        }
        try {
            return RecordsDeletionSessionSnapshot.OperationKind.valueOf(kindRaw);
        } catch (Exception ignored) {
            return RecordsDeletionSessionSnapshot.OperationKind.DELETE;
        }
    }

    private void publishSnapshot(
            @NonNull RecordsDeletionSessionSnapshot snapshot,
            boolean itemCompleted,
            boolean sessionFinished,
            boolean includeCompletionIntent
    ) {
        if (snapshot.isActive()) {
            notificationManager.updateProgress(snapshot);
        }

        Intent updateIntent = new Intent(Constants.ACTION_RECORDS_DELETE_SESSION_UPDATED);
        updateIntent.setPackage(getPackageName());
        updateIntent.putExtra(Constants.EXTRA_RECORDS_DELETE_SESSION_JSON,
                RecordsDeletionSessionSnapshot.toJson(snapshot));
        sendBroadcast(updateIntent);

        if (itemCompleted && !snapshot.completedUriStrings.isEmpty()) {
            List<String> completed = Collections.singletonList(
                    snapshot.completedUriStrings.get(snapshot.completedUriStrings.size() - 1));
            Intent itemIntent = new Intent(Constants.ACTION_RECORDS_DELETE_ITEM_COMPLETED);
            itemIntent.setPackage(getPackageName());
            itemIntent.putStringArrayListExtra(Constants.EXTRA_RECORDS_DELETE_COMPLETED_URIS,
                    new ArrayList<>(completed));
            itemIntent.putExtra(Constants.EXTRA_RECORDS_DELETE_SESSION_ID, snapshot.sessionId);
            itemIntent.putExtra(Constants.EXTRA_RECORDS_DELETE_OPERATION_KIND, snapshot.operationKind.name());
            sendBroadcast(itemIntent);
        }

        if (sessionFinished || includeCompletionIntent) {
            Intent finishedIntent = new Intent(Constants.ACTION_RECORDS_DELETE_SESSION_FINISHED);
            finishedIntent.setPackage(getPackageName());
            finishedIntent.putExtra(Constants.EXTRA_RECORDS_DELETE_SESSION_JSON,
                    RecordsDeletionSessionSnapshot.toJson(snapshot));
            sendBroadcast(finishedIntent);
        }
    }

    private static final class CancellationSignalException extends RuntimeException {
    }
}

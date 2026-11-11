package com.fadcam.service;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class RecordsDeletionSessionStore {

    private static final String PREFS_NAME = "records_deletion_session_store";
    private static final String KEY_SNAPSHOT_JSON = "snapshot_json";
    private static final Object JOURNAL_LOCK = new Object();

    @NonNull
    private final SharedPreferences prefs;

    public RecordsDeletionSessionStore(@NonNull Context context) {
        this(context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE));
    }

    RecordsDeletionSessionStore(SharedPreferences prefs) { this.prefs = prefs; }

    @Nullable
    public synchronized RecordsDeletionSessionSnapshot read() {
        synchronized (JOURNAL_LOCK) {
            return RecordsDeletionSessionSnapshot.fromJson(prefs.getString(KEY_SNAPSHOT_JSON, null));
        }
    }

    @NonNull
    public synchronized RecordsDeletionSessionSnapshot readOrIdle() {
        RecordsDeletionSessionSnapshot snapshot = read();
        return snapshot == null ? new RecordsDeletionSessionSnapshot() : snapshot;
    }

    public synchronized boolean write(@NonNull RecordsDeletionSessionSnapshot snapshot) {
        return write(snapshot, null);
    }

    synchronized boolean write(RecordsDeletionSessionSnapshot snapshot, @Nullable String requestId) {
        return replace(RecordsDeletionSessionSnapshot.toJson(snapshot), requestId);
    }

    private boolean replace(@Nullable String json, @Nullable String requestId) {
        synchronized (JOURNAL_LOCK) {
            String oldJson = prefs.getString(KEY_SNAPSHOT_JSON, null);
            String key = requestId == null ? null : "request:" + requestId;
            boolean wasAccepted = key != null && prefs.getBoolean(key, false);
            SharedPreferences.Editor edit = setSnapshot(prefs.edit(), json);
            if (key != null) edit.putBoolean(key, true);
            if (edit.commit()) return true;
            // Android updates the in-memory preferences even when disk commit fails.
            // Restore the last readable claim so callers cannot observe uncommitted success.
            SharedPreferences.Editor rollback = setSnapshot(prefs.edit(), oldJson);
            if (key != null && !wasAccepted) rollback.remove(key);
            rollback.commit();
            return false;
        }
    }

    private static SharedPreferences.Editor setSnapshot(SharedPreferences.Editor edit, @Nullable String json) {
        return json == null ? edit.remove(KEY_SNAPSHOT_JSON) : edit.putString(KEY_SNAPSHOT_JSON, json);
    }

    synchronized boolean alreadyAccepted(@Nullable String requestId) {
        synchronized (JOURNAL_LOCK) {
            return requestId != null && prefs.getBoolean("request:" + requestId, false);
        }
    }

    public synchronized boolean clear() {
        return replace(null, null);
    }
}

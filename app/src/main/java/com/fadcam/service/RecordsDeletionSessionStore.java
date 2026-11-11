package com.fadcam.service;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class RecordsDeletionSessionStore {

    private static final String PREFS_NAME = "records_deletion_session_store";
    private static final String KEY_SNAPSHOT_JSON = "snapshot_json";

    @NonNull
    private final SharedPreferences prefs;

    public RecordsDeletionSessionStore(@NonNull Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    @Nullable
    public synchronized RecordsDeletionSessionSnapshot read() {
        return RecordsDeletionSessionSnapshot.fromJson(prefs.getString(KEY_SNAPSHOT_JSON, null));
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
        SharedPreferences.Editor edit = prefs.edit()
                .putString(KEY_SNAPSHOT_JSON, RecordsDeletionSessionSnapshot.toJson(snapshot));
        if (requestId != null) edit.putBoolean("request:" + requestId, true);
        return edit.commit();
    }

    synchronized boolean alreadyAccepted(@Nullable String requestId) {
        return requestId != null && prefs.contains("request:" + requestId);
    }

    public synchronized void clear() {
        prefs.edit().remove(KEY_SNAPSHOT_JSON).apply();
    }
}

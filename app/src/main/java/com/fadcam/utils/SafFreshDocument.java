package com.fadcam.utils;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import androidx.documentfile.provider.DocumentFile;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Proves a returned SAF document is a new child before callers open it for writing. */
public final class SafFreshDocument {
    private SafFreshDocument() {}

    public static DocumentFile create(ContentResolver resolver, DocumentFile directory, String mime,
            String displayName) throws IOException {
        Uri parent = directory.getUri();
        Map<String, String> before = children(resolver, parent);
        if (before.containsValue(displayName)) return null;
        DocumentFile result = directory.createFile(mime, displayName);
        if (result == null) return null;
        Uri uri = result.getUri();
        if (!"content".equals(uri.getScheme()) || !Objects.equals(parent.getAuthority(), uri.getAuthority())
                || !DocumentsContract.isTreeUri(uri)
                || !DocumentsContract.getTreeDocumentId(parent).equals(DocumentsContract.getTreeDocumentId(uri))) {
            throw new IOException("Provider returned a document outside the selected tree");
        }
        String id = DocumentsContract.getDocumentId(uri);
        if (DocumentsContract.getDocumentId(parent).equals(id) || before.containsKey(id)
                || !displayName.equals(children(resolver, parent).get(id))) {
            // Ownership is uncertain: do not open or delete this URI.
            throw new IOException("Provider did not return a confirmed new document");
        }
        return result;
    }

    private static Map<String, String> children(ContentResolver resolver, Uri parent) throws IOException {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent));
        String[] projection = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME};
        Map<String, String> result = new HashMap<>();
        try (Cursor cursor = resolver.query(children, projection, null, null, null)) {
            if (cursor == null) throw new IOException("SAF folder could not be listed");
            Bundle extras = cursor.getExtras();
            if (extras != null && (extras.getBoolean(DocumentsContract.EXTRA_LOADING)
                    || extras.containsKey(DocumentsContract.EXTRA_ERROR))) {
                throw new IOException("SAF folder listing is incomplete");
            }
            int idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            if (idIndex < 0 || nameIndex < 0) throw new IOException("SAF document identity unavailable");
            while (cursor.moveToNext()) {
                String id = cursor.getString(idIndex);
                String name = cursor.getString(nameIndex);
                if (id == null || name == null || result.put(id, name) != null) {
                    throw new IOException("Ambiguous SAF document identity");
                }
            }
        }
        return result;
    }
}


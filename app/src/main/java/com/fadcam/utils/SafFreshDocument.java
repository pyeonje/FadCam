package com.fadcam.utils;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import androidx.documentfile.provider.DocumentFile;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Checks the available SAF evidence before a returned document may be written or deleted.
 * DocumentsProvider has no atomic create-if-absent operation: a provider fabricating both
 * metadata and content cannot be distinguished from a conforming one here.
 */
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
        if (DocumentsContract.getDocumentId(parent).equals(id) || before.containsKey(id)) {
            throw new IOException("Provider reused an existing document");
        }
        Map<String, String> after = children(resolver, parent);
        if (!displayName.equals(after.get(id)) || !after.entrySet().containsAll(before.entrySet())) {
            throw new IOException("Provider returned inconsistent child identity");
        }
        verifyEmptyDocument(resolver, uri, id, displayName);
        return result;
    }

    private static void verifyEmptyDocument(ContentResolver resolver, Uri uri, String id, String displayName)
            throws IOException {
        String[] projection = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE};
        try (Cursor cursor = resolver.query(uri, projection, null, null, null)) {
            requireComplete(cursor);
            if (!cursor.moveToFirst()) throw new IOException("New SAF document metadata unavailable");
            int idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
            if (idIndex < 0 || nameIndex < 0 || !id.equals(cursor.getString(idIndex))
                    || !displayName.equals(cursor.getString(nameIndex))) {
                throw new IOException("New SAF document metadata disagrees with parent listing");
            }
            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex) && cursor.getLong(sizeIndex) > 0) {
                throw new IOException("Returned SAF document already has content");
            }
            if (cursor.moveToNext()) throw new IOException("Ambiguous returned SAF document metadata");
        }
        // An absent or zero reported size does not prove emptiness. Never open for write
        // or let callers clean up the returned URI unless an independent read reached EOF.
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null || input.read() != -1) {
                throw new IOException("Returned SAF document is not verifiably empty");
            }
        }
    }

    private static void requireComplete(Cursor cursor) throws IOException {
        if (cursor == null) throw new IOException("SAF query unavailable");
        Bundle extras = cursor.getExtras();
        if (extras != null && (extras.getBoolean(DocumentsContract.EXTRA_LOADING)
                || extras.containsKey(DocumentsContract.EXTRA_ERROR))) {
            throw new IOException("SAF query is incomplete");
        }
    }

    private static Map<String, String> children(ContentResolver resolver, Uri parent) throws IOException {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent));
        String[] projection = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME};
        Map<String, String> result = new HashMap<>();
        try (Cursor cursor = resolver.query(children, projection, null, null, null)) {
            requireComplete(cursor);
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


package com.fadcam.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import java.util.HashSet;
import java.util.Set;

/** Viewer favorites are app metadata; starring never changes or copies the media file. */
final class GalleryFavorites {
    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences("gallery_favorites", Context.MODE_PRIVATE);
    }

    static boolean isFavorite(Context context, Uri uri) {
        return uri != null && preferences(context).getStringSet("uris", java.util.Collections.emptySet())
                .contains(uri.toString());
    }

    static boolean toggle(Context context, Uri uri) {
        if (uri == null) return false;
        SharedPreferences prefs = preferences(context);
        Set<String> entries = new HashSet<>(prefs.getStringSet("uris", java.util.Collections.emptySet()));
        boolean selected = entries.add(uri.toString());
        if (!selected) entries.remove(uri.toString());
        prefs.edit().putStringSet("uris", entries).apply();
        return selected;
    }

    private GalleryFavorites() { }
}

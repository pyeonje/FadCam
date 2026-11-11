package com.fadcam.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.FileProvider;
import com.fadcam.MainActivity;
import com.fadcam.R;
import com.fadcam.service.RecordsDeletionRequestItem;
import com.fadcam.service.RecordsDeletionService;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.File;
import java.util.Collections;

/** Viewer actions delegate deletion to the existing background deletion service. */
final class GalleryViewerActions {
    static final String EXTRA_OPEN_GALLERY = "com.fadcam.extra.OPEN_GALLERY";
    static void showMore(Activity host, View anchor, Uri uri, String name, String mime, Runnable info,
            Runnable playbackSettings) {
        PopupMenu menu = new PopupMenu(anchor.getContext(), anchor);
        menu.getMenu().add(0, 1, 0, R.string.viewer_gallery);
        menu.getMenu().add(0, 2, 1, R.string.video_menu_info);
        menu.getMenu().add(0, 3, 2, R.string.gallery_share);
        menu.getMenu().add(0, 4, 3, R.string.gallery_delete);
        if (playbackSettings != null) menu.getMenu().add(0, 5, 4, R.string.viewer_playback_settings);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: host.startActivity(new Intent(host, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(EXTRA_OPEN_GALLERY, true)); break;
                case 2: info.run(); break;
                case 3: share(host, uri, mime); break;
                case 4: confirmDelete(host, uri, name); break;
                case 5: if (playbackSettings != null) playbackSettings.run(); break;
                default: return false;
            }
            return true;
        });
        menu.show();
    }
    static void bindFavorite(Activity host, android.widget.ImageView button, Uri uri) {
        if (button == null || uri == null) return;
        updateFavorite(host, button, GalleryFavorites.isFavorite(host, uri));
        button.setOnClickListener(v -> updateFavorite(host, button, GalleryFavorites.toggle(host, uri)));
    }
    private static void updateFavorite(Activity host, android.widget.ImageView button, boolean favorite) {
        FavoriteTints original = (FavoriteTints) button.getTag(R.id.gallery_viewer_favorite);
        if (original == null) {
            android.content.res.ColorStateList nativeTint = whiteTint(androidx.core.widget.ImageViewCompat.getImageTintList(button));
            android.content.res.ColorStateList supportTint = button instanceof androidx.core.widget.TintableImageSourceView
                    ? whiteTint(((androidx.core.widget.TintableImageSourceView) button).getSupportImageTintList()) : null;
            original = new FavoriteTints(nativeTint, supportTint);
            button.setTag(R.id.gallery_viewer_favorite, original);
        }
        button.setSelected(favorite);
        button.setImageResource(favorite ? R.drawable.gallery_favorite_filled : R.drawable.gallery_favorite_outline);
        // Selected red and light-toolbar outline keep resource colors; dark photo toolbars keep their original white.
        androidx.core.widget.ImageViewCompat.setImageTintList(button, favorite ? null : original.nativeWhite);
        if (button instanceof androidx.core.widget.TintableImageSourceView)
            ((androidx.core.widget.TintableImageSourceView) button).setSupportImageTintList(favorite ? null : original.supportWhite);
        button.setContentDescription(host.getString(favorite ? R.string.viewer_remove_favorite : R.string.viewer_add_favorite));
    }
    private static android.content.res.ColorStateList whiteTint(android.content.res.ColorStateList tint) {
        if (tint == null) return null;
        int color = tint.getDefaultColor();
        return android.graphics.Color.red(color) == 255 && android.graphics.Color.green(color) == 255
                && android.graphics.Color.blue(color) == 255 && android.graphics.Color.alpha(color) > 0 ? tint : null;
    }
    private static final class FavoriteTints {
        final android.content.res.ColorStateList nativeWhite, supportWhite;
        FavoriteTints(android.content.res.ColorStateList nativeWhite, android.content.res.ColorStateList supportWhite) {
            this.nativeWhite = nativeWhite; this.supportWhite = supportWhite;
        }
    }
    static Intent editIntent(Activity host, Uri uri, boolean video) {
        return GalleryExternalApps.intent(host, uri, true, video);
    }
    static void bindEdit(Activity host, View button, Uri uri, boolean video) {
        if (button == null || uri == null) return;
        try {
            button.setEnabled(true); button.setAlpha(1f);
            button.setOnClickListener(v -> GalleryExternalApps.launch(host, editIntent(host, uri, video), R.string.viewer_edit));
        } catch (RuntimeException error) { button.setEnabled(false); button.setAlpha(.35f); }
    }
    static boolean playExternal(Activity host, Uri uri) {
        if (uri == null) return false;
        return GalleryExternalApps.launch(host, GalleryExternalApps.intent(host, uri, false, true), R.string.viewer_system_player);
    }
    static Intent shareIntent(Activity host, Uri uri, String mime) {
        Uri shareable = uri;
        if ("file".equals(uri.getScheme())) shareable = FileProvider.getUriForFile(host,
                host.getPackageName() + ".provider", new File(uri.getPath()));
        if (!"content".equals(shareable.getScheme())) throw new IllegalArgumentException("Unsupported media URI");
        Intent intent = new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, shareable)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri("", shareable));
        return intent;
    }
    static void share(Activity host, Uri uri, String mime) {
        if (uri == null) return;
        try { host.startActivity(Intent.createChooser(shareIntent(host, uri, mime), host.getString(R.string.gallery_share))); }
        catch (RuntimeException error) { android.widget.Toast.makeText(host,
                R.string.records_delete_error_invalid_item, android.widget.Toast.LENGTH_SHORT).show(); }
    }
    static String displayName(android.content.Context context, Uri uri, String hint) {
        if (hint != null && !hint.isEmpty()) return hint;
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) return new File(uri.getPath()).getName();
        if ("content".equals(uri.getScheme())) {
            try (android.database.Cursor cursor = context.getContentResolver().query(uri,
                    new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
            } catch (RuntimeException ignored) { }
        }
        return uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment();
    }
    static void confirmDelete(Activity host, Uri uri, String displayName) {
        if (uri == null || (!"file".equals(uri.getScheme()) && !"content".equals(uri.getScheme()))) return;
        String safeName = displayName(host, uri, displayName);
        new MaterialAlertDialogBuilder(host).setTitle(R.string.watch_delete_confirm_title)
                .setMessage(host.getString(R.string.watch_delete_confirm_msg, safeName))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.watch_delete_confirm_action, (dialog, which) -> {
                    RecordsDeletionRequestItem request = new RecordsDeletionRequestItem(uri.toString(), safeName,
                            "file".equals(uri.getScheme()) ? new File(uri.getPath()).length() : 0,
                            "content".equals(uri.getScheme()));
                    RecordsDeletionService.startDeleteSession(host, Collections.singletonList(request));
                    RecordsFragment.requestRefresh();
                    host.finish();
                }).show();
    }
}

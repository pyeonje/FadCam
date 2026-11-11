package com.fadcam.ui;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.appcompat.view.menu.ListMenuItemView;
import androidx.appcompat.view.menu.MenuBuilder;
import androidx.appcompat.view.menu.MenuItemImpl;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.core.graphics.Insets;
import androidx.core.view.DisplayCutoutCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.FragmentActivity;
import com.fadcam.R;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Real view/insets bounds and actual AppCompat menu rows; no device/OEM-runtime claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class SamsungGallerySafeUiTest {
    private FragmentActivity host() {
        FragmentActivity activity = Robolectric.buildActivity(FragmentActivity.class).setup().get();
        activity.setTheme(R.style.Base_Theme_FadCam); return activity;
    }
    private GalleryInlineLayout gallery() {
        GalleryInlineLayout root = (GalleryInlineLayout) LayoutInflater.from(host())
                .inflate(R.layout.activity_gallery_video_viewer, null, false);
        ((androidx.media3.ui.PlayerView) root.findViewById(R.id.player_view)).showController();
        return root;
    }
    private void layout(GalleryInlineLayout root, int width, int height) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
    }
    private Rect bounds(GalleryInlineLayout root, int id) {
        View view = root.findViewById(id); Rect bounds = new Rect();
        view.getDrawingRect(bounds); root.offsetDescendantRectToMyCoords(view, bounds); return bounds;
    }
    private WindowInsetsCompat insets(Insets cutoutSafe, Rect hole, Insets visibleBars, Insets stableNavigation) {
        WindowInsetsCompat.Builder builder = new WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), visibleBars)
                .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars(), stableNavigation);
        if (hole != null) builder.setDisplayCutout(new DisplayCutoutCompat(
                new Rect(cutoutSafe.left, cutoutSafe.top, cutoutSafe.right, cutoutSafe.bottom), Arrays.asList(hole)));
        return builder.build();
    }
    private void assertControlsSafe(GalleryInlineLayout root, Rect hole) {
        Rect safe = root.getSafeContentBounds();
        for (int id : new int[]{R.id.back_button, R.id.gallery_video_top_actions, R.id.gallery_video_bottom_dock}) {
            Rect actual = bounds(root, id);
            assertTrue("Control bounds " + actual + " must be inside " + safe, safe.contains(actual));
            if (hole != null) assertFalse(Rect.intersects(actual, hole));
        }
        assertFalse(Rect.intersects(bounds(root, R.id.back_button), bounds(root, R.id.gallery_video_top_actions)));
    }

    @Test public void portraitPunchHoleAndStatusNavigationInsetsKeepActualButtonsAndStripInsideSafeBounds() {
        GalleryInlineLayout root = gallery(); Rect hole = new Rect(500, 0, 580, 120);
        ViewCompat.dispatchApplyWindowInsets(root, insets(Insets.of(0, 120, 0, 0), hole,
                Insets.of(0, 96, 0, 100), Insets.of(0, 0, 0, 100)));
        layout(root, 1080, 2400);
        assertEquals(new Rect(0, 120, 1080, 2300), root.getSafeContentBounds());
        assertControlsSafe(root, hole);
        assertEquals(1080, root.findViewById(R.id.player_view).getWidth());
        assertEquals(2400, root.findViewById(R.id.player_view).getHeight());
    }

    @Test public void landscapeSideCutoutAndHiddenNavigationInsetReplaceAssumedLeftOffset() {
        GalleryInlineLayout root = gallery(); Rect hole = new Rect(0, 660, 112, 780);
        ViewCompat.dispatchApplyWindowInsets(root, insets(Insets.of(128, 0, 0, 0), hole,
                Insets.NONE, Insets.of(0, 0, 144, 0)));
        layout(root, 3120, 1440);
        assertEquals(new Rect(128, 0, 2976, 1440), root.getSafeContentBounds());
        assertEquals(173, bounds(root, R.id.back_button).left);
        assertEquals(189, root.getWidth() - bounds(root, R.id.gallery_video_top_actions).right);
        assertControlsSafe(root, hole);
    }

    @Test public void sameSizeInsetChangeRepositionsAndSubsequentRotationUsesNewPhysicalBounds() {
        GalleryInlineLayout root = gallery(); layout(root, 1080, 2400);
        int previousTop = bounds(root, R.id.back_button).top;
        Rect topHole = new Rect(500, 0, 580, 110);
        ViewCompat.dispatchApplyWindowInsets(root, insets(Insets.of(0, 110, 0, 0), topHole,
                Insets.NONE, Insets.NONE));
        layout(root, 1080, 2400);
        assertTrue(bounds(root, R.id.back_button).top > previousTop);
        assertControlsSafe(root, topHole);
        Rect sideHole = new Rect(0, 490, 100, 590);
        ViewCompat.dispatchApplyWindowInsets(root, insets(Insets.of(100, 0, 0, 0), sideHole,
                Insets.NONE, Insets.of(0, 0, 120, 0)));
        layout(root, 2400, 1080);
        assertControlsSafe(root, sideHole);
    }

    @Test public void compactSafeViewportKeepsActionsSeparateFromBottomControls() {
        GalleryInlineLayout root = gallery();
        ViewCompat.dispatchApplyWindowInsets(root, insets(Insets.NONE, null,
                Insets.of(80, 36, 40, 24), Insets.of(0, 0, 40, 24)));
        layout(root, 800, 280);
        assertControlsSafe(root, null);
        assertFalse(Rect.intersects(bounds(root, R.id.gallery_video_top_actions), bounds(root, R.id.gallery_video_bottom_dock)));
    }

    @Test public void actualPopupRowHasReadableNormal16spTextAndRoundedScopedSurface() {
        Context popup = new ContextThemeWrapper(host(), R.style.ThemeOverlay_PersonalSamsungVideoPopup);
        MenuBuilder menu = new MenuBuilder(popup);
        MenuItemImpl details = (MenuItemImpl) menu.add("Details");
        ListMenuItemView row = (ListMenuItemView) LayoutInflater.from(popup)
                .inflate(androidx.appcompat.R.layout.abc_popup_menu_item_layout, new FrameLayout(popup), false);
        row.initialize(details, 0);
        TextView title = row.findViewById(androidx.appcompat.R.id.title);
        assertEquals(Color.rgb(1, 1, 2), title.getCurrentTextColor());
        assertEquals(16 * popup.getResources().getDisplayMetrics().scaledDensity, title.getTextSize(), .1f);
        assertFalse(title.getTypeface().isBold());
        android.content.res.TypedArray values = popup.obtainStyledAttributes(null,
                new int[]{android.R.attr.popupBackground, android.R.attr.popupElevation}, androidx.appcompat.R.attr.popupMenuStyle, 0);
        GradientDrawable surface = (GradientDrawable) values.getDrawable(0);
        assertEquals(Color.argb(242, 252, 252, 255), surface.getColor().getDefaultColor());
        assertEquals(26 * popup.getResources().getDisplayMetrics().density, surface.getCornerRadius(), .1f);
        assertEquals(6 * popup.getResources().getDisplayMetrics().density, values.getDimension(1, 0), .1f);
        values.recycle();
    }

    @Test public void favoriteResourceKeepsItsOwnColorInsteadOfNativeOrSupportToolbarTint() {
        FragmentActivity host = host(); AppCompatImageButton heart = new AppCompatImageButton(host);
        heart.setImageTintList(ColorStateList.valueOf(Color.BLACK));
        heart.setSupportImageTintList(ColorStateList.valueOf(Color.BLACK));
        android.net.Uri uri = android.net.Uri.parse("content://gallery-safe-test/favorite");
        boolean before = GalleryFavorites.isFavorite(host, uri);
        GalleryViewerActions.bindFavorite(host, heart, uri);
        assertNull(heart.getImageTintList()); assertNull(heart.getSupportImageTintList());
        heart.performClick(); assertEquals(!before, heart.isSelected());
        assertNull(heart.getImageTintList()); assertNull(heart.getSupportImageTintList());
        heart.performClick(); assertEquals(before, GalleryFavorites.isFavorite(host, uri));
    }

    @Test public void darkPhotoFavoriteRestoresWhiteAfterToggleAndRebindingWhileSelected() {
        FragmentActivity host = host(); AppCompatImageButton heart = new AppCompatImageButton(host);
        ColorStateList nativeWhite = ColorStateList.valueOf(Color.WHITE);
        ColorStateList supportWhite = ColorStateList.valueOf(Color.WHITE);
        heart.setImageTintList(nativeWhite); heart.setSupportImageTintList(supportWhite);
        android.net.Uri uri = android.net.Uri.parse("content://gallery-safe-test/photo-white-favorite");
        assertFalse(GalleryFavorites.isFavorite(host, uri));
        GalleryViewerActions.bindFavorite(host, heart, uri);
        assertSame(nativeWhite, heart.getImageTintList()); assertSame(supportWhite, heart.getSupportImageTintList());
        heart.performClick(); assertTrue(heart.isSelected());
        assertNull(heart.getImageTintList()); assertNull(heart.getSupportImageTintList());
        GalleryViewerActions.bindFavorite(host, heart, uri); // Selected resource has no tint; original white must survive.
        assertTrue(heart.isSelected()); assertNull(heart.getImageTintList()); assertNull(heart.getSupportImageTintList());
        heart.performClick(); assertFalse(heart.isSelected());
        assertSame(nativeWhite, heart.getImageTintList()); assertSame(supportWhite, heart.getSupportImageTintList());
        GalleryViewerActions.bindFavorite(host, heart, uri);
        assertSame(nativeWhite, heart.getImageTintList()); assertSame(supportWhite, heart.getSupportImageTintList());
        assertFalse(GalleryFavorites.isFavorite(host, uri));
    }
}

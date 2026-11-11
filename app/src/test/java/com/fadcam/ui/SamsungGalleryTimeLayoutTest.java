package com.fadcam.ui;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.text.Layout;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.media3.ui.PlayerView;
import com.fadcam.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** Actual TextView layouts and ancestor bounds; no device or Samsung-runtime claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SamsungGalleryTimeLayoutTest {
    private GalleryInlineLayout gallery(float fontScale) {
        FragmentActivity host = Robolectric.buildActivity(FragmentActivity.class).setup().get();
        Configuration configuration = new Configuration(host.getResources().getConfiguration());
        configuration.fontScale = fontScale;
        Context context = new androidx.appcompat.view.ContextThemeWrapper(
                host.createConfigurationContext(configuration), R.style.Base_Theme_FadCam);
        GalleryInlineLayout root = (GalleryInlineLayout) LayoutInflater.from(context)
                .inflate(R.layout.activity_gallery_video_viewer, null, false);
        ((PlayerView) root.findViewById(R.id.player_view)).showController();
        return root;
    }
    private void layout(GalleryInlineLayout root, int width, int height) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
    }
    private Rect bounds(GalleryInlineLayout root, int id) {
        View view = root.findViewById(id); Rect result = new Rect();
        view.getDrawingRect(result); root.offsetDescendantRectToMyCoords(view, result); return result;
    }
    private void times(GalleryInlineLayout root, String position, String duration) {
        ((TextView) root.findViewById(androidx.media3.ui.R.id.exo_position)).setText(position);
        ((TextView) root.findViewById(androidx.media3.ui.R.id.exo_duration)).setText(duration);
    }
    private void assertFits(GalleryInlineLayout root, String position, String duration) {
        for (int id : new int[]{androidx.media3.ui.R.id.exo_position, androidx.media3.ui.R.id.exo_duration}) {
            TextView label = root.findViewById(id); Layout text = label.getLayout();
            assertNotNull(text);
            assertEquals(1, text.getLineCount());
            assertEquals(0, text.getEllipsisCount(0));
            assertTrue("Full text fits " + label.getText() + " width=" + label.getWidth(),
                    text.getLineWidth(0) <= label.getWidth() - label.getCompoundPaddingLeft()
                            - label.getCompoundPaddingRight() + 1);
            assertTrue("Full text height fits text=" + label.getText() + " class=" + label.getClass().getName()
                    + " size=" + label.getTextSize() + " layout=" + text.getHeight() + " view=" + label.getHeight()
                    + " padding=" + label.getCompoundPaddingTop() + "/" + label.getCompoundPaddingBottom()
                    + " lineHeight=" + label.getLineHeight() + " spacing=" + label.getLineSpacingExtra()
                    + "/" + label.getLineSpacingMultiplier() + " pill="
                    + bounds(root, androidx.media3.ui.R.id.exo_center_controls)
                    + " time=" + bounds(root, androidx.media3.ui.R.id.exo_time), text.getHeight() <= label.getHeight()
                    - label.getCompoundPaddingTop() - label.getCompoundPaddingBottom());
            assertEquals(id == androidx.media3.ui.R.id.exo_position ? position : duration,
                    label.getText().toString());
            assertTrue(bounds(root, androidx.media3.ui.R.id.exo_center_controls).contains(bounds(root, id)));
        }
        Rect pill = bounds(root, androidx.media3.ui.R.id.exo_center_controls);
        Rect play = bounds(root, androidx.media3.ui.R.id.exo_play_pause);
        Rect mute = bounds(root, R.id.gallery_video_mute);
        assertTrue(root.getSafeContentBounds().contains(pill));
        assertTrue(root.getSafeContentBounds().contains(mute));
        assertTrue(pill.contains(play)); assertTrue(play.width() > 0); assertTrue(play.height() > 0);
        assertFalse(Rect.intersects(pill, mute));
        assertFalse(Rect.intersects(play, bounds(root, androidx.media3.ui.R.id.exo_time)));
    }
    @Test public void fiftyThreeSecondsFitsInActualPortraitAndLandscapeTextLayouts() {
        GalleryInlineLayout root = gallery(1f); times(root, "00:00", "00:53");
        layout(root, 1080, 2400); assertFits(root, "00:00", "00:53");
        layout(root, 2400, 1080); assertFits(root, "00:00", "00:53");
    }
    @Test public void hoursAndLaterDurationUpdateRefitWithoutChangingWindowSizeOrText() {
        GalleryInlineLayout root = gallery(1f); times(root, "00:00", "00:53");
        layout(root, 1080, 2400);
        times(root, "12:34:53", "123:45:53");
        layout(root, 1080, 2400); assertFits(root, "12:34:53", "123:45:53");
        times(root, "00:01", "00:53");
        layout(root, 1080, 2400); assertFits(root, "00:01", "00:53");
    }
    @Test public void largeFontsFitCompactPortraitWithoutCoveringMute() {
        GalleryInlineLayout root = gallery(2f); times(root, "00:00", "00:53");
        layout(root, 360, 640); assertFits(root, "00:00", "00:53");
        times(root, "10:23:45", "123:45:53");
        layout(root, 360, 640); assertFits(root, "10:23:45", "123:45:53");
    }
    @Test public void longTimeAndLargeFontRemainSafeInCompactLandscapeWithCutoutAndNavigationInsets() {
        GalleryInlineLayout root = gallery(2f); times(root, "10:23:45", "123:45:53");
        ViewCompat.dispatchApplyWindowInsets(root, new WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(80, 36, 40, 24))
                .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 40, 24))
                .build());
        layout(root, 800, 280); assertFits(root, "10:23:45", "123:45:53");
        assertFalse(Rect.intersects(bounds(root, R.id.gallery_video_top_actions),
                bounds(root, R.id.gallery_video_bottom_dock)));
    }
}

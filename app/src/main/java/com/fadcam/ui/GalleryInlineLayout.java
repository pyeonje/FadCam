package com.fadcam.ui;

import android.content.Context;
import android.graphics.Rect;
import android.text.Layout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.fadcam.R;

/** Reference proportions with runtime cutout/system-bar-safe placement; video stays edge to edge. */
public final class GalleryInlineLayout extends FrameLayout {
    private int lastWidth, lastHeight;
    private Insets safeInsets = Insets.NONE;
    private boolean geometryDirty = true;
    private float referenceScale = 1f;
    public GalleryInlineLayout(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        ViewCompat.setOnApplyWindowInsetsListener(this, (view, insets) -> {
            applySafeInsets(insets); return insets;
        });
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow(); ViewCompat.requestApplyInsets(this);
    }
    void applySafeInsets(WindowInsetsCompat insets) {
        Insets visibleBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
        Insets navigation = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars());
        Insets cutout = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.displayCutout());
        Insets next = Insets.max(visibleBars, Insets.max(navigation, cutout));
        if (!next.equals(safeInsets)) {
            safeInsets = next; geometryDirty = true; requestLayout();
        }
    }
    /** Bounds for controls, in this root's coordinates; playback itself remains edge to edge. */
    public Rect getSafeContentBounds() { return safeBounds(getWidth(), getHeight()); }
    private Rect safeBounds(int width, int height) {
        int left = Math.min(safeInsets.left, Math.max(0, width - 1));
        int top = Math.min(safeInsets.top, Math.max(0, height - 1));
        return new Rect(left, top, Math.max(left + 1, width - safeInsets.right),
                Math.max(top + 1, height - safeInsets.bottom));
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        if (width > 0 && height > 0 && (geometryDirty || width != lastWidth || height != lastHeight)) {
            lastWidth = width; lastHeight = height;
            geometryDirty = false;
            applyGeometry(width, height);
        }
        super.onMeasure(widthSpec, heightSpec);
        if (fitTimePill()) super.onMeasure(widthSpec, heightSpec);
    }
    private void applyGeometry(int width, int height) {
        Rect safe = safeBounds(width, height);
        // Reference proportions size the controls; runtime safe bounds place and constrain them.
        float scale = Math.min(Math.min(width, height) / 1440f,
                Math.min(safe.width() / 1150f, safe.height() / 581f));
        referenceScale = scale;
        boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        int safeStart = rtl ? width - safe.right : safe.left;
        int safeEnd = rtl ? safe.left : width - safe.right;
        View bottom = findViewById(R.id.gallery_video_bottom_dock);
        if (bottom != null) relativeMargins(bottom, safeStart, 0, safeEnd, height - safe.bottom);
        View row = findViewById(R.id.gallery_video_preview_row);
        size(row, -1, px(113, scale));
        if (row != null) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) row.getLayoutParams();
            margins.leftMargin = margins.rightMargin = px(60, scale);
            margins.bottomMargin = px(59, scale);
        }
        View pill = findViewById(androidx.media3.ui.R.id.exo_center_controls);
        size(pill, px(492, scale), px(113, scale));
        if (pill != null) pill.setPadding(px(30, scale), 0, px(64, scale), 0);
        View play = findViewById(androidx.media3.ui.R.id.exo_play_pause);
        size(play, px(83, scale), px(83, scale)); if (play != null) play.setPadding(0, 0, 0, 0);
        ViewGroup time = findViewById(androidx.media3.ui.R.id.exo_time);
        if (time != null) {
            time.setPadding(px(26, scale), 0, 0, 0);
            for (int i = 0; i < time.getChildCount(); i++) if (time.getChildAt(i) instanceof TextView)
                ((TextView) time.getChildAt(i)).setTextSize(TypedValue.COMPLEX_UNIT_PX, 46 * scale);
        }
        View mute = findViewById(R.id.gallery_video_mute);
        size(mute, px(113, scale), px(113, scale));
        if (mute != null) mute.setPadding(px(23, scale), px(23, scale), px(23, scale), px(23, scale));
        size(findViewById(R.id.gallery_media_strip_frame), -1, px(184, scale));
        size(findViewById(R.id.gallery_frame_cursor), px(8, scale), px(180, scale));
        GalleryMediaStrip media = findViewById(R.id.gallery_media_strip);
        if (media != null) media.setReferenceShortEdge(Math.max(1, Math.round(scale * 1440f)));

        View back = findViewById(R.id.back_button);
        size(back, px(180, scale), px(180, scale));
        int backStart = safeStart + px(45, scale);
        if (back != null) {
            back.setPadding(px(52, scale), px(52, scale), px(52, scale), px(52, scale));
            relativeMargins(back, backStart, safe.top + px(15, scale), 0, 0);
        }
        ViewGroup actions = findViewById(R.id.gallery_video_top_actions);
        if (actions != null) {
            int end = safeEnd + px(45, scale);
            int available = Math.max(5, safe.width() - px(330, scale));
            int itemWidth = Math.min(px(155, scale), available / Math.max(1, actions.getChildCount()));
            size(actions, -2, px(180, scale)); actions.setPadding(0, 0, 0, 0);
            relativeMargins(actions, 0, safe.top + px(15, scale), end, 0);
            for (int i = 0; i < actions.getChildCount(); i++) {
                View action = actions.getChildAt(i);
                size(action, itemWidth, px(180, scale));
                int horizontalPadding = Math.min(px(40, scale), itemWidth / 4);
                action.setPadding(horizontalPadding, px(50, scale), horizontalPadding, px(50, scale));
            }
        }
    }
    /** Fit the actual time strings, including hours, without wrapping or covering the mute button. */
    private boolean fitTimePill() {
        View row = findViewById(R.id.gallery_video_preview_row);
        View pill = findViewById(androidx.media3.ui.R.id.exo_center_controls);
        View play = findViewById(androidx.media3.ui.R.id.exo_play_pause);
        View mute = findViewById(R.id.gallery_video_mute);
        ViewGroup time = findViewById(androidx.media3.ui.R.id.exo_time);
        if (row == null || pill == null || play == null || mute == null || time == null
                || row.getMeasuredWidth() == 0) return false;
        float baseSize = 46 * referenceScale * getResources().getConfiguration().fontScale;
        float textWidth = 0;
        int textHeight = 1;
        int textPadding = 0;
        for (int i = 0; i < time.getChildCount(); i++) {
            if (!(time.getChildAt(i) instanceof TextView)) continue;
            TextView label = (TextView) time.getChildAt(i);
            TextPaint paint = new TextPaint(label.getPaint()); paint.setTextSize(baseSize);
            textWidth += (float) Math.ceil(Layout.getDesiredWidth(label.getText(), paint));
            android.graphics.Paint.FontMetricsInt metrics = paint.getFontMetricsInt();
            textHeight = Math.max(textHeight, metrics.bottom - metrics.top
                    + label.getCompoundPaddingTop() + label.getCompoundPaddingBottom());
            textPadding += label.getCompoundPaddingLeft() + label.getCompoundPaddingRight();
        }
        int fixed = pill.getPaddingLeft() + pill.getPaddingRight() + play.getMeasuredWidth()
                + time.getPaddingLeft() + time.getPaddingRight() + textPadding;
        // Keep the pill centered with the same free space on both sides; mute occupies the end.
        int available = Math.max(1, row.getMeasuredWidth()
                - 2 * (mute.getMeasuredWidth() + px(16, referenceScale)));
        int desired = Math.max(px(492, referenceScale), fixed + (int) Math.ceil(textWidth) + 3);
        int pillWidth = Math.min(available, desired);
        float fit = textWidth == 0 ? 1 : Math.min(1f, Math.max(1, pillWidth - fixed - 3) / textWidth);
        fit = Math.min(fit, Math.max(1, pill.getMeasuredHeight() - pill.getPaddingTop()
                - pill.getPaddingBottom() - time.getPaddingTop() - time.getPaddingBottom() - 2)
                / (float) textHeight);
        boolean changed = pill.getLayoutParams().width != pillWidth;
        if (changed) {
            ViewGroup.LayoutParams params = pill.getLayoutParams(); params.width = pillWidth;
            pill.setLayoutParams(params);
        }
        for (int i = 0; i < time.getChildCount(); i++) {
            if (!(time.getChildAt(i) instanceof TextView)) continue;
            TextView label = (TextView) time.getChildAt(i);
            float size = baseSize * fit;
            if (Math.abs(label.getTextSize() - size) > .01f) {
                label.setTextSize(TypedValue.COMPLEX_UNIT_PX, size); changed = true;
            }
        }
        return changed;
    }
    private static void relativeMargins(View view, int start, int top, int end, int bottom) {
        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        margins.setMargins(start, top, end, bottom);
        margins.setMarginStart(start); margins.setMarginEnd(end);
        // Inflation may already have resolved XML margins; apply the new relative values before measurement.
        margins.resolveLayoutDirection(view.getLayoutDirection());
        view.setLayoutParams(margins);
    }
    private static int px(float reference, float scale) { return Math.max(1, Math.round(reference * scale)); }
    private static void size(View view, int width, int height) {
        if (view == null) return;
        ViewGroup.LayoutParams params = view.getLayoutParams(); params.width = width; params.height = height;
    }
}

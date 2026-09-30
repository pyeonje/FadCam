package com.fadcam.ui;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.fadcam.R;

/** Focused settings entry points for the personal recorder. */
public class PersonalSettingsFragment extends Fragment {
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        ScrollView scroll = new ScrollView(requireContext());
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFF111315);
        LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(24), dp(24), dp(88));
        scroll.addView(content);

        TextView title = text(R.string.personal_settings_title, 28, 0xFFF3F6F4);
        title.setTypeface(null, Typeface.BOLD);
        content.addView(title);
        TextView subtitle = text(R.string.personal_settings_subtitle, 14, 0xFF9CA7A2);
        subtitle.setPadding(0, dp(8), 0, dp(28));
        content.addView(subtitle);

        addRow(content, R.drawable.ic_camera, R.string.personal_settings_video,
                R.string.personal_settings_video_hint, VideoSettingsFragment::new);
        addRow(content, R.drawable.ic_mic_material, R.string.personal_settings_audio,
                R.string.personal_settings_audio_hint, AudioSettingsFragment::new);
        addRow(content, R.drawable.ic_folder_open, R.string.personal_settings_storage,
                R.string.personal_settings_storage_hint, StorageSettingsFragment::new);
        addRow(content, R.drawable.ic_house, R.string.personal_settings_shortcut,
                R.string.personal_settings_shortcut_hint, ShortcutsSettingsFragment::new);
        addRow(content, R.drawable.ic_notifications, R.string.personal_settings_notification,
                R.string.personal_settings_notification_hint, NotificationSettingsFragment::new);
        addRow(content, R.drawable.ic_camera, R.string.personal_settings_watermark,
                R.string.personal_settings_watermark_hint, WatermarkSettingsFragment::new);
        return scroll;
    }

    private TextView text(int stringRes, int size, int color) {
        TextView view = new TextView(requireContext());
        view.setText(stringRes);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private void addRow(LinearLayout parent, int iconRes, int titleRes, int hintRes,
                        java.util.function.Supplier<Fragment> destination) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(20), 0, dp(20));
        android.util.TypedValue ripple = new android.util.TypedValue();
        requireContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setBackgroundResource(ripple.resourceId);
        row.setFocusable(true);
        row.setOnClickListener(v -> OverlayNavUtil.show(requireActivity(), destination.get(), "personal_setting"));

        ImageView icon = new ImageView(requireContext());
        icon.setImageResource(iconRes);
        icon.setColorFilter(0xFF32B978);
        row.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        LinearLayout labels = new LinearLayout(requireContext());
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(18), 0, dp(12), 0);
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView heading = text(titleRes, 17, 0xFFF3F6F4);
        heading.setTypeface(null, Typeface.BOLD);
        labels.addView(heading);
        TextView hint = text(hintRes, 13, 0xFF9CA7A2);
        hint.setPadding(0, dp(5), 0, 0);
        labels.addView(hint);
        TextView arrow = new TextView(requireContext());
        arrow.setText("›");
        arrow.setTextSize(24);
        arrow.setTextColor(0xFF9CA7A2);
        row.addView(arrow);
        parent.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        View divider = new View(requireContext());
        divider.setBackgroundColor(0xFF252B29);
        parent.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
    }
}

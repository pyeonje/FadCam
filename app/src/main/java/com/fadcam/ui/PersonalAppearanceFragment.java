package com.fadcam.ui;

import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.core.content.pm.ShortcutManagerCompat;
import com.fadcam.FLog;
import com.fadcam.R;
import com.fadcam.shortcuts.ShortcutsManager;
import com.fadcam.shortcuts.ShortcutsPreferences;
import com.google.android.material.button.MaterialButton;
import java.util.HashMap;
import java.util.Map;

/** Edits the freely named and pictured home-screen entries for the personal app. */
public class PersonalAppearanceFragment extends Fragment {
    private final Map<String, ImageView> previews = new HashMap<>();
    private final Map<String, EditText> names = new HashMap<>();
    private String pendingImageId;
    private ShortcutsPreferences preferences;
    private ShortcutsManager shortcuts;
    private final ActivityResultLauncher<String> imagePicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> {
                String id = pendingImageId;
                pendingImageId = null;
                if (uri == null || id == null || !isAdded()) return;
                if (shortcuts.setCustomIconFromUri(id, uri)) {
                    showPreview(id);
                } else {
                    Toast.makeText(requireContext(), R.string.personal_appearance_image_failed, Toast.LENGTH_LONG).show();
                }
            });

    @Override public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = new ShortcutsPreferences(requireContext());
        shortcuts = new ShortcutsManager(requireContext());
        if (savedInstanceState != null) pendingImageId = savedInstanceState.getString("appearance_image_id");
    }

    @Override public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("appearance_image_id", pendingImageId);
        for (Map.Entry<String, EditText> entry : names.entrySet()) {
            outState.putString("appearance_name_" + entry.getKey(), entry.getValue().getText().toString());
        }
    }

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        ScrollView scroll = new ScrollView(requireContext());
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFF111315);
        LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(20), dp(24), dp(40));
        scroll.addView(content);
        MaterialButton back = button(R.string.personal_appearance_back);
        back.setOnClickListener(v -> OverlayNavUtil.dismiss(requireActivity()));
        content.addView(back);
        TextView title = text(getString(R.string.personal_appearance_title), 26, 0xFFF3F6F4);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, dp(14), 0, dp(8));
        content.addView(title);
        TextView helper = text(getString(R.string.personal_appearance_hint), 14, 0xFF9CA7A2);
        helper.setLineSpacing(dp(4), 1);
        content.addView(helper);
        addCard(content, ShortcutsManager.ID_PERSONAL_MAIN, R.string.personal_appearance_main,
                R.string.personal_appearance_main_hint, savedInstanceState);
        addCard(content, ShortcutsManager.ID_TOGGLE, R.string.personal_appearance_quick,
                R.string.personal_appearance_quick_hint, savedInstanceState);
        TextView limit = text(getString(R.string.personal_appearance_drawer_limit), 13, 0xFF9CA7A2);
        limit.setPadding(0, dp(20), 0, dp(10));
        limit.setLineSpacing(dp(4), 1);
        content.addView(limit);
        MaterialButton advanced = button(R.string.personal_appearance_other_shortcuts);
        advanced.setOnClickListener(v -> OverlayNavUtil.show(requireActivity(),
                new ShortcutsSettingsFragment(), "ShortcutsSettingsFragment"));
        content.addView(advanced);
        return scroll;
    }

    private void addCard(LinearLayout parent, String id, int titleRes, int hintRes,
            @Nullable Bundle savedInstanceState) {
        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(20), dp(18), dp(18));
        card.setBackgroundResource(R.drawable.settings_group_card_bg);
        LinearLayout.LayoutParams cardLayout = new LinearLayout.LayoutParams(-1, -2);
        cardLayout.topMargin = dp(22);
        parent.addView(card, cardLayout);
        TextView heading = text(getString(titleRes), 18, 0xFFF3F6F4);
        heading.setTypeface(null, Typeface.BOLD);
        card.addView(heading);
        TextView description = text(getString(hintRes), 13, 0xFF9CA7A2);
        description.setPadding(0, dp(6), 0, dp(18));
        card.addView(description);
        LinearLayout previewRow = new LinearLayout(requireContext());
        previewRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        card.addView(previewRow);
        ImageView preview = new ImageView(requireContext());
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setContentDescription(getString(R.string.personal_appearance_preview));
        previewRow.addView(preview, new LinearLayout.LayoutParams(dp(64), dp(64)));
        previews.put(id, preview);
        TextView previewLabel = text(defaultName(id), 17, 0xFFF3F6F4);
        previewLabel.setPadding(dp(18), 0, 0, 0);
        previewRow.addView(previewLabel, new LinearLayout.LayoutParams(0, -2, 1));
        EditText name = new EditText(requireContext());
        name.setSingleLine(true);
        name.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setTextColor(0xFFF3F6F4);
        name.setHintTextColor(0xFF9CA7A2);
        name.setHint(R.string.personal_appearance_name);
        String saved = preferences.getCustomLabel(id);
        name.setText(savedInstanceState != null && savedInstanceState.containsKey("appearance_name_" + id)
                ? savedInstanceState.getString("appearance_name_" + id) : saved != null ? saved : defaultName(id));
        previewLabel.setText(name.getText());
        name.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                previewLabel.setText(s.length() == 0 ? defaultName(id) : s);
            }
            @Override public void afterTextChanged(Editable editable) {}
        });
        LinearLayout.LayoutParams nameLayout = new LinearLayout.LayoutParams(-1, dp(56));
        nameLayout.topMargin = dp(12);
        card.addView(name, nameLayout);
        names.put(id, name);
        MaterialButton image = button(R.string.personal_appearance_choose_image);
        image.setOnClickListener(v -> { pendingImageId = id; imagePicker.launch("image/*"); });
        card.addView(image);
        LinearLayout defaults = new LinearLayout(requireContext());
        defaults.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(defaults);
        MaterialButton defaultIcon = button(R.string.personal_appearance_default_icon);
        defaultIcon.setOnClickListener(v -> {
            preferences.clearCustomIcon(id);
            preferences.clearCustomIconRes(id);
            showPreview(id);
        });
        defaults.addView(defaultIcon, new LinearLayout.LayoutParams(0, dp(48), 1));
        MaterialButton reset = button(R.string.personal_appearance_reset);
        reset.setOnClickListener(v -> {
            shortcuts.reset(id);
            name.setText(defaultName(id));
            showPreview(id);
        });
        defaults.addView(reset, new LinearLayout.LayoutParams(0, dp(48), 1));
        MaterialButton apply = button(R.string.personal_appearance_apply);
        apply.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF32B978));
        apply.setTextColor(0xFF07170F);
        apply.setOnClickListener(v -> apply(id, name.getText().toString()));
        LinearLayout.LayoutParams applyLayout = new LinearLayout.LayoutParams(-1, dp(56));
        applyLayout.topMargin = dp(12);
        card.addView(apply, applyLayout);
        showPreview(id);
    }

    private void showPreview(String id) {
        ImageView preview = previews.get(id);
        if (preview == null) return;
        int resource = preferences.getCustomIconRes(id);
        String path = preferences.getCustomIconPath(id);
        if (resource != 0) preview.setImageResource(resource);
        else if (path != null) {
            android.graphics.Bitmap bitmap = BitmapFactory.decodeFile(path);
            if (bitmap != null) preview.setImageBitmap(bitmap);
            else preview.setImageResource(defaultIcon(id));
        } else preview.setImageResource(defaultIcon(id));
    }

    private void apply(String id, String name) {
        shortcuts.setCustomLabel(id, name);
        Intent intent = new Intent(Intent.ACTION_MAIN).setClassName(requireContext(),
                ShortcutsManager.ID_PERSONAL_MAIN.equals(id)
                        ? "com.fadcam.SplashActivity" : "com.fadcam.PersonalRecordingStartActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (shortcuts.updateExistingPinned(id, intent, defaultIcon(id), defaultName(id))) {
                Toast.makeText(requireContext(), R.string.personal_appearance_updated, Toast.LENGTH_LONG).show();
            } else if (shortcuts.isPinSupported()) {
                boolean accepted = ShortcutManagerCompat.requestPinShortcut(requireContext(),
                        shortcuts.buildShortcutForPin(id, intent, defaultIcon(id), defaultName(id)), null);
                Toast.makeText(requireContext(), accepted ? R.string.personal_appearance_pin_requested
                        : R.string.personal_appearance_apply_failed, Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(requireContext(), R.string.personal_appearance_pin_unsupported, Toast.LENGTH_LONG).show();
            }
        } catch (RuntimeException e) {
            FLog.w("PersonalAppearance", "Home icon update failed", e);
            Toast.makeText(requireContext(), R.string.personal_appearance_apply_failed, Toast.LENGTH_LONG).show();
        }
    }

    private String defaultName(String id) {
        return getString(ShortcutsManager.ID_PERSONAL_MAIN.equals(id)
                ? R.string.app_name : R.string.personal_recording_launcher_label);
    }

    private int defaultIcon(String id) {
        return ShortcutsManager.ID_PERSONAL_MAIN.equals(id)
                ? R.drawable.personal_calendar_main : R.drawable.personal_calendar_quick;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(requireContext());
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        return view;
    }

    private MaterialButton button(int label) {
        MaterialButton button = new MaterialButton(requireContext());
        button.setText(label); button.setAllCaps(false); button.setTextColor(0xFF65D7A0);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF17211D));
        button.setCornerRadius(dp(12));
        button.setMinHeight(dp(48));
        return button;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override public void onDestroyView() {
        previews.clear(); names.clear();
        super.onDestroyView();
    }
}

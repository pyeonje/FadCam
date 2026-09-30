package com.fadcam.shortcuts;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import androidx.core.content.pm.ShortcutInfoCompat;
import com.fadcam.PersonalRecordingStartActivity;
import com.fadcam.R;
import com.fadcam.SplashActivity;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class PersonalAppearanceShortcutsTest {
    private Context context;
    private ShortcutManager system;
    private ShortcutsManager manager;
    private ShortcutsPreferences preferences;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("shortcuts_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        system = context.getSystemService(ShortcutManager.class);
        manager = new ShortcutsManager(context);
        preferences = new ShortcutsPreferences(context);
    }

    @Test public void newMainAndQuickPinsUseSavedNamesAndCorrectLaunchComponents() {
        preferences.setCustomLabel(ShortcutsManager.ID_PERSONAL_MAIN, "내 달력");
        preferences.setCustomLabel(ShortcutsManager.ID_TOGGLE, "오늘 일정");
        ShortcutInfoCompat main = manager.buildShortcutForPin(ShortcutsManager.ID_PERSONAL_MAIN,
                mainIntent(), R.drawable.personal_calendar_main, "Main");
        ShortcutInfoCompat quick = manager.buildShortcutForPin(ShortcutsManager.ID_TOGGLE,
                quickIntent(), R.drawable.personal_calendar_quick, "Quick");
        assertEquals("내 달력", main.getShortLabel().toString());
        assertEquals(SplashActivity.class.getName(), main.getIntent().getComponent().getClassName());
        assertEquals(context.getPackageName(), main.getIntent().getComponent().getPackageName());
        assertEquals(SplashActivity.class.getName(), main.getActivity().getClassName());
        assertEquals("오늘 일정", quick.getShortLabel().toString());
        assertEquals(PersonalRecordingStartActivity.class.getName(), quick.getIntent().getComponent().getClassName());
        assertEquals(PersonalRecordingStartActivity.class.getName(), quick.getActivity().getClassName());
        assertTrue((quick.getIntent().getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertNotNull(main.getIcon());
        assertNotNull(quick.getIcon());
        assertTrue(system.getPinnedShortcuts().isEmpty()); // Building a preview never pins or launches anything.
    }

    @Test public void updatingLegacyPinnedIdsChangesExistingEntriesWithoutDuplicates() {
        String base = ShortcutsManager.ID_TOGGLE;
        String[] legacyIds = {base, base + "_pin", base + "_pin_custom", base + "_custom"};
        for (String id : legacyIds) {
            system.requestPinShortcut(new ShortcutInfo.Builder(context, id).setShortLabel("Old name")
                    .setIntent(mainIntent()).setActivity(mainIntent().getComponent()).build(), null);
        }
        system.requestPinShortcut(new ShortcutInfo.Builder(context, "unrelated")
                .setShortLabel("Keep me").setIntent(mainIntent()).build(), null);
        preferences.setCustomLabel(base, "바꾼 이름");
        assertTrue(manager.updateExistingPinned(base, quickIntent(), R.drawable.personal_calendar_quick, "Quick"));
        assertEquals(5, system.getPinnedShortcuts().size());
        for (ShortcutInfo pinned : system.getPinnedShortcuts()) {
            if (pinned.getId().equals("unrelated")) {
                assertEquals("Keep me", pinned.getShortLabel().toString());
            } else {
                assertEquals("바꾼 이름", pinned.getShortLabel().toString());
                assertEquals(PersonalRecordingStartActivity.class.getName(), pinned.getIntent().getComponent().getClassName());
            }
        }
    }

    @Test public void applyingWithoutExistingPinRequiresExplicitAddAndResetUsesDefaults() {
        assertFalse(manager.updateExistingPinned(ShortcutsManager.ID_PERSONAL_MAIN,
                mainIntent(), R.drawable.personal_calendar_main, "Default name"));
        assertTrue(system.getPinnedShortcuts().isEmpty());
        preferences.setCustomLabel(ShortcutsManager.ID_PERSONAL_MAIN, "Custom");
        preferences.setCustomIconRes(ShortcutsManager.ID_PERSONAL_MAIN, R.drawable.personal_calendar_quick);
        manager.reset(ShortcutsManager.ID_PERSONAL_MAIN);
        assertNull(preferences.getCustomLabel(ShortcutsManager.ID_PERSONAL_MAIN));
        assertEquals(0, preferences.getCustomIconRes(ShortcutsManager.ID_PERSONAL_MAIN));
        ShortcutInfoCompat reset = manager.buildShortcutForPin(ShortcutsManager.ID_PERSONAL_MAIN,
                mainIntent(), R.drawable.personal_calendar_main, "Default name");
        assertEquals("Default name", reset.getShortLabel().toString());
    }

    private Intent mainIntent() {
        return new Intent(Intent.ACTION_MAIN).setClass(context, SplashActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private Intent quickIntent() {
        return new Intent(Intent.ACTION_MAIN).setClass(context, PersonalRecordingStartActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}

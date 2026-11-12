package com.fadcam.ui;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.os.Looper;
import android.widget.FrameLayout;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.RecyclerView;
import com.fadcam.R;
import com.fadcam.model.TrashItem;
import com.fadcam.utils.TrashManager;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
public class TrashUiSafetyTest {
    private static TrashItem item(String name, long id) {
        return new TrashItem("content://fixture/original/" + id, "fixture" + id + ".mp4", name, id, true, 3);
    }

    private static FragmentActivity host() {
        FragmentActivity activity = Robolectric.buildActivity(FragmentActivity.class).setup().get();
        activity.setTheme(R.style.Base_Theme_FadCam);
        return activity;
    }

    @Test public void unsafeTrashNamesBindAndRebindWithoutOpeningAnything() throws Exception {
        FragmentActivity activity = host();
        File root = TrashManager.getTrashDirectory(activity);
        assertNotNull(root);
        File valid = new File(root, "good.mp4");
        byte[] bytes = {3, 5, 7};
        Files.write(valid.toPath(), bytes);
        File sibling = new File(root.getParentFile(), "outside.mp4");
        Files.write(sibling.toPath(), bytes);
        List<TrashItem> entries = Arrays.asList(item("good.mp4",1),item("missing.mp4",2),
                item("../outside.mp4",3),item(sibling.getAbsolutePath(),4),item(null,5));
        TrashAdapter.OnTrashItemInteractionListener listener = mock(TrashAdapter.OnTrashItemInteractionListener.class);
        TrashAdapter adapter = new TrashAdapter(activity, entries, listener, null);
        FrameLayout parent = new FrameLayout(activity);
        RecyclerView.ViewHolder holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(1));
        for (int i=1;i<=entries.size();i++) {
            adapter.onBindViewHolder(holder,i);
            holder.itemView.performClick();
        }
        verify(listener).onPlayVideoRequested(entries.get(0));
        verifyNoMoreInteractions(listener);
        assertArrayEquals(bytes,Files.readAllBytes(valid.toPath()));
        assertArrayEquals(bytes,Files.readAllBytes(sibling.toPath()));
    }

    @Test public void trashFragmentRejectsUnsafePathWithoutLaunchingPlayer() throws Exception {
        FragmentActivity activity = host();
        TrashFragment fragment = spy(new TrashFragment());
        doReturn(activity).when(fragment).getContext();
        doReturn(activity.getResources()).when(fragment).getResources();
        File root = TrashManager.getTrashDirectory(activity);
        assertNotNull(root);
        File outside = new File(root.getParentFile(), "private-fixture.mp4");
        byte[] bytes = {2, 4, 6};
        Files.write(outside.toPath(),bytes);
        fragment.onPlayVideoRequested(item("../private-fixture.mp4",1));
        assertEquals(activity.getString(R.string.trash_file_not_found),ShadowToast.getTextOfLatestToast());
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        assertArrayEquals(bytes,Files.readAllBytes(outside.toPath()));
    }

    @Test public void watchFailedSingleDeleteKeepsItemAndShowsFailure() {
        try(MockedStatic<TrashManager> manager=mockStatic(TrashManager.class)) {
            Fixture f = new Fixture();
            TrashItem retained=item("keep.mp4",1);
            f.items.add(retained);
            manager.when(() -> TrashManager.permanentlyDeleteItems(any(Context.class),eq(Collections.singletonList(retained))))
                    .thenReturn(false);
            manager.when(() -> TrashManager.loadTrashMetadata(any(Context.class))).thenReturn(Collections.singletonList(retained));
            f.confirm(0);f.work.remove().run();f.idle();
            assertEquals(Collections.singletonList(retained),f.items);
            assertEquals(f.host.getString(R.string.trash_error_deleting_items_toast),ShadowToast.getTextOfLatestToast());
        }
    }

    @Test public void watchLateSingleCompletionCannotRemoveAReorderedRow() {
        try(MockedStatic<TrashManager> manager=mockStatic(TrashManager.class)) {
            Fixture f=new Fixture();
            TrashItem removed=item("gone.mp4",1), retained=item("keep.mp4",2);
            f.items.addAll(Arrays.asList(removed,retained));
            manager.when(() -> TrashManager.permanentlyDeleteItems(any(Context.class),eq(Collections.singletonList(removed))))
                    .thenReturn(true);
            f.confirm(0);f.work.remove().run();
            Collections.swap(f.items,0,1);
            f.idle();
            assertEquals(Collections.singletonList(retained),f.items);
            assertEquals(f.host.getString(R.string.trash_items_deleted_toast,1),ShadowToast.getTextOfLatestToast());
        }
    }

    @Test public void watchPartialEmptyReloadsPersistedItemsAndShowsFailure() {
        try(MockedStatic<TrashManager> manager=mockStatic(TrashManager.class)) {
            Fixture f=new Fixture();
            TrashItem removed=item("gone.mp4",1), retained=item("keep.mp4",2);
            f.items.addAll(Arrays.asList(removed,retained));
            manager.when(() -> TrashManager.emptyAllTrash(any(Context.class))).thenReturn(false);
            manager.when(() -> TrashManager.loadTrashMetadata(any(Context.class))).thenReturn(Collections.singletonList(retained));
            f.confirmAll();f.work.remove().run();f.idle();
            assertEquals(Collections.singletonList(retained),f.items);
            assertEquals(f.host.getString(R.string.trash_error_deleting_items_toast),ShadowToast.getTextOfLatestToast());
        }
    }

    @Test public void watchSuccessfulEmptyReportsActualRemoval() {
        try(MockedStatic<TrashManager> manager=mockStatic(TrashManager.class)) {
            Fixture f=new Fixture();f.items.add(item("gone.mp4",1));
            manager.when(() -> TrashManager.emptyAllTrash(any(Context.class))).thenReturn(true);
            manager.when(() -> TrashManager.loadTrashMetadata(any(Context.class))).thenReturn(Collections.emptyList());
            f.confirmAll();f.work.remove().run();f.idle();
            assertTrue(f.items.isEmpty());
            assertEquals(f.host.getString(R.string.trash_items_deleted_toast,1),ShadowToast.getTextOfLatestToast());
        }
    }

    @Test public void watchDestroyedViewDoesNotReceiveCompletionAndRejectsDuplicateRequest() {
        try(MockedStatic<TrashManager> manager=mockStatic(TrashManager.class)) {
            Fixture f=new Fixture();TrashItem removed=item("gone.mp4",1);f.items.add(removed);
            manager.when(() -> TrashManager.permanentlyDeleteItems(any(Context.class),eq(Collections.singletonList(removed))))
                    .thenReturn(true);
            f.confirm(0);f.confirm(0);
            assertEquals(1,f.work.size());
            String prior=ShadowToast.getTextOfLatestToast();
            f.work.remove().run();
            f.fragment.onDestroyView();
            f.idle();
            assertEquals(Collections.singletonList(removed),f.items);
            assertEquals(prior,ShadowToast.getTextOfLatestToast());
        }
    }

    @Test public void watchCompletionAfterViewExitDoesNotMutateRowsOrNotify() {
        try(MockedStatic<TrashManager> manager=mockStatic(TrashManager.class)) {
            Fixture f=new Fixture();TrashItem removed=item("gone.mp4",1);f.items.add(removed);
            manager.when(() -> TrashManager.permanentlyDeleteItems(any(Context.class),eq(Collections.singletonList(removed))))
                    .thenReturn(true);
            f.confirm(0);f.work.remove().run();
            String prior=ShadowToast.getTextOfLatestToast();
            f.fragment.onDestroyView();
            f.idle();
            assertEquals(Collections.singletonList(removed),f.items);
            assertEquals(prior,ShadowToast.getTextOfLatestToast());
        }
    }

    private static final class Fixture {
        final FragmentActivity host=host();
        final WatchTrashFragment fragment=new WatchTrashFragment();
        final ArrayDeque<Runnable> work=new ArrayDeque<>();
        final List<TrashItem> items;
        Fixture() {
            ExecutorService executor=mock(ExecutorService.class);
            doAnswer(call -> { work.add(call.getArgument(0)); return null; }).when(executor).execute(any(Runnable.class));
            ReflectionHelpers.setField(fragment,"executor",executor);
            host.getSupportFragmentManager().beginTransaction().add(android.R.id.content,fragment).commitNow();
            RecyclerView list=ReflectionHelpers.getField(fragment,"recyclerView");
            list.setAdapter(null); // Keep thumbnail jobs out of the controlled mutation queue.
            idle();
            work.clear();
            items=ReflectionHelpers.getField(fragment,"items");
        }
        void confirm(int position) {
            AlertDialog previous=ShadowAlertDialog.getLatestAlertDialog();
            ReflectionHelpers.callInstanceMethod(fragment,"confirmDeleteItem",
                    ReflectionHelpers.ClassParameter.from(int.class,position));
            AlertDialog current=ShadowAlertDialog.getLatestAlertDialog();
            if(current!=previous)current.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            idle();
        }
        void confirmAll() {
            ReflectionHelpers.callInstanceMethod(fragment,"confirmDeleteAll");
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            idle();
        }
        void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    }
}

package com.fadcam.ui;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.graphics.Outline;
import android.view.ViewOutlineProvider;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;


import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.model.TrashItem;
import com.fadcam.utils.TrashManager;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * WatchTrashFragment — Watch-optimized "Recently Deleted" screen.
 * Shows trash items with thumbnail, name, and date.
 * Each row has RESTORE and DELETE icons.
 * Bottom button deletes everything.
 */
public class WatchTrashFragment extends Fragment {

    private static final String TAG = "WatchTrashFragment";

    private RecyclerView recyclerView;
    private TextView tvEmpty;
    private TextView btnDeleteAll;

    private final List<TrashItem> items = new ArrayList<>();
    private WatchTrashAdapter adapter;

    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private long viewGeneration;
    private long loadGeneration;
    private boolean actionInFlight;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_watch_trash, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ++viewGeneration;
        if (executor.isShutdown()) executor = Executors.newSingleThreadExecutor();

        recyclerView  = view.findViewById(R.id.rv_watch_trash);
        tvEmpty       = view.findViewById(R.id.tv_watch_trash_empty);
        btnDeleteAll  = view.findViewById(R.id.btn_watch_trash_delete_all);

        final View btnBack = view.findViewById(R.id.btn_watch_trash_back);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> OverlayNavUtil.popLevel(requireActivity()));
        }

        adapter = new WatchTrashAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);

        btnDeleteAll.setOnClickListener(v -> confirmDeleteAll());

        // Always reload items when view is created to ensure fresh data
        loadItems();
        FLog.d(TAG, "WatchTrashFragment onViewCreated - trash overlay shown");
    }

    @Override
    public void onResume() {
        super.onResume();
        // Reload items when returning to this fragment
        loadItems();
        FLog.d(TAG, "WatchTrashFragment onResume - refreshing trash items");
    }

    @Override
    public void onDestroyView() {
        ++viewGeneration;
        // Confirmed actions finish against the application context, never the old view.
        executor.shutdown();
        recyclerView = null;
        tvEmpty = null;
        btnDeleteAll = null;
        adapter = null;
        super.onDestroyView();
    }

    private boolean ownsView(long generation) {
        return viewGeneration == generation && isAdded() && recyclerView != null && adapter != null;
    }

    private void loadItems() {
        if (!ownsView(viewGeneration) || actionInFlight || executor.isShutdown()) return;
        final Context context = requireContext().getApplicationContext();
        final long view = viewGeneration;
        final long request = ++loadGeneration;
        executor.execute(() -> {
            List<TrashItem> loaded = readRemainingItems(context, Collections.emptyList());
            mainHandler.post(() -> {
                if (!ownsView(view) || request != loadGeneration || actionInFlight) return;
                replaceItems(loaded);
            });
        });
    }

    /** A bulk operation can remove some files before its metadata write fails. */
    private List<TrashItem> readRemainingItems(Context context, List<TrashItem> fallback) {
        List<TrashItem> loaded = TrashManager.loadTrashMetadata(context);
        // A failed mutation plus an unreadable metadata file may look like an empty list.
        List<TrashItem> remaining = new ArrayList<>(loaded == null || loaded.isEmpty() ? fallback : loaded);
        remaining.removeIf(java.util.Objects::isNull);
        try {
            File directory = TrashManager.getTrashDirectory(context);
            if (directory != null) {
                File root = directory.getCanonicalFile();
                remaining.removeIf(item -> definitelyMissing(root,
                        TrashManager.resolveTrashFile(root, item.getTrashFileName())));
            }
        } catch (java.io.IOException | SecurityException error) {
            FLog.w(TAG, "Could not verify missing Watch trash rows", error);
        }
        return remaining;
    }

    private static boolean definitelyMissing(File root, @Nullable File file) {
        // Keep uncertain/inaccessible rows. Only a successfully listed parent can prove absence.
        for (File child = file; child != null && !child.equals(root); child = child.getParentFile()) {
            File parent = child.getParentFile();
            if (parent == null) break;
            String[] children = parent.list();
            if (children != null) {
                for (String name : children) if (name.equals(child.getName())) return false;
                return true;
            }
            if (parent.exists()) break;
        }
        return false;
    }

    private void replaceItems(List<TrashItem> loaded) {
        items.clear();
        items.addAll(loaded);
        adapter.notifyDataSetChanged();
        updateEmptyState();
    }

    private void updateEmptyState() {
        final boolean empty = items.isEmpty();
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        btnDeleteAll.setVisibility(empty ? View.GONE : View.VISIBLE);
        btnDeleteAll.setEnabled(!actionInFlight);
    }

    private boolean beginAction() {
        if (actionInFlight || !ownsView(viewGeneration) || executor.isShutdown()) return false;
        actionInFlight = true;
        ++loadGeneration;
        updateEmptyState();
        return true;
    }

    private boolean finishAction(long view) {
        actionInFlight = false;
        if (!ownsView(view)) {
            // A replacement view may need a fresh load, but never an old action result/toast.
            loadItems();
            return false;
        }
        updateEmptyState();
        return true;
    }

    private void removeItem(TrashItem item) {
        int current = items.indexOf(item);
        if (current >= 0) {
            items.remove(current);
            adapter.notifyItemRemoved(current);
            updateEmptyState();
        }
    }

    private void restoreItem(int position) {
        if (position < 0 || position >= items.size() || !beginAction()) return;
        final TrashItem item = items.get(position);
        final Context context = requireContext().getApplicationContext();
        final long view = viewGeneration;
        executor.execute(() -> {
            boolean ok;
            try {
                ok = TrashManager.restoreWatchItem(context, item);
            } catch (RuntimeException e) {
                FLog.e(TAG, "restoreItem failed", e);
                ok = false;
            }
            final boolean success = ok;
            mainHandler.post(() -> {
                if (!finishAction(view)) return;
                if (success) removeItem(item);
                Toast.makeText(requireContext(), success
                        ? getString(R.string.trash_restore_success_toast, 1)
                        : getString(R.string.trash_restore_fail_toast), Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void confirmDeleteItem(int position) {
        if (actionInFlight || position < 0 || position >= items.size()) return;
        final TrashItem item = items.get(position);
        final String name = item.getOriginalDisplayName();
        final long view = viewGeneration;
        new AlertDialog.Builder(requireContext(), R.style.WatchDeleteConfirmDialog)
                .setTitle(R.string.watch_perm_delete_title)
                .setMessage(getString(R.string.watch_perm_delete_msg, name != null ? name : ""))
                .setPositiveButton(R.string.watch_perm_delete_action, (d, w) -> {
                    if (ownsView(view) && items.contains(item)) deleteItem(item);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmDeleteAll() {
        if (actionInFlight || items.isEmpty()) return;
        final long view = viewGeneration;
        new AlertDialog.Builder(requireContext(), R.style.WatchDeleteConfirmDialog)
                .setTitle(R.string.watch_perm_delete_all_title)
                .setMessage(getString(R.string.watch_perm_delete_all_msg, items.size()))
                .setPositiveButton(R.string.watch_perm_delete_action, (d, w) -> {
                    if (ownsView(view)) deleteAll();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deleteItem(TrashItem item) {
        if (!beginAction()) return;
        final Context context = requireContext().getApplicationContext();
        final long view = viewGeneration;
        executor.execute(() -> {
            boolean ok;
            try {
                ok = TrashManager.permanentlyDeleteItems(context, Collections.singletonList(item));
            } catch (RuntimeException error) {
                FLog.e(TAG, "deleteItem failed", error);
                ok = false;
            }
            final boolean success = ok;
            mainHandler.post(() -> {
                if (!finishAction(view)) return;
                if (success) removeItem(item);
                showDeleteResult(success, 1);
            });
        });
    }

    private void deleteAll() {
        if (items.isEmpty() || !beginAction()) return;
        final Context context = requireContext().getApplicationContext();
        final long view = viewGeneration;
        final List<TrashItem> requested = new ArrayList<>(items);
        final int count = requested.size();
        executor.execute(() -> {
            boolean ok;
            try {
                ok = TrashManager.emptyAllTrash(context);
            } catch (RuntimeException error) {
                FLog.e(TAG, "deleteAll failed", error);
                ok = false;
            }
            final boolean success = ok;
            final List<TrashItem> remaining = readRemainingItems(context, success ? Collections.emptyList() : requested);
            mainHandler.post(() -> {
                if (!finishAction(view)) return;
                replaceItems(remaining);
                showDeleteResult(success, count);
            });
        });
    }

    private void showDeleteResult(boolean success, int count) {
        Toast.makeText(requireContext(), success
                ? getString(R.string.trash_items_deleted_toast, count)
                : getString(R.string.trash_error_deleting_items_toast), Toast.LENGTH_SHORT).show();
    }

    // -------------------------------------------------------------------------
    // Adapter
    // -------------------------------------------------------------------------

    private class WatchTrashAdapter extends RecyclerView.Adapter<WatchTrashAdapter.VH> {

        private final SimpleDateFormat dateFormat =
                new SimpleDateFormat("MMM d, HH:mm", Locale.getDefault());

        class VH extends RecyclerView.ViewHolder {
            final ImageView ivThumb;
            final TextView  tvName;
            final TextView  tvDate;
            final TextView  btnRestore;
            final TextView  btnDelete;

            VH(@NonNull View v) {
                super(v);
                ivThumb    = v.findViewById(R.id.iv_watch_trash_thumb);
                tvName     = v.findViewById(R.id.tv_watch_trash_name);
                tvDate     = v.findViewById(R.id.tv_watch_trash_date);
                btnRestore = v.findViewById(R.id.btn_watch_trash_restore);
                btnDelete  = v.findViewById(R.id.btn_watch_trash_delete);
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_watch_trash, parent, false);
            final VH holder = new VH(v);
            // Hardware-clip thumbnail to rounded rect — covers background placeholder + image bitmap.
            holder.ivThumb.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    final float r = 12f * view.getResources().getDisplayMetrics().density;
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
                }
            });
            holder.ivThumb.setClipToOutline(true);
            return holder;
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            final TrashItem item = items.get(position);

            // Name (strip extension)
            String displayName = item.getOriginalDisplayName();
            if (displayName == null) displayName = item.getTrashFileName();
            if (displayName == null) displayName = "Unknown";
            if (displayName.contains(".")) {
                displayName = displayName.substring(0, displayName.lastIndexOf('.'));
            }
            holder.tvName.setText(displayName);

            // Date
            final String dateStr = dateFormat.format(new Date(item.getDateTrashed()));
            holder.tvDate.setText(dateStr);

            // Thumbnail (async)
            holder.ivThumb.setImageBitmap(null);
            holder.ivThumb.setBackgroundColor(0xFF1A1A1A); // dark placeholder, clipped by ViewOutlineProvider
            final String trashFileName = item.getTrashFileName();
            if (trashFileName != null && !executor.isShutdown()) {
                final Context context = holder.itemView.getContext().getApplicationContext();
                final long view = viewGeneration;
                executor.execute(() -> {
                    Bitmap bmp = null;
                    android.media.MediaMetadataRetriever retriever = null;
                    try {
                        final File trashDir = TrashManager.getTrashDirectory(context);
                        final File videoFile = TrashManager.resolveTrashFile(trashDir, trashFileName);
                        if (videoFile != null) {
                            FLog.d(TAG, "Loading trash thumbnail: " + videoFile.getAbsolutePath());
                            FLog.d(TAG, "File exists: " + videoFile.exists() + ", can read: " + videoFile.canRead());
                            
                            if (videoFile.exists()) {
                                retriever = new android.media.MediaMetadataRetriever();
                                retriever.setDataSource(videoFile.getAbsolutePath());
                                Bitmap frame = retriever.getFrameAtTime(1000000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                                if (frame != null) {
                                    FLog.d(TAG, "Got trash frame: " + frame.getWidth() + "x" + frame.getHeight());
                                    bmp = Bitmap.createScaledBitmap(frame, 64, 64, true);
                                }
                            }
                        }
                    } catch (Exception e) {
                        FLog.e(TAG, "Thumbnail failed for: " + trashFileName, e);
                    } finally {
                        if (retriever != null) {
                            try { retriever.release(); } catch (Exception ignored) {}
                        }
                    }
                    final Bitmap finalBmp = bmp;
                    mainHandler.post(() -> {
                        int current = holder.getBindingAdapterPosition();
                        if (finalBmp == null) return;
                        if (ownsView(view) && current >= 0 && current < items.size()
                                && item.equals(items.get(current))) {
                            holder.ivThumb.setImageBitmap(finalBmp);
                        } else {
                            finalBmp.recycle();
                        }
                    });
                });
            }

            // Action buttons
            holder.btnRestore.setOnClickListener(v -> {
                final int pos = holder.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_ID) restoreItem(pos);
            });
            holder.btnDelete.setOnClickListener(v -> {
                final int pos = holder.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_ID) confirmDeleteItem(pos);
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}

package com.fadcam.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.fadcam.R;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Presents the repository's existing folder-derived categories; never creates or moves files. */
final class GalleryAlbumAdapter extends RecyclerView.Adapter<GalleryAlbumAdapter.Holder> {
    interface Listener {
        String title(VideoItem.Category category);
        void open(VideoItem.Category category);
    }
    static final class Album {
        final VideoItem.Category category;
        final VideoItem cover;
        int count;
        Album(VideoItem item) { category = item.category; cover = item; count = 1; }
    }
    private final Listener listener;
    private final List<Album> albums = new ArrayList<>();
    private boolean hideThumbnails;
    GalleryAlbumAdapter(Listener listener) { this.listener = listener; }
    static List<Album> group(List<VideoItem> items) {
        Map<VideoItem.Category, Album> grouped = new LinkedHashMap<>();
        for (VideoItem item : items) {
            if (item == null || item.isSkeleton) continue;
            Album album = grouped.get(item.category);
            if (album == null) grouped.put(item.category, new Album(item)); else album.count++;
        }
        return new ArrayList<>(grouped.values());
    }
    void update(List<VideoItem> items, boolean hide) {
        albums.clear(); albums.addAll(group(items)); hideThumbnails = hide; notifyDataSetChanged();
    }
    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_gallery_album, parent, false));
    }
    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        Album album = albums.get(position);
        holder.title.setText(listener.title(album.category));
        holder.count.setText(String.valueOf(album.count));
        holder.itemView.setOnClickListener(v -> listener.open(album.category));
        holder.requests.clear(holder.cover);
        holder.cover.setImageResource(R.drawable.ic_video_placeholder);
        if (!hideThumbnails) holder.requests.load(album.cover.uri).centerCrop().into(holder.cover);
    }
    @Override public void onViewRecycled(@NonNull Holder holder) {
        holder.requests.clear(holder.cover); super.onViewRecycled(holder);
    }
    @Override public int getItemCount() { return albums.size(); }
    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView cover;
        // Resolve while the view is created, never look up a destroyed Activity during recycling.
        final RequestManager requests;
        final TextView title, count;
        Holder(View view) { super(view); cover = view.findViewById(R.id.gallery_album_cover);
            requests = Glide.with(cover);
            title = view.findViewById(R.id.gallery_album_title); count = view.findViewById(R.id.gallery_album_count); }
    }
}

package com.fadcam.ui;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Intent;
import android.net.Uri;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
public class RecentMediaLauncherTest {
    private VideoItem item(String path, long modified, VideoItem.MediaType type) {
        return new VideoItem(Uri.parse(path), "fixture", 32, modified, VideoItem.Category.CAMERA, type);
    }
    @Test public void recentUsesTimestampRatherThanGallerySortOrMediaKind() {
        VideoItem old = item("file:///old.mp4", 1, VideoItem.MediaType.VIDEO);
        VideoItem recent = item("content://fixture/recent", 4, VideoItem.MediaType.IMAGE);
        assertSame(recent, RecentMediaLauncher.selectLatest(Arrays.asList(recent, old)));
        assertSame(recent, RecentMediaLauncher.selectLatest(Arrays.asList(old, recent)));
    }
    @Test public void inProgressAndNonLocalEntriesNeverBecomeRecentPlayback() {
        VideoItem completed = item("file:///done.mp4", 1, VideoItem.MediaType.VIDEO);
        VideoItem temporary = item("file:///active.mp4", 20, VideoItem.MediaType.VIDEO);temporary.isTemporary=true;
        VideoItem processing = item("file:///processing.mp4", 21, VideoItem.MediaType.VIDEO);processing.isProcessingUri=true;
        VideoItem remote = item("https://example.invalid/video.mp4", 22, VideoItem.MediaType.VIDEO);
        VideoItem empty = new VideoItem(Uri.parse("file:///empty.mp4"), "empty", 0, 23);
        assertSame(completed, RecentMediaLauncher.selectLatest(Arrays.asList(null, empty, temporary,
                processing, VideoItem.createSkeleton(), remote, completed)));
        assertNull(RecentMediaLauncher.selectLatest(Collections.emptyList()));
    }
    @Test public void latestVideoOpensPlayerAndImageOpensImageViewerWithReadGrant() {
        VideoItem video = item("content://fixture/video", 1, VideoItem.MediaType.VIDEO);
        Intent intent = RecentMediaLauncher.createIntent(RuntimeEnvironment.getApplication(), video);
        assertEquals(VideoPlayerActivity.class.getName(), intent.getComponent().getClassName());
        assertEquals(video.uri, intent.getData());
        assertEquals("video/*", intent.getType());
        assertTrue((intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0);
        assertEquals("CAMERA", intent.getStringExtra("item_category"));
        Intent image = RecentMediaLauncher.createIntent(RuntimeEnvironment.getApplication(),
                item("content://fixture/photo", 2, VideoItem.MediaType.IMAGE));
        assertEquals(ImageViewerActivity.class.getName(), image.getComponent().getClassName());
    }
}

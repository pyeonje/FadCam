package com.fadcam.ui;

import android.net.Uri;
import com.bumptech.glide.load.Key;
import com.bumptech.glide.load.engine.cache.DiskCache;
import com.bumptech.glide.load.engine.cache.DiskLruCacheFactory;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.File;
import java.io.IOException;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = android.app.Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class GalleryThumbnailSignatureTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    @Test public void actualHolderReplacementCancelsPreviousGlideTargetsBeforeLateResults() throws Exception {
        androidx.fragment.app.FragmentActivity host = org.robolectric.Robolectric
                .buildActivity(androidx.fragment.app.FragmentActivity.class).setup().get();
        host.setTheme(com.fadcam.R.style.Base_Theme_FadCam);
        RecordsAdapter.RecordViewHolder holder = new RecordsAdapter.RecordViewHolder(
                android.view.LayoutInflater.from(host).inflate(com.fadcam.R.layout.item_record,
                        new android.widget.FrameLayout(host), false));
        ControlledThumb delayedA = new ControlledThumb();
        ControlledThumb latestB = new ControlledThumb();
        com.bumptech.glide.Glide.get(host).getRegistry().prepend(Uri.class, Bitmap.class,
                new com.bumptech.glide.load.model.ModelLoaderFactory<Uri, Bitmap>() {
                    @Override public com.bumptech.glide.load.model.ModelLoader<Uri, Bitmap> build(
                            com.bumptech.glide.load.model.MultiModelLoaderFactory f) {
                        return new com.bumptech.glide.load.model.ModelLoader<Uri, Bitmap>() {
                            @Override public boolean handles(Uri uri) {
                                return "thumbnail-fixture".equals(uri.getScheme());
                            }
                            @Override public LoadData<Bitmap> buildLoadData(Uri uri, int w, int h,
                                    com.bumptech.glide.load.Options opts) {
                                return new LoadData<>(new com.bumptech.glide.signature.ObjectKey(uri),
                                        "A".equals(uri.getLastPathSegment()) ? delayedA : latestB);
                            }
                        };
                    }
                    @Override public void teardown() {}
                });
        com.fadcam.SharedPreferencesManager settings = org.mockito.Mockito.mock(com.fadcam.SharedPreferencesManager.class);
        java.util.concurrent.ExecutorService metadata = org.mockito.Mockito.mock(java.util.concurrent.ExecutorService.class);
        org.mockito.Mockito.when(settings.getOpenedVideoUris()).thenReturn(java.util.Collections.emptySet());
        VideoItem a = new VideoItem(Uri.parse("thumbnail-fixture://fixture/A"), "A.png", 1, 1,
                VideoItem.Category.CAMERA, VideoItem.MediaType.IMAGE);
        VideoItem b = new VideoItem(Uri.parse("thumbnail-fixture://fixture/B"), "B.png", 1, 1,
                VideoItem.Category.CAMERA, VideoItem.MediaType.IMAGE);
        RecordsAdapter adapter = new RecordsAdapter(host, Arrays.asList(a, b), metadata, settings,
                org.mockito.Mockito.mock(RecordsAdapter.OnVideoClickListener.class), null,
                org.mockito.Mockito.mock(RecordActionListener.class));
        adapter.setGridSpan(4);
        adapter.setDeferHeavyMetadataWork(true);
        com.bumptech.glide.request.target.BitmapImageViewTarget target =
                new com.bumptech.glide.request.target.BitmapImageViewTarget(holder.imageViewThumbnail);
        try {
            adapter.onBindViewHolder(holder, adapter.findPositionByUri(a.uri));
            com.bumptech.glide.request.Request aRequest = target.getRequest();
            assertNotNull(aRequest);
            assertTrue(delayedA.started.await(5, java.util.concurrent.TimeUnit.SECONDS));
            adapter.onBindViewHolder(holder, adapter.findPositionByUri(b.uri));
            com.bumptech.glide.request.Request bRequest = target.getRequest();
            assertNotSame(aRequest, bRequest);
            assertTrue(aRequest.isCleared());
            assertTrue(delayedA.cancelled);
            assertTrue(latestB.started.await(5, java.util.concurrent.TimeUnit.SECONDS));
            latestB.deliver(0xff174cca);
            awaitGlideResult(bRequest);
            assertEquals(0xff174cca, displayedPixel(holder));
            // An in-flight fetch may return even after cancellation. Dispatch it through Glide itself.
            delayedA.deliver(0xffd41b21);
            assertTrue(delayedA.cleaned.await(5, java.util.concurrent.TimeUnit.SECONDS));
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertSame(bRequest, target.getRequest());
            assertEquals(0xff174cca, displayedPixel(holder));
            // Even the same URI gets a new request when the source version changes.
            VideoItem updated = new VideoItem(b.uri, "B.png", 1, 2, VideoItem.Category.CAMERA, VideoItem.MediaType.IMAGE);
            adapter.updateRecords(Arrays.asList(a, updated));
            adapter.onBindViewHolder(holder, adapter.findPositionByUri(b.uri));
            assertTrue(bRequest.isCleared());
            assertNotSame(bRequest, target.getRequest());
        } finally {
            adapter.onViewRecycled(holder);
        }
    }

    private static int displayedPixel(RecordsAdapter.RecordViewHolder holder) {
        return ((android.graphics.drawable.BitmapDrawable) holder.imageViewThumbnail.getDrawable())
                .getBitmap().getPixel(0, 0);
    }

    private static void awaitGlideResult(com.bumptech.glide.request.Request request) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!request.isComplete() && System.nanoTime() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            Thread.yield();
        }
        assertTrue("Glide must publish the current request", request.isComplete());
    }

    private static final class ControlledThumb implements com.bumptech.glide.load.data.DataFetcher<Bitmap> {
        final java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch cleaned = new java.util.concurrent.CountDownLatch(1);
        volatile DataCallback<? super Bitmap> callback;
        volatile boolean cancelled;
        @Override public void loadData(com.bumptech.glide.Priority p, DataCallback<? super Bitmap> result) {
            callback = result;
            started.countDown();
        }
        @Override public void cleanup() { cleaned.countDown(); }
        @Override public void cancel() { cancelled = true; }
        @Override public Class<Bitmap> getDataClass() { return Bitmap.class; }
        @Override public com.bumptech.glide.load.DataSource getDataSource() {
            return com.bumptech.glide.load.DataSource.LOCAL;
        }
        void deliver(int color) {
            Bitmap bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            bitmap.setPixel(0, 0, color);
            callback.onDataReady(bitmap);
        }
    }

    @Test public void glideDiskCacheDoesNotSubstituteCollidingUrisAndIgnoresOldVersion() throws Exception {
        File dir = temp.newFolder();
        DiskCache cache = new DiskLruCacheFactory(() -> dir, 1_000_000L).build();
        assertNotNull(cache);
        String a = "file:///Aa.mp4";
        String b = "file:///BB.mp4";
        Key keyA = RecordsAdapter.thumbnailSignature(new VideoItem(Uri.parse(a), "Aa", 100, 200));
        Key keyB = RecordsAdapter.thumbnailSignature(new VideoItem(Uri.parse(b), "BB", 100, 200));
        cache.put(keyA, file -> saveImage(file, 0xffd41b21));
        assertNull(cache.get(keyB));
        cache.put(keyB, file -> saveImage(file, 0xff174cca));
        assertEquals(0xffd41b21, BitmapFactory.decodeFile(cache.get(keyA).getAbsolutePath()).getPixel(0, 0));
        assertEquals(0xff174cca, BitmapFactory.decodeFile(cache.get(keyB).getAbsolutePath()).getPixel(0, 0));
        Key changed = RecordsAdapter.thumbnailSignature(new VideoItem(Uri.parse(a), "Aa", 100, 201));
        assertNull(cache.get(changed));
    }

    private static boolean saveImage(File file, int rgb) {
        Bitmap image = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        image.setPixel(0, 0, rgb);
        try (FileOutputStream output = new FileOutputStream(file)) {
            return image.compress(Bitmap.CompressFormat.PNG, 100, output);
        } catch (IOException e) {
            throw new AssertionError(e);
        } finally {
            image.recycle();
        }
    }
    @Test public void collidingUriHashesAndChangedSourceGetDifferentDiskKeys() throws Exception {
        String a = "file:///Aa.mp4";
        String b = "file:///BB.mp4";
        assertEquals(a.hashCode(), b.hashCode());
        byte[] first = digest(RecordsAdapter.thumbnailSignature(new VideoItem(Uri.parse(a), "Aa", 100, 200)));
        assertFalse(Arrays.equals(first, digest(RecordsAdapter.thumbnailSignature(new VideoItem(Uri.parse(b), "BB", 100, 200)))));
        assertFalse(Arrays.equals(first, digest(RecordsAdapter.thumbnailSignature(new VideoItem(Uri.parse(a), "Aa", 100, 201)))));
    }

    private static byte[] digest(Key key) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        key.updateDiskCacheKey(digest);
        return digest.digest();
    }
}

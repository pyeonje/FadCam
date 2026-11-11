package com.fadcam.playback;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.os.Bundle;
import android.net.Uri;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import androidx.media3.common.MediaItem;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.robolectric.shadows.ShadowContentResolver;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = android.app.Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class GallerySourcePreparationTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    @Test public void unindexedFragmentedMp4DoesNotClipFramesToShortMetadata() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Uri uri = Uri.parse("content://gallery.fixture.provider/short-mvhd-fragmented.mp4");
        byte[] fixture;
        try (java.io.InputStream input = getClass().getResourceAsStream("/playback/short-mvhd-fragmented.mp4")) {
            assertNotNull(input);
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[512];
            for (int n; (n = input.read(buffer)) >= 0;) bytes.write(buffer, 0, n);
            fixture = bytes.toByteArray();
        }
        shadowOf(context.getContentResolver()).registerInputStreamSupplier(uri,
                () -> new java.io.ByteArrayInputStream(fixture));
        org.robolectric.shadows.ShadowMediaMetadataRetriever.addMetadata(
                org.robolectric.shadows.util.DataSource.toDataSource(context, uri),
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION, "100");
        try (android.media.MediaMetadataRetriever metadata = new android.media.MediaMetadataRetriever()) {
            metadata.setDataSource(context, uri);
            assertEquals("100", metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION));
        }
        SeekableFragmentedMp4MediaSourceFactory factory = new SeekableFragmentedMp4MediaSourceFactory(context) {
            @Override ParcelFileDescriptor openScanDescriptor(Uri ignored) { return null; }
        };
        assertTrue(factory.isFragmentedMp4(uri));
        MediaSource source = factory.createMediaSource(MediaItem.fromUri(uri));
        assertEquals(uri, source.getMediaItem().localConfiguration.uri);
        assertTrue("100 ms of metadata cannot truncate valid fragment data",
                source instanceof androidx.media3.exoplayer.source.ProgressiveMediaSource);
    }

    @Test public void newestRequestPreparesAtDeadlineEvenWhenTwoProvidersIgnoreCancellation() throws Exception {
        PlayerHolder holder = ReflectionHelpers.callConstructor(PlayerHolder.class);
        ExoPlayer owner = mock(ExoPlayer.class);
        when(owner.getApplicationLooper()).thenReturn(Looper.getMainLooper());
        when(owner.getPlayWhenReady()).thenReturn(true);
        SeekableFragmentedMp4MediaSourceFactory factory = mock(SeekableFragmentedMp4MediaSourceFactory.class);
        ReflectionHelpers.setField(holder, "player", owner);
        ReflectionHelpers.setField(holder, "fmp4SourceFactory", factory);
        ThreadPoolExecutor worker = ReflectionHelpers.getField(holder, "preparationExecutor");
        CountDownLatch enteredA = new CountDownLatch(1);
        CountDownLatch enteredB = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MediaSource staleSource = mock(MediaSource.class);
        Uri latest = Uri.parse("content://provider/latest");
        when(factory.createMediaSource(any())).thenAnswer(invocation -> {
            MediaItem item = invocation.getArgument(0);
            if (!latest.equals(item.localConfiguration.uri)) {
                ("blocked-a".equals(item.localConfiguration.uri.getLastPathSegment())
                        ? enteredA : enteredB).countDown();
                while (true) {
                    try { release.await(); break; }
                    catch (InterruptedException ignored) { /* Broken providers can ignore cancellation. */ }
                }
            }
            return staleSource;
        });
        try {
            holder.setMediaIfNeeded(Uri.parse("content://provider/blocked-a"));
            assertTrue("A must start before it is superseded", enteredA.await(5, TimeUnit.SECONDS));
            holder.setMediaIfNeeded(Uri.parse("content://provider/blocked-b"));
            assertTrue("B must start before the last selection", enteredB.await(5, TimeUnit.SECONDS));
            holder.setMediaIfNeeded(latest);
            shadowOf(Looper.getMainLooper()).idleFor(3, TimeUnit.SECONDS);
            assertEquals(latest, holder.getCurrentUri());
            verify(owner, times(1)).prepare();
            release.countDown();
            worker.shutdown();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            shadowOf(Looper.getMainLooper()).idle();
            verify(owner, never()).setMediaSource(same(staleSource), anyLong());
            verify(owner, times(1)).prepare();
            verify(factory, times(2)).createMediaSource(any());
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }

    @Test public void sameNameAcrossVolumesStillReadsSelectedProviderThroughSeparatePlaybackDescriptor() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        String authority = "gallery.fixture.provider";
        Uri requested = Uri.parse("content://" + authority + "/tree/SD%3ACamera/document/SD%3ACamera%2Fa.mp4");
        Uri primary = Uri.parse("content://" + authority + "/tree/primary%3ACamera/document/primary%3ACamera%2Fa.mp4");
        File sdFile = temp.newFile("sd.mp4");
        File primaryFile = temp.newFile("primary.mp4");
        byte[] sdBytes = new byte[] {7, 9, 11, 13};
        Files.write(sdFile.toPath(), sdBytes);
        Files.write(primaryFile.toPath(), new byte[] {2, 3, 5});
        List<Uri> opens = new ArrayList<>();
        List<ParcelFileDescriptor> descriptors = new ArrayList<>();
        ContentProvider provider = new ContentProvider() {
            @Override public boolean onCreate() { return true; }
            @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
            @Override public String getType(Uri u) { return "video/mp4"; }
            @Override public Uri insert(Uri u, ContentValues v) { return null; }
            @Override public int delete(Uri u, String s, String[] a) { return 0; }
            @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
            @Override public ParcelFileDescriptor openFile(Uri u, String mode) throws FileNotFoundException {
                if (!requested.equals(u) && !primary.equals(u)) throw new FileNotFoundException(u.toString());
                ParcelFileDescriptor pfd = ParcelFileDescriptor.open(requested.equals(u) ? sdFile : primaryFile,
                        ParcelFileDescriptor.MODE_READ_ONLY);
                opens.add(u);
                descriptors.add(pfd);
                return pfd;
            }
            @Override public AssetFileDescriptor openTypedAssetFile(Uri u, String mime, Bundle options)
                    throws FileNotFoundException {
                return new AssetFileDescriptor(openFile(u, "r"), 0L, AssetFileDescriptor.UNKNOWN_LENGTH);
            }
        };
        ProviderInfo info = new ProviderInfo();
        info.authority = authority;
        provider.attachInfo(app, info);
        ShadowContentResolver.registerProviderInternal(authority, provider);
        SeekableFragmentedMp4MediaSourceFactory factory = new SeekableFragmentedMp4MediaSourceFactory(app) {
            @Override FragmentedMp4IndexBuilder.FragmentIndex scanDescriptor(ParcelFileDescriptor pfd) {
                // ContentResolver may wrap the provider parcel while retaining its descriptor.
                assertSame(descriptors.get(0).getFileDescriptor(), pfd.getFileDescriptor());
                return new FragmentedMp4IndexBuilder.FragmentIndex(Collections.singletonList(
                        new FragmentedMp4IndexBuilder.FragmentEntry(0L, sdBytes.length, 0L)), 1000L, 1000L);
            }
        };
        MediaSource source = factory.createMediaSource(MediaItem.fromUri(requested));
        assertEquals(requested, source.getMediaItem().localConfiguration.uri);
        assertEquals(Collections.singletonList(requested), opens);
        DataSource.Factory playbackFactory = ReflectionHelpers.getField(source, "dataSourceFactory");
        DataSource playback = playbackFactory.createDataSource();
        try {
            playback.open(new DataSpec(source.getMediaItem().localConfiguration.uri));
            assertEquals(java.util.Arrays.asList(requested, requested), opens);
            assertNotSame(descriptors.get(0).getFileDescriptor(), descriptors.get(1).getFileDescriptor());
            byte[] read = new byte[sdBytes.length];
            int readCount = 0;
            while (readCount < read.length) {
                int n = playback.read(read, readCount, read.length - readCount);
                assertTrue(n > 0);
                readCount += n;
            }
            assertArrayEquals(sdBytes, read);
        } finally {
            playback.close();
        }
    }

    @Test public void providerUriIsRetainedAndScanDescriptorClosedBeforePlayback() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Uri selected = Uri.parse("content://media.provider/tree/SD%3ACamera/document/SD%3ACamera%2Fa.mp4");
        ParcelFileDescriptor scan = mock(ParcelFileDescriptor.class);
        SeekableFragmentedMp4MediaSourceFactory factory = new SeekableFragmentedMp4MediaSourceFactory(context) {
            @Override ParcelFileDescriptor openScanDescriptor(Uri uri) {
                assertEquals(selected, uri);
                return scan;
            }
            @Override FragmentedMp4IndexBuilder.FragmentIndex scanDescriptor(ParcelFileDescriptor pfd) {
                assertSame(scan, pfd);
                return new FragmentedMp4IndexBuilder.FragmentIndex(Collections.singletonList(
                        new FragmentedMp4IndexBuilder.FragmentEntry(40L, 32L, 0L)), 1000L, 1000L);
            }
        };
        MediaSource source = factory.createMediaSource(MediaItem.fromUri(selected));
        assertEquals(selected, source.getMediaItem().localConfiguration.uri);
        verify(scan, times(1)).close();
    }

    @Test public void failedProviderScanClosesDescriptorOnce() throws Exception {
        Uri selected = Uri.parse("content://other.provider/file:Camera.mp4");
        ParcelFileDescriptor scan = mock(ParcelFileDescriptor.class);
        SeekableFragmentedMp4MediaSourceFactory factory = new SeekableFragmentedMp4MediaSourceFactory(RuntimeEnvironment.getApplication()) {
            @Override ParcelFileDescriptor openScanDescriptor(Uri uri) {
                assertEquals(selected, uri);
                return scan;
            }
            @Override FragmentedMp4IndexBuilder.FragmentIndex scanDescriptor(ParcelFileDescriptor pfd) {
                throw new IllegalStateException("seek unsupported");
            }
        };
        MediaSource fallback = factory.createMediaSource(MediaItem.fromUri(selected));
        assertEquals(selected, fallback.getMediaItem().localConfiguration.uri);
        verify(scan, times(1)).close();
    }

    @Test public void closedViewerCannotApplyPreparedSourceToSharedPlayer() throws Exception {
        PlayerHolder holder = ReflectionHelpers.callConstructor(PlayerHolder.class);
        ExoPlayer player = mock(ExoPlayer.class);
        when(player.getApplicationLooper()).thenReturn(Looper.getMainLooper());
        SeekableFragmentedMp4MediaSourceFactory factory = mock(SeekableFragmentedMp4MediaSourceFactory.class);
        ReflectionHelpers.setField(holder, "player", player);
        ReflectionHelpers.setField(holder, "fmp4SourceFactory", factory);
        ThreadPoolExecutor worker = ReflectionHelpers.getField(holder, "preparationExecutor");
        AtomicBoolean viewerOpen = new AtomicBoolean(true);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        MediaSource late = mock(MediaSource.class);
        when(factory.createMediaSource(any())).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(finish.await(5, TimeUnit.SECONDS));
            return late;
        });
        try {
            holder.setMediaIfNeeded(Uri.parse("content://provider/closed"), viewerOpen::get);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            viewerOpen.set(false);
            finish.countDown();
            worker.shutdown();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            shadowOf(Looper.getMainLooper()).idle();
            verify(player, never()).setMediaSource(same(late), anyLong());
            verify(player, never()).prepare();
        } finally {
            finish.countDown();
            worker.shutdownNow();
        }
    }

    @Test public void delayedReadDoesNotApplyObsoleteSourceOrBlockApplicationLooper() throws Exception {
        PlayerHolder holder = ReflectionHelpers.callConstructor(PlayerHolder.class);
        ExoPlayer owner = mock(ExoPlayer.class);
        when(owner.getApplicationLooper()).thenReturn(Looper.getMainLooper());
        when(owner.getCurrentPosition()).thenReturn(700L);
        when(owner.getPlayWhenReady()).thenReturn(true);
        SeekableFragmentedMp4MediaSourceFactory factory = mock(SeekableFragmentedMp4MediaSourceFactory.class);
        ReflectionHelpers.setField(holder, "player", owner);
        ReflectionHelpers.setField(holder, "fmp4SourceFactory", factory);
        ThreadPoolExecutor worker = ReflectionHelpers.getField(holder, "preparationExecutor");
        CountDownLatch enteredA = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        CountDownLatch enteredB = new CountDownLatch(1);
        Uri a = Uri.parse("content://provider/A");
        Uri b = Uri.parse("content://provider/B");
        MediaSource obsolete = mock(MediaSource.class);
        MediaSource current = mock(MediaSource.class);
        when(factory.createMediaSource(any())).thenAnswer(invocation -> {
            assertNotSame(Looper.getMainLooper(), Looper.myLooper());
            MediaItem item = invocation.getArgument(0);
            if (a.equals(item.localConfiguration.uri)) {
                enteredA.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                boolean released = false;
                while (!released && System.nanoTime() < deadline) {
                    try { released = releaseA.await(deadline - System.nanoTime(), TimeUnit.NANOSECONDS); }
                    catch (InterruptedException ignored) { /* Simulate a provider that ignores cancellation. */ }
                }
                assertTrue(released);
                return obsolete;
            }
            enteredB.countDown();
            return current;
        });
        AtomicBoolean appliedOnMain = new AtomicBoolean(false);
        doAnswer(invocation -> {
            appliedOnMain.set(Looper.myLooper() == Looper.getMainLooper());
            return null;
        }).when(owner).setMediaSource(same(current), eq(700L));
        try {
            holder.setMediaIfNeeded(a);
            assertTrue(enteredA.await(5, TimeUnit.SECONDS));
            holder.setMediaIfNeeded(b);
            assertEquals(b, holder.getCurrentUri());
            assertTrue("B may prepare before blocked A", enteredB.await(5, TimeUnit.SECONDS));
            worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
            shadowOf(Looper.getMainLooper()).idle();
            verify(owner).setMediaSource(same(current), eq(700L));
            releaseA.countDown();
            worker.shutdown();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            shadowOf(Looper.getMainLooper()).idle();
            verify(owner, never()).setMediaSource(same(obsolete), anyLong());
            verify(owner).setMediaSource(same(current), eq(700L));
            assertTrue(appliedOnMain.get());
        } finally {
            releaseA.countDown();
            worker.shutdownNow();
        }
    }
}

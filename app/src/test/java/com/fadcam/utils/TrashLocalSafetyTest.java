package com.fadcam.utils;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import com.fadcam.Constants;
import com.fadcam.model.TrashItem;
import com.google.gson.JsonParser;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class TrashLocalSafetyTest {
    private static final byte[] MEDIA = {11, 22, 33, 44};
    private static final byte[] SENTINEL = {97, 98, 99};

    @Test public void restoreRejectsTraversalDisplayNameWithoutCreatingOrModifyingOutsideTarget() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File trash = TrashManager.getTrashDirectory(context);
        File source = new File(trash, "local-trash.mp4");
        Files.write(source.toPath(), MEDIA);
        File external = context.getExternalFilesDir(null);
        File outside = new File(external, "qa-outside.mp4");
        Files.write(outside.toPath(), SENTINEL);
        TrashItem item = item("../qa-outside.mp4", source.getName());
        assertTrue(TrashManager.saveTrashMetadata(context, List.of(item)));
        assertFalse(TrashManager.restoreItemsFromTrash(context, List.of(item),
                TrashManager.RestoreDestination.INTERNAL_STORAGE));
        assertArrayEquals(SENTINEL, Files.readAllBytes(outside.toPath()));
        assertFalse(new File(external, "qa-outside (1).mp4").exists());
        assertArrayEquals(MEDIA, Files.readAllBytes(source.toPath()));
    }

    @Test public void maliciousTrashMetadataCannotDeleteOrRestoreAFileOutsideTrash() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File outside = new File(TrashManager.getTrashDirectory(context).getParentFile(), "qa-retained.mp4");
        Files.write(outside.toPath(), SENTINEL);
        TrashItem item = item("restored.mp4", "../qa-retained.mp4");
        assertTrue(TrashManager.saveTrashMetadata(context, List.of(item)));
        assertFalse(TrashManager.restoreItemsFromTrash(context, List.of(item),
                TrashManager.RestoreDestination.INTERNAL_STORAGE));
        assertArrayEquals(SENTINEL, Files.readAllBytes(outside.toPath()));
        assertFalse(TrashManager.permanentlyDeleteItems(context, List.of(item)));
        assertArrayEquals(SENTINEL, Files.readAllBytes(outside.toPath()));
    }

    @Test public void localRestoreRecordsActualDigestAndKeepsExistingKoreanNameCollision() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File source = new File(TrashManager.getTrashDirectory(context), "stored.mp4");
        Files.write(source.toPath(), MEDIA);
        TrashItem item = item("테스트.mp4", source.getName());
        // Model a recoverable file without a materialized list. Windows Robolectric's
        // AtomicFile rename cannot always replace an existing metadata file.
        File target = new File(context.getExternalFilesDir(null), Constants.RECORDING_DIRECTORY);
        assertTrue(target.isDirectory() || target.mkdirs());
        File original = new File(target, "테스트.mp4");
        Files.write(original.toPath(), SENTINEL);
        assertTrue(TrashManager.restoreItemsFromTrash(context, List.of(item),
                TrashManager.RestoreDestination.INTERNAL_STORAGE));
        assertFalse(source.exists());
        assertArrayEquals(SENTINEL, Files.readAllBytes(original.toPath()));
        assertArrayEquals(MEDIA, Files.readAllBytes(new File(target, "테스트 (1).mp4").toPath()));
        File[] journals = new File(context.getFilesDir(), "trash-restore-journal").listFiles();
        assertNotNull(journals);
        assertEquals(1, journals.length);
        var digest = JsonParser.parseString(new String(Files.readAllBytes(journals[0].toPath()), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("digest");
        assertNotNull("recovery record must persist independently checked destination digest", digest);
        byte[] expected = CopyIntegrity.sha256().digest(MEDIA);
        assertEquals(expected.length, digest.size());
        for (int i = 0; i < expected.length; i++) assertEquals(expected[i], digest.get(i).getAsByte());
        assertTrue(TrashManager.loadTrashMetadata(context).isEmpty());
        assertFalse(journals[0].exists());
    }

    @Test public void localRestoreRejectsOtherNonBasenames() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File source = new File(TrashManager.getTrashDirectory(context), "still-in-trash.mp4");
        Files.write(source.toPath(), MEDIA);
        for (String name : List.of("..\\outside.mp4", "/qa-absolute.mp4", "C:\\qa-absolute.mp4",
                "nested/file.mp4", "", ".", "..", "line\nfeed.mp4")) {
            TrashItem item = item(name, source.getName());
            assertFalse(name, TrashManager.restoreItemsFromTrash(context, List.of(item),
                    TrashManager.RestoreDestination.INTERNAL_STORAGE));
            assertArrayEquals(MEDIA, Files.readAllBytes(source.toPath()));
        }
    }

    private static TrashItem item(String name, String stored) {
        return new TrashItem("file:///qa-original.mp4", name, stored, 123L, false, MEDIA.length);
    }
}

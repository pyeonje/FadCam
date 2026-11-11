package com.fadcam.service;

import static org.junit.Assert.*;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=android.app.Application.class)
public class RecordsDeletionSessionTransactionTest {
    @Test public void completionAcknowledgementCannotStopOrClearAnActiveExport() {
        var context = RuntimeEnvironment.getApplication();
        var store = new RecordsDeletionSessionStore(context);
        var item = new RecordsDeletionRequestItem("file:///A.mp4", "A.mp4", 100, false);
        var active = RecordsDeletionSessionSnapshot.create(Arrays.asList(item),
                RecordsDeletionSessionSnapshot.OperationKind.SAVE_EXPORT_TO_CUSTOM_TREE);
        active.customTreeUri = "content://documents/tree/B";
        assertSame(item, active.claimNext());
        assertTrue(store.write(active));
        var controller = org.robolectric.Robolectric.buildService(RecordsDeletionService.class).create();
        try {
            var service = controller.get();
            for (String id : new String[]{"old-completion", active.sessionId}) {
                var intent = new android.content.Intent(RecordsDeletionService.ACTION_ACKNOWLEDGE_DELETE_COMPLETION);
                intent.putExtra(RecordsDeletionService.EXTRA_SESSION_ID, id);
                service.onStartCommand(intent, 0, 1);
                assertFalse(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
                assertEquals(active.sessionId, store.read().sessionId);
                assertEquals(item.uriString, store.read().inFlight.uriString);
            }
        } finally {
            controller.destroy();
        }
    }

    @Test public void differentTreeRejectedAndDurableClaimNeverReplayed() {
        var kind = RecordsDeletionSessionSnapshot.OperationKind.SAVE_EXPORT_TO_CUSTOM_TREE;
        var first = new RecordsDeletionRequestItem("file:///A.mp4", "A.mp4", 100, false);
        var second = new RecordsDeletionRequestItem("file:///B.mp4", "B.mp4", 100, false);
        var snapshot = RecordsDeletionSessionSnapshot.create(Arrays.asList(first, second), kind);
        snapshot.customTreeUri = "content://documents/tree/A";
        assertTrue(snapshot.accepts(kind, "content://documents/tree/A"));
        assertFalse(snapshot.accepts(kind, "content://documents/tree/B"));
        assertSame(first, snapshot.claimNext());
        snapshot.inFlightOutputUri = "content://documents/document/partial";
        var store = new RecordsDeletionSessionStore(RuntimeEnvironment.getApplication());
        assertTrue(store.write(snapshot, "intent-1"));
        var reopened = new RecordsDeletionSessionStore(RuntimeEnvironment.getApplication());
        assertTrue(reopened.alreadyAccepted("intent-1"));
        snapshot = reopened.read();
        assertEquals(first.uriString, snapshot.inFlight.uriString);
        snapshot.recoverInterruptedClaim();
        assertEquals(Arrays.asList(first.uriString), snapshot.failedUriStrings);
        assertEquals(Arrays.asList("content://documents/document/partial"), snapshot.residualOutputUris);
        assertEquals(second.uriString, snapshot.claimNext().uriString);
    }

    @Test public void earlyEofAndTruncatedOutputNeverPassSizeVerification() {
        assertFalse(RecordsDeletionService.verifiedExportSize(100, 40, 40L));
        assertFalse(RecordsDeletionService.verifiedExportSize(100, 100, 40L));
        assertTrue(RecordsDeletionService.verifiedExportSize(-1, 100, 100L));
        assertTrue(RecordsDeletionService.verifiedExportSize(-1, 100, null));
    }

    @Test public void nullStreamCloseFailureAndCancellationDoNotCompleteExport() {
        RecordsDeletionService.ExportStream<java.io.InputStream> source =
                () -> new java.io.ByteArrayInputStream(new byte[100]);
        assertThrows(java.io.IOException.class, () ->
                RecordsDeletionService.copyExportPayload(() -> null, java.io.ByteArrayOutputStream::new, () -> false));
        assertThrows(java.io.IOException.class, () -> RecordsDeletionService.copyExportPayload(source,
                () -> new java.io.ByteArrayOutputStream() {
                    @Override public void close() throws java.io.IOException { throw new java.io.IOException("close"); }
                }, () -> false));
        assertThrows(java.util.concurrent.CancellationException.class, () ->
                RecordsDeletionService.copyExportPayload(source, java.io.ByteArrayOutputStream::new, () -> true));
    }
}

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
    @Test public void failedPreferencesCommitCannotHideTheDurableClaimOrConsumeRequestId() {
        var app = RuntimeEnvironment.getApplication();
        var backing = app.getSharedPreferences("fixture-journal", android.content.Context.MODE_PRIVATE);
        var durable = new RecordsDeletionSessionStore(backing);
        var item = new RecordsDeletionRequestItem("file:///source.mp4", "source.mp4", 100, false);
        var snapshot = RecordsDeletionSessionSnapshot.create(Arrays.asList(item));
        snapshot.claimNext();
        assertTrue(durable.write(snapshot));
        var wrapped = org.mockito.Mockito.mock(android.content.SharedPreferences.class,
                org.mockito.AdditionalAnswers.delegatesTo(backing));
        var fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        org.mockito.Mockito.doAnswer(call -> {
            var actual = backing.edit();
            var editor = org.mockito.Mockito.mock(android.content.SharedPreferences.Editor.class,
                    org.mockito.AdditionalAnswers.delegatesTo(actual));
            org.mockito.Mockito.doAnswer(c -> {
                actual.putString(c.getArgument(0), c.getArgument(1));
                return editor;
            }).when(editor).putString(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
            org.mockito.Mockito.doAnswer(c -> {
                actual.commit();
                return !fail.getAndSet(false);
            }).when(editor).commit();
            return editor;
        }).when(wrapped).edit();
        var store = new RecordsDeletionSessionStore(wrapped);
        var terminal = snapshot.copy();
        terminal.finishRecoveredOperation(100);
        assertFalse(store.write(terminal, "new-intent"));
        assertFalse(durable.alreadyAccepted("new-intent"));
        assertEquals(item.uriString, durable.read().inFlight.uriString);
        assertEquals(0, durable.read().completedItemCount);
    }

    @Test public void actualServiceRepliesOnlyAfterAcceptanceAndDeduplicatesCompetingExports() {
        var app = RuntimeEnvironment.getApplication();
        var controller = org.robolectric.Robolectric.buildService(RecordsDeletionService.class).create();
        try {
            var service = controller.get();
            var processing = (java.util.concurrent.atomic.AtomicBoolean)
                    org.robolectric.util.ReflectionHelpers.getField(service, "processing");
            processing.set(true); // Keep the durable queue without running provider operations.
            var item = new RecordsDeletionRequestItem("file:///input.mp4", "input.mp4", 100, false);
            var first = org.mockito.Mockito.mock(android.os.ResultReceiver.class);
            var competing = org.mockito.Mockito.mock(android.os.ResultReceiver.class);
            var original = exportIntent(item, "content://fixture/tree/A", "request-A", first);
            service.onStartCommand(original, 0, 1);
            service.onStartCommand(exportIntent(item, "content://fixture/tree/B", "request-B", competing), 0, 2);
            service.onStartCommand(original, 0, 3);
            org.mockito.Mockito.verify(first, org.mockito.Mockito.times(2)).send(android.app.Activity.RESULT_OK, null);
            org.mockito.Mockito.verify(competing).send(android.app.Activity.RESULT_CANCELED, null);
            var state = new RecordsDeletionSessionStore(app).read();
            assertEquals("content://fixture/tree/A", state.customTreeUri);
            assertEquals(1, state.totalItemCount);
            assertEquals(1, state.pendingItems.size());
        } finally {
            controller.destroy();
        }
    }

    private static android.content.Intent exportIntent(RecordsDeletionRequestItem item, String tree, String id,
            android.os.ResultReceiver reply) {
        return new android.content.Intent(RecordsDeletionService.ACTION_START_DELETE_SESSION)
                .putExtra(RecordsDeletionService.EXTRA_DELETE_ITEMS_JSON, RecordsDeletionRequestItem.toJson(Arrays.asList(item)))
                .putExtra(RecordsDeletionService.EXTRA_OPERATION_KIND,
                        RecordsDeletionSessionSnapshot.OperationKind.SAVE_EXPORT_TO_CUSTOM_TREE.name())
                .putExtra(RecordsDeletionService.EXTRA_CUSTOM_TREE_URI, tree)
                .putExtra("request_id", id).putExtra("export_acceptance", reply);
    }

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
        assertFalse(RecordsDeletionService.verifiedExportSize(-1, 100, 100L));
        assertFalse(RecordsDeletionService.verifiedExportSize(-1, 100, null));
    }

    @Test public void failedTerminalJournalWriteDoesNotShowCompletion() {
        var controller = org.robolectric.Robolectric.buildService(RecordsDeletionService.class).create();
        try {
            var service = controller.get();
            var store = org.mockito.Mockito.mock(RecordsDeletionSessionStore.class);
            var notification = org.mockito.Mockito.mock(RecordsDeletionNotificationManager.class);
            org.robolectric.util.ReflectionHelpers.setField(service, "sessionStore", store);
            org.robolectric.util.ReflectionHelpers.setField(service, "notificationManager", notification);
            var snapshot = RecordsDeletionSessionSnapshot.create(Arrays.asList(
                    new RecordsDeletionRequestItem("file:///result.mp4", "result.mp4", 100, false)));
            snapshot.completedItemCount = 1;
            org.robolectric.util.ReflectionHelpers.callInstanceMethod(service, "finalizeSession",
                    org.robolectric.util.ReflectionHelpers.ClassParameter.from(RecordsDeletionSessionSnapshot.class, snapshot));
            org.mockito.Mockito.verify(store).write(snapshot);
            org.mockito.Mockito.verify(notification, org.mockito.Mockito.never())
                    .showCompletion(org.mockito.ArgumentMatchers.any());
        } finally {
            controller.destroy();
        }
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

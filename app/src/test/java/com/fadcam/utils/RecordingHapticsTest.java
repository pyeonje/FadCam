package com.fadcam.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class RecordingHapticsTest {
    @Test public void successAndStopHaveOneConfirmationEachDespiteRepeatedCallbacks() {
        AtomicInteger starts = new AtomicInteger(), stops = new AtomicInteger();
        RecordingHaptics feedback = new RecordingHaptics(ctx -> starts.incrementAndGet(), ctx -> stops.incrementAndGet());
        feedback.beginSession(); // Accepting STARTING is not a successful start.
        assertEquals(0, starts.get());
        feedback.onRecordingStarted(null);
        feedback.onRecordingStarted(null); // Session reconfiguration / repeated STARTED.
        assertEquals(1, starts.get());
        feedback.onRecordingStopped(null);
        feedback.onRecordingStopped(null); // STOPPED followed by onDestroy.
        feedback.onRecordingStarted(null); // Late callback from the ended session.
        assertEquals(1, starts.get());
        assertEquals(1, stops.get());
    }

    @Test public void failedOrCancelledStartupDoesNotPretendARecordingStartedOrEnded() {
        AtomicInteger events = new AtomicInteger();
        RecordingHaptics feedback = new RecordingHaptics(ctx -> events.incrementAndGet(), ctx -> events.incrementAndGet());
        feedback.onRecordingStopped(null); // An idle state-query service is destroyed.
        feedback.beginSession();
        feedback.onRecordingStopped(null); // Cancellation/error before encoder success.
        feedback.onRecordingStarted(null); // Ignore a late success callback after cleanup.
        assertEquals(0, events.get());
    }

    @Test public void anotherSessionCanConfirmStartAndStopAgain() {
        AtomicInteger starts = new AtomicInteger(), stops = new AtomicInteger();
        RecordingHaptics feedback = new RecordingHaptics(ctx -> starts.incrementAndGet(), ctx -> stops.incrementAndGet());
        for (int session = 0; session < 2; session++) {
            feedback.beginSession();
            feedback.onRecordingStarted(null);
            feedback.onRecordingStopped(null);
        }
        assertEquals(2, starts.get());
        assertEquals(2, stops.get());
    }

    @Test public void concurrentSuccessAndCleanupCallbacksAreDeduplicated() throws Exception {
        AtomicInteger starts = new AtomicInteger(), stops = new AtomicInteger();
        RecordingHaptics feedback = new RecordingHaptics(ctx -> starts.incrementAndGet(), ctx -> stops.incrementAndGet());
        feedback.beginSession();
        ExecutorService workers = Executors.newFixedThreadPool(8);
        for (int i = 0; i < 100; i++) workers.submit(() -> feedback.onRecordingStarted(null));
        workers.shutdown();
        assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        workers = Executors.newFixedThreadPool(8);
        for (int i = 0; i < 100; i++) workers.submit(() -> feedback.onRecordingStopped(null));
        workers.shutdown();
        assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(1, starts.get());
        assertEquals(1, stops.get());
    }
}

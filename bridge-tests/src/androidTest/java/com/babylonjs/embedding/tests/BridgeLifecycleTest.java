package com.babylonjs.embedding.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.babylonjs.embedding.BabylonNative;
import com.babylonjs.embedding.JsBridge;
import com.babylonjs.embedding.JsException;
import com.babylonjs.embedding.JsObject;
import com.babylonjs.embedding.JsValue;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RunWith(AndroidJUnit4.class)
public final class BridgeLifecycleTest {
    private static final int BRIDGE_CREATE_RACE_ROUNDS = 500;
    private static final int CONCURRENT_BRIDGE_CREATORS = 8;
    private static final int REQUEST_ADMISSION_RACE_ROUNDS = 100;

    @Test
    public void preAttachWorkCompletesAfterResize() throws Exception {
        try (BridgeTestHarness harness = new BridgeTestHarness()) {
            harness.createUnattachedRuntime();
            JsBridge bridge = harness.createBridge();
            CompletableFuture<Void> write =
                    bridge.setPropertyAsync("preAttach", JsValue.of("queued"));
            assertFalse(write.isDone());
            harness.attachAndResize();
            BridgeTestHarness.await(write);
            assertEquals(
                    "queued",
                    BridgeTestHarness.await(
                            bridge.getPropertyAsync("preAttach")).asString());
        }
    }

    @Test
    public void neverAttachedDestroyFailsPendingWork() throws Exception {
        try (BridgeTestHarness harness = new BridgeTestHarness()) {
            harness.createUnattachedRuntime();
            JsBridge bridge = harness.createBridge();
            CompletableFuture<JsValue> pending = bridge.getPropertyAsync("never");
            harness.activity().runOnUiThread(harness.activity()::destroyRuntime);
            assertLifecycleFailure(pending);
        }
    }

    @Test
    public void suspendedWorkCompletesAfterResume() throws Exception {
        try (BridgeTestHarness harness = readyHarness()) {
            JsBridge bridge = harness.createBridge();
            harness.suspendRuntime();
            CompletableFuture<Void> pending =
                    bridge.setPropertyAsync("suspendedValue", JsValue.of(true));
            assertFalse(pending.isDone());
            harness.resumeRuntime();
            BridgeTestHarness.await(pending);
        }
    }

    @Test
    public void suspendedDestroyFailsPendingWithoutResume() throws Exception {
        try (BridgeTestHarness harness = readyHarness()) {
            JsBridge bridge = harness.createBridge();
            harness.suspendRuntime();
            CompletableFuture<JsValue> pending =
                    bridge.getPropertyAsync("fixtureOrderedValue");
            harness.stopRuntime();
            assertLifecycleFailure(pending);
        }
    }

    @Test
    public void detachReattachPreservesObjects() throws Exception {
        try (BridgeTestHarness harness = readyHarness()) {
            JsBridge bridge = harness.createBridge();
            JsObject object = BridgeTestHarness.await(bridge.createObjectAsync());
            BridgeTestHarness.await(
                    bridge.setPropertyAsync(object, "state", JsValue.of("preserved")));
            harness.detach();
            harness.attachAndResize();
            assertEquals(
                    "preserved",
                    BridgeTestHarness.await(
                            bridge.getPropertyAsync(object, "state")).asString());
        }
    }

    @Test
    public void cancelBeforeInitializationIsTerminal() throws Exception {
        try (BridgeTestHarness harness = new BridgeTestHarness()) {
            harness.createUnattachedRuntime();
            JsBridge bridge = harness.createBridge();
            CompletableFuture<JsObject> pending = bridge.createObjectAsync();
            assertTrue(pending.cancel(false));
            assertTrue(pending.isCancelled());
            harness.attachAndResize();
            try {
                BridgeTestHarness.await(pending);
                fail("Expected cancellation");
            } catch (CancellationException expected) {
            }
        }
    }

    @Test
    public void completionCanReleaseWithoutDeadlock() throws Exception {
        try (BridgeTestHarness harness = readyHarness()) {
            JsBridge bridge = harness.createBridge();
            CompletableFuture<Void> released = bridge.createObjectAsync()
                    .thenCompose(JsObject::releaseAsync);
            BridgeTestHarness.await(released);
        }
    }

    @Test
    public void concurrentBridgeCreateAndRuntimeDestroyIsSafe() throws Exception {
        ExecutorService executor =
                Executors.newFixedThreadPool(CONCURRENT_BRIDGE_CREATORS + 1);
        try {
            for (int round = 0; round < BRIDGE_CREATE_RACE_ROUNDS; round++) {
                long runtimeHandle = BabylonNative.runtimeCreate();
                CyclicBarrier start = new CyclicBarrier(CONCURRENT_BRIDGE_CREATORS + 1);
                List<CompletableFuture<JsBridge>> creating = new ArrayList<>();
                for (int creator = 0; creator < CONCURRENT_BRIDGE_CREATORS; creator++) {
                    creating.add(CompletableFuture.supplyAsync(
                            () -> {
                                awaitBarrier(start);
                                return new JsBridge(runtimeHandle);
                            },
                            executor));
                }
                CompletableFuture<Void> destroying = CompletableFuture.runAsync(
                        () -> {
                            awaitBarrier(start);
                            BabylonNative.runtimeDestroy(runtimeHandle);
                        },
                        executor);

                List<JsBridge> bridges = new ArrayList<>();
                for (CompletableFuture<JsBridge> created : creating) {
                    try {
                        bridges.add(BridgeTestHarness.await(created));
                    } catch (ExecutionException error) {
                        assertTrue(error.getCause() instanceof IllegalStateException);
                    }
                }
                BridgeTestHarness.await(destroying);
                for (JsBridge bridge : bridges) {
                    assertTerminalLifecycleFailure(bridge.getPropertyAsync("afterDestroy"));
                    bridge.close();
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void concurrentRequestAdmissionAndRuntimeDestroyIsTerminal() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < REQUEST_ADMISSION_RACE_ROUNDS; round++) {
                long runtimeHandle = BabylonNative.runtimeCreate();
                JsBridge bridge = new JsBridge(runtimeHandle);
                CyclicBarrier start = new CyclicBarrier(2);
                CompletableFuture<CompletableFuture<JsValue>> submitting =
                        CompletableFuture.supplyAsync(
                                () -> {
                                    awaitBarrier(start);
                                    return bridge.getPropertyAsync("duringDestroy");
                                },
                                executor);
                CompletableFuture<Void> destroying = CompletableFuture.runAsync(
                        () -> {
                            awaitBarrier(start);
                            BabylonNative.runtimeDestroy(runtimeHandle);
                        },
                        executor);

                CompletableFuture<JsValue> request = BridgeTestHarness.await(submitting);
                BridgeTestHarness.await(destroying);
                assertTerminalLifecycleFailure(request);
                bridge.close();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static void assertLifecycleFailure(CompletableFuture<?> future)
            throws Exception {
        try {
            BridgeTestHarness.await(future);
            fail("Expected lifecycle failure");
        } catch (ExecutionException error) {
            assertTrue(error.getCause() instanceof JsException);
            assertEquals("lifecycle", ((JsException) error.getCause()).getOperation());
        }
    }

    private static void assertTerminalLifecycleFailure(CompletableFuture<?> future)
            throws Exception {
        try {
            BridgeTestHarness.await(future);
            fail("Expected lifecycle failure");
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof JsException) {
                assertEquals("lifecycle", ((JsException) cause).getOperation());
            } else {
                assertTrue(cause instanceof IllegalStateException);
                assertEquals("JsBridge is closed", cause.getMessage());
            }
        }
        assertTrue(future.isDone());
    }

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception error) {
            throw new IllegalStateException("Unable to synchronize lifecycle race", error);
        }
    }

    private static BridgeTestHarness readyHarness() throws Exception {
        BridgeTestHarness harness = new BridgeTestHarness();
        harness.startRuntime();
        harness.loadFixtureAndEvalMarker("BRIDGE_TEST_LIFECYCLE_READY");
        return harness;
    }
}

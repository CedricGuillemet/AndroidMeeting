package com.babylonjs.embedding.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.babylonjs.embedding.JsBridge;
import com.babylonjs.embedding.JsException;
import com.babylonjs.embedding.JsObject;
import com.babylonjs.embedding.JsValue;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

@RunWith(AndroidJUnit4.class)
public final class BridgeErrorsTest {
    @Test
    public void missingAndNonCallableFailAndRecover() throws Exception {
        try (BridgeTestHarness harness = readyHarness()) {
            JsBridge bridge = harness.createBridge();
            assertFails(bridge.callPropertyAsync("definitelyMissing"), "call");
            BridgeTestHarness.await(
                    bridge.setPropertyAsync("notCallable", JsValue.of(1.0)));
            assertFails(bridge.callPropertyAsync("notCallable"), "call");
            assertRecovery(bridge);
        }
    }

    @Test
    public void getterSetterAndMethodErrorsReachJava() throws Exception {
        try (BridgeTestHarness harness = readyHarness()) {
            JsBridge bridge = harness.createBridge();
            JsException getter =
                    assertFails(bridge.getPropertyAsync("bridgeThrowingGetter"), "get");
            assertEquals("bridgeThrowingGetter", getter.getPropertyKey());
            assertFails(
                    bridge.setPropertyAsync("bridgeThrowingSetter", JsValue.of(1.0)), "set");
            JsObject receiver = BridgeTestHarness.await(
                    bridge.getPropertyAsync("bridgeMethodObject")).asObject();
            JsException method =
                    assertFails(bridge.callPropertyAsync(receiver, "throwError"), "call");
            assertEquals("throwError", method.getPropertyKey());
            assertRecovery(bridge);
        }
    }

    @Test
    public void nonErrorValuesAndDiagnosticFailureDoNotPoisonQueue() throws Exception {
        try (BridgeTestHarness harness = readyHarness()) {
            JsBridge bridge = harness.createBridge();
            JsObject receiver = BridgeTestHarness.await(
                    bridge.getPropertyAsync("bridgeMethodObject")).asObject();
            for (String method : new String[]{
                    "throwString", "throwNumber", "throwNull", "throwObject",
                    "throwBadDiagnostic"}) {
                assertFails(bridge.callPropertyAsync(receiver, method), "call");
                assertRecovery(bridge);
            }
        }
    }

    @Test
    public void rejectedAssignmentsFailAndRecover() throws Exception {
        try (BridgeTestHarness harness = readyHarness()) {
            JsBridge bridge = harness.createBridge();
            assertFails(bridge.setPropertyAsync("bridgeReadOnly", JsValue.of(2.0)), "set");
            JsObject proxy = BridgeTestHarness.await(
                    bridge.getPropertyAsync("bridgeRejectingProxy")).asObject();
            assertFails(bridge.setPropertyAsync(proxy, "value", JsValue.of(1.0)), "set");
            assertRecovery(bridge);
        }
    }

    private static JsException assertFails(
            CompletableFuture<?> future, String operation) throws Exception {
        try {
            BridgeTestHarness.await(future);
            fail("Expected " + operation + " operation to fail");
            throw new AssertionError();
        } catch (ExecutionException error) {
            assertTrue(error.getCause() instanceof JsException);
            JsException jsError = (JsException) error.getCause();
            assertEquals(operation, jsError.getOperation());
            return jsError;
        }
    }

    private static void assertRecovery(JsBridge bridge) throws Exception {
        assertEquals(
                "loaded-before-bridge",
                BridgeTestHarness.await(
                        bridge.getPropertyAsync("fixtureOrderedValue")).asString());
    }

    private static BridgeTestHarness readyHarness() throws Exception {
        BridgeTestHarness harness = new BridgeTestHarness();
        harness.startRuntime();
        harness.loadFixtureAndEvalMarker("BRIDGE_TEST_ERRORS_READY");
        return harness;
    }
}

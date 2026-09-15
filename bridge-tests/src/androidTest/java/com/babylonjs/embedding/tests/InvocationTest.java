package com.babylonjs.embedding.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.babylonjs.embedding.JsBridge;
import com.babylonjs.embedding.JsObject;
import com.babylonjs.embedding.JsValue;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class InvocationTest {
    @Test
    public void globalCallSupportsZeroAndMultipleArguments() throws Exception {
        try (BridgeTestHarness harness = readyHarness()) {
            JsBridge bridge = harness.createBridge();
            JsObject zero = BridgeTestHarness.await(
                    bridge.callPropertyAsync("bridgeGlobalCall")).asObject();
            assertEquals(
                    0.0,
                    BridgeTestHarness.await(bridge.getPropertyAsync(zero, "count")).asNumber(),
                    0.0);
            JsObject multiple = BridgeTestHarness.await(
                    bridge.callPropertyAsync(
                            "bridgeGlobalCall",
                            JsValue.of("one"),
                            JsValue.of(2.0),
                            JsValue.of(true))).asObject();
            assertEquals(
                    "one|2|true",
                    BridgeTestHarness.await(
                            bridge.getPropertyAsync(multiple, "joined")).asString());
        }
    }

    @Test
    public void receiverBindingIsPreserved() throws Exception {
        try (BridgeTestHarness harness = readyHarness()) {
            JsBridge bridge = harness.createBridge();
            JsObject object = BridgeTestHarness.await(
                    bridge.getPropertyAsync("bridgeMethodObject")).asObject();
            assertEquals(
                    13.0,
                    BridgeTestHarness.await(
                            bridge.callPropertyAsync(
                                    object, "multiply", JsValue.of(3.0), JsValue.of(1.0)))
                            .asNumber(),
                    0.0);
            JsObject globalResult = BridgeTestHarness.await(
                    bridge.callPropertyAsync("bridgeGlobalCall")).asObject();
            assertTrue(BridgeTestHarness.await(
                    bridge.getPropertyAsync(globalResult, "receiverIsGlobal")).asBoolean());
        }
    }

    @Test
    public void objectArgumentsAndReturnsRemainLive() throws Exception {
        try (BridgeTestHarness harness = readyHarness()) {
            JsBridge bridge = harness.createBridge();
            JsObject receiver = BridgeTestHarness.await(
                    bridge.getPropertyAsync("bridgeMethodObject")).asObject();
            JsObject argument = BridgeTestHarness.await(bridge.createObjectAsync());
            JsObject returned = BridgeTestHarness.await(
                    bridge.callPropertyAsync(
                            receiver, "returnArgument", JsValue.of(argument))).asObject();
            assertTrue(BridgeTestHarness.await(
                    bridge.getPropertyAsync(argument, "changedByCall")).asBoolean());
            BridgeTestHarness.await(
                    bridge.setPropertyAsync(returned, "throughAlias", JsValue.of("yes")));
            assertEquals(
                    "yes",
                    BridgeTestHarness.await(
                            bridge.getPropertyAsync(argument, "throughAlias")).asString());
        }
    }

    @Test
    public void replacingMethodChangesNextCall() throws Exception {
        try (BridgeTestHarness harness = readyHarness()) {
            JsBridge bridge = harness.createBridge();
            JsObject receiver = BridgeTestHarness.await(
                    bridge.getPropertyAsync("bridgeMethodObject")).asObject();
            assertEquals(
                    "first",
                    BridgeTestHarness.await(
                            bridge.callPropertyAsync(receiver, "replaceable")).asString());
            JsObject replacement = BridgeTestHarness.await(
                    bridge.getPropertyAsync("bridgeGlobalCall")).asObject();
            BridgeTestHarness.await(
                    bridge.setPropertyAsync(receiver, "replaceable", JsValue.of(replacement)));
            assertEquals(
                    JsValue.Kind.OBJECT,
                    BridgeTestHarness.await(
                            bridge.callPropertyAsync(receiver, "replaceable")).getKind());
        }
    }

    @Test
    public void promiseReturnIsImmediateObjectHandle() throws Exception {
        try (BridgeTestHarness harness = readyHarness()) {
            JsBridge bridge = harness.createBridge();
            JsObject receiver = BridgeTestHarness.await(
                    bridge.getPropertyAsync("bridgeMethodObject")).asObject();
            JsValue promise = BridgeTestHarness.await(
                    bridge.callPropertyAsync(receiver, "neverSettlingPromise"));
            assertEquals(JsValue.Kind.OBJECT, promise.getKind());
        }
    }

    private static BridgeTestHarness readyHarness() throws Exception {
        BridgeTestHarness harness = new BridgeTestHarness();
        harness.startRuntime();
        harness.loadFixtureAndEvalMarker("BRIDGE_TEST_INVOCATION_READY");
        return harness;
    }
}

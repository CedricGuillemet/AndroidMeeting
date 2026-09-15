package com.babylonjs.embedding.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
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
public final class ObjectPropertiesTest {
    @Test
    public void createPopulatePublishRetrieve() throws Exception {
        try (BridgeTestHarness harness = readyHarness()) {
            JsBridge bridge = harness.createBridge();
            JsObject created = BridgeTestHarness.await(bridge.createObjectAsync());
            BridgeTestHarness.await(
                    bridge.setPropertyAsync(created, "answer", JsValue.of(42.5)));
            BridgeTestHarness.await(
                    bridge.setPropertyAsync("publishedObject", JsValue.of(created)));
            JsObject retrieved = BridgeTestHarness.await(
                    bridge.getPropertyAsync("publishedObject")).asObject();
            assertEquals(
                    42.5,
                    BridgeTestHarness.await(
                            bridge.getPropertyAsync(retrieved, "answer")).asNumber(),
                    0.0);
            BridgeTestHarness.await(created.releaseAsync());
            BridgeTestHarness.await(retrieved.releaseAsync());
        }
    }

    @Test
    public void nestedObjectsRemainLive() throws Exception {
        try (BridgeTestHarness harness = readyHarness()) {
            JsBridge bridge = harness.createBridge();
            JsObject root = BridgeTestHarness.await(
                    bridge.getPropertyAsync("bridgeRoot")).asObject();
            JsObject child = BridgeTestHarness.await(
                    bridge.getPropertyAsync(root, "child")).asObject();
            BridgeTestHarness.await(
                    bridge.setPropertyAsync(child, "value", JsValue.of(99.0)));
            JsObject alias = BridgeTestHarness.await(
                    bridge.getPropertyAsync("bridgeAlias")).asObject();
            assertEquals(
                    99.0,
                    BridgeTestHarness.await(bridge.getPropertyAsync(alias, "value")).asNumber(),
                    0.0);
        }
    }

    @Test
    public void aliasesHaveIndependentHandles() throws Exception {
        try (BridgeTestHarness harness = readyHarness()) {
            JsBridge bridge = harness.createBridge();
            JsObject first = BridgeTestHarness.await(
                    bridge.getPropertyAsync("bridgeAlias")).asObject();
            JsObject second = BridgeTestHarness.await(
                    bridge.getPropertyAsync("bridgeAlias")).asObject();
            assertNotSame(first, second);
            BridgeTestHarness.await(first.releaseAsync());
            assertEquals(
                    10.0,
                    BridgeTestHarness.await(bridge.getPropertyAsync(second, "value")).asNumber(),
                    0.0);
        }
    }

    @Test
    public void nullAndOmittedTargetsUseGlobalButForeignTargetFails() throws Exception {
        try (BridgeTestHarness harness = readyHarness()) {
            JsBridge firstBridge = harness.createBridge();
            JsBridge secondBridge = new JsBridge(harness.activity().getRuntimeHandle());
            assertEquals(
                    "loaded-before-bridge",
                    BridgeTestHarness.await(
                            firstBridge.getPropertyAsync(null, "fixtureOrderedValue")).asString());
            JsObject foreign = BridgeTestHarness.await(secondBridge.createObjectAsync());
            try {
                firstBridge.getPropertyAsync(foreign, "value");
                fail("Expected foreign handle rejection");
            } catch (IllegalArgumentException expected) {
            }
            secondBridge.close();
        }
    }

    @Test
    public void handleRetainsObjectAfterRootRemoval() throws Exception {
        try (BridgeTestHarness harness = readyHarness()) {
            JsBridge bridge = harness.createBridge();
            JsObject root = BridgeTestHarness.await(
                    bridge.getPropertyAsync("bridgeRoot")).asObject();
            JsObject retained = BridgeTestHarness.await(
                    bridge.getPropertyAsync(root, "retained")).asObject();
            BridgeTestHarness.await(
                    bridge.setPropertyAsync("bridgeRoot", JsValue.undefined()));
            BridgeTestHarness.await(root.releaseAsync());
            assertEquals(
                    "still-live",
                    BridgeTestHarness.await(
                            bridge.getPropertyAsync(retained, "value")).asString());
        }
    }

    @Test
    public void releaseIsIdempotentAndRejectsNewUse() throws Exception {
        try (BridgeTestHarness harness = readyHarness()) {
            JsBridge bridge = harness.createBridge();
            JsObject object = BridgeTestHarness.await(bridge.createObjectAsync());
            BridgeTestHarness.await(object.releaseAsync());
            BridgeTestHarness.await(object.releaseAsync());
            try {
                bridge.getPropertyAsync(object, "value");
                fail("Expected released handle rejection");
            } catch (IllegalStateException expected) {
            }
        }
    }

    @Test
    public void previouslyAdmittedOperationSurvivesRelease() throws Exception {
        try (BridgeTestHarness harness = readyHarness()) {
            JsBridge bridge = harness.createBridge();
            JsObject object = BridgeTestHarness.await(bridge.createObjectAsync());
            CompletableFuture<Void> write =
                    bridge.setPropertyAsync(object, "beforeRelease", JsValue.of(true));
            CompletableFuture<Void> release = object.releaseAsync();
            BridgeTestHarness.await(write);
            BridgeTestHarness.await(release);
        }
    }

    @Test
    public void destructionWithLiveHandlesDoesNotLeavePendingRelease() throws Exception {
        try (BridgeTestHarness harness = readyHarness()) {
            JsBridge bridge = harness.createBridge();
            JsObject object = BridgeTestHarness.await(bridge.createObjectAsync());
            harness.stopRuntime();
            BridgeTestHarness.await(object.releaseAsync());
        }
    }

    @Test
    public void unsupportedValuesFailExplicitly() throws Exception {
        try (BridgeTestHarness harness = readyHarness()) {
            JsBridge bridge = harness.createBridge();
            assertUnsupported(bridge, "bridgeSymbol");
            JsValue maybeBigInt = null;
            try {
                maybeBigInt = BridgeTestHarness.await(
                        bridge.getPropertyAsync("bridgeBigInt"));
            } catch (ExecutionException error) {
                assertTrue(error.getCause() instanceof JsException);
            }
            if (maybeBigInt != null) {
                assertEquals(JsValue.Kind.UNDEFINED, maybeBigInt.getKind());
            }
        }
    }

    private static void assertUnsupported(JsBridge bridge, String key) throws Exception {
        try {
            BridgeTestHarness.await(bridge.getPropertyAsync(key));
            fail("Expected unsupported value failure");
        } catch (ExecutionException error) {
            assertTrue(error.getCause() instanceof JsException);
        }
    }

    private static BridgeTestHarness readyHarness() throws Exception {
        BridgeTestHarness harness = new BridgeTestHarness();
        harness.startRuntime();
        harness.loadFixtureAndEvalMarker("BRIDGE_TEST_OBJECTS_READY");
        return harness;
    }
}

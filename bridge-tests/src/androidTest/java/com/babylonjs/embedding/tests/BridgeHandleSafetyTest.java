package com.babylonjs.embedding.tests;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.babylonjs.embedding.BabylonNative;
import com.babylonjs.embedding.JsBridge;
import com.babylonjs.embedding.JsObject;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class BridgeHandleSafetyTest {
    @Test
    public void foreignRuntimeObjectIsRejected() throws Exception {
        try (BridgeTestHarness harness = readyHarness()) {
            JsBridge first = harness.createBridge();
            JsObject object = BridgeTestHarness.await(first.createObjectAsync());
            AtomicLong otherRuntime = new AtomicLong();
            AtomicReference<JsBridge> otherBridge = new AtomicReference<>();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                otherRuntime.set(BabylonNative.runtimeCreate());
                otherBridge.set(new JsBridge(otherRuntime.get()));
            });
            JsBridge other = otherBridge.get();
            try {
                other.getPropertyAsync(object, "value");
                fail("Expected cross-runtime rejection");
            } catch (IllegalArgumentException expected) {
            } finally {
                other.close();
                InstrumentationRegistry.getInstrumentation().runOnMainSync(
                        () -> BabylonNative.runtimeDestroy(otherRuntime.get()));
            }
        }
    }

    @Test
    public void staleObjectAfterRuntimeDestroyIsRejected() throws Exception {
        JsObject stale;
        try (BridgeTestHarness harness = readyHarness()) {
            JsBridge bridge = harness.createBridge();
            stale = BridgeTestHarness.await(bridge.createObjectAsync());
            harness.stopRuntime();
            try {
                bridge.getPropertyAsync(stale, "value");
                fail("Expected closed bridge rejection");
            } catch (IllegalStateException expected) {
            }
        }
        BridgeTestHarness.await(stale.releaseAsync());
    }

    @Test
    public void repeatedCreateReleaseAndShutdown() throws Exception {
        try (BridgeTestHarness harness = readyHarness()) {
            JsBridge bridge = harness.createBridge();
            for (int i = 0; i < 100; i++) {
                JsObject object = BridgeTestHarness.await(bridge.createObjectAsync());
                BridgeTestHarness.await(object.releaseAsync());
            }
            assertTrue(true);
        }
    }

    private static BridgeTestHarness readyHarness() throws Exception {
        BridgeTestHarness harness = new BridgeTestHarness();
        harness.startRuntime();
        harness.loadFixtureAndEvalMarker("BRIDGE_TEST_HANDLE_READY");
        return harness;
    }
}

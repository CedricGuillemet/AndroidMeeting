package com.babylonjs.embedding.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.os.Looper;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.babylonjs.embedding.JsBridge;
import com.babylonjs.embedding.JsException;
import com.babylonjs.embedding.JsValue;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

@RunWith(AndroidJUnit4.class)
public final class GlobalPropertiesTest {
    @Test
    public void primitiveRoundTrips() throws Exception {
        try (BridgeTestHarness harness = readyHarness()) {
            JsBridge bridge = harness.createBridge();
            assertRoundTrip(bridge, "boolean", JsValue.of(true));
            assertRoundTrip(bridge, "fraction", JsValue.of(12.75));
            assertRoundTrip(bridge, "negative", JsValue.of(-81.5));
            assertRoundTrip(bridge, "nan", JsValue.of(Double.NaN));
            assertRoundTrip(bridge, "positiveInfinity", JsValue.of(Double.POSITIVE_INFINITY));
            assertRoundTrip(bridge, "negativeInfinity", JsValue.of(Double.NEGATIVE_INFINITY));
            JsValue negativeZero = roundTrip(bridge, "negativeZero", JsValue.of(-0.0d));
            assertEquals(
                    Double.doubleToRawLongBits(-0.0d),
                    Double.doubleToRawLongBits(negativeZero.asNumber()));
        }
    }

    @Test
    public void missingIsUndefinedAndNullIsDistinct() throws Exception {
        try (BridgeTestHarness harness = readyHarness()) {
            JsBridge bridge = harness.createBridge();
            assertEquals(
                    JsValue.Kind.UNDEFINED,
                    BridgeTestHarness.await(bridge.getPropertyAsync("definitelyMissing")).getKind());
            BridgeTestHarness.await(
                    bridge.setPropertyAsync("explicitNull", JsValue.nullValue()));
            assertEquals(
                    JsValue.Kind.NULL,
                    BridgeTestHarness.await(bridge.getPropertyAsync("explicitNull")).getKind());
        }
    }

    @Test
    public void literalKeysAndUnicodeRoundTrip() throws Exception {
        try (BridgeTestHarness harness = readyHarness()) {
            JsBridge bridge = harness.createBridge();
            assertRoundTrip(bridge, "", JsValue.of(""));
            assertRoundTrip(bridge, "literal.key", JsValue.of("not a path"));
            assertRoundTrip(
                    bridge,
                    "nul\u0000key\uD83D\uDE80",
                    JsValue.of("value\u0000with supplementary \uD83D\uDE80"));
        }
    }

    @Test
    public void loadThenReadIsOrdered() throws Exception {
        try (BridgeTestHarness harness = readyHarness()) {
            JsBridge bridge = harness.createBridge();
            assertEquals(
                    "loaded-before-bridge",
                    BridgeTestHarness.await(
                            bridge.getPropertyAsync("fixtureOrderedValue")).asString());
        }
    }

    @Test
    public void consecutiveWritesReadInOrder() throws Exception {
        try (BridgeTestHarness harness = readyHarness()) {
            JsBridge bridge = harness.createBridge();
            CompletableFuture<Void> first =
                    bridge.setPropertyAsync("orderedWrite", JsValue.of(1.0));
            CompletableFuture<Void> second =
                    bridge.setPropertyAsync("orderedWrite", JsValue.of(2.0));
            CompletableFuture<JsValue> read = bridge.getPropertyAsync("orderedWrite");
            BridgeTestHarness.await(first);
            BridgeTestHarness.await(second);
            assertEquals(2.0, BridgeTestHarness.await(read).asNumber(), 0.0);
        }
    }

    @Test
    public void setterFailureReachesJavaAndQueueRecovers() throws Exception {
        try (BridgeTestHarness harness = readyHarness()) {
            JsBridge bridge = harness.createBridge();
            try {
                BridgeTestHarness.await(
                        bridge.setPropertyAsync("bridgeThrowingSetter", JsValue.of(1.0)));
                fail("Expected setter failure");
            } catch (ExecutionException error) {
                assertTrue(error.getCause() instanceof JsException);
                assertEquals("set", ((JsException) error.getCause()).getOperation());
                assertEquals(
                        "bridgeThrowingSetter",
                        ((JsException) error.getCause()).getPropertyKey());
            }
            assertEquals(
                    "loaded-before-bridge",
                    BridgeTestHarness.await(
                            bridge.getPropertyAsync("fixtureOrderedValue")).asString());
        }
    }

    @Test
    public void completionRunsOnMainLooper() throws Exception {
        try (BridgeTestHarness harness = readyHarness()) {
            JsBridge bridge = harness.createBridge();
            CompletableFuture<Boolean> isMain = new CompletableFuture<>();
            bridge.getPropertyAsync("fixtureOrderedValue").thenRun(
                    () -> isMain.complete(Looper.myLooper() == Looper.getMainLooper()));
            assertTrue(BridgeTestHarness.await(isMain));
        }
    }

    @Test
    public void destroyedBeforeInitializationSettlesRequest() throws Exception {
        try (BridgeTestHarness harness = new BridgeTestHarness()) {
            harness.createUnattachedRuntime();
            JsBridge bridge = harness.createBridge();
            CompletableFuture<JsValue> pending = bridge.getPropertyAsync("neverRuns");
            harness.activity().runOnUiThread(() -> {
                bridge.close();
                harness.activity().destroyRuntime();
            });
            try {
                BridgeTestHarness.await(pending);
                fail("Expected runtime destruction to reject the pending request");
            } catch (ExecutionException error) {
                assertTrue(error.getCause() instanceof JsException);
            }
        }
    }

    private static BridgeTestHarness readyHarness() throws Exception {
        BridgeTestHarness harness = new BridgeTestHarness();
        harness.startRuntime();
        harness.loadFixtureAndEvalMarker("BRIDGE_TEST_GLOBALS_READY");
        return harness;
    }

    private static void assertRoundTrip(JsBridge bridge, String key, JsValue input)
            throws Exception {
        JsValue output = roundTrip(bridge, key, input);
        assertEquals(input.getKind(), output.getKind());
        switch (input.getKind()) {
            case BOOLEAN:
                assertEquals(input.asBoolean(), output.asBoolean());
                break;
            case NUMBER:
                if (Double.isNaN(input.asNumber())) {
                    assertTrue(Double.isNaN(output.asNumber()));
                } else {
                    assertEquals(input.asNumber(), output.asNumber(), 0.0);
                }
                break;
            case STRING:
                assertEquals(input.asString(), output.asString());
                break;
            default:
                assertFalse("Unexpected round-trip kind", true);
        }
    }

    private static JsValue roundTrip(JsBridge bridge, String key, JsValue input)
            throws Exception {
        BridgeTestHarness.await(bridge.setPropertyAsync(key, input));
        return BridgeTestHarness.await(bridge.getPropertyAsync(key));
    }
}

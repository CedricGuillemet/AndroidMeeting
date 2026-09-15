package com.babylonjs.embedding.tests;

import static org.junit.Assert.assertNotEquals;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class RuntimeSmokeTest {
    @Test
    public void attachedResizedRuntimeExecutesFixture() throws Exception {
        try (BridgeTestHarness harness = new BridgeTestHarness()) {
            assertNotEquals(0, harness.startRuntime());
            harness.loadFixtureAndEvalMarker("BRIDGE_TEST_EVAL_READY");
            harness.awaitLogcat("BRIDGE_TEST_FIXTURE_READY");
        }
    }

    @Test
    public void legacyEvalStillExecutes() throws Exception {
        try (BridgeTestHarness harness = new BridgeTestHarness()) {
            harness.startRuntime();
            harness.loadFixtureAndEvalMarker("BRIDGE_TEST_LEGACY_EVAL");
            harness.awaitLogcat("BRIDGE_TEST_LEGACY_EVAL");
        }
    }

    @Test
    public void detachThenDestroyCompletes() throws Exception {
        try (BridgeTestHarness harness = new BridgeTestHarness()) {
            harness.startRuntime();
            harness.stopRuntime();
        }
    }
}

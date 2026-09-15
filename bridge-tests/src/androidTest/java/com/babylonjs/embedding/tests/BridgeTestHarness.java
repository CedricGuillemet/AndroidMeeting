package com.babylonjs.embedding.tests;

import android.app.Instrumentation;
import android.os.ParcelFileDescriptor;

import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.babylonjs.embedding.JsBridge;

final class BridgeTestHarness implements AutoCloseable {
    static final long TIMEOUT_SECONDS = 30;

    private final ActivityScenario<BridgeTestActivity> scenario;
    private final AtomicReference<BridgeTestActivity> activity = new AtomicReference<>();

    BridgeTestHarness() throws Exception {
        clearLogcat();
        scenario = ActivityScenario.launch(BridgeTestActivity.class);
        scenario.onActivity(activity::set);
    }

    long startRuntime() throws Exception {
        long handle = createUnattachedRuntime();
        AtomicReference<CompletableFuture<Void>> ready = new AtomicReference<>();
        scenario.onActivity(host -> ready.set(host.attachAndResizeView()));
        await(ready.get());
        return handle;
    }

    long createUnattachedRuntime() {
        AtomicReference<Long> handle = new AtomicReference<>();
        scenario.onActivity(host -> handle.set(host.createRuntime()));
        return handle.get();
    }

    JsBridge createBridge() {
        AtomicReference<JsBridge> bridge = new AtomicReference<>();
        scenario.onActivity(host -> bridge.set(host.createBridge()));
        return bridge.get();
    }

    void loadFixtureAndEvalMarker(String marker) {
        scenario.onActivity(host -> host.loadFixtureAndEvalMarker(marker));
    }

    void attachAndResize() throws Exception {
        AtomicReference<CompletableFuture<Void>> ready = new AtomicReference<>();
        scenario.onActivity(host -> ready.set(host.attachAndResizeView()));
        await(ready.get());
    }

    void detach() throws Exception {
        AtomicReference<CompletableFuture<Void>> detached = new AtomicReference<>();
        scenario.onActivity(host -> detached.set(host.detachView()));
        await(detached.get());
    }

    void suspendRuntime() {
        scenario.onActivity(BridgeTestActivity::suspendRuntime);
    }

    void resumeRuntime() {
        scenario.onActivity(BridgeTestActivity::resumeRuntime);
    }

    BridgeTestActivity activity() {
        return activity.get();
    }

    void awaitLogcat(String marker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        do {
            if (readLogcat().contains(marker)) {
                return;
            }
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Timed out waiting for logcat marker: " + marker);
    }

    void stopRuntime() throws Exception {
        detach();
        scenario.onActivity(BridgeTestActivity::destroyRuntime);
    }

    @Override
    public void close() throws Exception {
        try {
            if (activity.get() != null && activity.get().getRuntimeHandle() != 0) {
                stopRuntime();
            }
        } finally {
            scenario.close();
        }
    }

    static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static void clearLogcat() throws Exception {
        executeShell("logcat -c");
    }

    private static String readLogcat() throws Exception {
        return executeShell("logcat -d -v brief");
    }

    private static String executeShell(String command) throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        try (ParcelFileDescriptor descriptor =
                     instrumentation.getUiAutomation().executeShellCommand(command);
             FileInputStream input = new FileInputStream(descriptor.getFileDescriptor())) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
}

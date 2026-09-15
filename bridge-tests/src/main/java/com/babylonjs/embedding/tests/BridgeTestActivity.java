package com.babylonjs.embedding.tests;

import android.app.Activity;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;

import com.babylonjs.embedding.BabylonNative;
import com.babylonjs.embedding.JsBridge;
import com.babylonjs.meeting.BabylonView;

import java.util.concurrent.CompletableFuture;

public final class BridgeTestActivity extends Activity {
    private FrameLayout root;
    private volatile long runtimeHandle;
    private BabylonView babylonView;
    private SurfaceView surfaceView;
    private JsBridge bridge;
    private CompletableFuture<Void> surfaceReady = new CompletableFuture<>();
    private CompletableFuture<Void> surfaceDestroyed = new CompletableFuture<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root = new FrameLayout(this);
        BabylonNative.setContext(getApplicationContext());
        BabylonNative.setCurrentActivity(this);
        setContentView(root);
    }

    public long createRuntime() {
        requireMainThread();
        if (runtimeHandle != 0) {
            throw new IllegalStateException("Runtime already created");
        }
        runtimeHandle = BabylonNative.runtimeCreate();
        return runtimeHandle;
    }

    public CompletableFuture<Void> attachAndResizeView() {
        requireMainThread();
        if (runtimeHandle == 0 || babylonView != null) {
            throw new IllegalStateException("Create one runtime before attaching one view");
        }

        surfaceReady = new CompletableFuture<>();
        surfaceDestroyed = new CompletableFuture<>();
        babylonView = new BabylonView(this, runtimeHandle);
        surfaceView = (SurfaceView) babylonView.getChildAt(0);
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                if (width > 0 && height > 0) {
                    surfaceReady.complete(null);
                }
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                surfaceDestroyed.complete(null);
            }
        });
        root.addView(babylonView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        return surfaceReady;
    }

    public void loadFixtureAndEvalMarker(String marker) {
        requireMainThread();
        requireRuntime();
        BabylonNative.runtimeLoadScript(runtimeHandle, "app:///bridge-fixture.js");
        BabylonNative.runtimeEval(
                runtimeHandle,
                "console.log(" + quoteForJavaScript(marker) + ");",
                "app:///bridge-test-marker.js");
    }

    public JsBridge createBridge() {
        requireMainThread();
        requireRuntime();
        if (bridge == null) {
            bridge = new JsBridge(runtimeHandle);
        }
        return bridge;
    }

    public CompletableFuture<Void> detachView() {
        requireMainThread();
        if (babylonView == null) {
            return CompletableFuture.completedFuture(null);
        }
        root.removeView(babylonView);
        babylonView = null;
        surfaceView = null;
        return surfaceDestroyed;
    }

    public void suspendRuntime() {
        requireMainThread();
        BabylonNative.pause();
    }

    public void resumeRuntime() {
        requireMainThread();
        BabylonNative.resume();
    }

    public void destroyRuntime() {
        requireMainThread();
        if (babylonView != null) {
            throw new IllegalStateException("Detach the view before destroying its runtime");
        }
        if (bridge != null) {
            bridge.close();
            bridge = null;
        }
        if (runtimeHandle != 0) {
            BabylonNative.runtimeDestroy(runtimeHandle);
            runtimeHandle = 0;
        }
    }

    public long getRuntimeHandle() {
        return runtimeHandle;
    }

    @Override
    protected void onDestroy() {
        if (babylonView != null) {
            root.removeView(babylonView);
            babylonView = null;
        }
        if (runtimeHandle != 0) {
            if (bridge != null) {
                bridge.close();
                bridge = null;
            }
            BabylonNative.runtimeDestroy(runtimeHandle);
            runtimeHandle = 0;
        }
        BabylonNative.setCurrentActivity(null);
        super.onDestroy();
    }

    private void requireRuntime() {
        if (runtimeHandle == 0) {
            throw new IllegalStateException("Runtime not created");
        }
    }

    private static void requireMainThread() {
        if (!android.os.Looper.getMainLooper().isCurrentThread()) {
            throw new IllegalStateException("Host operations must run on the main looper");
        }
    }

    private static String quoteForJavaScript(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}

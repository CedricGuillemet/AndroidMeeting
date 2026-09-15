package com.babylonjs.embedding;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Opaque live JavaScript object reference owned by one {@link JsBridge}.
 *
 * <p>Every returned wrapper owns an independent native reference. Prefer {@link #releaseAsync()} so
 * callers can observe release completion. Release is idempotent; runtime destruction invalidates
 * all of its outstanding handles.
 */
public final class JsObject implements AutoCloseable {
    private final JsBridge owner;
    private final AtomicLong nativeHandle;

    JsObject(JsBridge owner, long nativeHandle) {
        this.owner = owner;
        this.nativeHandle = new AtomicLong(nativeHandle);
    }

    public CompletableFuture<Void> releaseAsync() {
        long handle = nativeHandle.getAndSet(0);
        if (handle == 0) {
            return CompletableFuture.completedFuture(null);
        }
        return owner.releaseObjectAsync(handle);
    }

    @Override
    public void close() {
        releaseAsync();
    }

    long nativeHandle() {
        long handle = nativeHandle.get();
        if (handle == 0) {
            throw new IllegalStateException("JsObject is released");
        }
        return handle;
    }

    void requireOwner(JsBridge expected) {
        if (owner != expected) {
            throw new IllegalArgumentException("JsObject belongs to another JsBridge");
        }
    }
}

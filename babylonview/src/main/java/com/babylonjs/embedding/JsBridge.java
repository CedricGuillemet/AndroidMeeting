package com.babylonjs.embedding;

import android.os.Handler;
import android.os.Looper;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Asynchronous, typed access to an existing Babylon Native JavaScript runtime.
 *
 * <p>The runtime remains owned by the host. Calls are admitted on the main looper, execute on the
 * runtime's JavaScript thread, and complete on the main looper. Do not block either thread waiting
 * for a returned future.
 */
public final class JsBridge implements AutoCloseable {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicLong nextRequestId = new AtomicLong(1);
    private final ConcurrentHashMap<Long, CompletableFuture<JsValue>> pending =
            new ConcurrentHashMap<>();
    private volatile long nativeHandle;

    public JsBridge(long runtimeHandle) {
        if (runtimeHandle == 0) {
            throw new IllegalArgumentException("runtimeHandle must not be zero");
        }
        nativeHandle = BabylonNative.bridgeCreate(runtimeHandle, this);
        if (nativeHandle == 0) {
            throw new IllegalStateException("Runtime is not live");
        }
    }

    public CompletableFuture<JsValue> getPropertyAsync(String key) {
        return getPropertyAsync(null, key);
    }

    public CompletableFuture<JsObject> createObjectAsync() {
        CompletableFuture<JsValue> source =
                submit("createObject", "", requestId -> BabylonNative.bridgeCreateObject(
                        requireOpenHandle(), requestId));
        return mapFuture(source, JsValue::asObject, true);
    }

    public CompletableFuture<JsValue> getPropertyAsync(JsObject target, String key) {
        Objects.requireNonNull(key, "key");
        long targetHandle = targetHandle(target);
        return submit("get", key, requestId -> BabylonNative.bridgeGet(
                requireOpenHandle(), requestId, targetHandle, key));
    }

    public CompletableFuture<Void> setPropertyAsync(String key, JsValue value) {
        return setPropertyAsync(null, key, value);
    }

    public CompletableFuture<Void> setPropertyAsync(
            JsObject target, String key, JsValue value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        long targetHandle = targetHandle(target);
        int valueKind = value.getKind().ordinal();
        boolean booleanValue = value.booleanPayload();
        double numberValue = value.numberPayload();
        String stringValue = value.stringPayload();
        long objectValue = value.objectPayload();
        CompletableFuture<JsValue> source = submit("set", key, requestId -> BabylonNative.bridgeSet(
                requireOpenHandle(),
                requestId,
                targetHandle,
                key,
                valueKind,
                booleanValue,
                numberValue,
                stringValue,
                objectValue));
        return mapFuture(source, ignored -> null, true);
    }

    public CompletableFuture<JsValue> callPropertyAsync(String key, JsValue... arguments) {
        return callPropertyAsync(null, key, arguments);
    }

    public CompletableFuture<JsValue> callPropertyAsync(
            JsObject target, String key, JsValue... arguments) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(arguments, "arguments");
        long targetHandle = targetHandle(target);
        int count = arguments.length;
        int[] kinds = new int[count];
        boolean[] booleans = new boolean[count];
        double[] numbers = new double[count];
        String[] strings = new String[count];
        long[] objects = new long[count];
        for (int i = 0; i < count; i++) {
            JsValue argument = Objects.requireNonNull(arguments[i], "arguments[" + i + "]");
            kinds[i] = argument.getKind().ordinal();
            booleans[i] = argument.booleanPayload();
            numbers[i] = argument.numberPayload();
            strings[i] = argument.stringPayload();
            objects[i] = argument.objectPayload();
        }
        return submit("call", key, requestId -> BabylonNative.bridgeCall(
                requireOpenHandle(),
                requestId,
                targetHandle,
                key,
                kinds,
                booleans,
                numbers,
                strings,
                objects));
    }

    @Override
    public void close() {
        long handle = nativeHandle;
        nativeHandle = 0;
        if (handle == 0) {
            return;
        }
        runOnMain(() -> BabylonNative.bridgeClose(handle));
    }

    private CompletableFuture<JsValue> submit(
            String operation, String key, NativeSubmission submission) {
        long requestId = nextRequestId.getAndIncrement();
        CompletableFuture<JsValue> future = new CompletableFuture<>();
        pending.put(requestId, future);
        future.whenComplete((value, error) -> {
            if (future.isCancelled() && pending.remove(requestId, future)) {
                runOnMain(() -> {
                    long handle = nativeHandle;
                    if (handle != 0) {
                        BabylonNative.bridgeCancel(handle, requestId);
                    }
                });
            }
        });
        runOnMain(() -> {
            if (!pending.containsKey(requestId)) {
                return;
            }
            try {
                submission.submit(requestId);
            } catch (Throwable error) {
                CompletableFuture<JsValue> request = pending.remove(requestId);
                if (request != null) {
                    request.completeExceptionally(error);
                }
            }
        });
        return future;
    }

    @SuppressWarnings("unused")
    private void acceptNativeResult(
            long requestId,
            int kind,
            boolean booleanValue,
            double numberValue,
            String stringValue,
            long objectHandle) {
        runOnMain(() -> {
            CompletableFuture<JsValue> future = pending.remove(requestId);
            if (future == null) {
                if (kind == JsValue.Kind.OBJECT.ordinal() && objectHandle != 0) {
                    releaseObjectAsync(objectHandle);
                }
                return;
            }
            try {
                JsValue value =
                        fromNative(kind, booleanValue, numberValue, stringValue, objectHandle);
                if (!future.complete(value)
                        && value.getKind() == JsValue.Kind.OBJECT) {
                    value.asObject().releaseAsync();
                }
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        });
    }

    @SuppressWarnings("unused")
    private void acceptNativeError(
            long requestId,
            String operation,
            String key,
            String name,
            String message,
            String stack) {
        runOnMain(() -> {
            CompletableFuture<JsValue> future = pending.remove(requestId);
            if (future != null) {
                future.completeExceptionally(
                        new JsException(operation, key, name, message, stack));
            }
        });
    }

    @SuppressWarnings("unused")
    private void acceptNativeClosed(String message) {
        runOnMain(() -> {
            nativeHandle = 0;
            JsException error =
                    new JsException("lifecycle", "", "AbortError", message, "");
            for (CompletableFuture<JsValue> future : pending.values()) {
                future.completeExceptionally(error);
            }
            pending.clear();
        });
    }

    private JsValue fromNative(
            int kind,
            boolean booleanValue,
            double numberValue,
            String stringValue,
            long objectHandle) {
        JsValue.Kind[] kinds = JsValue.Kind.values();
        if (kind < 0 || kind >= kinds.length) {
            throw new IllegalStateException("Unknown native JsValue kind " + kind);
        }
        switch (kinds[kind]) {
            case UNDEFINED:
                return JsValue.undefined();
            case NULL:
                return JsValue.nullValue();
            case BOOLEAN:
                return JsValue.of(booleanValue);
            case NUMBER:
                return JsValue.of(numberValue);
            case STRING:
                return JsValue.of(Objects.requireNonNull(stringValue, "native string"));
            case OBJECT:
                return JsValue.of(new JsObject(this, objectHandle));
            default:
                throw new IllegalStateException("Unsupported native JsValue kind " + kind);
        }
    }

    private long requireOpenHandle() {
        long handle = nativeHandle;
        if (handle == 0) {
            throw new IllegalStateException("JsBridge is closed");
        }
        return handle;
    }

    private long targetHandle(JsObject target) {
        if (target == null) {
            return 0;
        }
        target.requireOwner(this);
        return target.nativeHandle();
    }

    CompletableFuture<Void> releaseObjectAsync(long objectHandle) {
        if (nativeHandle == 0) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<JsValue> source =
                submit("release", "", requestId -> BabylonNative.bridgeReleaseObject(
                        requireOpenHandle(), requestId, objectHandle));
        return mapFuture(source, ignored -> null, false);
    }

    private <T> CompletableFuture<T> mapFuture(
            CompletableFuture<JsValue> source,
            Function<JsValue, T> mapper,
            boolean propagateCancellation) {
        CompletableFuture<T> mapped = new CompletableFuture<>();
        if (propagateCancellation) {
            mapped.whenComplete((value, error) -> {
                if (mapped.isCancelled()) {
                    source.cancel(false);
                }
            });
        }
        source.whenComplete((value, error) -> {
            if (error != null) {
                mapped.completeExceptionally(error);
                return;
            }
            if (mapped.isCancelled()) {
                releaseDiscardedObject(value);
                return;
            }
            try {
                T result = mapper.apply(value);
                if (!mapped.complete(result)) {
                    releaseDiscardedObject(value);
                }
            } catch (Throwable mappingError) {
                mapped.completeExceptionally(mappingError);
                releaseDiscardedObject(value);
            }
        });
        return mapped;
    }

    private static void releaseDiscardedObject(JsValue value) {
        if (value != null && value.getKind() == JsValue.Kind.OBJECT) {
            value.asObject().releaseAsync();
        }
    }

    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else if (!mainHandler.post(action)) {
            throw new IllegalStateException("Main looper is shutting down");
        }
    }

    private interface NativeSubmission {
        void submit(long requestId);
    }
}

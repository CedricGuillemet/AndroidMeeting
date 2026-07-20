# AndroidMeeting

An Android **library** project that produces an **AAR** embedding
[BabylonJS/BabylonNative](https://github.com/BabylonJS/BabylonNative) as a
transparent 3D view.

## What the AAR contains

- **Native `.so`** — `libBabylonNativeEmbedding.so`, produced by an *external
  CMake build* (`babylonview/CMakeLists.txt`) that **fetches** BabylonNative via
  CMake `FetchContent` and enables the cross-platform `Embedding` facade plus
  the `Embedding/Android` JNI interop layer.
- **Java definitions**
  - `com.babylonjs.embedding.BabylonNative` — the JNI binding whose native
    methods map to the `Java_com_babylonjs_embedding_BabylonNative_*` symbols in
    BabylonNative's `Embedding/Android/.../BabylonNativeEmbedding.cpp`.
  - `com.babylonjs.meeting.BabylonView` — a `SurfaceView`-based view configured
    for **transparency**.

## Transparency

`BabylonView` renders into a `SurfaceView` set up so that OpenGL back-buffer
pixels with **alpha == 0 are transparent**:

- `SurfaceView.setZOrderOnTop(true)` — the surface is alpha-blended by the
  compositor instead of punched out as an opaque hole.
- `SurfaceHolder.setFormat(PixelFormat.TRANSLUCENT)` — an RGBA_8888 buffer with
  a real alpha channel.

bgfx's GL backend already selects an EGL config with `EGL_ALPHA_SIZE = 8` for
its RGBA8 back buffer, so the alpha the scene writes survives composition. On
the JS side the scene must clear with a zero-alpha colour:

```js
scene.clearColor = new BABYLON.Color4(0, 0, 0, 0);
```

## Building

```bash
./gradlew :babylonview:assembleRelease
```

The first configure fetches BabylonNative and all of its dependencies (bgfx,
JsRuntimeHost, the JS engine, glslang, ...) and compiles them, so the initial
build is long. Restrict to a single ABI while iterating:

```bash
./gradlew :babylonview:assembleRelease -PARM64Only
```

Output: `babylonview/build/outputs/aar/babylonview-release.aar`.

### Requirements

- JDK 17
- Android SDK, NDK `29.0.14206865`, CMake `3.22.1+`, Ninja

## CI

`.github/workflows/build-aar.yml` builds the AAR on GitHub Actions (single ABI)
and uploads it as the `babylonview-aar` artifact.

## Using the view

```java
BabylonNative.setContext(getApplicationContext());
long runtime = BabylonNative.runtimeCreate();
BabylonNative.runtimeLoadScript(runtime, "app:///scene.js");
BabylonView view = new BabylonView(this, runtime);
setContentView(view);
```

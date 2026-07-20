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

## Native build size trade-offs

To keep `libBabylonNativeEmbedding.so` small, the following are **disabled** in
the native build:

- **Image loading / decoding** (`NATIVEENGINE_LOAD_IMAGES=OFF`) — removes bimg
  decode/encode and WebP. Textures must be provided in a GPU-ready form; runtime
  decoding of PNG/JPEG/WebP is not available.
- **Image encoding** (`NATIVEENCODING=OFF`) — no native PNG encoding /
  `EncodeImageAsync` (screenshots, asset export).
- **Runtime shader compilation** (`NATIVEENGINE_COMPILESHADERS=OFF`, plus
  `SHADERCOMPILER`/`SHADERTOOL` off) — removes glslang + SPIRV-Cross.

Because shader compilation is off, **you must supply a prebuilt GPU shader
cache** or nothing will render. The `ShaderCache` plugin stays enabled and is
exposed through the embedding layer via `RuntimeOptions.shaderCachePath`:

- On the **first view attach**, the file at `shaderCachePath` is loaded
  (`ShaderCache::Load`); a missing/unreadable file is ignored.
- On **suspend** and on **runtime destroy**, the cache is written back
  (`ShaderCache::Save`).

Populate the cache once from a build that has shader compilation enabled, ship
that file with your app, and point `shaderCachePath` at a writable copy of it.

## Using the view

```java
BabylonNative.setContext(getApplicationContext());

// Shader compilation is disabled in this build, so a prebuilt shader cache is
// required. Point shaderCachePath at a writable file seeded from your prebuilt
// cache; it is loaded on first attach and saved on suspend/destroy.
BabylonNative.RuntimeOptions options = new BabylonNative.RuntimeOptions();
options.shaderCachePath = new File(getFilesDir(), "shaders.bin").getAbsolutePath();

long runtime = BabylonNative.runtimeCreate(options);
BabylonNative.runtimeLoadScript(runtime, "app:///scene.js");
BabylonView view = new BabylonView(this, runtime);
setContentView(view);
```


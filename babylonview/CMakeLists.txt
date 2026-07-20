# External CMake build for the AndroidMeeting Babylon view AAR.
#
# This build FETCHES BabylonJS/BabylonNative from GitHub and enables the
# cross-platform `Embedding` facade together with the Android JNI interop
# layer. The result is a single shared library:
#
#     libBabylonNativeEmbedding.so
#
# whose exported JNI symbols follow `Java_com_babylonjs_embedding_BabylonNative_*`
# and are bound by `src/main/java/com/babylonjs/embedding/BabylonNative.java`.
#
# Gradle's `externalNativeBuild { cmake { path "CMakeLists.txt" } }` invokes
# this file once per ABI; the produced .so is packaged into the AAR.

cmake_minimum_required(VERSION 3.21)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

project(AndroidMeetingBabylon)

include(FetchContent)

# --- Babylon Native configuration -------------------------------------------
# Build the Embedding facade (Runtime + View) and its Android JNI layer.
set(BABYLON_NATIVE_EMBEDDING ON CACHE BOOL "" FORCE)
set(BABYLON_NATIVE_EMBEDDING_ANDROID ON CACHE BOOL "" FORCE)

# We only want the library; skip the sample apps and install targets.
set(BABYLON_NATIVE_BUILD_APPS OFF CACHE BOOL "" FORCE)
set(BABYLON_NATIVE_INSTALL OFF CACHE BOOL "" FORCE)

# ARCore-backed and other unneeded plugins/polyfills are disabled to keep the
# AAR lean and the build self-contained. NativeCamera/NativeXr would otherwise
# require extracting the ARCore native libraries at build time.
set(BABYLON_NATIVE_POLYFILL_CANVAS OFF CACHE BOOL "" FORCE)
set(BABYLON_NATIVE_PLUGIN_NATIVECAMERA OFF CACHE BOOL "" FORCE)
set(BABYLON_NATIVE_PLUGIN_NATIVECAPTURE OFF CACHE BOOL "" FORCE)
set(BABYLON_NATIVE_PLUGIN_NATIVEXR OFF CACHE BOOL "" FORCE)
set(BABYLON_NATIVE_PLUGIN_TESTUTILS OFF CACHE BOOL "" FORCE)
set(BABYLON_NATIVE_PLUGIN_EXTERNALTEXTURE OFF CACHE BOOL "" FORCE)
set(BABYLON_NATIVE_PLUGIN_NATIVETRACING OFF CACHE BOOL "" FORCE)

# Drop image loading (decode), image encoding, and runtime shader compilation
# to shrink the shipped .so. With shader compilation off, the app MUST provide a
# prebuilt GPU shader cache at runtime via RuntimeOptions.shaderCachePath; the
# ShaderCache plugin stays enabled so that load/save path works.
set(BABYLON_NATIVE_PLUGIN_NATIVEENGINE_LOAD_IMAGES OFF CACHE BOOL "" FORCE)
set(BABYLON_NATIVE_PLUGIN_NATIVEENGINE_COMPILESHADERS OFF CACHE BOOL "" FORCE)
set(BABYLON_NATIVE_PLUGIN_NATIVEENCODING OFF CACHE BOOL "" FORCE)
set(BABYLON_NATIVE_PLUGIN_SHADERCOMPILER OFF CACHE BOOL "" FORCE)
set(BABYLON_NATIVE_PLUGIN_SHADERTOOL OFF CACHE BOOL "" FORCE)
set(BABYLON_NATIVE_PLUGIN_SHADERCACHE ON CACHE BOOL "" FORCE)

# --- Fetch BabylonNative -----------------------------------------------------
# BabylonNative pulls all of its own dependencies (bgfx, JsRuntimeHost, the JS
# engine, arcana, glslang, ...) via FetchContent, so a plain clone is enough.
# Pin GIT_TAG to a commit/tag for fully reproducible CI builds.
FetchContent_Declare(BabylonNative
    GIT_REPOSITORY https://github.com/BabylonJS/BabylonNative.git
    GIT_TAG master)

FetchContent_MakeAvailable(BabylonNative)

# `BabylonNativeEmbedding` (the SHARED .so) is defined by
# BabylonNative/Embedding/Android/CMakeLists.txt once the flags above are set.

# --- Minimal-size linking ----------------------------------------------------
# Drop unreferenced code/data, fold identical functions, hide symbols pulled in
# from static archives (only our JNIEXPORT entry points need to be exported),
# and finish ThinLTO codegen at link time.
target_link_options(BabylonNativeEmbedding PRIVATE
    "-flto=thin"
    "-Wl,--gc-sections"
    "-Wl,--icf=all"
    "-Wl,--exclude-libs,ALL")

# --- Link-size diagnostics ---------------------------------------------------
# Emit an lld linker map next to the .so so CI can report exactly which static
# archives (.a) were pulled into the final library and how many bytes each
# contributed. Cheap to produce and does not affect the shipped binary.
target_link_options(BabylonNativeEmbedding PRIVATE
    "-Wl,-Map=${CMAKE_CURRENT_BINARY_DIR}/BabylonNativeEmbedding.map")

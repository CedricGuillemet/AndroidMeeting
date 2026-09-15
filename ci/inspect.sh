#!/usr/bin/env bash
#
# Inspect a freshly built babylonview AAR / native library:
#   - report the shipped libBabylonNativeEmbedding.so size(s)
#   - verify the JNI entry points survived the size/strip flags
#   - report which static archives (.a) were linked in, via the lld map
#
# Usage: ci/inspect.sh "<flavor label>"
set -euo pipefail

LABEL="${1:-AAR}"
echo "=================================================================="
echo "Inspecting flavor: ${LABEL}"
echo "=================================================================="

echo "libBabylonNativeEmbedding.so sizes:"
find babylonview/build -name 'libBabylonNativeEmbedding.so' -print0 \
  | xargs -0 --no-run-if-empty ls -lh
echo "All .so sizes under build outputs:"
find babylonview/build -name '*.so' -printf '%s\t%p\n' | sort -rn \
  | awk '{ printf "%10.2f MiB  %s\n", $1/1048576, $2 }'

# Inspect the exact stripped libraries packaged in the release AAR.
AAR="babylonview/build/outputs/aar/babylonview-release.aar"
[ -f "${AAR}" ] || { echo "::error::Release AAR not found: ${AAR}"; exit 1; }
INSPECT_DIR="babylonview/build/inspect-aar"
rm -rf "${INSPECT_DIR}"
mkdir -p "${INSPECT_DIR}"
unzip -q "${AAR}" 'jni/*/libBabylonNativeEmbedding.so' -d "${INSPECT_DIR}"

NM=$(find "${ANDROID_HOME}/ndk" -name 'llvm-nm' -print -quit)
PREFIX="Java_com_babylonjs_embedding_BabylonNative_"
REQUIRED=(
  setContext setCurrentActivity pause resume requestPermissionsResult
  runtimeCreate__ runtimeCreate__Lcom_babylonjs_embedding_BabylonNative_00024RuntimeOptions_2
  runtimeDestroy runtimeLoadScript runtimeLoadShaderCache runtimeEval
  runtimeSetXrSurface runtimeIsXrActive viewAttach viewDetach
  runtimeAddSecondarySurface runtimeRemoveSecondarySurface runtimeMirrorFrame
  viewRenderFrame viewResize viewPointerDown viewPointerMove viewPointerUp
  bridgeCreate bridgeClose bridgeCancel bridgeCreateObject bridgeGet bridgeSet
  bridgeCall bridgeReleaseObject
)

for ABI in arm64-v8a x86_64; do
  SO="${INSPECT_DIR}/jni/${ABI}/libBabylonNativeEmbedding.so"
  [ -f "${SO}" ] || { echo "::error::Packaged ${ABI} library is missing."; exit 1; }
  SYMBOLS=$("${NM}" -D --defined-only "${SO}" | awk '{print $NF}')
  for NAME in "${REQUIRED[@]}"; do
    grep -Fxq "${PREFIX}${NAME}" <<< "${SYMBOLS}" || {
      echo "::error::Missing packaged ${ABI} JNI export: ${PREFIX}${NAME}"
      exit 1
    }
  done
  echo "Verified ${#REQUIRED[@]} exact JNI exports in packaged ${ABI} library."
done

# Parse the lld linker map (-Wl,-Map) to show which static libraries were pulled
# into libBabylonNativeEmbedding.so and their size.
MAP=$(find babylonview -name 'BabylonNativeEmbedding.map' -print -quit)
echo "Linker map: ${MAP:-<not found>}"
if [ -n "${MAP}" ]; then
  python3 ci/report_link_sizes.py "${MAP}"
else
  echo "No linker map found; skipping .a report."
fi

# Surface the shipped (stripped) arm64-v8a .so size in the job summary so the
# two flavors are easy to compare at a glance.
SO="${INSPECT_DIR}/jni/arm64-v8a/libBabylonNativeEmbedding.so"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  SIZE=$(stat -c %s "${SO}")
  printf '### %s\n\n- Stripped `libBabylonNativeEmbedding.so` (arm64-v8a): **%.2f MiB** (%s bytes)\n' \
    "${LABEL}" "$(awk "BEGIN{print ${SIZE}/1048576}")" "${SIZE}" >> "${GITHUB_STEP_SUMMARY}"
fi

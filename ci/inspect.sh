#!/usr/bin/env bash
#
# Inspect a freshly built babylonview AAR / native library:
#   - report the shipped libBabylonNativeEmbedding.so size(s)
#   - verify the JNI entry points survived the size/strip flags
#   - report which static archives (.a) were linked in, via the lld map
#
# Usage: ci/inspect.sh "<flavor label>"
set -uo pipefail

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

# The size flags (hidden visibility, --exclude-libs, gc/icf) must not strip the
# JNIEXPORT entry points the AAR's Java layer binds against.
SO=$(find babylonview/build -path '*stripReleaseDebugSymbols*arm64-v8a*' -name 'libBabylonNativeEmbedding.so' | head -n1)
[ -z "${SO}" ] && SO=$(find babylonview/build -name 'libBabylonNativeEmbedding.so' | head -n1)
echo "Inspecting symbols: ${SO}"
NM=$(find "${ANDROID_HOME}/ndk" -name 'llvm-nm' | head -n1)
COUNT=$("${NM}" -D --defined-only "${SO}" | grep -c 'Java_com_babylonjs_embedding_BabylonNative_' || true)
echo "Exported Java_com_babylonjs_embedding_BabylonNative_* symbols: ${COUNT}"
if [ "${COUNT}" -lt 1 ]; then
  echo "::error::No BabylonNative JNI symbols exported; size flags broke the ABI."
  exit 1
fi

# Parse the lld linker map (-Wl,-Map) to show which static libraries were pulled
# into libBabylonNativeEmbedding.so and their size.
MAP=$(find babylonview -name 'BabylonNativeEmbedding.map' | head -n1)
echo "Linker map: ${MAP:-<not found>}"
if [ -n "${MAP}" ]; then
  python3 ci/report_link_sizes.py "${MAP}"
else
  echo "No linker map found; skipping .a report."
fi

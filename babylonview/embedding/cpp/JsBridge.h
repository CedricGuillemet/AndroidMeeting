#pragma once

#include <jni.h>

namespace Babylon::Embedding
{
    class Runtime;
}

namespace Babylon::Embedding::Android::JsBridgeNative
{
    void RegisterRuntime(jlong runtimeHandle, Runtime* runtime);
    void CloseRuntime(JNIEnv* env, jlong runtimeHandle);
}

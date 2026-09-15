#include "JsBridge.h"

#include <Babylon/Embedding/Runtime.h>
#include <napi/napi.h>

#include <atomic>
#include <cstdint>
#include <exception>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

namespace Babylon::Embedding::Android::JsBridgeNative
{
    namespace
    {
        constexpr jint UndefinedKind{0};
        constexpr jint NullKind{1};
        constexpr jint BooleanKind{2};
        constexpr jint NumberKind{3};
        constexpr jint StringKind{4};
        constexpr jint ObjectKind{5};

        struct ObjectRegistry
        {
            explicit ObjectRegistry(uint64_t runtimeGeneration)
                : generation{runtimeGeneration}
            {
            }

            uint64_t generation{};
            napi_env env{};
            napi_ref anchor{};
            bool finalized{};
            std::unordered_map<jlong, napi_ref> objects;

            static void Finalize(napi_env callbackEnv, void* data, void*)
            {
                auto* owner = static_cast<std::shared_ptr<ObjectRegistry>*>(data);
                std::shared_ptr<ObjectRegistry> registry = *owner;
                registry->FinalizeReferences(callbackEnv);
                delete owner;
            }

            void EnsureAttached(Napi::Env jsEnv)
            {
                napi_env nativeEnv = jsEnv;
                if (finalized)
                {
                    throw std::runtime_error{"JavaScript environment has been destroyed"};
                }
                if (env != nullptr)
                {
                    if (env != nativeEnv)
                    {
                        throw std::runtime_error{"JavaScript environment changed unexpectedly"};
                    }
                    return;
                }

                env = nativeEnv;
                auto* owner = new std::shared_ptr<ObjectRegistry>{shared_from_this()};
                napi_value external{};
                napi_status status =
                    napi_create_external(env, owner, Finalize, nullptr, &external);
                if (status != napi_ok)
                {
                    delete owner;
                    env = nullptr;
                    throw Napi::Error::New(jsEnv);
                }
                status = napi_create_reference(env, external, 1, &anchor);
                if (status != napi_ok)
                {
                    env = nullptr;
                    throw Napi::Error::New(jsEnv);
                }
            }

            jlong Retain(Napi::Env jsEnv, const Napi::Value& value)
            {
                EnsureAttached(jsEnv);
                napi_ref reference{};
                if (napi_create_reference(env, value, 1, &reference) != napi_ok)
                {
                    throw Napi::Error::New(jsEnv);
                }
                const jlong objectHandle = g_nextObjectHandle.fetch_add(1);
                objects.emplace(objectHandle, reference);
                return objectHandle;
            }

            Napi::Object Resolve(Napi::Env jsEnv, jlong objectHandle)
            {
                EnsureAttached(jsEnv);
                auto it = objects.find(objectHandle);
                if (it == objects.end())
                {
                    throw std::invalid_argument{"Invalid or released JsObject handle"};
                }
                napi_value value{};
                if (napi_get_reference_value(env, it->second, &value) != napi_ok ||
                    value == nullptr)
                {
                    throw Napi::Error::New(jsEnv);
                }
                Napi::Value resolved{env, value};
                if (!resolved.IsObject() && !resolved.IsFunction())
                {
                    throw std::runtime_error{"JsObject handle no longer refers to an object"};
                }
                return resolved.As<Napi::Object>();
            }

            bool Release(jlong objectHandle)
            {
                if (finalized)
                {
                    return true;
                }
                auto it = objects.find(objectHandle);
                if (it == objects.end())
                {
                    return false;
                }
                napi_ref reference = it->second;
                objects.erase(it);
                return napi_delete_reference(env, reference) == napi_ok;
            }

            void FinalizeReferences(napi_env callbackEnv)
            {
                if (finalized)
                {
                    return;
                }
                finalized = true;
                std::vector<napi_ref> references;
                references.reserve(objects.size());
                for (const auto& [objectHandle, reference] : objects)
                {
                    (void)objectHandle;
                    references.push_back(reference);
                }
                objects.clear();
                for (napi_ref reference : references)
                {
                    napi_delete_reference(callbackEnv, reference);
                }
                if (anchor != nullptr)
                {
                    napi_ref anchorReference = anchor;
                    anchor = nullptr;
                    napi_delete_reference(callbackEnv, anchorReference);
                }
                env = nullptr;
            }

            static std::atomic<jlong> g_nextObjectHandle;

        private:
            std::shared_ptr<ObjectRegistry> shared_from_this()
            {
                return self.lock();
            }

        public:
            std::weak_ptr<ObjectRegistry> self;
        };

        std::atomic<jlong> ObjectRegistry::g_nextObjectHandle{1};

        struct RuntimeEntry
        {
            Runtime* runtime{};
            uint64_t generation{};
            std::shared_ptr<ObjectRegistry> objects;
        };

        struct BridgeState
        {
            JavaVM* javaVm{};
            jobject javaBridge{};
            jmethodID resultMethod{};
            jmethodID errorMethod{};
            jmethodID closedMethod{};
            jlong runtimeHandle{};
            uint64_t generation{};
            Runtime* runtime{};
            std::shared_ptr<ObjectRegistry> objects;
            std::mutex mutex;
            bool closing{};
            std::unordered_set<jlong> pending;

            ~BridgeState()
            {
                if (javaVm == nullptr || javaBridge == nullptr)
                {
                    return;
                }
                JNIEnv* env{};
                bool attached{};
                if (javaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK)
                {
                    if (javaVm->AttachCurrentThread(&env, nullptr) != JNI_OK)
                    {
                        return;
                    }
                    attached = true;
                }
                env->DeleteGlobalRef(javaBridge);
                if (attached)
                {
                    javaVm->DetachCurrentThread();
                }
            }

            JNIEnv* GetEnv(bool& attached)
            {
                attached = false;
                JNIEnv* env{};
                if (javaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK)
                {
                    return env;
                }
                if (javaVm->AttachCurrentThread(&env, nullptr) != JNI_OK)
                {
                    return nullptr;
                }
                attached = true;
                return env;
            }

            bool TakePending(jlong requestId)
            {
                std::lock_guard lock{mutex};
                return pending.erase(requestId) != 0;
            }

            bool IsPending(jlong requestId)
            {
                std::lock_guard lock{mutex};
                return pending.contains(requestId);
            }

            void Cancel(jlong requestId)
            {
                std::lock_guard lock{mutex};
                pending.erase(requestId);
            }

            void Complete(
                jlong requestId,
                jint kind,
                jboolean booleanValue = JNI_FALSE,
                jdouble numberValue = 0,
                const std::u16string* stringValue = nullptr,
                jlong objectHandle = 0)
            {
                if (!TakePending(requestId))
                {
                    if (objectHandle != 0)
                    {
                        objects->Release(objectHandle);
                    }
                    return;
                }
                bool attached{};
                JNIEnv* env = GetEnv(attached);
                if (env == nullptr)
                {
                    return;
                }
                jstring javaString = stringValue == nullptr
                    ? nullptr
                    : env->NewString(
                        reinterpret_cast<const jchar*>(stringValue->data()),
                        static_cast<jsize>(stringValue->size()));
                env->CallVoidMethod(
                    javaBridge,
                    resultMethod,
                    requestId,
                    kind,
                    booleanValue,
                    numberValue,
                    javaString,
                    objectHandle);
                if (javaString != nullptr)
                {
                    env->DeleteLocalRef(javaString);
                }
                if (env->ExceptionCheck())
                {
                    env->ExceptionClear();
                }
                if (attached)
                {
                    javaVm->DetachCurrentThread();
                }
            }

            void Fail(
                jlong requestId,
                const std::u16string& operation,
                const std::u16string& key,
                const std::u16string& name,
                const std::u16string& message,
                const std::u16string& stack)
            {
                if (!TakePending(requestId))
                {
                    return;
                }
                bool attached{};
                JNIEnv* env = GetEnv(attached);
                if (env == nullptr)
                {
                    return;
                }
                auto makeString = [&](const std::u16string& value) {
                    return env->NewString(
                        reinterpret_cast<const jchar*>(value.data()),
                        static_cast<jsize>(value.size()));
                };
                jstring javaOperation = makeString(operation);
                jstring javaKey = makeString(key);
                jstring javaName = makeString(name);
                jstring javaMessage = makeString(message);
                jstring javaStack = makeString(stack);
                env->CallVoidMethod(
                    javaBridge,
                    errorMethod,
                    requestId,
                    javaOperation,
                    javaKey,
                    javaName,
                    javaMessage,
                    javaStack);
                env->DeleteLocalRef(javaOperation);
                env->DeleteLocalRef(javaKey);
                env->DeleteLocalRef(javaName);
                env->DeleteLocalRef(javaMessage);
                env->DeleteLocalRef(javaStack);
                if (env->ExceptionCheck())
                {
                    env->ExceptionClear();
                }
                if (attached)
                {
                    javaVm->DetachCurrentThread();
                }
            }

            void Close(const std::u16string& message)
            {
                std::vector<jlong> requests;
                {
                    std::lock_guard lock{mutex};
                    if (closing)
                    {
                        return;
                    }
                    closing = true;
                    runtime = nullptr;
                    requests.assign(pending.begin(), pending.end());
                }
                for (jlong request : requests)
                {
                    Fail(request, u"lifecycle", u"", u"AbortError", message, u"");
                }
                bool attached{};
                JNIEnv* env = GetEnv(attached);
                if (env != nullptr)
                {
                    jstring javaMessage = env->NewString(
                        reinterpret_cast<const jchar*>(message.data()),
                        static_cast<jsize>(message.size()));
                    env->CallVoidMethod(javaBridge, closedMethod, javaMessage);
                    env->DeleteLocalRef(javaMessage);
                    if (env->ExceptionCheck())
                    {
                        env->ExceptionClear();
                    }
                    if (attached)
                    {
                        javaVm->DetachCurrentThread();
                    }
                }
            }
        };

        std::mutex g_mutex;
        std::unordered_map<jlong, RuntimeEntry> g_runtimes;
        std::unordered_map<jlong, std::shared_ptr<BridgeState>> g_bridges;
        std::atomic<uint64_t> g_nextGeneration{1};
        std::atomic<jlong> g_nextBridgeHandle{1};

        std::u16string FromJavaString(JNIEnv* env, jstring value)
        {
            if (value == nullptr)
            {
                return {};
            }
            const jsize length = env->GetStringLength(value);
            const jchar* chars = env->GetStringChars(value, nullptr);
            if (chars == nullptr)
            {
                throw std::runtime_error{"Unable to read Java string"};
            }
            std::u16string result{
                reinterpret_cast<const char16_t*>(chars),
                static_cast<size_t>(length)};
            env->ReleaseStringChars(value, chars);
            return result;
        }

        std::shared_ptr<BridgeState> FindBridge(jlong bridgeHandle)
        {
            std::lock_guard lock{g_mutex};
            auto it = g_bridges.find(bridgeHandle);
            return it == g_bridges.end() ? nullptr : it->second;
        }

        std::u16string ErrorProperty(Napi::Error& error, const char* name)
        {
            try
            {
                Napi::Value value = error.Value().Get(name);
                return value.IsString() ? value.As<Napi::String>().Utf16Value() : std::u16string{};
            }
            catch (...)
            {
                return {};
            }
        }

        void FailJs(
            const std::shared_ptr<BridgeState>& state,
            jlong requestId,
            const std::u16string& operation,
            const std::u16string& key,
            Napi::Error& error)
        {
            std::u16string name = ErrorProperty(error, "name");
            std::u16string message = ErrorProperty(error, "message");
            std::u16string stack = ErrorProperty(error, "stack");
            state->Fail(
                requestId,
                operation,
                key,
                name.empty() ? u"Error" : name,
                message.empty() ? u"JavaScript operation failed" : message,
                stack);
        }

        Napi::Object ResolveTarget(
            const std::shared_ptr<BridgeState>& state,
            Napi::Env env,
            jlong objectHandle)
        {
            if (objectHandle == 0)
            {
                return env.Global();
            }
            return state->objects->Resolve(env, objectHandle);
        }

        Napi::Value DecodeValue(
            Napi::Env env,
            jint kind,
            jboolean booleanValue,
            jdouble numberValue,
            const std::u16string& stringValue,
            jlong objectHandle,
            const std::shared_ptr<BridgeState>& state)
        {
            switch (kind)
            {
                case UndefinedKind:
                    return env.Undefined();
                case NullKind:
                    return env.Null();
                case BooleanKind:
                    return Napi::Boolean::New(env, booleanValue == JNI_TRUE);
                case NumberKind:
                    return Napi::Number::New(env, numberValue);
                case StringKind:
                    return Napi::String::New(env, stringValue);
                case ObjectKind:
                    return state->objects->Resolve(env, objectHandle);
                default:
                    throw std::invalid_argument{"Unknown JsValue kind"};
            }
        }

        void CompleteValue(
            const std::shared_ptr<BridgeState>& state,
            jlong requestId,
            const Napi::Value& value)
        {
            if (value.IsUndefined())
            {
                state->Complete(requestId, UndefinedKind);
            }
            else if (value.IsNull())
            {
                state->Complete(requestId, NullKind);
            }
            else if (value.IsBoolean())
            {
                state->Complete(
                    requestId,
                    BooleanKind,
                    value.As<Napi::Boolean>().Value() ? JNI_TRUE : JNI_FALSE);
            }
            else if (value.IsNumber())
            {
                state->Complete(
                    requestId,
                    NumberKind,
                    JNI_FALSE,
                    value.As<Napi::Number>().DoubleValue());
            }
            else if (value.IsString())
            {
                auto text = value.As<Napi::String>().Utf16Value();
                state->Complete(requestId, StringKind, JNI_FALSE, 0, &text);
            }
            else if (value.IsObject() || value.IsFunction())
            {
                const jlong objectHandle = state->objects->Retain(value.Env(), value);
                state->Complete(
                    requestId, ObjectKind, JNI_FALSE, 0, nullptr, objectHandle);
            }
            else
            {
                throw Napi::TypeError::New(
                    value.Env(), "Unsupported JavaScript value type (Symbol or BigInt)");
            }
        }

        template<typename Callback>
        void Dispatch(
            const std::shared_ptr<BridgeState>& state,
            jlong requestId,
            const std::u16string& operation,
            const std::u16string& key,
            Callback&& callback)
        {
            bool unavailable{};
            try
            {
                std::lock_guard lifecycleLock{g_mutex};
                auto runtimeIt = g_runtimes.find(state->runtimeHandle);
                {
                    std::lock_guard stateLock{state->mutex};
                    state->pending.insert(requestId);
                    unavailable =
                        state->closing ||
                        state->runtime == nullptr ||
                        runtimeIt == g_runtimes.end() ||
                        runtimeIt->second.generation != state->generation ||
                        runtimeIt->second.runtime != state->runtime;
                }
                if (!unavailable)
                {
                    state->runtime->RunOnJsThread(
                        [state,
                         requestId,
                         operation,
                         key,
                         callback = std::forward<Callback>(callback)](
                            Napi::Env env) mutable {
                            if (!state->IsPending(requestId))
                            {
                                return;
                            }
                            try
                            {
                                callback(env);
                            }
                            catch (Napi::Error& error)
                            {
                                FailJs(state, requestId, operation, key, error);
                            }
                            catch (const std::exception& error)
                            {
                                const std::string message{error.what()};
                                state->Fail(
                                    requestId,
                                    operation,
                                    key,
                                    u"Error",
                                    std::u16string{message.begin(), message.end()},
                                    u"");
                            }
                            catch (...)
                            {
                                state->Fail(
                                    requestId,
                                    operation,
                                    key,
                                    u"Error",
                                    u"Unknown native bridge failure",
                                    u"");
                            }
                        },
                        true);
                }
            }
            catch (const std::exception& error)
            {
                const std::string message{error.what()};
                state->Fail(
                    requestId,
                    operation,
                    key,
                    u"Error",
                    std::u16string{message.begin(), message.end()},
                    u"");
            }
            catch (...)
            {
                state->Fail(
                    requestId,
                    operation,
                    key,
                    u"Error",
                    u"Unable to submit JavaScript operation",
                    u"");
            }
            if (unavailable)
            {
                state->Fail(
                    requestId,
                    operation,
                    key,
                    u"AbortError",
                    u"JavaScript runtime is closing",
                    u"");
            }
        }
    }

    void RegisterRuntime(jlong runtimeHandle, Runtime* runtime)
    {
        std::lock_guard lock{g_mutex};
        const uint64_t generation = g_nextGeneration.fetch_add(1);
        auto objects = std::make_shared<ObjectRegistry>(generation);
        objects->self = objects;
        g_runtimes.emplace(runtimeHandle, RuntimeEntry{runtime, generation, std::move(objects)});
    }

    void CloseRuntime(JNIEnv*, jlong runtimeHandle)
    {
        std::vector<std::shared_ptr<BridgeState>> closing;
        {
            std::lock_guard lock{g_mutex};
            g_runtimes.erase(runtimeHandle);
            for (auto it = g_bridges.begin(); it != g_bridges.end();)
            {
                if (it->second->runtimeHandle == runtimeHandle)
                {
                    closing.push_back(it->second);
                    it = g_bridges.erase(it);
                }
                else
                {
                    ++it;
                }
            }
        }
        for (const auto& state : closing)
        {
            state->Close(u"JavaScript runtime was destroyed");
        }
    }
}

extern "C"
{
JNIEXPORT jlong JNICALL
Java_com_babylonjs_embedding_BabylonNative_bridgeCreate(
    JNIEnv* env, jclass, jlong runtimeHandle, jobject javaBridge)
{
    using namespace Babylon::Embedding::Android::JsBridgeNative;
    JavaVM* javaVm{};
    if (env->GetJavaVM(&javaVm) != JNI_OK)
    {
        return 0;
    }
    jclass bridgeClass = env->GetObjectClass(javaBridge);
    if (bridgeClass == nullptr)
    {
        return 0;
    }
    jmethodID resultMethod = env->GetMethodID(
        bridgeClass, "acceptNativeResult", "(JIZDLjava/lang/String;J)V");
    jmethodID errorMethod = env->GetMethodID(
        bridgeClass,
        "acceptNativeError",
        "(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    jmethodID closedMethod = env->GetMethodID(
        bridgeClass, "acceptNativeClosed", "(Ljava/lang/String;)V");
    env->DeleteLocalRef(bridgeClass);
    if (resultMethod == nullptr || errorMethod == nullptr || closedMethod == nullptr)
    {
        return 0;
    }

    jobject globalBridge = env->NewGlobalRef(javaBridge);
    if (globalBridge == nullptr)
    {
        return 0;
    }
    auto state = std::make_shared<Babylon::Embedding::Android::JsBridgeNative::BridgeState>();
    state->javaVm = javaVm;
    state->javaBridge = globalBridge;
    state->resultMethod = resultMethod;
    state->errorMethod = errorMethod;
    state->closedMethod = closedMethod;
    state->runtimeHandle = runtimeHandle;
    const jlong bridgeHandle =
        Babylon::Embedding::Android::JsBridgeNative::g_nextBridgeHandle.fetch_add(1);
    {
        std::lock_guard lock{Babylon::Embedding::Android::JsBridgeNative::g_mutex};
        auto runtimeIt =
            Babylon::Embedding::Android::JsBridgeNative::g_runtimes.find(runtimeHandle);
        if (runtimeIt == Babylon::Embedding::Android::JsBridgeNative::g_runtimes.end())
        {
            return 0;
        }
        state->generation = runtimeIt->second.generation;
        state->runtime = runtimeIt->second.runtime;
        state->objects = runtimeIt->second.objects;
        Babylon::Embedding::Android::JsBridgeNative::g_bridges.emplace(bridgeHandle, state);
    }
    return bridgeHandle;
}

JNIEXPORT void JNICALL
Java_com_babylonjs_embedding_BabylonNative_bridgeClose(
    JNIEnv*, jclass, jlong bridgeHandle)
{
    std::shared_ptr<Babylon::Embedding::Android::JsBridgeNative::BridgeState> state;
    {
        std::lock_guard lock{Babylon::Embedding::Android::JsBridgeNative::g_mutex};
        auto it = Babylon::Embedding::Android::JsBridgeNative::g_bridges.find(bridgeHandle);
        if (it == Babylon::Embedding::Android::JsBridgeNative::g_bridges.end())
        {
            return;
        }
        state = it->second;
        Babylon::Embedding::Android::JsBridgeNative::g_bridges.erase(it);
    }
    state->Close(u"JsBridge was closed");
}

JNIEXPORT void JNICALL
Java_com_babylonjs_embedding_BabylonNative_bridgeCancel(
    JNIEnv*, jclass, jlong bridgeHandle, jlong requestId)
{
    auto state =
        Babylon::Embedding::Android::JsBridgeNative::FindBridge(bridgeHandle);
    if (state != nullptr)
    {
        state->Cancel(requestId);
    }
}

JNIEXPORT void JNICALL
Java_com_babylonjs_embedding_BabylonNative_bridgeCreateObject(
    JNIEnv*,
    jclass,
    jlong bridgeHandle,
    jlong requestId)
{
    using namespace Babylon::Embedding::Android::JsBridgeNative;
    auto state = FindBridge(bridgeHandle);
    if (state == nullptr)
    {
        return;
    }
    Dispatch(
        state,
        requestId,
        u"createObject",
        u"",
        [state, requestId](Napi::Env jsEnv) {
            CompleteValue(state, requestId, Napi::Object::New(jsEnv));
        });
}

JNIEXPORT void JNICALL
Java_com_babylonjs_embedding_BabylonNative_bridgeGet(
    JNIEnv* env,
    jclass,
    jlong bridgeHandle,
    jlong requestId,
    jlong objectHandle,
    jstring key)
{
    using namespace Babylon::Embedding::Android::JsBridgeNative;
    auto state = FindBridge(bridgeHandle);
    if (state == nullptr)
    {
        return;
    }
    std::u16string nativeKey;
    try
    {
        nativeKey = FromJavaString(env, key);
    }
    catch (const std::exception& error)
    {
        {
            std::lock_guard lock{state->mutex};
            state->pending.insert(requestId);
        }
        const std::string message{error.what()};
        state->Fail(
            requestId, u"get", u"", u"Error", std::u16string{message.begin(), message.end()}, u"");
        return;
    }
    Dispatch(
        state,
        requestId,
        u"get",
        nativeKey,
        [state, requestId, objectHandle, nativeKey](Napi::Env jsEnv) {
            Napi::Object target = ResolveTarget(state, jsEnv, objectHandle);
            Napi::Value value = target.Get(Napi::String::New(jsEnv, nativeKey));
            CompleteValue(state, requestId, value);
        });
}

JNIEXPORT void JNICALL
Java_com_babylonjs_embedding_BabylonNative_bridgeSet(
    JNIEnv* env,
    jclass,
    jlong bridgeHandle,
    jlong requestId,
    jlong objectHandle,
    jstring key,
    jint valueKind,
    jboolean booleanValue,
    jdouble numberValue,
    jstring stringValue,
    jlong valueObjectHandle)
{
    using namespace Babylon::Embedding::Android::JsBridgeNative;
    auto state = FindBridge(bridgeHandle);
    if (state == nullptr)
    {
        return;
    }
    std::u16string nativeKey;
    std::u16string nativeString;
    try
    {
        nativeKey = FromJavaString(env, key);
        nativeString = FromJavaString(env, stringValue);
    }
    catch (const std::exception& error)
    {
        {
            std::lock_guard lock{state->mutex};
            state->pending.insert(requestId);
        }
        const std::string message{error.what()};
        state->Fail(
            requestId, u"set", u"", u"Error", std::u16string{message.begin(), message.end()}, u"");
        return;
    }
    Dispatch(
        state,
        requestId,
        u"set",
        nativeKey,
        [state,
         requestId,
         objectHandle,
         nativeKey,
         valueKind,
         booleanValue,
         numberValue,
         nativeString,
         valueObjectHandle](Napi::Env jsEnv) {
            Napi::Object target = ResolveTarget(state, jsEnv, objectHandle);
            Napi::Value value = DecodeValue(
                jsEnv,
                valueKind,
                booleanValue,
                numberValue,
                nativeString,
                valueObjectHandle,
                state);
            if (!target.Set(Napi::String::New(jsEnv, nativeKey), value))
            {
                throw std::runtime_error{"JavaScript property assignment was rejected"};
            }
            CompleteValue(state, requestId, jsEnv.Undefined());
        });
}

JNIEXPORT void JNICALL
Java_com_babylonjs_embedding_BabylonNative_bridgeCall(
    JNIEnv* env,
    jclass,
    jlong bridgeHandle,
    jlong requestId,
    jlong objectHandle,
    jstring key,
    jintArray valueKinds,
    jbooleanArray booleanValues,
    jdoubleArray numberValues,
    jobjectArray stringValues,
    jlongArray objectValues)
{
    using namespace Babylon::Embedding::Android::JsBridgeNative;
    auto state = FindBridge(bridgeHandle);
    if (state == nullptr)
    {
        return;
    }

    std::u16string nativeKey;
    std::vector<jint> kinds;
    std::vector<jboolean> booleans;
    std::vector<jdouble> numbers;
    std::vector<std::u16string> strings;
    std::vector<jlong> objects;
    try
    {
        nativeKey = FromJavaString(env, key);
        const jsize count = env->GetArrayLength(valueKinds);
        if (env->GetArrayLength(booleanValues) != count ||
            env->GetArrayLength(numberValues) != count ||
            env->GetArrayLength(stringValues) != count ||
            env->GetArrayLength(objectValues) != count)
        {
            throw std::invalid_argument{"Mismatched call argument arrays"};
        }
        kinds.resize(static_cast<size_t>(count));
        booleans.resize(static_cast<size_t>(count));
        numbers.resize(static_cast<size_t>(count));
        objects.resize(static_cast<size_t>(count));
        env->GetIntArrayRegion(valueKinds, 0, count, kinds.data());
        env->GetBooleanArrayRegion(booleanValues, 0, count, booleans.data());
        env->GetDoubleArrayRegion(numberValues, 0, count, numbers.data());
        env->GetLongArrayRegion(objectValues, 0, count, objects.data());
        strings.reserve(static_cast<size_t>(count));
        for (jsize i = 0; i < count; ++i)
        {
            auto stringValue =
                static_cast<jstring>(env->GetObjectArrayElement(stringValues, i));
            strings.push_back(FromJavaString(env, stringValue));
            if (stringValue != nullptr)
            {
                env->DeleteLocalRef(stringValue);
            }
        }
        if (env->ExceptionCheck())
        {
            throw std::runtime_error{"Unable to copy call arguments"};
        }
    }
    catch (const std::exception& error)
    {
        if (env->ExceptionCheck())
        {
            env->ExceptionClear();
        }
        {
            std::lock_guard lock{state->mutex};
            state->pending.insert(requestId);
        }
        const std::string message{error.what()};
        state->Fail(
            requestId,
            u"call",
            nativeKey,
            u"Error",
            std::u16string{message.begin(), message.end()},
            u"");
        return;
    }

    Dispatch(
        state,
        requestId,
        u"call",
        nativeKey,
        [state,
         requestId,
         objectHandle,
         nativeKey,
         kinds = std::move(kinds),
         booleans = std::move(booleans),
         numbers = std::move(numbers),
         strings = std::move(strings),
         objects = std::move(objects)](Napi::Env jsEnv) {
            Napi::Object target = ResolveTarget(state, jsEnv, objectHandle);
            Napi::Value property = target.Get(Napi::String::New(jsEnv, nativeKey));
            if (!property.IsFunction())
            {
                throw std::invalid_argument{"Property is missing or is not callable"};
            }
            std::vector<napi_value> nativeArguments;
            nativeArguments.reserve(kinds.size());
            for (size_t i = 0; i < kinds.size(); ++i)
            {
                nativeArguments.push_back(DecodeValue(
                    jsEnv,
                    kinds[i],
                    booleans[i],
                    numbers[i],
                    strings[i],
                    objects[i],
                    state));
            }
            Napi::Value result = property.As<Napi::Function>().Call(
                target, nativeArguments);
            CompleteValue(state, requestId, result);
        });
}

JNIEXPORT void JNICALL
Java_com_babylonjs_embedding_BabylonNative_bridgeReleaseObject(
    JNIEnv*,
    jclass,
    jlong bridgeHandle,
    jlong requestId,
    jlong objectHandle)
{
    using namespace Babylon::Embedding::Android::JsBridgeNative;
    auto state = FindBridge(bridgeHandle);
    if (state == nullptr)
    {
        return;
    }
    Dispatch(
        state,
        requestId,
        u"release",
        u"",
        [state, requestId, objectHandle](Napi::Env jsEnv) {
            (void)jsEnv;
            if (!state->objects->Release(objectHandle))
            {
                throw std::invalid_argument{"Invalid or released JsObject handle"};
            }
            CompleteValue(state, requestId, jsEnv.Undefined());
        });
}
}

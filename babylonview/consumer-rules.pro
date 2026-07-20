# Keep the JNI binding class and its native methods: they are resolved by
# name from libBabylonNativeEmbedding.so (Java_com_babylonjs_embedding_*).
-keep class com.babylonjs.embedding.** { *; }
-keepclasseswithmembernames class com.babylonjs.embedding.** {
    native <methods>;
}

# Keep the public view API consumed by host apps.
-keep class com.babylonjs.meeting.** { *; }

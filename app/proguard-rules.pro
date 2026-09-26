-keep class com.itantra.proto.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# ONNX Runtime
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Kotlin coroutines
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

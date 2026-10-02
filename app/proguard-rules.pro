# sherpa-onnx's native code finds its Kotlin config/result classes, fields and
# constructors by name through JNI, so none of them may be renamed or removed.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Native code calls this callback by name and exact signature.
-keep class io.github.christiantwu.longhand.engine.DiarizationProgress { *; }

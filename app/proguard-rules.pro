# Keep native methods
-keepclassmembers class * {
    native <methods>;
}

# Keep classes that are used as a parameter type of methods that are also marked as keep
# to preserve changing those methods' signature.
-keep class helium314.keyboard.latin.dictionary.Dictionary
-keep class helium314.keyboard.latin.NgramContext
-keep class helium314.keyboard.latin.makedict.ProbabilityInfo

# after upgrading to gradle 8, stack traces contain "unknown source"
-keepattributes SourceFile,LineNumberTable
-dontobfuscate

# MediaPipe LLM Inference (on-device completion). Uses JNI + AutoValue + protobuf via reflection.
-keep class com.google.mediapipe.** { *; }
-keep class mediapipe.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.auto.value.**

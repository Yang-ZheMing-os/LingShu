# Add project specific ProGuard rules here.

# Keep data models (package name fixed to production: com.lingshu)
-keep class com.lingshu.core.** { *; }
-keep class com.lingshu.feature.**.domain.** { *; }
-keep class com.lingshu.feature.**.data.model.** { *; }

-keepattributes *Annotation*
-keep class dagger.hilt.** { *; }
-keep class androidx.hilt.** { *; }

-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase
-keep class androidx.datastore.** { *; }

-keep class retrofit2.** { *; }
-keepattributes Signature
-keepattributes Exceptions

-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# Sherpa-ONNX JNI
-keep class com.k2fsa.sherpa.onnx.** { *; }

# ONNX Runtime
-keep class ai.onnxruntime.** { *; }

# Vosk
-keep class org.vosk.** { *; }

# Hilt generated classes
-keep class **_HiltModules { *; }
-keep class **_HiltComponents { *; }
-keep,allowobfuscation @dagger.hilt.android.HiltAndroidApp class *
-keep class dagger.hilt.android.internal.lifecycle.HiltViewModelFactory$ViewModelFactoriesEntryPoint { *; }
# Add project specific ProGuard rules here.
-keep class com.lingshu.agent.core.model.** { *; }
-keep class com.lingshu.agent.feature.chat.model.** { *; }
-keep class com.lingshu.agent.feature.persona.model.** { *; }
-keep class com.lingshu.agent.feature.mod.model.** { *; }

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

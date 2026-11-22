# Lumina Gallery ProGuard / R8 Rules

# Keep Kotlinx Serialization DTOs and Navigation Routes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.luminagallery.drive.data.remote.dto.**$$serializer { *; }
-keepclassmembers class com.luminagallery.drive.data.remote.dto.** {
    *** Companion;
}
-keepclasseswithmembers class com.luminagallery.drive.data.remote.dto.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.luminagallery.drive.ui.navigation.**$$serializer { *; }
-keepclassmembers class com.luminagallery.drive.ui.navigation.** {
    *** Companion;
}
-keepclasseswithmembers class com.luminagallery.drive.ui.navigation.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit 2
-keepattributes Signature, Exceptions, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*

# Google Auth Library (Service Account JWT / OAuth2)
-keep class com.google.auth.** { *; }
-keep class com.google.api.client.** { *; }
-dontwarn com.google.auth.**
-dontwarn com.google.api.client.**
-dontwarn org.apache.http.**
-dontwarn java.lang.management.**
-dontwarn javax.naming.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

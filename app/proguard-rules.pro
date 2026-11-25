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
-keep,includedescriptorclasses class com.luminagallery.drive.data.repository.**$$serializer { *; }
-keepclassmembers class com.luminagallery.drive.data.repository.** {
    *** Companion;
}
-keepclasseswithmembers class com.luminagallery.drive.data.repository.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.luminagallery.drive.data.remote.auth.**$$serializer { *; }
-keepclassmembers class com.luminagallery.drive.data.remote.auth.** {
    *** Companion;
}
-keepclasseswithmembers class com.luminagallery.drive.data.remote.auth.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.luminagallery.drive.widget.**$$serializer { *; }
-keepclassmembers class com.luminagallery.drive.widget.** {
    *** Companion;
}
-keepclasseswithmembers class com.luminagallery.drive.widget.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.luminagallery.drive.widget.LuminaWidgetRefreshWorker { *; }
-keep class com.luminagallery.drive.widget.LuminaWidgetRecoveryReceiver { *; }
-keep enum com.luminagallery.drive.widget.** { *; }

# Retrofit 2
-keepattributes Signature, Exceptions, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*

# Google Auth Library (Service Account JWT / OAuth2) & Play Services Identity
-keep class com.google.auth.oauth2.** { *; }
-keep class com.google.api.client.json.** { *; }
-keep class com.google.api.client.http.** { *; }
-keep class com.google.android.gms.auth.api.identity.** { *; }
-keep class com.google.android.gms.common.api.Scope { *; }
-keep class com.google.android.gms.common.api.ApiException { *; }
-dontwarn com.google.auth.**
-dontwarn com.google.api.client.**
-dontwarn org.apache.http.**
-dontwarn java.lang.management.**
-dontwarn javax.naming.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Media3 / ExoPlayer + NextLib FFmpeg extensions
-keep class androidx.media3.exoplayer.DefaultRenderersFactory { *; }
-keep class androidx.media3.exoplayer.video.MediaCodecVideoRenderer { *; }
-keep class androidx.media3.exoplayer.audio.MediaCodecAudioRenderer { *; }
-keep class androidx.media3.extractor.ts.PsExtractor { *; }
-keep class androidx.media3.ui.PlayerView { *; }
-keep class io.github.anilbeesetti.nextlib.media3ext.** { *; }
-dontwarn androidx.media3.**
-dontwarn io.github.anilbeesetti.nextlib.**

# MapLibre Native SDK
-keep class org.maplibre.android.** { *; }
-dontwarn org.maplibre.**

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep class com.luminagallery.drive.data.local.dao.** { *; }
-dontwarn androidx.room.paging.**

# ML Kit On-Device Vision (OCR, Face Detection, Image Labeling), LiteRT & Biometric
# ML Kit discovers its registrars by class name in the manifest and invokes their constructors.
-keep class com.google.mlkit.** implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
}
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
-dontwarn org.tensorflow.lite.**
-dontwarn com.google.ai.edge.litert.**
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.internal.mlkit_**
-dontwarn androidx.biometric.**

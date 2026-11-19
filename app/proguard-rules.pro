# kotlinx.serialization: keep generated serializers for the entry document and navigation routes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.justdataplease.boringtravel.** {
    *** Companion;
}
-keepclasseswithmembers class com.justdataplease.boringtravel.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# MapLibre uses JNI callbacks into these classes.
-keep class org.maplibre.android.** { *; }
-dontwarn org.maplibre.android.**

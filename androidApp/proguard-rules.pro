# Acanthis Mobile Release R8 / ProGuard Rules

# Keep line numbers & source file names for readable crash stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Preserve annotations used by serialization, Room, and Compose
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# kotlinx.serialization (DTOs & Navigation Compose @Serializable routes)
-keep,includedescriptorclasses class io.acanthis.app.core.network.** { *; }
-keep,includedescriptorclasses class io.acanthis.app.app.navigation.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
    @kotlinx.serialization.Transient <fields>;
}
-keep,includedescriptorclasses class **$$serializer { *; }
-keepclassmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room KMP & bundled SQLite JNI bindings
-keep class io.acanthis.app.core.database.** { *; }
-keep class * extends androidx.room.RoomDatabase
-keep class * implements androidx.room.RoomDatabaseConstructor
-keep class androidx.sqlite.driver.bundled.** { *; }

# Koin ViewModel & DI constructors
-keep class io.acanthis.app.feature.**ViewModel { *; }

# Optional runtime backends referenced by OkHttp / Ktor / SLF4J
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn java.lang.invoke.**

# Boost12 ProGuard / R8 configuration

# Conscrypt TLSv1.3 & EKM reflection entrypoints
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**

# BouncyCastle X.509 certificate builders
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }

# Room entities
-keep class com.boost12.rd12.data.local.entity.** { *; }

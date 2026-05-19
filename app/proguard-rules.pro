# Сохраняем атрибуты, важные для Gson / Compose.
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# --- Gson ---
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
  @com.google.gson.annotations.SerializedName <fields>;
}

# --- Доменные модели (сериализуются Gson через рефлексию) ---
-keep class ru.greemlab.neiro.domain.models.** { *; }

# --- Kotlin metadata, нужна Compose ---
-keep class kotlin.Metadata { *; }

# --- Compose: оставляем сгенерированные функции с runtime-аннотациями ---
-keep,allowobfuscation,allowshrinking class androidx.compose.runtime.Composer

# Сохраняем атрибуты, важные для Gson / Compose / kotlinx.
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

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
-keep class ru.greemlab.neiro.data.UserProfileJson { *; }
-keep class ru.greemlab.neiro.data.StoreSnapshot { *; }
-keep class ru.greemlab.neiro.data.network.** { *; }

# --- Retrofit ---
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# --- OkHttp ---
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# --- Kotlin metadata, нужна Compose ---
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata { public <methods>; }

# --- AndroidX ViewModel: R8 full mode иначе обрезает рефлексивный конструктор ---
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keepclassmembers class * extends androidx.lifecycle.AndroidViewModel {
    <init>(android.app.Application);
}

# --- Compose: оставляем сгенерированные функции с runtime-аннотациями ---
-keep,allowobfuscation,allowshrinking class androidx.compose.runtime.Composer
-keep,allowobfuscation,allowshrinking class androidx.compose.runtime.internal.ComposableLambda

# --- Java time desugar: ничего лишнего из desugared API не дёргаем ---
-dontwarn java.lang.invoke.StringConcatFactory

# --- Безопасные «универсальные» правила для kotlinx.coroutines ---
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.flow.**
-dontwarn kotlinx.coroutines.debug.**

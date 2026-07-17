# Сохраняем атрибуты, важные для Gson / Compose / kotlinx.
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Gson ---
# Широкий -keep com.google.gson.** не нужен: библиотека несёт consumer-rules,
# достаточно сохранить наши адаптеры и поля с @SerializedName.
-dontwarn sun.misc.**
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
  @com.google.gson.annotations.SerializedName <fields>;
}

# --- Модели, сериализуемые Gson через рефлексию ---
-keep class ru.greemlab.neiro.domain.models.** { *; }
-keep class ru.greemlab.neiro.data.UserProfileJson { *; }
-keep class ru.greemlab.neiro.data.StoreSnapshot { *; }
-keep class ru.greemlab.neiro.data.network.** { *; }
-keep class ru.greemlab.neiro.notifications.InAppNotification { *; }
-keep class ru.greemlab.neiro.notifications.TrackedSession { *; }
-keep class ru.greemlab.neiro.notifications.SessionNotificationPreferences$SnapshotDto { *; }
-keep class ru.greemlab.neiro.notifications.SessionNotificationPreferences$* { *; }

# --- Retrofit ---
# Retrofit/OkHttp несут собственные consumer-rules; оставляем только
# аннотации HTTP-методов наших интерфейсов и -dontwarn.
-dontwarn retrofit2.**
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# --- OkHttp ---
-dontwarn okhttp3.**
-dontwarn okio.**

# --- Kotlin metadata, нужна Compose ---
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata { public <methods>; }

# --- AndroidX ViewModel: R8 full mode иначе обрезает рефлексивный конструктор ---
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keepclassmembers class * extends androidx.lifecycle.AndroidViewModel {
    <init>(android.app.Application);
    <init>(android.app.Application, androidx.lifecycle.SavedStateHandle);
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

# --- WorkManager (R8 full mode fix) ---
# WorkManager использует рефлексию для создания БД и воркеров.
-keep class androidx.work.impl.WorkDatabase_Impl { <init>(...); }
-keep class * extends androidx.work.ListenableWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class ru.greemlab.neiro.notifications.SessionReminderWorker { *; }
-keep class ru.greemlab.neiro.notifications.SessionDailyNotificationWorker { *; }
-keep class ru.greemlab.neiro.notifications.SessionScheduledDigestWorker { *; }
-keep class ru.greemlab.neiro.push.** { *; }
-keep class com.google.firebase.messaging.FirebaseMessagingService { *; }
-keep class * extends com.google.firebase.messaging.FirebaseMessagingService { *; }
-dontwarn com.google.firebase.**
-keep class ru.greemlab.neiro.sync.LiveApiRefreshWorker { *; }
-keep class ru.greemlab.neiro.notifications.SessionNotificationBootReceiver { *; }
# Сохраняем сервисы, чтобы система не теряла связь с UID при запуске Job/Alarm.
-keep class androidx.work.impl.background.systemjob.SystemJobService { *; }
-keep class androidx.work.impl.background.systemalarm.SystemAlarmService { *; }
-keep class androidx.work.impl.foreground.SystemForegroundService { *; }

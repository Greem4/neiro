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
# Одного @SerializedName тут мало: R8 сохраняет атрибут Signature только у классов
# под keep-правилом. Без keep у поля Map<String, MonthEntry> стираются параметры
# типа, Gson читает его как сырой Map и кладёт LinkedTreeMap вместо MonthEntry —
# профиль в release падал с ClassCastException, а debug (minify off) работал.
-keep class ru.greemlab.neiro.domain.models.** { *; }
-keep class ru.greemlab.neiro.data.SalaryLedger { *; }
-keep class ru.greemlab.neiro.data.SessionMeta { *; }
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
# Пакет целиком, а не точечно: рядом с воркерами тут лежат Gson-модели push
# (PushApi, PushSessionEvent), у части полей нет @SerializedName — сузим правило,
# и R8 переименует type/date/time/kind, а разбор payload молча сломается в release.
-keep class ru.greemlab.neiro.push.** { *; }
-keep class com.google.firebase.messaging.FirebaseMessagingService { *; }
-keep class * extends com.google.firebase.messaging.FirebaseMessagingService { *; }
-dontwarn com.google.firebase.**
-keep class ru.greemlab.neiro.notifications.SessionNotificationBootReceiver { *; }
# Сохраняем сервисы, чтобы система не теряла связь с UID при запуске Job/Alarm.
-keep class androidx.work.impl.background.systemjob.SystemJobService { *; }
-keep class androidx.work.impl.background.systemalarm.SystemAlarmService { *; }
-keep class androidx.work.impl.foreground.SystemForegroundService { *; }

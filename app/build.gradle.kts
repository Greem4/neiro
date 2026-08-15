import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // Принимает профиль от :baselineprofile и подмешивает его в release.
    // Ручной app/src/main/baseline-prof.txt при этом остаётся в силе — они
    // складываются, а не вытесняют друг друга.
    alias(libs.plugins.androidx.baselineprofile)
}

// ------------------------------------------------------------
// Настройки сервиса Neiro из local.properties → BuildConfig.
// Сам local.properties в .gitignore, ключи не уезжают в репозиторий.
//
// Ключей YClients здесь больше нет: partner_token и user_token живут на Pi,
// приложение ходит только в свой сервис (docs/neiro-push/ARCHITECTURE.md).
// Ключ приложения открывает ровно одну дверь — вход по логину и паролю.
// ------------------------------------------------------------
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val devLogin: String = localProps.getProperty("DEV_LOGIN", "")
val devPassword: String = localProps.getProperty("DEV_PASSWORD", "")
val releaseStoreFile: String = localProps.getProperty("RELEASE_STORE_FILE", "")
val releaseStorePassword: String = localProps.getProperty("RELEASE_STORE_PASSWORD", "")
val releaseKeyAlias: String = localProps.getProperty("RELEASE_KEY_ALIAS", "")
val releaseKeyPassword: String = localProps.getProperty("RELEASE_KEY_PASSWORD", "")
// Публичный адрес сервиса; пути в приложении начинаются с v1/, поэтому номера
// версии в базовом URL быть не должно (API.md § Про префиксы).
val neiroPushApiBaseUrl: String = localProps.getProperty(
    "NEIRO_PUSH_API_BASE_URL",
    "https://push.neiro.greemlab.ru",
)
val neiroPushApiKey: String = localProps.getProperty("NEIRO_PUSH_API_KEY", "")
val hasGoogleServices = file("google-services.json").exists()
val pushServerConfigured: Boolean =
    neiroPushApiBaseUrl.isNotBlank() && neiroPushApiKey.isNotBlank()
val hasReleaseSigning: Boolean =
    releaseStoreFile.isNotBlank() &&
        releaseStorePassword.isNotBlank() &&
        releaseKeyAlias.isNotBlank() &&
        releaseKeyPassword.isNotBlank()
// Считаем здесь, а не внутри buildTypes { release { … } }: там `any` цепляется
// к неявному ресиверу вложенных лямбд DSL, и IDE справедливо подсвечивает это
// как подозрительный вызов.
val releaseTaskRequested: Boolean =
    gradle.startParameter.taskNames.any { it.contains("Release") }

// ------------------------------------------------------------
// Версия живёт в version.properties, а не здесь: тот же файл читает CI,
// сверяя его с тегом. Один источник — нельзя выпустить тег v0.2.0 из
// сборки, которая внутри считает себя 0.1.0.
// ------------------------------------------------------------
val versionProps = Properties().apply {
    val file = rootProject.file("version.properties")
    if (!file.exists()) throw GradleException("Нет version.properties в корне проекта")
    file.inputStream().use { load(it) }
}
val appVersionName: String = versionProps.getProperty("VERSION").orEmpty()
val appVersionCode: Int = run {
    val parts = Regex("""^(\d+)\.(\d+)\.(\d+)$""").matchEntire(appVersionName)
        ?: throw GradleException("VERSION должна быть вида X.Y.Z, сейчас «$appVersionName»")
    val (major, minor, patch) = parts.destructured.toList().map(String::toInt)
    // Та же формула живёт в ReleaseVersion.kt — разойдутся, и приложение
    // начнёт предлагать обновление на самого себя.
    if (minor > 99 || patch > 99) {
        throw GradleException("minor и patch не больше 99: «$appVersionName»")
    }
    major * 10_000 + minor * 100 + patch
}

if (hasGoogleServices) {
    plugins.apply("com.google.gms.google-services")
}

android {
    namespace = "ru.greemlab.neiro"
    //noinspection GradleDependency
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.greemlab.neiro"
        minSdk = 24
        //noinspection OldTargetApi
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables { useSupportLibrary = true }

        buildConfigField("String", "NEIRO_PUSH_API_BASE_URL", "\"$neiroPushApiBaseUrl\"")
        buildConfigField("String", "NEIRO_PUSH_API_KEY", "\"$neiroPushApiKey\"")
        buildConfigField("boolean", "PUSH_FCM_ENABLED", hasGoogleServices.toString())
        buildConfigField("boolean", "PUSH_SERVER_CONFIGURED", pushServerConfigured.toString())

        // Самообновление разрешено только релизной сборке: у debug и prerelease
        // другой applicationId, и релизный APK для них — не обновление, а второе
        // приложение рядом. release переопределяет UPDATE_ENABLED на true.
        buildConfigField("boolean", "UPDATE_ENABLED", "false")
        buildConfigField("String", "UPDATE_REPO", "\"Greem4/neiro\"")
    }

    @Suppress("UnstableApiUsage")
    androidResources {
        localeFilters += listOf("ru")
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
            // Отключаем регистрацию профилировщика в debug — быстрее холодный старт при разработке.
            isDebuggable = true
            buildConfigField("String", "DEV_LOGIN", "\"$devLogin\"")
            buildConfigField("String", "DEV_PASSWORD", "\"$devPassword\"")
        }
        create("prerelease") {
            initWith(getByName("release"))
            applicationIdSuffix = ".prerelease"
            versionNameSuffix = "-pre"
            // Промежуточная сборка перед релизом: release-поведение, но отдельный пакет.
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            buildConfigField("String", "DEV_LOGIN", "\"\"")
            buildConfigField("String", "DEV_PASSWORD", "\"\"")
        }
        release {
            buildConfigField("String", "DEV_LOGIN", "\"\"")
            buildConfigField("String", "DEV_PASSWORD", "\"\"")
            // Единственная сборка, которая обновляет сама себя. prerelease его
            // не наследует: initWith выше сработал до этого блока.
            buildConfigField("boolean", "UPDATE_ENABLED", "true")
            isMinifyEnabled = true
            isShrinkResources = true
            isCrunchPngs = true
            @Suppress("UnstableApiUsage")
            installation {
                enableBaselineProfile = true
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig =
                when {
                    hasReleaseSigning -> signingConfigs.getByName("release")
                    releaseTaskRequested ->
                        throw GradleException(
                            "Release требует RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, " +
                                "RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD в local.properties. " +
                                "Соберите debug или prerelease для локальной отладки."
                        )
                    else -> null
                }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    // Сразу отключаем всё, что приложению не нужно — экономит время сборки и размер APK.
    // buildConfig включён намеренно: пробрасываем адрес и ключ сервиса Neiro
    // из local.properties в скомпилированный класс BuildConfig.
    buildFeatures {
        compose = true
        buildConfig = true
        resValues = false
        shaders = false
        viewBinding = false
        dataBinding = false
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "/META-INF/*.kotlin_module",
                "/META-INF/versions/**",
                "kotlin/**",
                "**/*.kotlin_metadata",
                "**/*.kotlin_builtins",
                "DebugProbesKt.bin",
            )
        }
    }

    // Lint работает в release, но не блокирует сборку — баги видны, но не валят CI.
    lint {
        checkReleaseBuilds = true
        abortOnError = false
        checkDependencies = false
        warningsAsErrors = false
    }
}

// Настройка имени выходного APK файла + подключение baseline-profile к release-варианту.
@Suppress("UnstableApiUsage")
androidComponents {
    onVariants { variant ->
        val vName = android.defaultConfig.versionName ?: "unknown"
        val variantSuffix =
            when (variant.name) {
                "debug" -> "debug"
                "prerelease" -> "pre-release"
                "release" -> "release"
                else -> variant.name
            }
        variant.outputs.forEach { output ->
            output.outputFileName.set("neiro-v$vName-$variantSuffix.apk")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-Xjvm-default=all",
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }
}

// Compose Compiler 2.x: strong skipping mode и intrinsic remember уже включены по умолчанию.
// Здесь оставляем хук на случай подключения отчётов/метрик через -Pcompose.reports="..." при разработке.

dependencies {
    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))

    // Compose UI
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // Compose Activity
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)

    // Core KTX + SplashScreen для мгновенного старта без чёрного окна.
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)

    // Lifecycle
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // collectAsStateWithLifecycle — подписки Compose не будят UI в STOPPED
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)

    // Фоновая автосинхронизация YClients
    implementation(libs.androidx.work.runtime.ktx)

    // ViewModel для Compose
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.material.icons.extended)

    // DataStore для сохранения данных
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.gson)

    // Network (YClients API)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Security для хранения токенов
    implementation(libs.security.crypto)

    // Загрузка аватара YClients в профиле
    implementation(libs.coil.compose)

    // Baseline profile (ускоряет холодный старт Compose)
    implementation(libs.androidx.profileinstaller)

    // Откуда :app берёт снятый профиль. Без этой строки плагин выше не знает
    // о модуле-генераторе, и задачи :app:generateReleaseBaselineProfile нет.
    baselineProfile(project(":baselineprofile"))

    // Core library desugaring (java.time на API < 26)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // FCM (нужен google-services.json для токена; без файла сборка проходит, push выключен)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    // Test
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    // Debug
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

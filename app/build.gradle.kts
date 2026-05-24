import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ------------------------------------------------------------
// Секреты YClients из local.properties → BuildConfig.
// Сам local.properties в .gitignore, ключи не уезжают в репозиторий.
// При пустых значениях приложение собирается, но API не заработает,
// пока пользователь не введёт Partner Token вручную (через UI).
// ------------------------------------------------------------
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val yclientsPartnerToken: String = localProps.getProperty("YCLIENTS_PARTNER_TOKEN", "")
val yclientsCompanyId: String = localProps.getProperty("YCLIENTS_COMPANY_ID", "0")
val devLogin: String = localProps.getProperty("DEV_LOGIN", "")
val devPassword: String = localProps.getProperty("DEV_PASSWORD", "")

android {
    namespace = "ru.greemlab.neiro"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.greemlab.neiro"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "0.6.5.245"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables { useSupportLibrary = true }

        // YClients: Partner Token и ID филиала прокидываются из local.properties.
        // Доступны в коде как BuildConfig.YCLIENTS_PARTNER_TOKEN и BuildConfig.YCLIENTS_COMPANY_ID.
        buildConfigField("String", "YCLIENTS_PARTNER_TOKEN", "\"$yclientsPartnerToken\"")
        buildConfigField("int", "YCLIENTS_COMPANY_ID", yclientsCompanyId)
        buildConfigField("String", "DEV_LOGIN", "\"$devLogin\"")
        buildConfigField("String", "DEV_PASSWORD", "\"$devPassword\"")
    }

    // Уменьшаем APK: только нужные локали (актуальный API в AGP 9.x).
    androidResources {
        localeFilters += listOf("ru", "en")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            // Отключаем регистрацию профилировщика в debug — быстрее холодный старт при разработке.
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isCrunchPngs = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    // Сразу отключаем всё, что приложению не нужно — экономит время сборки и размер APK.
    // buildConfig включён намеренно: пробрасываем секреты YClients (Partner Token, Company ID)
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
androidComponents {
    onVariants { variant ->
        val vName = android.defaultConfig.versionName ?: "unknown"
        variant.outputs.forEach { output ->
            output.outputFileName.set("neiro-v$vName-pre-release.apk")
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

    // Core library desugaring (java.time на API < 26)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

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

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ru.greemlab.neiro"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.greemlab.neiro"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "0.5.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables { useSupportLibrary = true }
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
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    // Сразу отключаем всё, что приложению не нужно — экономит время сборки и размер APK.
    // (buildConfig/aidl/renderScript уже отключены по умолчанию в AGP 9.x.)
    buildFeatures {
        compose = true
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

    // Lint в release-сборке выключен — отдельный шаг CI быстрее запускать вручную.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
        checkDependencies = false
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

    // ViewModel для Compose
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.material.icons.extended)

    // DataStore для сохранения данных
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.gson)

    // Immutable-коллекции — стабильные параметры для Compose, меньше рекомпозиций.
    implementation(libs.kotlinx.collections.immutable)

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

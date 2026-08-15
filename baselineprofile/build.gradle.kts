import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Модуль существует ради одного: снять baseline-профиль настоящим запуском
// приложения, а не написать его руками. Ручной файл для этого приложения жил
// до первой перестройки главного экрана — R8 сливает и инлайнит классы, и
// правила переставали матчиться молча (аудит 14.08.26, B1).
//
// Прогон (нужен подключённый телефон, API 28+):
//
//   ./gradlew :app:generateReleaseBaselineProfile
//
// Задача собирает настоящий release, поэтому в local.properties нужны ключи
// подписи — иначе :app упадёт с требованием RELEASE_STORE_FILE и остальных.
//
// Плагин положит результат в app/src/release/generated/baselineProfiles/ и
// сам подмешает его в сборку. После первого удачного прогона ручной
// app/src/main/baseline-prof.txt можно удалить.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "ru.greemlab.neiro.baselineprofile"
    //noinspection GradleDependency
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // Профиль снимается только на API 28+: до него ART его не понимает.
        // Само приложение остаётся minSdk 24 — на старых устройствах оно
        // просто стартует без AOT-подготовки, как и раньше.
        minSdk = 28
        //noinspection OldTargetApi
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Имена вариантов тестового модуля обязаны совпадать с :app, иначе Gradle
    // не найдёт под них приложение. Тело пустое намеренно: из каждого
    // объявленного здесь типа (кроме debug) плагин сам достраивает пару
    // nonMinified*/benchmark* и проставляет им отладочную подпись и
    // matchingFallbacks. Пару под release он добавляет сам, а вот prerelease
    // придумать не может — его и объявляем.
    //
    // Типа release здесь не заводим и от него не наследуемся: в com.android.test
    // по умолчанию существует только debug, и getByName("release") падает.
    buildTypes {
        create("prerelease")
    }

    targetProjectPath = ":app"
}

baselineProfile {
    // Гоняем на подключённом телефоне, а не на managed-устройстве: образ
    // эмулятора весит гигабайты, а телефон для этого проекта и так под рукой.
    useConnectedDevices = true
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.benchmark.macro.junit4)
}

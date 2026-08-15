// Top-level build file where you can add configuration options common to all sub-projects/modules.
// Плагины перечислены здесь с apply false не для красоты: так их версия
// попадает в корневой classpath, и модули могут запрашивать их по alias.
// com.android.test лежит в том же артефакте, что и com.android.application, —
// без строки ниже Gradle видит его на classpath «с неизвестной версией» и
// отказывается разрешать запрос из :baselineprofile.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.google.services) apply false
}

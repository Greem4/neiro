package ru.greemlab.neiro.update

/**
 * Версия релиза, разобранная из тега вида `v0.2.0`.
 *
 * versionCode считается той же формулой, что и в build.gradle.kts:
 * major * 10000 + minor * 100 + patch. Если формулы разойдутся,
 * приложение начнёт предлагать «обновление» на само себя — поэтому
 * обе стороны проверяются тестами, а CI сверяет тег с version.properties.
 */
data class ReleaseVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<ReleaseVersion> {

    val versionCode: Int get() = major * 10_000 + minor * 100 + patch
    val versionName: String get() = "$major.$minor.$patch"

    /**
     * Сравниваем сборки только по versionCode. Не по строке: «0.10.0» меньше
     * «0.9.0» по алфавиту, и однажды приложение предложило бы откатиться.
     */
    override fun compareTo(other: ReleaseVersion): Int = versionCode.compareTo(other.versionCode)

    /** Новее установленной сборки — единственное условие показать обновление. */
    fun isNewerThan(installedVersionCode: Int): Boolean = versionCode > installedVersionCode

    companion object {
        private val TAG_REGEX = Regex("""^v(\d+)\.(\d+)\.(\d+)$""")

        /** null — тег не нашей схемы; такой релиз молча игнорируем. */
        fun parseTag(tag: String): ReleaseVersion? {
            val m = TAG_REGEX.matchEntire(tag.trim()) ?: return null
            val (major, minor, patch) = m.destructured
            // Числа из тега могут не влезть в Int — «v99999999999.0.0» не наш
            // релиз, а мусор, и падать на нём разбор не должен.
            val v = ReleaseVersion(
                major.toIntOrNull() ?: return null,
                minor.toIntOrNull() ?: return null,
                patch.toIntOrNull() ?: return null,
            )
            // minor и patch выше 99 сломали бы монотонность versionCode:
            // 0.1.100 и 0.2.0 дали бы одно и то же число.
            return if (v.minor > 99 || v.patch > 99) null else v
        }
    }
}

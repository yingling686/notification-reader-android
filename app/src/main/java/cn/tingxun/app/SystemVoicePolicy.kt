package cn.tingxun.app

object SystemVoicePolicy {
    private const val OPLUS_ENGINE = "com.oplus.ttsaccessibilityengine"
    /** This engine reports its installed Chinese voices as Locale("chn"). */
    fun chinese(language: String, enginePackage: String?): Boolean = language.equals("zh", true) ||
        (enginePackage == OPLUS_ENGINE && language.equals("chn", true))

    fun offlineChinese(language: String, networkRequired: Boolean, enginePackage: String?) =
        !networkRequired && chinese(language, enginePackage)

    fun label(language: String, enginePackage: String?, original: String) =
        if (enginePackage == OPLUS_ENGINE && language.equals("chn", true)) "中文" else original
}

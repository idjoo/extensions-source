package eu.kanade.tachiyomi.lib.dramaplatform

import java.net.URLEncoder

data class DramaPlatformLocale(
    val siteLocale: String,
    val aniyomiLang: String,
    val displayName: String,
    val popularCategorySlug: String = "populer",
    val latestCategorySlug: String = "newly-added",
) {
    val pathPrefix: String
        get() = if (siteLocale == "en") "" else "/$siteLocale"
}

object DramaPlatformLocales {
    val ALL: List<DramaPlatformLocale> = listOf(
        DramaPlatformLocale("en", "en", "English", popularCategorySlug = "popular", latestCategorySlug = "new-releases"),
        DramaPlatformLocale("de", "de", "Deutsch", popularCategorySlug = "trending", latestCategorySlug = "new-releases"),
        DramaPlatformLocale("es", "es", "Español", popularCategorySlug = "popular", latestCategorySlug = "new-releases"),
        DramaPlatformLocale("ru", "ru", "Русский", popularCategorySlug = "popular", latestCategorySlug = "new-releases"),
        DramaPlatformLocale("fr", "fr", "Français"),
        DramaPlatformLocale("ar", "ar", "العربية"),
        DramaPlatformLocale("ko", "ko", "한국어"),
        DramaPlatformLocale("th", "th", "ไทย"),
        DramaPlatformLocale("pt", "pt-BR", "Português"),
        DramaPlatformLocale("it", "it", "Italiano"),
        DramaPlatformLocale("ja", "ja", "日本語"),
        DramaPlatformLocale("id", "id", "Bahasa Indonesia"),
        DramaPlatformLocale("vi", "vi", "Tiếng Việt"),
        DramaPlatformLocale("fil", "fil", "Filipino"),
        DramaPlatformLocale("hi", "hi", "हिन्दी"),
        DramaPlatformLocale("ms", "ms", "Bahasa Melayu"),
    )
}

object DramaPlatformCatalogRoutes {

    fun popularUrl(baseUrl: String, locale: DramaPlatformLocale, page: Int): String =
        paginatedPathUrl(baseUrl, "${locale.pathPrefix}/category/${locale.popularCategorySlug}", page)

    fun latestUrl(baseUrl: String, locale: DramaPlatformLocale, page: Int): String =
        paginatedPathUrl(baseUrl, "${locale.pathPrefix}/category/${locale.latestCategorySlug}", page)

    fun allSeriesFallbackUrl(baseUrl: String, locale: DramaPlatformLocale, page: Int): String =
        paginatedPathUrl(baseUrl, "${locale.pathPrefix}/series", page)

    fun searchUrl(
        baseUrl: String,
        locale: DramaPlatformLocale,
        page: Int,
        query: String,
        categorySlug: String?,
        upstreamProviderSlug: String?,
    ): String {
        val cleanBase = baseUrl.trimEnd('/')
        val trimmedQuery = query.trim()
        if (trimmedQuery.isNotEmpty()) {
            val encodedQuery = URLEncoder.encode(trimmedQuery, "UTF-8")
            val pageSuffix = if (page > 1) "&page=$page" else ""
            return "$cleanBase${locale.pathPrefix}/search?q=$encodedQuery$pageSuffix"
        }
        if (!upstreamProviderSlug.isNullOrBlank()) {
            return paginatedPathUrl(cleanBase, "${locale.pathPrefix}/source/$upstreamProviderSlug", page)
        }
        if (!categorySlug.isNullOrBlank()) {
            return paginatedPathUrl(cleanBase, "${locale.pathPrefix}/category/$categorySlug", page)
        }
        return popularUrl(cleanBase, locale, page)
    }

    private fun paginatedPathUrl(baseUrl: String, path: String, page: Int): String {
        val cleanBase = baseUrl.trimEnd('/')
        val cleanPath = path.trimEnd('/')
        val pageSuffix = if (page > 1) "/page/$page" else ""
        return "$cleanBase$cleanPath$pageSuffix"
    }
}

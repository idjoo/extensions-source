package eu.kanade.tachiyomi.animeextension.all.dramatip

import java.net.URLEncoder

data class DramaTipLocale(
    val siteLocale: String,
    val aniyomiLang: String,
    val displayName: String,
    val popularCategorySlug: String = "populer",
    val latestCategorySlug: String = "newly-added",
) {
    val pathPrefix: String
        get() = if (siteLocale == "en") "" else "/$siteLocale"
}

object DramaTipLocales {
    val ALL: List<DramaTipLocale> = listOf(
        DramaTipLocale("en", "en", "English", popularCategorySlug = "popular", latestCategorySlug = "new-releases"),
        DramaTipLocale("de", "de", "Deutsch", popularCategorySlug = "trending", latestCategorySlug = "new-releases"),
        DramaTipLocale("es", "es", "Español", popularCategorySlug = "popular", latestCategorySlug = "new-releases"),
        DramaTipLocale("ru", "ru", "Русский", popularCategorySlug = "popular", latestCategorySlug = "new-releases"),
        DramaTipLocale("fr", "fr", "Français"),
        DramaTipLocale("ar", "ar", "العربية"),
        DramaTipLocale("ko", "ko", "한국어"),
        DramaTipLocale("th", "th", "ไทย"),
        DramaTipLocale("pt", "pt-BR", "Português"),
        DramaTipLocale("it", "it", "Italiano"),
        DramaTipLocale("ja", "ja", "日本語"),
        DramaTipLocale("id", "id", "Bahasa Indonesia"),
        DramaTipLocale("vi", "vi", "Tiếng Việt"),
        DramaTipLocale("fil", "fil", "Filipino"),
        DramaTipLocale("hi", "hi", "हिन्दी"),
        DramaTipLocale("ms", "ms", "Bahasa Melayu"),
    )
}

object DramaTipCatalogRoutes {

    fun popularUrl(baseUrl: String, locale: DramaTipLocale, page: Int): String =
        paginatedPathUrl(baseUrl, "${locale.pathPrefix}/category/${locale.popularCategorySlug}", page)

    fun latestUrl(baseUrl: String, locale: DramaTipLocale, page: Int): String =
        paginatedPathUrl(baseUrl, "${locale.pathPrefix}/category/${locale.latestCategorySlug}", page)

    fun allSeriesFallbackUrl(baseUrl: String, locale: DramaTipLocale, page: Int): String =
        paginatedPathUrl(baseUrl, "${locale.pathPrefix}/series", page)

    fun searchUrl(
        baseUrl: String,
        locale: DramaTipLocale,
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

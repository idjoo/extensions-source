package eu.kanade.tachiyomi.animeextension.all.dramatip

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.streamproxy.LoopbackStreamProxy
import eu.kanade.tachiyomi.network.GET
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class DramaTip(
    private val locale: DramaTipLocale,
) : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name: String = "DramaTip"

    override val lang: String = locale.anikkuLang

    override val supportsLatest: Boolean = true

    private val preferences: SharedPreferences? by lazy {
        runCatching { getSourcePreferences() }.getOrNull()
    }

    override val baseUrl: String
        get() {
            val custom = preferences?.getString(PREF_BASE_URL_KEY, DEFAULT_BASE_URL)?.trim()?.trimEnd('/')
            return if (!custom.isNullOrBlank() && (custom.startsWith("https://") || custom.startsWith("http://"))) {
                custom
            } else {
                DEFAULT_BASE_URL
            }
        }

    private val cipher: DramaTipCipher by lazy {
        val savedKey = preferences?.getString(PREF_ROTATED_AES_KEY, null)
            ?.takeIf(String::isNotBlank)
            ?: DramaTipCipher.DEFAULT_KEY_BASE64
        DramaTipCipher(
            initialKeyBase64 = savedKey,
            onKeyRotated = { newKey ->
                preferences?.edit()?.putString(PREF_ROTATED_AES_KEY, newKey)?.apply()
            },
        )
    }

    private val streamResolver: DramaTipStreamResolver by lazy {
        activeSiteBaseUrl = baseUrl
        DramaTipStreamResolver(
            httpClient = client,
            cipher = cipher,
            loopbackProxy = obtainSharedProxy(client),
            userAgent = USER_AGENT,
        )
    }

    private val refreshingFilters = AtomicBoolean(false)

    override fun headersBuilder(): Headers.Builder =
        DramaTipCdnRouter.buildCatalogHeaders(USER_AGENT)

    override fun popularAnimeRequest(page: Int): Request =
        GET(DramaTipCatalogRoutes.popularUrl(baseUrl, locale, page), headers)

    override fun popularAnimeParse(response: Response): AnimesPage =
        parseSeriesListingResponse(response)

    override suspend fun getPopularAnime(page: Int): AnimesPage =
        fetchShelfWithFallback(popularAnimeRequest(page), page)

    override fun latestUpdatesRequest(page: Int): Request =
        GET(DramaTipCatalogRoutes.latestUrl(baseUrl, locale, page), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage =
        parseSeriesListingResponse(response)

    override suspend fun getLatestUpdates(page: Int): AnimesPage =
        fetchShelfWithFallback(latestUpdatesRequest(page), page)

    private suspend fun fetchShelfWithFallback(primaryRequest: Request, page: Int): AnimesPage =
        withContext(Dispatchers.IO) {
            client.newCall(primaryRequest).execute().use { res ->
                if (res.isSuccessful) {
                    val parsed = parseSeriesListingHtml(res.body.string(), primaryRequest.url.toString())
                    if (parsed.animes.isNotEmpty() || page > 1) {
                        return@withContext parsed
                    }
                } else if (res.code != 404) {
                    error("HTTP error ${res.code}")
                }
            }
            val fallbackReq = GET(DramaTipCatalogRoutes.allSeriesFallbackUrl(baseUrl, locale, page), headers)
            client.newCall(fallbackReq).execute().use { fallbackRes ->
                if (!fallbackRes.isSuccessful) {
                    error("HTTP error ${fallbackRes.code}")
                }
                parseSeriesListingHtml(fallbackRes.body.string(), fallbackReq.url.toString())
            }
        }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val categorySlug = filters.filterIsInstance<CategoryFilter>().firstOrNull()?.selectedSlug()
        val providerSlug = filters.filterIsInstance<UpstreamProviderFilter>().firstOrNull()?.selectedSlug()
        val url = DramaTipCatalogRoutes.searchUrl(
            baseUrl = baseUrl,
            locale = locale,
            page = page,
            query = query,
            categorySlug = categorySlug,
            upstreamProviderSlug = providerSlug,
        )
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage =
        parseSeriesListingResponse(response)

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage = withContext(Dispatchers.IO) {
        val directPath = DramaTipParser.resolveDirectSeriesPath(query, locale.pathPrefix, baseUrl)
        if (directPath != null) {
            val req = GET("$baseUrl$directPath", headers)
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    return@withContext AnimesPage(emptyList(), false)
                }
                val details = DramaTipParser.parseSeriesDetails(res.body.string(), directPath, baseUrl)
                val anime = SAnime.create().apply {
                    url = directPath
                    title = details.title
                    thumbnail_url = details.thumbnailUrl
                    description = details.description
                    genre = details.categories.joinToString()
                    author = details.upstreamProvider
                    artist = details.upstreamProvider
                    status = details.status
                    initialized = true
                }
                return@withContext AnimesPage(listOf(anime), false)
            }
        }

        val req = searchAnimeRequest(page, query, filters)
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) {
                error("HTTP error ${res.code}")
            }
            parseSeriesListingHtml(res.body.string(), req.url.toString())
        }
    }

    override fun animeDetailsParse(response: Response): SAnime = response.use { res ->
        val seriesPath = res.request.url.encodedPath
        val details = DramaTipParser.parseSeriesDetails(res.body.string(), seriesPath, baseUrl)
        SAnime.create().apply {
            url = seriesPath
            title = details.title
            description = details.description
            thumbnail_url = details.thumbnailUrl
            genre = details.categories.joinToString()
            author = details.upstreamProvider
            artist = details.upstreamProvider
            status = details.status
            initialized = true
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = response.use { res ->
        val seriesPath = res.request.url.encodedPath
        val showLocked = preferences?.getBoolean(PREF_SHOW_LOCKED_KEY, false) ?: false
        val entries = DramaTipParser.parseEpisodeList(
            html = res.body.string(),
            seriesPath = seriesPath,
            showLockedEpisodes = showLocked,
            baseUrl = baseUrl,
        )
        entries.map { entry ->
            SEpisode.create().apply {
                url = entry.url
                name = entry.name
                episode_number = entry.episodeNumber
                scanlator = entry.upstreamProvider
            }
        }
    }

    @Suppress("unused")
    protected fun episodeVideoParse(response: Response): SEpisode {
        response.close()
        throw UnsupportedOperationException("Not used")
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> = withContext(Dispatchers.IO) {
        resolveVideosBlocking(episode)
    }

    @Deprecated("Use getVideoList instead", replaceWith = ReplaceWith("getVideoList(episode)"))
    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> =
        Observable.fromCallable { resolveVideosBlocking(episode) }

    override fun videoListParse(response: Response): List<Video> {
        response.close()
        throw UnsupportedOperationException("Use getVideoList")
    }

    override fun videoUrlParse(response: Response): String {
        response.close()
        throw UnsupportedOperationException("Video URLs are resolved in getVideoList")
    }

    override fun getFilterList(): AnimeFilterList {
        val prefs = preferences
        val cachedCategories = DramaTipFilters.deserializeOptions(prefs?.getString(PREF_CACHED_CATEGORIES_KEY, null))
            ?: DramaTipFilters.defaultCategoriesFor(locale)
        val cachedProviders = DramaTipFilters.deserializeOptions(prefs?.getString(PREF_CACHED_PROVIDERS_KEY, null))
            ?: DramaTipFilters.DEFAULT_UPSTREAM_PROVIDERS

        val updatedAt = prefs?.getLong(PREF_FILTERS_UPDATED_AT_KEY, 0L) ?: 0L
        if (prefs != null && System.currentTimeMillis() - updatedAt > FILTER_CACHE_TTL_MS) {
            refreshFiltersInBackground(prefs)
        }

        return AnimeFilterList(
            CategoryFilter(cachedCategories),
            UpstreamProviderFilter(cachedProviders),
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred Quality"
            entries = arrayOf("1080p", "720p", "480p", "270p")
            entryValues = arrayOf("1080p", "720p", "480p", "270p")
            setDefaultValue(DEFAULT_PREFERRED_QUALITY)
            summary = "%s"
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_FAST_HLS_KEY
            title = "Fast HLS Mode"
            summary = "Hand master HLS playlists directly to MPV without pre-parsing resolution variants."
            setDefaultValue(false)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_LOCKED_KEY
            title = "Show Locked Episodes"
            summary = "Display episodes marked by DramaTip as unavailable for free playback with a [Locked] prefix."
            setDefaultValue(false)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_BASE_URL_KEY
            title = "Base URL Override"
            summary = "Override the DramaTip domain if it migrates (default: $DEFAULT_BASE_URL)."
            setDefaultValue(DEFAULT_BASE_URL)
        }.also(screen::addPreference)
    }

    private fun resolveVideosBlocking(episode: SEpisode): List<Video> {
        val currentBase = baseUrl
        activeSiteBaseUrl = currentBase
        val preferredQuality = preferences?.getString(PREF_QUALITY_KEY, DEFAULT_PREFERRED_QUALITY)
            ?: DEFAULT_PREFERRED_QUALITY
        val fastHlsMode = preferences?.getBoolean(PREF_FAST_HLS_KEY, false) ?: false

        return streamResolver.resolveEpisodeVideos(
            episodeUrl = episode.url,
            baseUrl = currentBase,
            locale = locale,
            preferredQuality = preferredQuality,
            fastHlsMode = fastHlsMode,
        )
    }

    private fun parseSeriesListingResponse(response: Response): AnimesPage = response.use { res ->
        parseSeriesListingHtml(res.body.string(), res.request.url.toString())
    }

    private fun parseSeriesListingHtml(html: String, requestUrl: String): AnimesPage {
        val currentPage = PAGE_PATH_REGEX.find(requestUrl)?.groupValues?.get(1)?.toIntOrNull()
            ?: PAGE_QUERY_REGEX.find(requestUrl)?.groupValues?.get(1)?.toIntOrNull()
            ?: 1
        val parsed = DramaTipParser.parseSeriesPage(html, currentPage = currentPage, baseUrl = baseUrl)
        val animes = parsed.series.map { item ->
            SAnime.create().apply {
                url = item.url
                title = item.title
                thumbnail_url = item.thumbnailUrl
            }
        }
        return AnimesPage(animes, parsed.hasNextPage)
    }

    private fun refreshFiltersInBackground(prefs: SharedPreferences) {
        if (!refreshingFilters.compareAndSet(false, true)) return
        filterRefreshExecutor.execute {
            try {
                val cleanBase = baseUrl
                val catUrl = "$cleanBase${locale.pathPrefix}/categories"
                val srcUrl = "$cleanBase${locale.pathPrefix}/sources"

                val catOptions = runCatching {
                    client.newCall(GET(catUrl, headers)).execute().use { res ->
                        if (res.isSuccessful) DramaTipParser.parseCategories(res.body.string(), cleanBase) else emptyList()
                    }
                }.getOrDefault(emptyList())

                val srcOptions = runCatching {
                    client.newCall(GET(srcUrl, headers)).execute().use { res ->
                        if (res.isSuccessful) DramaTipParser.parseUpstreamProviders(res.body.string(), cleanBase) else emptyList()
                    }
                }.getOrDefault(emptyList())

                if (catOptions.isNotEmpty() || srcOptions.isNotEmpty()) {
                    val editor = prefs.edit()
                    if (catOptions.isNotEmpty()) {
                        editor.putString(PREF_CACHED_CATEGORIES_KEY, DramaTipFilters.serializeOptions(catOptions))
                    }
                    if (srcOptions.isNotEmpty()) {
                        editor.putString(PREF_CACHED_PROVIDERS_KEY, DramaTipFilters.serializeOptions(srcOptions))
                    }
                    editor.putLong(PREF_FILTERS_UPDATED_AT_KEY, System.currentTimeMillis())
                    editor.apply()
                }
            } finally {
                refreshingFilters.set(false)
            }
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://dramatip.net"
        const val DEFAULT_PREFERRED_QUALITY = "1080p"
        const val USER_AGENT = LoopbackStreamProxy.DEFAULT_USER_AGENT

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_FAST_HLS_KEY = "fast_hls_mode"
        private const val PREF_SHOW_LOCKED_KEY = "show_locked_episodes"
        private const val PREF_BASE_URL_KEY = "base_url_override"
        private const val PREF_ROTATED_AES_KEY = "rotated_aes_key_b64"
        private const val PREF_CACHED_CATEGORIES_KEY = "cached_categories_v1"
        private const val PREF_CACHED_PROVIDERS_KEY = "cached_providers_v1"
        private const val PREF_FILTERS_UPDATED_AT_KEY = "cached_filters_updated_at"
        private const val FILTER_CACHE_TTL_MS = 24 * 60 * 60 * 1000L

        private val PAGE_PATH_REGEX = Regex("""/page/(\d+)(?:[/?#]|$)""")
        private val PAGE_QUERY_REGEX = Regex("""[?&]page=(\d+)(?:[&#]|$)""")

        @Volatile
        private var activeSiteBaseUrl: String = DEFAULT_BASE_URL

        private val sharedProxyLock = Any()
        private var sharedProxy: LoopbackStreamProxy? = null

        private val filterRefreshExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "dramatip-filter-refresh").apply { isDaemon = true }
        }

        private fun obtainSharedProxy(client: OkHttpClient): LoopbackStreamProxy = synchronized(sharedProxyLock) {
            sharedProxy ?: LoopbackStreamProxy(
                httpClient = client,
                siteBaseUrlProvider = { activeSiteBaseUrl },
                userAgent = USER_AGENT,
            ).also { sharedProxy = it }
        }
    }
}

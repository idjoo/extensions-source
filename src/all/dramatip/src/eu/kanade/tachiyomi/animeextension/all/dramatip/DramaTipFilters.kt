package eu.kanade.tachiyomi.animeextension.all.dramatip

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

class CategoryFilter(
    private val options: List<FilterOption>,
) : AnimeFilter.Select<String>(
    name = "Category",
    values = (listOf("All Categories") + options.map { it.name }).toTypedArray(),
) {
    fun selectedSlug(): String? = if (state <= 0) null else options.getOrNull(state - 1)?.slug
}

class UpstreamProviderFilter(
    private val options: List<FilterOption>,
) : AnimeFilter.Select<String>(
    name = "Upstream Provider",
    values = (listOf("All Upstream Providers") + options.map { it.name }).toTypedArray(),
) {
    fun selectedSlug(): String? = if (state <= 0) null else options.getOrNull(state - 1)?.slug
}

object DramaTipFilters {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    val DEFAULT_CATEGORIES: List<FilterOption> = listOf(
        FilterOption("Most Watched", "popular"),
        FilterOption("New Releases", "new-releases"),
        FilterOption("Trending Now", "trending"),
        FilterOption("Revenge & Payback", "revenge"),
        FilterOption("Rise & Comeback", "rise-comeback"),
        FilterOption("Hidden Identity", "hidden-identity"),
        FilterOption("Second Chance & Rebirth", "second-chance"),
        FilterOption("Romance & Sweet Love", "romance"),
        FilterOption("Enemies to Lovers", "enemies-to-lovers"),
        FilterOption("Marriage & Love", "marriage-love"),
        FilterOption("CEO & Billionaire", "ceo-billionaire"),
        FilterOption("Rich Family & Elite", "rich-family"),
        FilterOption("Strong Women", "strong-women"),
        FilterOption("Family Drama", "family-drama"),
        FilterOption("Modern Drama", "modern-drama"),
        FilterOption("Historical & Period", "historical"),
        FilterOption("Fantasy & Supernatural", "fantasy"),
        FilterOption("Powerful Hero", "powerful-hero"),
        FilterOption("Adored & Pampered", "adored"),
        FilterOption("Anime", "anime"),
    )

    val DEFAULT_UPSTREAM_PROVIDERS: List<FilterOption> = listOf(
        FilterOption("DramaBox", "dramabox"),
        FilterOption("GoodShort", "goodshort"),
        FilterOption("Vigloo", "vigloo"),
        FilterOption("Reelshort", "reelshort"),
        FilterOption("FlickReels", "flickreels"),
        FilterOption("MyDramaWave", "mydramawave"),
        FilterOption("MoboReels", "moboreels"),
        FilterOption("NetShort", "netshort"),
        FilterOption("ShortTV", "shorttv"),
        FilterOption("IDrama", "idrama"),
        FilterOption("Stardust", "stardust"),
        FilterOption("FreeReels", "freereels"),
        FilterOption("KalosTV", "kalostv"),
        FilterOption("JoyReels", "joyreels"),
        FilterOption("MyDrama", "mydrama"),
        FilterOption("FlareFlow", "flareflow"),
        FilterOption("HappyShort", "happyshort"),
        FilterOption("StoreEl", "storeel"),
        FilterOption("StoryReel", "storyreel"),
        FilterOption("PetaDrama", "petadrama"),
        FilterOption("ToonShort", "toonshort"),
        FilterOption("HaHip", "hahip"),
        FilterOption("AuraReel", "aura"),
        FilterOption("BiliTV", "bilitv"),
    )

    fun defaultCategoriesFor(locale: DramaTipLocale): List<FilterOption> = buildList {
        add(FilterOption("Most Watched", locale.popularCategorySlug))
        add(FilterOption("New Releases", locale.latestCategorySlug))
        for (option in DEFAULT_CATEGORIES.drop(2)) {
            if (none { it.slug == option.slug }) {
                add(option)
            }
        }
    }

    fun serializeOptions(options: List<FilterOption>): String = json.encodeToString(
        buildJsonArray {
            for (opt in options) {
                add(
                    buildJsonObject {
                        put("n", JsonPrimitive(opt.name))
                        put("s", JsonPrimitive(opt.slug))
                    },
                )
            }
        },
    )

    fun deserializeOptions(raw: String?): List<FilterOption>? {
        if (raw.isNullOrBlank()) return null
        val arr = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonArray ?: return null
        val parsed = arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val name = (obj["n"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val slug = (obj["s"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            FilterOption(name = name, slug = slug)
        }
        return parsed.takeIf { it.isNotEmpty() }
    }
}

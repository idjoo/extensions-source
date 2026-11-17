package eu.kanade.tachiyomi.animeextension.en.dramatap

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

/** Category picker backed by `/tag/{slug}` pages. Ignored when a text query is entered. */
class CategoryFilter : AnimeFilter.Select<String>("Category", CATEGORIES.map { it.first }.toTypedArray()) {
    fun selectedSlug(): String? = CATEGORIES[state].second.ifEmpty { null }
}

fun dramaTapFilters() = AnimeFilterList(
    AnimeFilter.Header("Text search ignores the Category filter"),
    CategoryFilter(),
)

/** Largest Categories from https://dramatap.app/tags (300+ Series each). */
private val CATEGORIES = listOf(
    "Any" to "",
    "Revenge" to "revenge",
    "Female" to "female",
    "Romance" to "romance",
    "Plot" to "plot",
    "Contemporary" to "contemporary",
    "Counterattack" to "counterattack",
    "Fantasy" to "fantasy",
    "Underdog Rise" to "underdog-rise",
    "Playing Dumb" to "playing-dumb",
    "Strong Female Lead" to "strong-female-lead",
    "Male" to "male",
    "Urban" to "urban",
    "Betrayal" to "betrayal",
    "Dark Romance" to "dark-romance",
    "Rags to Riches" to "rags-to-riches",
    "CEO" to "ceo",
    "Rebirth" to "rebirth",
    "New" to "new",
    "Feel-Good" to "feel-good",
    "Comeback" to "comeback",
    "Women's growth" to "women-s-growth",
    "Karma Payback" to "karma-payback",
    "Scumbag-punishing" to "scumbag-punishing",
    "Contemporary Romance" to "contemporary-romance",
    "Drama" to "drama",
    "Time Travel" to "time-travel",
    "Werewolf" to "werewolf",
    "Family" to "family",
    "Independent Woman" to "independent-woman",
    "Regret" to "regret",
    "Family Drama" to "family-drama",
    "Sweet" to "sweet",
    "Strong Heroine" to "strong-heroine",
    "Reborn" to "reborn",
    "Second Chance" to "second-chance",
    "Plot Twist" to "plot-twist",
    "Payback" to "payback",
    "Toxic Love" to "toxic-love",
    "Ambitious" to "ambitious",
    "Sweet Romance" to "sweet-romance",
    "Modern Romance" to "modern-romance",
    "Powerful" to "powerful",
    "Destiny" to "destiny",
    "Wealthy Family" to "wealthy-family",
    "Misunderstanding" to "misunderstanding",
    "Contract Marriage" to "contract-marriage",
    "Heiress" to "heiress",
    "Elite" to "elite",
    "Mafia" to "mafia",
    "Tragic Love" to "tragic-love",
)

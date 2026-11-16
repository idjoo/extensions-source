package eu.kanade.tachiyomi.animeextension.all.dramatip

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory

class DramaTipFactory : AnimeSourceFactory {
    override fun createSources(): List<AnimeSource> =
        DramaTipLocales.ALL.map(::DramaTip)
}

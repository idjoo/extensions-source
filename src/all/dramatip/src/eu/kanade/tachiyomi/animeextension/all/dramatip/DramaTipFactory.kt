package eu.kanade.tachiyomi.animeextension.all.dramatip

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import eu.kanade.tachiyomi.lib.dramaplatform.DramaPlatformLocales

class DramaTipFactory : AnimeSourceFactory {
    override fun createSources(): List<AnimeSource> =
        DramaPlatformLocales.ALL.map(::DramaTip)
}

package eu.kanade.tachiyomi.animeextension.all.dramaexpress

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import eu.kanade.tachiyomi.lib.dramaplatform.DramaPlatformLocales

class DramaExpressFactory : AnimeSourceFactory {
    override fun createSources(): List<AnimeSource> =
        DramaPlatformLocales.ALL.map(::DramaExpress)
}

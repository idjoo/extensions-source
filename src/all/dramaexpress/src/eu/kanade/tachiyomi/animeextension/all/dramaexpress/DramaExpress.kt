package eu.kanade.tachiyomi.animeextension.all.dramaexpress

import eu.kanade.tachiyomi.lib.dramaplatform.DramaPlatformLocale
import eu.kanade.tachiyomi.lib.dramaplatform.DramaPlatformSource

class DramaExpress(locale: DramaPlatformLocale) : DramaPlatformSource(locale) {

    override val name: String = "DramaExpress"

    override val defaultBaseUrl: String = DEFAULT_BASE_URL

    companion object {
        const val DEFAULT_BASE_URL = "https://dramaexpress.net"
    }
}

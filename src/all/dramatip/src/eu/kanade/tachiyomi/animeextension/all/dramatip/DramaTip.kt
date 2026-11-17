package eu.kanade.tachiyomi.animeextension.all.dramatip

import eu.kanade.tachiyomi.lib.dramaplatform.DramaPlatformLocale
import eu.kanade.tachiyomi.lib.dramaplatform.DramaPlatformSource

class DramaTip(locale: DramaPlatformLocale) : DramaPlatformSource(locale) {

    override val name: String = "DramaTip"

    override val defaultBaseUrl: String = DEFAULT_BASE_URL

    companion object {
        const val DEFAULT_BASE_URL = "https://dramatip.net"
    }
}

# Shared drama platform library

DramaExpress (`dramaexpress.net`) runs the same Next.js platform as DramaTip: identical `/series/` paths, bookIds, Locales, and encrypted `/api/episode-source/` Stream Descriptors. Instead of copying the DramaTip module, we moved the Catalog Source, parsers, AES-GCM cipher, 9:16 HLS parser, CDN routing, and Stream Chain resolver into `lib/drama-platform` (package `eu.kanade.tachiyomi.lib.dramaplatform`). Each extension is a thin subclass of `DramaPlatformSource` that sets its display name and default domain, and keeps its own package, so source IDs (derived from name, lang, and versionId) and update paths stay stable.

The only per-site network difference is the `Origin`/`Referer` sent to `cdn.dramadizilerim.xyz`. It defaults to the extension's default domain. On 2026-11-17 the CDN accepted both `https://dramatip.net` and `https://dramaexpress.net` and rejected requests without an Origin (`451`), so each site sends its own.

We chose a `lib/` module over a keiyoushi-style `lib-multisrc/` theme because this repo's Gradle setup already auto-includes `lib/*` and there are only two sites.

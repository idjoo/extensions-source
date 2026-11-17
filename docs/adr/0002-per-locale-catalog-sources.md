# Register one Catalog Source per DramaTip Locale

DramaTip publishes 16 regional editions that alter both catalog shelving and per-episode stream/subtitle language, while Aniyomi (and forks such as Anikku) binds library bookmarks and watch history permanently to a deterministic hash of `(name, lang, versionId)`. We use `AnimeSourceFactory` to register 16 separate Catalog Sources rather than a single source with a locale setting, accepting multiple entries in the app's global catalog list so users get native language filtering, side-by-side multi-language libraries, and stable library IDs.

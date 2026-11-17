# DramaTip Aniyomi Extension

This context bridges DramaTip's aggregated vertical short-drama catalog into Aniyomi-compatible apps (Aniyomi, Anikku, and other forks) so users can browse, track, stream, and download microdrama series alongside their other video libraries.

## Language

**Catalog Source**:
A localized DramaTip catalog exposed as a distinct browsable entry in the app for a single Locale.
_Avoid_: Provider, channel, feed

**Locale**:
One of the regional language editions published by DramaTip, governing catalog shelves, localized metadata, and preferred audio or subtitle language.
_Avoid_: Region, market

**Upstream Provider**:
A third-party short-drama platform (such as Vigloo, DramaBox, or GoodShort) from which DramaTip aggregates Series and streams.
_Avoid_: Source, studio, origin

**Category**:
A genre, trope, or editorial shelf grouping Series within a Locale (such as Popular, New Releases, Revenge, or CEO/Billionaire).
_Avoid_: Tag, collection

**Series**:
A vertical microdrama title composed of sequential short-form installments.
_Avoid_: Anime, book, film, show

**Episode**:
A single numbered installment of a Series.
_Avoid_: Chapter, part

**Locked Episode**:
An Episode marked by DramaTip as unavailable for free playback.
_Avoid_: VIP episode, paywalled chapter

**Stream Descriptor**:
The localized playback record for an Episode, pairing its Stream Chain with any standalone subtitle track.
_Avoid_: Video response, source object

**Stream Chain**:
An ordered fallback sequence of candidate video streams returned inside a Stream Descriptor for playing an Episode in a given Locale.
_Avoid_: Mirror list, playlist

**Extension Repository**:
A static HTTP index publishing signed extension APK metadata and icons so Aniyomi-compatible apps can install and update the extension via URL.
_Avoid_: App store, plugin feed

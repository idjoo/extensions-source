# AGENTS.md

Anikku (`komikku-app/anikku`) video extension repository using the standard `aniyomi-extensions` multi-module layout (`common.gradle`, `core/`, `lib/`, `src/all/dramatip/`).

## Build and verification

Run all Gradle and Android commands inside the Nix dev shell (`nix develop -c <cmd>`):

- **Unit tests**: `nix develop -c ./gradlew testDebugUnitTest`
- **Live smoke tests** (opt-in network check against `dramatip.net`): `nix develop -c ./gradlew testDebugUnitTest -PliveSmoke=true`
- **Debug APK**: `nix develop -c ./gradlew :extensions:all:dramatip:assembleDebug`
- **Headless APK check**: run `komikku-app/aniyomi-extensions-tester` against the built `.apk` (`--tests popular,latest,search,anidetails,eplist,videolist --check-videos`).

Gotcha on `aarch64-linux`: Google Maven publishes `x86_64` binaries only for `aapt2`. `flake.nix` sets `AAPT2_FROM_MAVEN_OVERRIDE` to a native `aarch64` `aapt2` binary and `settings.gradle.kts` forwards it to `android.aapt2FromMavenOverride`. Keep that wiring intact.

## Coding standards

- **Domain language**: Name public classes, methods, and tests after `GLOSSARY.md` (`Catalog Source`, `Locale`, `Upstream Provider`, `Category`, `Series`, `Episode`, `Locked Episode`, `Stream Descriptor`, `Stream Chain`, `Extension Repository`) and respect `docs/adr/0001` through `0006`.
- **Deep JVM-testable modules**: Keep HTML/RSC parsing, AES-GCM descriptor decryption, 9:16 HLS parsing, and loopback stream proxying behind small pure-Kotlin interfaces so JUnit tests exercise real behavior without mocking Android internals.
- **OkHttp hygiene**: Close every `Response` (`response.use { ... }`). Always send a browser `User-Agent` (DramaTip blocks `okhttp`/`curl`/`httpclient`). Attach same-origin `Referer` on `/api/episode-source/`, attach `Origin: https://dramatip.net` and `Referer: https://dramatip.net/` on `cdn.dramadizilerim.xyz`, and omit `Referer`/`Origin` on `video.reelintv.com` and `cfcdn.petadrama.com`.
- **Loopback proxy safety**: Bind `LoopbackStreamProxy` strictly to `127.0.0.1` on an ephemeral port, require the random per-session token on every request, allow upstream fetches only to `https://` public hosts (plus configured localhost test upstream in unit tests), and stop automatically after idle timeout.
- **Paywall respect**: Treat `is_locked == true` as a `Locked Episode`. Hide Locked Episodes by default and never attempt to unlock or bypass them.
- **Copy style**: Write concise prose and comments without em dashes.

## Agent skills

### Issue tracker

Issues live in GitHub Issues (`idjoo/extensions`). See `docs/agents/issue-tracker.md`.

### Domain docs

Single-context layout (`GLOSSARY.md` + `docs/adr/` at repo root). See `docs/agents/domain.md`.

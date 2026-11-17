# idjo Extensions Source

Source code of the [Aniyomi](https://github.com/aniyomiorg/aniyomi) extensions published in [`idjoo/extensions`](https://github.com/idjoo/extensions). The extensions use the standard Aniyomi extensions-lib, so they run in Aniyomi and its forks, such as [Anikku](https://github.com/komikku-app/anikku).

The layout follows [`keiyoushi/extensions-source`](https://github.com/keiyoushi/extensions-source): this repository holds code and CI, and CI pushes signed APKs and the repository index to the `repo` branch of `idjoo/extensions`.

## Using the extensions

Add this URL under **More** -> **Settings** -> **Browse** -> **Anime extension repos**:

```text
https://raw.githubusercontent.com/idjoo/extensions/repo/index.min.json
```

## Extensions

| Extension | Package | Languages | Notes |
| --- | --- | --- | --- |
| **DramaTip** (`dramatip.net`) | `eu.kanade.tachiyomi.animeextension.all.dramatip` | 16 (`en`, `de`, `es`, `ru`, `fr`, `ar`, `ko`, `th`, `pt-BR`, `it`, `ja`, `id`, `vi`, `fil`, `hi`, `ms`) | Popular, New Releases, search (query, `slug:<slug>`, or full URL), Category and Upstream Provider filters, 9:16 HLS track selection, and a localhost stream adapter for encrypted or key-rewritten streams. |
| **DramaExpress** (`dramaexpress.net`) | `eu.kanade.tachiyomi.animeextension.all.dramaexpress` | Same 16 as DramaTip | Same platform as DramaTip, built on `lib/drama-platform`. |

## Layout

| Path | Purpose |
| --- | --- |
| `src/<lang>/<name>/` | One Gradle module per extension |
| `core/`, `lib/` | Shared extension code (`lib/drama-platform` holds the DramaTip-family Catalog Source, `lib/stream-proxy` the loopback proxy) |
| `common.gradle`, `buildSrc/` | Shared build logic |
| `.github/workflows/build_push.yml` | Tests, signs release APKs, publishes to `idjoo/extensions` |
| `.github/scripts/build-repo.mjs` | Builds `index.min.json`, `index.json`, `repo.json`, and icons |
| `.github/repo-readme.md` | README published to `idjoo/extensions` |
| `.github/repo-site/index.html` | Redirect from https://idjoo.github.io/extensions/ to the main site (source: idjoo/idjoo.github.io) |

## Release setup (maintainers)

CI signs release APKs with a persistent PKCS12 keystore and refuses to publish without it.

1. Generate the keystore inside the Nix shell and keep a secure backup of `signingkey.jks` (`*.jks` is gitignored):
   ```bash
   nix develop -c ./scripts/gen-keystore.sh
   ```
2. Set the printed secrets (`SIGNING_KEY`, `ALIAS`, `KEY_STORE_PASSWORD`, `KEY_PASSWORD`) in `idjoo/extensions-source`.
3. Add an SSH deploy key with write access to `idjoo/extensions`, and store its private key as the `REPO_DEPLOY_KEY` secret in `idjoo/extensions-source`.
4. Push to `master` (or run **Build and Publish Extensions** manually).

## Local development (NixOS / Linux / macOS)

`flake.nix` provides JDK 17 and Android SDK 34 tools, including native `aarch64-linux` `aapt2` and `zipalign`:

```bash
# Unit tests
nix develop -c ./gradlew testDebugUnitTest

# Opt-in live smoke tests against dramatip.net and dramaexpress.net
nix develop -c ./gradlew testDebugUnitTest -PliveSmoke=true

# Debug APK
nix develop -c ./gradlew :src:all:dramatip:assembleDebug
```

Domain terms are in [GLOSSARY.md](./GLOSSARY.md), and architectural decisions are under [docs/adr/](./docs/adr/).

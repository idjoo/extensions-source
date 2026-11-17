# Anikku Extensions (`idjoo/extensions`)

Standalone [Anikku](https://github.com/komikku-app/anikku) video extension repository following the standard `aniyomi-extensions` multi-module layout.

## Included Extensions

| Extension | Package | Locales | Notes |
| --- | --- | --- | --- |
| **DramaTip** (`dramatip.net`) | `eu.kanade.tachiyomi.animeextension.all.dramatip` | 16 (`en`, `de`, `es`, `ru`, `fr`, `ar`, `ko`, `th`, `pt-BR`, `it`, `ja`, `id`, `vi`, `fil`, `hi`, `ms`) | Popular, New Releases, search (query, `slug:<slug>`, or full URL), Category and Upstream Provider filters, 9:16 HLS track selection, and localhost stream adapter for encrypted/key-rewritten streams. |

## Adding This Repository in Anikku

1. Open **Anikku** -> **More** -> **Settings** -> **Browse** -> **Anime extension repos**.
2. Tap **Add** and enter:
   ```text
   https://raw.githubusercontent.com/idjoo/extensions/repo/index.min.json
   ```
3. Refresh the **Extensions** tab and install **Aniyomi: DramaTip**.

## Release Signing Setup (Maintainers)

CI signs release APKs using a persistent PKCS12 keystore stored in GitHub Actions secrets and refuses to publish unsigned or ephemerally signed APKs.

1. Generate a persistent keystore locally inside the Nix shell:
   ```bash
   nix develop -c ./scripts/gen-keystore.sh
   ```
2. Set the four printed repository secrets (`SIGNING_KEY`, `ALIAS`, `KEY_STORE_PASSWORD`, `KEY_PASSWORD`) in `idjoo/extensions` (**Settings** -> **Secrets and variables** -> **Actions**). Keep a secure backup of `signingkey.jks` (`*.jks` is gitignored).
3. Push to `master` (or trigger **Build and Publish Extensions** via `workflow_dispatch`) to publish `apk/`, `icon/`, `index.min.json`, `index.json`, and `repo.json` onto the orphan `repo` branch.

## Local Development (NixOS / Linux / macOS)

All JDK 17 and Android SDK 34 tools (including native `aarch64-linux` `aapt2` and `zipalign`) are provided by `flake.nix`:

```bash
# Run unit tests across all modules
nix develop -c ./gradlew testDebugUnitTest

# Run opt-in live network smoke tests against dramatip.net
nix develop -c ./gradlew testDebugUnitTest -PliveSmoke=true

# Build the debug APK
nix develop -c ./gradlew :src:all:dramatip:assembleDebug
```

Domain terminology is documented in [GLOSSARY.md](./GLOSSARY.md) and architectural decisions are recorded under [docs/adr/](./docs/adr/).

#!/usr/bin/env bash
set -euo pipefail

KEYSTORE_FILE="${1:-signingkey.jks}"
KEY_ALIAS="${ALIAS:-dramatip}"

if [[ -f "$KEYSTORE_FILE" && "${FORCE:-0}" != "1" ]]; then
  echo "Error: $KEYSTORE_FILE already exists. Set FORCE=1 to overwrite." >&2
  exit 1
fi

if ! command -v keytool >/dev/null 2>&1; then
  echo "Error: keytool not found on PATH. Run inside 'nix develop'." >&2
  exit 1
fi

STORE_PASS="$(head -c 256 /dev/urandom | LC_ALL=C tr -dc 'A-Za-z0-9' | cut -c1-32)"
KEY_PASS="$STORE_PASS"

rm -f "$KEYSTORE_FILE"

keytool -genkeypair \
  -v \
  -keystore "$KEYSTORE_FILE" \
  -storetype PKCS12 \
  -alias "$KEY_ALIAS" \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storepass "$STORE_PASS" \
  -keypass "$KEY_PASS" \
  -dname "CN=idjoo, OU=Extensions, O=idjoo, L=Jakarta, ST=DKI, C=ID" >/dev/null 2>&1

FINGERPRINT="$(
  keytool -list -v -keystore "$KEYSTORE_FILE" -storepass "$STORE_PASS" -alias "$KEY_ALIAS" \
    | awk -F': ' '/SHA256:/ && !done { gsub(":", "", $2); print tolower($2); done = 1 }'
)"

B64_KEY="$(base64 -w 0 "$KEYSTORE_FILE" 2>/dev/null || base64 "$KEYSTORE_FILE" | tr -d '\n')"

cat <<EOF
Generated keystore: $KEYSTORE_FILE
Certificate SHA-256 fingerprint: $FINGERPRINT

Configure these four GitHub Actions secrets in idjoo/extensions-source:
  SIGNING_KEY         (base64 keystore below)
  ALIAS               $KEY_ALIAS
  KEY_STORE_PASSWORD  $STORE_PASS
  KEY_PASSWORD        $KEY_PASS

Example commands using GitHub CLI:
  printf '%s' '$B64_KEY' | gh secret set SIGNING_KEY --repo idjoo/extensions-source
  printf '%s' '$KEY_ALIAS' | gh secret set ALIAS --repo idjoo/extensions-source
  printf '%s' '$STORE_PASS' | gh secret set KEY_STORE_PASSWORD --repo idjoo/extensions-source
  printf '%s' '$KEY_PASS' | gh secret set KEY_PASSWORD --repo idjoo/extensions-source
EOF

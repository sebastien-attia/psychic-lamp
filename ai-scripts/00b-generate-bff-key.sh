#!/usr/bin/env bash
# Generates the BFF's RSA signing key used to authenticate to Keycloak via
# private_key_jwt. The matching public key is published by the BFF at
# /.well-known/jwks.json — Keycloak fetches it (use.jwks.url=true), so we
# never need to register it server-side.
#
# Usage:
#   ./ai-scripts/00b-generate-bff-key.sh                # local-intg (Docker)
#   OUT=./bff/src/main/resources/keys ./00b-...         # IDE-run BFF
set -euo pipefail

OUT="${OUT:-./infra/docker/keys}"
KEY_PATH="${OUT}/bff-signing-key.pem"
KID="${BFF_SIGNING_KEY_ID:-bff-key-1}"

mkdir -p "${OUT}"

if [[ -f "${KEY_PATH}" ]]; then
  echo "▸ Key already exists at ${KEY_PATH} (kid=${KID}). Refusing to overwrite."
  exit 0
fi

echo "▸ Generating RSA 2048 PKCS#8 PEM at ${KEY_PATH} (kid=${KID})..."
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "${KEY_PATH}"
chmod 600 "${KEY_PATH}"

echo "▸ Done. The BFF reads this file via BFF_SIGNING_KEY_PATH and"
echo "  publishes the public half at http://bff:8080/.well-known/jwks.json"
echo "  (Keycloak's client jwks.url points at that endpoint)."

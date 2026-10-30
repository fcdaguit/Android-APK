#!/usr/bin/env bash
# Downloads the small English Vosk model and bundles it into the app's assets.
# Run once before building (needs internet only at build time, never on the phone).
set -euo pipefail
cd "$(dirname "$0")"
DEST=app/src/main/assets/model-en
if [ -d "$DEST/am" ]; then echo "English model already present"; exit 0; fi
mkdir -p app/src/main/assets .tmp-model
curl -fL -o .tmp-model/en.zip https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip
unzip -q .tmp-model/en.zip -d .tmp-model
rm -rf "$DEST"
mv .tmp-model/vosk-model-small-en-us-0.15 "$DEST"
rm -rf .tmp-model
echo "Bundled English model -> $DEST"

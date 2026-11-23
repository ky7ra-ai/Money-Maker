#!/usr/bin/env bash
# Mengunduh font terbuka (SIL Open Font License) dari repo google/fonts ke assets aplikasi.
# Dijalankan otomatis oleh GitHub Actions sebelum build. Untuk build lokal, jalankan sekali:
#   bash scripts/fetch-fonts.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/src/main/assets/fonts"
mkdir -p "$DEST"

FAMILIES=(
  lato opensans montserrat poppins inter nunito oswald raleway playfairdisplay merriweather
  lora bebasneue anton pacifico dancingscript caveat robotomono ubuntu worksans dmsans
  arimo tinos cousine sourcesans3 firasans librebaskerville comfortaa quicksand rubik karla
  josefinsans lobster archivoblack comicneue carlito caladea gelasio roboto robotocondensed
  robotoslab barlow cabin titilliumweb mulish manrope bitter crimsontext ibmplexsans
  spacegrotesk jost outfit lexend sora archivo assistant heebo hind sourcecodepro bangers
  permanentmarker satisfy greatvibes
)

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

git clone --quiet --depth 1 --filter=blob:none --sparse https://github.com/google/fonts.git "$TMP/gf"
cd "$TMP/gf"
ARGS=()
for f in "${FAMILIES[@]}"; do ARGS+=("ofl/$f"); done
git sparse-checkout set "${ARGS[@]}"

COUNT=0
for f in "${FAMILIES[@]}"; do
  d="ofl/$f"
  if [ ! -d "$d" ]; then echo "lewati (tidak ada): $f"; continue; fi
  for ttf in "$d"/*.ttf; do
    [ -f "$ttf" ] || continue
    size=$(stat -c%s "$ttf")
    if [ "$size" -gt 1300000 ]; then echo "lewati (besar): $ttf"; continue; fi
    # Normalisasi nama: OpenSans[wdth,wght].ttf -> OpenSans~wdth-wght.ttf
    name="$(basename "$ttf" | sed -E 's/\[(.*)\]/~\1/; s/,/-/g')"
    cp "$ttf" "$DEST/$name"
    COUNT=$((COUNT + 1))
  done
done
echo "Selesai: $COUNT file font disalin ke $DEST"

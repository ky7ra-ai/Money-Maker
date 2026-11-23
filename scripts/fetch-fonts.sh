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
  ptsans ptserif merriweathersans kanit prompt mukta oxygen exo2 fjallaone abrilfatface
  archivonarrow alegreya alegreyasans cinzel cormorantgaramond ebgaramond teko signika vollkorn
  zillaslab yanonekaffeesatz staatliches passionone righteous fredoka baloo2 chewy luckiestguy
  pressstart2p specialelite courierprime spacemono inconsolata jetbrainsmono firacode overpass
  hankengrotesk plusjakartasans bevietnampro figtree redhatdisplay syne epilogue albertsans
  bricolagegrotesque
)
LICENSES=(ofl apache ufl)

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

git clone --quiet --depth 1 --filter=blob:none --sparse https://github.com/google/fonts.git "$TMP/gf"
cd "$TMP/gf"
ARGS=()
for f in "${FAMILIES[@]}"; do for l in "${LICENSES[@]}"; do ARGS+=("$l/$f"); done; done
git sparse-checkout set "${ARGS[@]}"

COUNT=0
for f in "${FAMILIES[@]}"; do
  d=""
  for l in "${LICENSES[@]}"; do [ -d "$l/$f" ] && d="$l/$f" && break; done
  if [ -z "$d" ]; then echo "lewati (tidak ada): $f"; continue; fi
  for ttf in "$d"/*.ttf; do
    [ -f "$ttf" ] || continue
    size=$(stat -c%s "$ttf")
    if [ "$size" -gt 3500000 ]; then echo "lewati (besar): $ttf"; continue; fi
    # Normalisasi nama: OpenSans[wdth,wght].ttf -> OpenSans~wdth-wght.ttf
    name="$(basename "$ttf" | sed -E 's/\[(.*)\]/~\1/; s/,/-/g')"
    cp "$ttf" "$DEST/$name"
    COUNT=$((COUNT + 1))
  done
done
echo "Selesai: $COUNT file font disalin ke $DEST"

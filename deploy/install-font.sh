#!/bin/sh
#
# Download static Noto TTF fonts for PDF generation & local development.
# Uses the Google Fonts CSS API to resolve per-weight static font URLs,
# ensuring compatibility with OpenHTMLtoPDF / PDFBox (which cannot load
# variable fonts).
#
# Usage:
#   sh deploy/install-font.sh              # auto-detect OS font dir
#   sh deploy/install-font.sh /app/fonts    # explicit target dir (Docker)
#
set -e

# ── Resolve target directory ──
if [ -n "$1" ]; then
  FONT_DIR="$1"
else
  case "$(uname -s)" in
    Darwin) FONT_DIR="$HOME/Library/Fonts" ;;
    Linux)
      if [ "$(id -u)" = "0" ]; then
        FONT_DIR="/usr/share/fonts/noto"
      else
        FONT_DIR="$HOME/.local/share/fonts"
      fi
      ;;
    *) echo "Unsupported OS — pass target dir as argument"; exit 1 ;;
  esac
fi

mkdir -p "$FONT_DIR"

echo "Downloading Noto fonts to $FONT_DIR ..."

# Resolve a static font URL from the Google Fonts CSS API, then download it.
#   $1 = CSS API query  (e.g. "Noto+Sans:wght@400")
#   $2 = target filename (e.g. NotoSans-Regular.ttf)
fetch() {
  local query="$1"
  local file="$2"
  local url
  url=$(curl -fsSL "https://fonts.googleapis.com/css2?family=$query" \
        | grep -o 'https://[^)]*')
  if [ -z "$url" ]; then
    echo "  FAIL  $file (could not resolve URL from CSS API)" >&2
    return 1
  fi
  if curl -fsSL -o "$FONT_DIR/$file" "$url"; then
    echo "  fetch $file"
  else
    echo "  FAIL  $file (download error)" >&2
    rm -f "$FONT_DIR/$file"
    return 1
  fi
}

# ── Noto Sans — Latin / Cyrillic / Greek ──
fetch "Noto+Sans:wght@400"           NotoSans-Regular.ttf
fetch "Noto+Sans:wght@700"           NotoSans-Bold.ttf
fetch "Noto+Sans:ital,wght@1,400"    NotoSans-Italic.ttf
fetch "Noto+Sans:ital,wght@1,700"    NotoSans-BoldItalic.ttf

# ── Noto Serif — Latin / Cyrillic / Greek ──
fetch "Noto+Serif:wght@400"          NotoSerif-Regular.ttf
fetch "Noto+Serif:wght@700"          NotoSerif-Bold.ttf
fetch "Noto+Serif:ital,wght@1,400"   NotoSerif-Italic.ttf
fetch "Noto+Serif:ital,wght@1,700"   NotoSerif-BoldItalic.ttf

# ── Noto Sans Mono ──
fetch "Noto+Sans+Mono:wght@400"      NotoSansMono-Regular.ttf
fetch "Noto+Sans+Mono:wght@700"      NotoSansMono-Bold.ttf

# ── Simplified Chinese ──
fetch "Noto+Sans+SC:wght@400"        NotoSansSC-Regular.ttf
fetch "Noto+Serif+SC:wght@400"       NotoSerifSC-Regular.ttf

# ── Traditional Chinese ──
fetch "Noto+Sans+TC:wght@400"        NotoSansTC-Regular.ttf

# ── Japanese ──
fetch "Noto+Sans+JP:wght@400"        NotoSansJP-Regular.ttf

# ── Korean ──
fetch "Noto+Sans+KR:wght@400"        NotoSansKR-Regular.ttf

# ── Arabic ──
fetch "Noto+Sans+Arabic:wght@400"    NotoSansArabic-Regular.ttf

# ── Thai ──
fetch "Noto+Sans+Thai:wght@400"      NotoSansThai-Regular.ttf

# ── Refresh font cache (Linux only) ──
if command -v fc-cache >/dev/null 2>&1; then
  fc-cache -f "$FONT_DIR"
fi

COUNT=$(ls -1 "$FONT_DIR"/Noto*.ttf 2>/dev/null | wc -l | tr -d ' ')
echo "Done — $COUNT Noto font files in $FONT_DIR"

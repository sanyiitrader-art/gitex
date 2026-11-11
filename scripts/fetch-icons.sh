#!/usr/bin/env bash
# Downloads real language / file-type logos (Devicon: MIT, Material Icon Theme: MIT,
# Simple Icons: CC0) and renders them to 96px PNGs in
#   app/src/main/assets/file-icons/<key>.png
# The app loads them by key (see FileIcons.kt).
#
# - A PNG that already exists in the folder is treated as a manual override and kept.
# - Missing icons never fail the build; they are listed in the job summary instead.
#
# Candidate syntax:  d:<folder>:<variant>  -> Devicon   icons/<folder>/<folder>-<variant>.svg
#                    m:<name>              -> Material  icons/<name>.svg
#                    s:<slug>              -> Simple Icons icons/<slug>.svg (recoloured light grey)
set -uo pipefail

OUT="app/src/main/assets/file-icons"
SIZE=96
WORK="$(mktemp -d)"
mkdir -p "$OUT"

declare -A SRC_URL=(
  [d]="https://github.com/devicons/devicon.git"
  [m]="https://github.com/PKief/vscode-material-icon-theme.git"
  [s]="https://github.com/simple-icons/simple-icons.git"
)
declare -A SRC_DIR=([d]="devicon" [m]="material" [s]="simple")

declare -A ICONS=(
  # --- languages ---
  [python]="d:python:original"
  [kotlin]="d:kotlin:original"
  [typescript]="d:typescript:original"
  [javascript]="d:javascript:original"
  [toml]="m:toml s:toml"
  [gdscript]="d:godot:original m:godot s:godotengine"
  [cpp]="d:cplusplus:original"
  [swift]="d:swift:original"
  [java]="d:java:original"
  [ruby]="d:ruby:original"
  [php]="d:php:original"
  [rust]="d:rust:original"
  [tsx]="m:react_ts d:react:original"
  [yaml]="m:yaml d:yaml:original"
  [xml]="m:xml"
  [qt]="d:qt:original s:qt"
  [gitignore]="d:git:original m:git"
  [csharp]="d:csharp:original"
  [go]="d:go:original"
  [dart]="d:dart:original"
  [sql]="m:database"
  [html]="d:html5:original"
  [css]="d:css3:original"
  [json]="m:json"
  [bash]="d:bash:original m:console"
  [lua]="d:lua:original"
  [powershell]="d:powershell:original m:powershell"
  [docker]="d:docker:original m:docker"
  [makefile]="m:makefile"
  [r]="d:r:original"
  [julia]="d:julia:original"
  [matlab]="d:matlab:original"
  [scala]="d:scala:original"
  [c]="d:c:original"
  [graphql]="d:graphql:plain d:graphql:original m:graphql"
  [haskell]="d:haskell:original"
  [elixir]="d:elixir:original"
  [erlang]="d:erlang:original"
  [perl]="d:perl:original"
  [assembly]="m:assembly"
  [zig]="d:zig:original m:zig"
  [nix]="d:nixos:original m:nix"
  [terraform]="d:terraform:original"
  [vue]="d:vuejs:original"
  [svelte]="d:svelte:original"
  [clojure]="d:clojure:original"
  [markdown]="m:markdown d:markdown:original"
  # --- plain formats ---
  [text]="m:document"
  [log]="m:log"
  [image]="m:image"
  [pdf]="m:pdf"
  [docx]="m:word"
  [archive]="m:zip"
  [audio]="m:audio"
  [video]="m:video"
  [file]="m:document"
)

SHAS=""
for k in d m s; do
  dir="$WORK/${SRC_DIR[$k]}"
  if git clone --quiet --depth 1 --filter=blob:none --sparse "${SRC_URL[$k]}" "$dir" 2>/dev/null \
     && git -C "$dir" sparse-checkout set icons 2>/dev/null; then
    SHAS+="- ${SRC_DIR[$k]}: $(git -C "$dir" rev-parse --short HEAD)"$'\n'
  else
    echo "WARNING: could not fetch ${SRC_DIR[$k]}"
  fi
done

find_svg() {
  local src a b
  IFS=: read -r src a b <<<"$1"
  local base="$WORK/${SRC_DIR[$src]}/icons"
  case "$src" in
    d) echo "$base/$a/$a-$b.svg" ;;
    m|s) echo "$base/$a.svg" ;;
  esac
}

render() { # $1 = svg, $2 = png
  if command -v rsvg-convert >/dev/null 2>&1; then
    rsvg-convert -w "$SIZE" -h "$SIZE" --keep-aspect-ratio "$1" -o "$2" 2>/dev/null && [ -s "$2" ] && return 0
  fi
  convert -background none -density 300 "$1" -resize "${SIZE}x${SIZE}" "$2" 2>/dev/null && [ -s "$2" ]
}

ok=0
overrides=0
missing=()

for key in $(printf '%s\n' "${!ICONS[@]}" | sort); do
  if [ -s "$OUT/$key.png" ]; then
    overrides=$((overrides + 1))
    continue
  fi
  done_one=0
  for cand in ${ICONS[$key]}; do
    svg="$(find_svg "$cand")"
    [ -f "$svg" ] || continue
    src="${cand%%:*}"
    if [ "$src" = "s" ]; then
      sed 's/<svg /<svg fill="#C9C9C9" /' "$svg" > "$WORK/tinted.svg"
      svg="$WORK/tinted.svg"
    fi
    if render "$svg" "$OUT/$key.png"; then
      ok=$((ok + 1))
      done_one=1
      break
    fi
  done
  if [ "$done_one" -eq 0 ]; then
    missing+=("$key  (tried: ${ICONS[$key]})")
  fi
done

report() {
  echo "### File-type icons"
  echo ""
  echo "Rendered: **$ok** of ${#ICONS[@]} (manual overrides kept: $overrides)"
  echo ""
  echo "Sources used:"
  echo "$SHAS"
  if [ "${#missing[@]}" -gt 0 ]; then
    echo "**Missing icons (the app shows its generic file icon for these):**"
    echo ""
    for m in "${missing[@]}"; do echo "- $m"; done
  else
    echo "No missing icons."
  fi
}

report
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  report >> "$GITHUB_STEP_SUMMARY"
fi

rm -rf "$WORK"
exit 0

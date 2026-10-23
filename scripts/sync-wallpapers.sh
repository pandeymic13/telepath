#!/usr/bin/env bash
# Copies a folder of photos into telepath's slideshow on the TV: the macOS and Linux version of
# Sync-Wallpapers.ps1. Photos (jpg, jpeg, png, webp) go as they are; telepath turns them upright and fills the
# screen with them. Only files this script copied (pc_*) are ever removed from the TV. Preview by default.
#
#   scripts/sync-wallpapers.sh [--apply] [--tv <address>] [--keep-metadata] [folder]
#
# The folder defaults to wallpapers.folder in the settings, the TV to tv.address (or the one device adb sees).
# Location, camera and date data are removed with exiftool before copying (brew install exiftool, or
# apt install libimage-exiftool-perl). Without exiftool, --keep-metadata copies the photos with it.
set -euo pipefail
export LC_ALL=C # letter ranges, sorting and lengths the same in every language

root="$(cd "$(dirname "$0")/.." && pwd)"
apply=0
keep=0
tv=""
source=""
while [ $# -gt 0 ]; do
  case "$1" in
    --apply) apply=1 ;;
    --keep-metadata) keep=1 ;;
    --tv) tv="${2:-}"; shift ;;
    -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
    *) source="$1" ;;
  esac
  shift
done

# A setting from telepath.local.properties, else from telepath.properties.
setting() {
  local key="${1//./\\.}" file value
  for file in "$root/telepath.local.properties" "$root/telepath.properties"; do
    [ -f "$file" ] || continue
    value="$(grep -E "^[[:space:]]*$key[[:space:]]*[=:]" "$file" | tail -n 1 | sed -E 's/^[^=:]*[=:][[:space:]]*//; s/[[:space:]]*$//')" || true
    if [ -n "$value" ]; then printf '%s' "$value"; return; fi
  done
}

fail() { echo "$*" >&2; exit 1; }

adb="${ADB:-adb}"
command -v "$adb" >/dev/null 2>&1 || fail "adb wasn't found: install Android SDK Platform-Tools, or set ADB=/path/to/adb."
pkg="$(setting app.id)"
[ -n "$pkg" ] || fail "app.id is missing from telepath.properties."
[ -n "$tv" ] || tv="${TELEPATH_TV:-$(setting tv.address)}"
if [ -n "$tv" ]; then
  case "$tv" in *:*) ;; *) tv="$tv:5555" ;; esac
  "$adb" connect "$tv" 2>&1 | grep -q 'connected to' || fail "Couldn't reach the TV at $tv. Is it on, with network debugging on?"
else
  tv="$("$adb" devices | awk 'NR > 1 && $2 == "device" { print $1 }')"
  [ "$(printf '%s\n' "$tv" | grep -c .)" = 1 ] || fail "Which TV? Pass --tv <address>, or set tv.address in telepath.local.properties."
fi
run() { "$adb" -s "$tv" "$@"; }
[ "$(run get-state 2>/dev/null | tr -d '\r')" = device ] || fail "The TV at $tv isn't ready for adb: allow this computer on the TV, then try again."

[ -n "$source" ] || source="$(setting wallpapers.folder)"
source="${source:-wallpapers}"
case "$source" in /*) ;; *) source="$root/$source" ;; esac
[ -d "$source" ] || fail "Photo folder not found: $source"
remote="/sdcard/Android/data/$pkg/files/slideshow"

if command -v sha256sum >/dev/null 2>&1; then hasher="sha256sum"; else hasher="shasum -a 256"; fi
nl='
'

# The name each photo gets on the TV: pc_<its name>_<start of its SHA-256>.<type>. Built with bash's own string
# handling and one batch of hashes, so even a folder of thousands of photos starts only a few programs.
files=()
types=()
while IFS= read -r -d '' file; do
  case "${file##*.}" in
    [Jj][Pp][Gg]) types+=(jpg) ;;
    [Jj][Pp][Ee][Gg]) types+=(jpeg) ;;
    [Pp][Nn][Gg]) types+=(png) ;;
    [Ww][Ee][Bb][Pp]) types+=(webp) ;;
    *) continue ;;
  esac
  files+=("$file")
done < <(find "$source" -maxdepth 1 -type f -print0 | sort -z)
hashes=()
if [ "${#files[@]}" -gt 0 ]; then
  while read -r hash _; do hashes+=("${hash#\\}"); done < <(printf '%s\0' "${files[@]}" | xargs -0 $hasher)
fi
[ "${#hashes[@]}" = "${#files[@]}" ] || fail "Couldn't read every photo in $source."
names=()
for i in ${files[@]+"${!files[@]}"}; do
  base="${files[$i]##*/}"
  safe="${base%.*}"
  safe="${safe//[!A-Za-z0-9_-]/_}"
  names+=("pc_${safe:0:60}_${hashes[$i]:0:12}.${types[$i]}")
done

on_tv="$(run shell "ls -1 $remote 2>/dev/null" | tr -d '\r' | grep -E '^pc_[A-Za-z0-9_-]+\.(jpg|jpeg|png|webp)$' || true)"
planned="$(printf '%s\n' ${names[@]+"${names[@]}"})"
# Whether a name is a line of a list, without starting a program.
listed() { case "$nl$2$nl" in *"$nl$1$nl"*) return 0 ;; esac; return 1; }
add=()
for i in ${names[@]+"${!names[@]}"}; do
  listed "${names[$i]}" "$on_tv" || add+=("$i")
done
remove=()
set -f
oldifs="$IFS"
IFS="$nl"
for name in $on_tv; do
  [ -n "$name" ] || continue
  listed "$name" "$planned" || remove+=("$name")
done
IFS="$oldifs"
set +f

echo "Source: $source"
echo "Photos in folder: ${#names[@]}. To add: ${#add[@]}. To remove from the TV: ${#remove[@]}."
echo "TV folder (telepath only): $remote"
strip=0
if command -v exiftool >/dev/null 2>&1; then strip=1; fi
if [ "$apply" != 1 ]; then
  [ "$strip" = 1 ] || [ "$keep" = 1 ] || echo "Note: exiftool isn't installed. Install it to remove location and camera data, or add --keep-metadata."
  echo "PREVIEW ONLY. Add --apply to copy the photos to the TV."
  exit 0
fi
[ "$strip" = 1 ] || [ "$keep" = 1 ] || [ "${#add[@]}" = 0 ] ||
  fail "exiftool isn't installed, so your photos would keep their location and camera data. Install it (brew install exiftool, or apt install libimage-exiftool-perl), or add --keep-metadata to copy them as they are."

# telepath creates its own folder when it starts, so the files copied in stay readable by it.
if ! run shell "ls -d $remote" >/dev/null 2>&1; then
  run shell am start -W -n "$pkg/dev.plume.MainActivity" >/dev/null
  sleep 3
  run shell "ls -d $remote" >/dev/null 2>&1 || fail "telepath did not create its slideshow folder. Open it once on the TV and try again."
fi
work=""
if [ "$strip" = 1 ]; then
  work="$(mktemp -d)"
  trap 'rm -rf "$work"' EXIT
fi
for i in ${add[@]+"${add[@]}"}; do
  copy="${files[$i]}"
  if [ -n "$work" ]; then
    copy="$work/${names[$i]}"
    cp "${files[$i]}" "$copy"
    exiftool -q -overwrite_original -all= -tagsfromfile @ -orientation "$copy" >/dev/null
  fi
  run push "$copy" "$remote/${names[$i]}" >/dev/null
  echo "Copied: $(basename "${files[$i]}") -> ${names[$i]}"
done
for name in ${remove[@]+"${remove[@]}"}; do
  run shell rm -f "$remote/$name" >/dev/null
  echo "Removed from the TV: $name"
done
echo "Done: ${#names[@]} photos in the slideshow. A new one shows each time you return to Home."

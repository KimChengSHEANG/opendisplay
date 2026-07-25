#!/bin/zsh
# Print one adb serial to stdout. If ANDROID_SERIAL is set, use it.
# With multiple devices, prompt: 1. … 2. …
set -euo pipefail

if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  echo "$ANDROID_SERIAL"
  exit 0
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found — install Android platform-tools (e.g. brew install --cask android-platform-tools)" >&2
  exit 1
fi

typeset -a serials labels
while IFS=$'\t' read -r serial state rest; do
  [[ -z "$serial" || "$serial" == "List" ]] && continue
  [[ "$state" != "device" ]] && continue
  model=$(adb -s "$serial" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
  [[ -z "$model" ]] && model="unknown"
  serials+=("$serial")
  labels+=("$serial  ($model)")
done < <(adb devices | awk 'NR>1 && NF>=2 {print $1 "\t" $2}')

n=${#serials[@]}
if (( n == 0 )); then
  echo "No authorized Android devices. Plug in USB (or adb connect <ip>) and tap Allow." >&2
  exit 1
fi
if (( n == 1 )); then
  echo "Using Android device: ${labels[1]}" >&2
  echo "${serials[1]}"
  exit 0
fi

echo "Multiple Android devices — pick one:" >&2
for i in {1..$n}; do
  echo "  $i. ${labels[$i]}" >&2
done
echo -n "Select [1-$n]: " >&2
read -r choice
if [[ ! "$choice" =~ ^[0-9]+$ ]] || (( choice < 1 || choice > n )); then
  echo "Invalid selection: $choice" >&2
  exit 1
fi
echo "${serials[$choice]}"

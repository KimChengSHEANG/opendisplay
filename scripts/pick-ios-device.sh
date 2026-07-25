#!/bin/zsh
# Print one physical iOS device UDID (same set Xcode shows as run destinations).
# If IOS_DEVICE is set, use it as-is.
# With multiple devices, prompt: 1. … 2. …
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if [[ -n "${IOS_DEVICE:-}" ]]; then
  echo "$IOS_DEVICE"
  exit 0
fi

project="$ROOT/OpenSidecar.xcodeproj"
scheme="OpenSidecariOS"
if [[ ! -d "$project" ]]; then
  echo "OpenSidecar.xcodeproj missing — run make generate first." >&2
  exit 1
fi

dest_out=$(xcodebuild -project "$project" -scheme "$scheme" -showdestinations 2>/dev/null) || {
  echo "xcodebuild -showdestinations failed." >&2
  exit 1
}

usbmux_json="[]"
if command -v pymobiledevice3 >/dev/null 2>&1; then
  usbmux_json=$(PYTHONWARNINGS=ignore pymobiledevice3 usbmux list 2>/dev/null || echo '[]')
fi

# Parse destinations + optional USB/Network hints in one Python pass.
mapfile=$(DEST_OUT="$dest_out" USBMUX_JSON="$usbmux_json" python3 <<'PY'
import os, re, json

text = os.environ.get("DEST_OUT", "")
try:
    mux = json.loads(os.environ.get("USBMUX_JSON") or "[]")
except Exception:
    mux = []

conn_by_udid = {}
for d in mux if isinstance(mux, list) else []:
    udid = d.get("UniqueDeviceID") or d.get("Identifier")
    if udid:
        # Prefer USB if the same UDID appears twice (USB + Network).
        cur = conn_by_udid.get(udid)
        ct = d.get("ConnectionType") or ""
        if cur != "USB":
            conn_by_udid[udid] = ct

rows = []
seen = set()
for line in text.splitlines():
    if "{ platform:iOS," not in line:
        continue
    if "Simulator" in line or "placeholder" in line or "Designed for" in line:
        continue
    m_id = re.search(r"\bid:\s*([^,}]+)", line)
    # name ends at the next key (error:/arch:/OS:) or closing brace
    m_name = re.search(r"\bname:\s*(.+?)(?=,\s*(?:error|arch|OS|id):|\})", line)
    if not m_id or not m_name:
        continue
    udid = m_id.group(1).strip()
    name = m_name.group(1).strip()
    conn = conn_by_udid.get(udid, "")
    note = ""
    if re.search(r"\berror:", line):
        # Still list it — usbmux often sees older iPads while Xcode briefly
        # marks them unavailable (locked / wake). Prefer USB over dropping.
        if conn != "USB":
            continue
        note = "  (unlock device)"
    rank = 0 if conn == "USB" else 1
    suffix = f"  ({conn})" if conn else ""
    label = f"{name}  [{udid}]{suffix}{note}"
    rows.append((rank, name.lower(), udid, label))
    seen.add(udid)

# Include usbmux-only peers Xcode omitted (common for older MobileDevice iPads).
for udid, conn in conn_by_udid.items():
    if udid in seen:
        continue
    name = next(
        (
            d.get("DeviceName") or d.get("ProductType") or udid
            for d in (mux if isinstance(mux, list) else [])
            if (d.get("UniqueDeviceID") or d.get("Identifier")) == udid
        ),
        udid,
    )
    rank = 0 if conn == "USB" else 1
    suffix = f"  ({conn})" if conn else ""
    label = f"{name}  [{udid}]{suffix}"
    rows.append((rank, str(name).lower(), udid, label))

rows.sort(key=lambda r: (r[0], r[1]))
for _, _, udid, label in rows:
    print(f"{udid}\t{label}")
PY
)

typeset -a ids labels
while IFS=$'\t' read -r id label; do
  [[ -z "$id" ]] && continue
  ids+=("$id")
  labels+=("$label")
done <<< "$mapfile"

n=${#ids[@]}
if (( n == 0 )); then
  echo "No iOS devices found. Connect an iPhone/iPad (USB or network) and trust this Mac." >&2
  echo "Tip: the device should appear under Product → Destination in Xcode." >&2
  exit 1
fi
if (( n == 1 )); then
  echo "Using iOS device: ${labels[1]}" >&2
  echo "${ids[1]}"
  exit 0
fi

echo "Multiple iOS devices — pick one:" >&2
for i in {1..$n}; do
  echo "  $i. ${labels[$i]}" >&2
done
echo -n "Select [1-$n]: " >&2
read -r choice
if [[ ! "$choice" =~ ^[0-9]+$ ]] || (( choice < 1 || choice > n )); then
  echo "Invalid selection: $choice" >&2
  exit 1
fi
echo "${ids[$choice]}"

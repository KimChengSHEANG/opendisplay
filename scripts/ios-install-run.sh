#!/bin/zsh
# Install + launch an .app on a physical iOS device (UDID from pick-ios-device.sh).
# Tries: pymobiledevice3 (covers older devices like iPad Air 2) → ios-deploy →
# CoreDevice `devicectl` (modern paired devices).
set -euo pipefail

udid="${1:?usage: ios-install-run.sh <udid> <app> <bundle-id>}"
app="${2:?}"
bundle="${3:?}"

if [[ ! -d "$app" ]]; then
  echo "App bundle missing: $app" >&2
  exit 1
fi

# --- pymobiledevice3 (usbmux / legacy MobileDevice — works for iPad Air 2) ---
if command -v pymobiledevice3 >/dev/null 2>&1; then
  echo "Installing via pymobiledevice3 on $udid..."
  # Filter noisy dependency warnings from nested envs.
  PYTHONWARNINGS=ignore pymobiledevice3 apps install --udid "$udid" "$app"
  echo "Launching $bundle..."
  if ! PYTHONWARNINGS=ignore pymobiledevice3 developer dvt launch --udid "$udid" "$bundle" 2>/dev/null; then
    # Launch can fail if DeveloperDisk isn't mounted; install still succeeded.
    echo "Install OK — open OpenDisplay on the device if it did not launch." >&2
  fi
  exit 0
fi

# --- ios-deploy ---
if command -v ios-deploy >/dev/null 2>&1; then
  echo "Installing via ios-deploy on $udid..."
  ios-deploy --id "$udid" --bundle "$app" --justlaunch
  exit 0
fi

# --- CoreDevice (modern devices only; skips Air 2 / pairing unsupported) ---
core_id=""
tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT
if xcrun devicectl list devices --json-output "$tmp" >/dev/null 2>&1; then
  core_id=$(python3 - "$tmp" "$udid" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))
want = sys.argv[2]
for d in data.get("result", {}).get("devices", []):
    hw = d.get("hardwareProperties") or {}
    conn = d.get("connectionProperties") or {}
    if hw.get("udid") == want and conn.get("pairingState") == "paired":
        print(d.get("identifier") or "")
        break
PY
)
fi
if [[ -n "$core_id" ]]; then
  echo "Installing via devicectl on $core_id..."
  xcrun devicectl device install app --device "$core_id" "$app"
  xcrun devicectl device process launch --device "$core_id" "$bundle"
  exit 0
fi

echo "No installer found for device $udid." >&2
echo "Install one of:" >&2
echo "  pip install pymobiledevice3     # recommended (supports older iPads)" >&2
echo "  brew install ios-deploy" >&2
exit 1

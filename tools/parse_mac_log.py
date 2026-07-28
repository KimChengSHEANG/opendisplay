#!/usr/bin/env python3
"""Summarize OpenDisplay mac log transport and PHONE-STATS lines."""

from __future__ import annotations

import argparse
import json
import re
import statistics
from pathlib import Path


TRANSPORT_RE = re.compile(r"video transport:\s*(.*)", re.IGNORECASE)
PHONE_STATS_RE = re.compile(r"PHONE-STATS\s+(\{.*\})(?:\s+\|\s+mac\s+(.*))?$")
MAC_METRICS_RE = re.compile(r"(enc|net|pending)[^=]*=(\d+)")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Summarize /tmp/opensidecar-mac.log transport telemetry."
    )
    parser.add_argument(
        "logfile",
        nargs="?",
        default="/tmp/opensidecar-mac.log",
        help="Path to the mac log file (default: /tmp/opensidecar-mac.log).",
    )
    parser.add_argument(
        "--tail",
        type=int,
        default=5,
        help="How many recent PHONE-STATS samples to print (default: 5).",
    )
    return parser.parse_args()


def format_number(value: object) -> str:
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float):
        if value.is_integer():
            return str(int(value))
        return f"{value:.1f}"
    return str(value)


def summarize_window(values: list[float]) -> str:
    if not values:
        return "n/a"
    avg = statistics.fmean(values)
    return format_number(avg)


def main() -> int:
    args = parse_args()
    path = Path(args.logfile)
    if not path.exists():
        print(f"log file not found: {path}")
        return 1

    transport_lines: list[str] = []
    samples: list[dict[str, object]] = []

    for raw_line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        transport_match = TRANSPORT_RE.search(raw_line)
        if transport_match:
            transport_lines.append(transport_match.group(1).strip())

        stats_match = PHONE_STATS_RE.search(raw_line)
        if not stats_match:
            continue

        try:
            sample = json.loads(stats_match.group(1))
        except json.JSONDecodeError:
            continue

        mac_summary = stats_match.group(2) or ""
        for key, value in MAC_METRICS_RE.findall(mac_summary):
            sample[f"mac_{key}"] = int(value)
        samples.append(sample)

    print(f"log: {path}")
    if transport_lines:
        print("recent transports:")
        for line in transport_lines[-args.tail :]:
            print(f"  {line}")
    else:
        print("recent transports: none")

    if not samples:
        print("PHONE-STATS samples: 0")
        print("No PHONE-STATS entries found yet. Wait for an active stream and rerun.")
        return 0

    print(f"PHONE-STATS samples: {len(samples)}")

    fps_values = [float(s["fps"]) for s in samples if isinstance(s.get("fps"), (int, float))]
    e2e50_values = [float(s["e2e50"]) for s in samples if isinstance(s.get("e2e50"), (int, float))]
    e2e95_values = [float(s["e2e95"]) for s in samples if isinstance(s.get("e2e95"), (int, float))]
    mbps_values = [float(s["mbps"]) for s in samples if isinstance(s.get("mbps"), (int, float))]
    stalls_values = [float(s["stalls"]) for s in samples if isinstance(s.get("stalls"), (int, float))]

    print(
        "window averages:"
        f" fps={summarize_window(fps_values)}"
        f" e2e50={summarize_window(e2e50_values)}ms"
        f" e2e95={summarize_window(e2e95_values)}ms"
        f" mbps={summarize_window(mbps_values)}"
        f" stalls={summarize_window(stalls_values)}"
    )

    print("recent PHONE-STATS:")
    for sample in samples[-args.tail :]:
        fields = [
            ("transport", sample.get("transport", "n/a")),
            ("fps", sample.get("fps", "n/a")),
            ("e2e50", sample.get("e2e50", "n/a")),
            ("e2e95", sample.get("e2e95", "n/a")),
            ("mbps", sample.get("mbps", "n/a")),
            ("rtt", sample.get("rtt", "n/a")),
            ("stalls", sample.get("stalls", "n/a")),
            ("capFps", sample.get("capFps", "n/a")),
            ("mac_enc", sample.get("mac_enc", "n/a")),
            ("mac_net", sample.get("mac_net", "n/a")),
            ("pending", sample.get("mac_pending", "n/a")),
        ]
        rendered = " ".join(f"{key}={format_number(value)}" for key, value in fields)
        print(f"  {rendered}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())

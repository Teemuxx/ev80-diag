#!/bin/bash
# Cuts the EV80-diag test fixtures out of the raw CAN logs (candump -L format, gzip).
# Usage: make_fixtures.sh <dir that contains CAN-logs/>   (default: ~/VSCode/EV80-EVCC)
# Windows are epoch seconds (local time CET/CEST on the machine that recorded the logs).
set -euo pipefail
OUT="$(cd "$(dirname "$0")/.." && pwd)/app/src/test/resources/fixtures"
cd "${1:-$HOME/VSCode/EV80-EVCC}"
mkdir -p "$OUT"

cut_window() {  # <log> <from-epoch> <to-epoch> <out.gz>
  awk -v a="$2" -v b="$3" '{ t = substr($1, 2, 17) + 0; if (t >= a && t <= b) print }' "$1" | gzip -9 > "$4"
}

cut_window CAN-logs/can0_ET_ABBTerra54_2026-10-29_1228.log \
  "$(date -d '2026-10-29 12:29:00' +%s)" "$(date -d '2026-10-29 12:34:00' +%s)" "$OUT/et_terra54_start.log.gz"
cut_window CAN-logs/can0_ET_ABBTerra54_2026-10-29_1228.log \
  "$(date -d '2026-10-29 12:48:30' +%s)" "$(date -d '2026-10-29 12:52:40' +%s)" "$OUT/et_terra54_end.log.gz"
cut_window CAN-logs/can0_Terra60_interrupt_2026-10-23_1124.log \
  "$(date -d '2026-10-23 11:47:00' +%s)" "$(date -d '2026-10-23 11:50:30' +%s)" "$OUT/terra60l_trip.log.gz"
# AC charge with two pauses: the drive side is awake and READY-like (gear P) around the 0x15 phases
cut_window "CAN-logs/AC-charge_CAN_2026-09-10_1803(first failed, the sucess, phases switched).log" \
  1789056715 1789056830 "$OUT/ac_charge_pauses.log.gz"
# bench log uses the adapter name "lx" as interface; the tests expect "can0"
sed 's/^\(([0-9.]*)\) lx /\1 can0 /' CAN-logs/bench_lx_all_stn_2026-10-31_1455.log | grep -v ' # ' | gzip -9 > "$OUT/lx_state_changes.log.gz"
# drive of 2026-10-23 12:19 (5.6 km): consumption, range and cell snapshot tests
gzip -9 < CAN-logs/can0_drive_2026-10-23_1219.log > "$OUT/drive_1023.log.gz"
ls -l "$OUT"

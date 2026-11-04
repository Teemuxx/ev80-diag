# EV80-diag: Android app design

Status: draft for owner approval, 2026-11-03 (updated after the builder review: §11, §12).
Replaces the EV80BatteryAndroid app ("Maxus EV80 Battery Analyser"), which is reworked in place.
Mockups: `2026-11-03-ev80-diag-mockups/` next to this file (HTML fragments from the design
session; open them in a browser, inline styles carry the phone frames).

## 1. Intent

The Maxus EV80 (2017) is an electric van with a hard-to-read battery system and
recurring DC-charging problems. The owner runs two of them ("Lytee" and "ET") and is part
of a small community of EV80 owners. Over the past months the owner decoded the van's CAN
bus. The result is in `/claude.md`, section "merged signal table", and in the laptop tools
`tools/can_live.py` and `tools/elm_bench.py`.

The app makes those decodes useful in daily life:
- **While driving (main use):** a glanceable view of how the cells are doing, how far the
  van will really go, and whether to drive more gently. The van's own dash already shows
  BMS SOC and range, so the app adds what the dash doesn't: cell spread, the weakest cell,
  the app's own SOC/range, power, 12 V and temperatures.
- **While charging:** power, energy, time, time to full, battery temperature (decisive in
  winter) and insulation resistance (the signal behind the charging failures).
- **For the community:** other EV80 owners can install and use it without background
  knowledge, and can send the owner complete recordings of charge sessions and cell data
  with one tap. That helps find why some chargers fail with EV80s.

Deep analysis happens on the owner's laptop with the exported logs. The phone records
everything cleanly and allows quick checks on the go.

## 2. Users

- **The owner:** technical, knows the CAN decodes, runs laptop tools on exports, owns
  two vans.
- **Other EV80 owners:** non-technical to semi-technical, one van each, mostly German
  speaking. They must understand every screen without explanation from the owner.

## 3. Goals, non-goals, anti-goals

**Goals**
1. Lossless, listen-only CAN capture with an OBDLink LX (Bluetooth) or a USB-CAN adapter
   via OTG.
2. The screens below, readable at a glance, following the phone's rotation.
3. Complete raw recording of every charge session (all IDs, undecoded ones included),
   compressed, shareable as one file.
4. Per-van profiles with backup and restore, and unobtrusive van recognition.
5. German and English, switchable in Settings.
6. Everything decoded is shown with plain names, units and an explanation; unconfirmed
   signals are kept apart (Experimental).

**Non-goals (v1)**
- Reading or clearing fault codes (DTCs). No addresses are known yet. A later opt-in
  feature, after the request/response IDs are sniffed.
- Cell-dip warnings ("range will drop / limp mode ahead"). Needs more data. v1 only
  collects that data (marks, drive logs).
- Embedding Grafana or any server/cloud component. Analysis on the laptop.
- iOS, desktop, web.
- ELM327 adapters of any kind (see §11), BLE adapters, slcan.

**Anti-goals (failures even if everything "works")**
- **The app transmits on the van's CAN bus.** It must not, ever, in any mode. That means
  no OBD probe (`0100`), no diagnostic requests, and adapters always configured
  listen-only. (Safety: the HV system and BMS are on that bus.)
- **Silent data loss.** A shared log that looks complete but isn't (dropped frames, an
  ELM clone) is worse than no log. Gaps and limited adapters must be marked in the data.
- **The app confuses other owners.** Raw hex, unexplained jargon or unprompted profile
  switching on the main screens count as failures.
- **The read loop stalls because of the UI or the database.** A slow insert must never
  block adapter reads.

## 4. Constraints and facts

- **Codebase:** `EV80BatteryAndroid/`. Kotlin, Jetpack Compose, Material 3, Room,
  foreground service. minSdk 24, target/compileSdk 36. Rework on a **new branch** from
  `refine-soc-and-db-delete`. The debug build uses `applicationIdSuffix ".dev"`.
- **Keep and reuse:**
  - `calc/SocEstimator.kt`, `calc/SocTracker.kt`, `calc/CoulombCounter.kt`,
    `calc/ConsumptionCalculator.kt` (review; coulomb counting and SOC anchoring stay);
  - `CrashLogger.kt`;
  - `can/SocFrameFilter.kt` (wake-burst filter for `0x322`);
  - `can/CellVoltageAccumulator.kt` (group → cell mapping: cell = (B1 − 7)/4 × 3 + slot);
  - the unit tests with their real-log fixtures;
  - `elm327/Elm327Connection.kt`'s RFCOMM socket handling as a starting point for the
    STN transport.
- **Replace:**
  - `data/db/BatteryLogEntry.kt` (one wide row with cells and temperatures as CSV
    strings; its `socPercent` is the wrong byte);
  - the hand-written `can/CanFrameParser.kt`, by the signal table (§7);
  - `BatteryMonitorService`'s read loop (§6).
- **Bus facts** (2026-10-31/11-01 benchmarks, `CAN-logs/bench_*`):
  - Traffic is about 624 frames/s with the van on or READY. The LX delivered 100 % in STN
    mode (`STMA`) and in ELM mode (`ATMA`), filtered or not.
  - During DC charging only the BMS broadcast set is on the bus (about 165 frames/s).
  - On off/on transitions the BMS set is silent for up to about 4 s, and `CAN ERROR`
    lines appear at every wake and sleep. Both are normal, not link failures.
  - The LX delivers frames in chunks. Laptop arrival timestamps had only 0.2 s
    resolution, so per-frame timing inside a chunk is not reliable.
  - Generic "ELM327 v2.1" clones: 0 frames (they search protocols and transmit).
- **Measured log size:** a 22-min DC session = 223,615 frames = 10.3 MB candump text →
  1.5 MB gzip / 1.0 MB zstd. Driving ≈ 2.3 M frames/h ≈ 15 MB/h gzip.

## 5. Screens

Navigation: bottom bar **Live · Tiles · Battery · Charging · Details · ⋯**. In portrait
the bar shows the first four plus ⋯. The ⋯ menu holds Export & Debug, Experimental and
Settings. All screens follow the device rotation when Android auto-rotate is on (no
forced orientation). Dark theme by default.

A **top bar** on every connected screen shows: van profile, adapter, van state chip
(Off / On / READY / Charging with gear), clock. Warnings turn the chip amber/red, always
with icon + text, never colour alone.

### 5.1 Connect / profiles (start screen); mockup `connect-v2.html`
- Van cards (name, colour, odometer, last seen), ＋ Add van, ✎ edit.
- Adapter choice:
  - OBDLink LX (Bluetooth), labelled "full logging";
  - USB-CAN via OTG, labelled "full logging", shown as plugged / not plugged;
  - (The "Other Bluetooth (ELM327)" card in the mockup is dropped for v1; see §11.)
- **Connect**, then a checklist: adapter connected → CAN data n frames/s → van awake/READY
  → jump to Live (or Charging, if a charge is running).
- **Recognition banner** (only when ≥ 2 profiles exist): "This looks like ET (odometer
  matches). Switch to ET · keep Lytee".
  - It is a suggestion only and never switches by itself.
  - Fingerprint: odometer `0x380` B2–4 when the van is on. When only the BMS is awake
    (charging), the charge-energy counter `0x590` B5–6, which only rises while charging.
    Both are compared with each profile's last seen values (odometer must be ≥ last
    and plausible).
- **Edit van:**
  - name, colour, default adapter, VIN (optional, typed by hand);
  - odometer at creation (auto) and usable capacity (default 160 Ah, learned by the SOC
    estimator, editable);
  - dated notes/history (e.g. "2026-06-19 cells rebalanced") and the recognition values;
  - data size, **Back up** (one compressed file: profile, notes, all sessions),
    **Restore** (also on a new phone), **Delete** (asks twice, offers a backup first).
- **First start** for new owners: 3 steps: language, adapter, van name (odometer and
  battery size read automatically). With one van the app never asks which van it is.

### 5.2 Live (standard driving view); mockup `live-driving-v2.html`
Layout "cell health first", portrait and landscape:
- **Hero:** lowest cell voltage with its number (`0x530`), a bar from 2.90 V (empty) to
  3.65 V (full) marking min and max cell, spread in mV with status chip, highest cell
  number and voltage.
- **Power band** (large, directly below): power kW, pack voltage, current (`0x550`).
- **Tiles:**
  - **SOC (app)**, big, with BMS SOC small (`0x322` B2/2);
  - **Range (app)**, big, with dash range small (`0x08A` B1);
  - speed (`0x380` B0–1);
  - consumption now (kWh/100 km);
  - 12 V (`0x303` B2 × 0.125);
  - battery / motor temperature.
- Keeps the screen on while connected (setting).

### 5.3 Tiles (separate tab); mockup `live-driving-v2.html`, lower part
- Grid of 8 equal tiles (2 × 4 landscape, 4 × 2 portrait). Long-press a tile → picker
  grouped by:
  - **Battery:** lowest / highest cell, spread, pack V, current, power, SOC app/BMS,
    range app/dash;
  - **Driving:** speed, odometer, gear/state, consumption now, consumption over a period
    (since last charge, all time, custom period);
  - **Temperatures & 12 V:** battery mean / min / max, motor, 12 V;
  - **Charging:** charge power, kWh charged, insulation.
- Layout stored per profile.

### 5.4 Charging; mockup `charging-v4.html`
Opens automatically when a charge starts (setting). Layout A (stacked charts):
- **Header:** charge power (big), voltage · current · "tapering" label; SOC app big, BMS
  small.
- **Tiles:**
  - charged kWh (from `0x550` energy and/or integrated power; also `0x590` counts);
  - time since start;
  - **to full** (estimate: missing energy ÷ current power, with a taper correction above
    ~95 % that is refined from logged sessions).
- **Battery temperature panel:**
  - average big (`0x322` B5), min/max (`0x323` B1/B2);
  - strip of all **18 sensors** (`0x520`: 4 frames, B2 = group 1–4, B3–B7 = 5 sensors
    each, raw − 40, 0xFF = unused);
  - status "✓ ok" or "❄ cold" with plain text: "AC charging won't start below ≈ 3 °C. DC
    charges slowly until the battery heater has warmed the pack." The threshold is a
    setting.
- **Insulation:** value with ✓/⚠, tap → explanation ("resistance between the HV battery
  and the van's body; higher is better; under ≈ 180 kΩ it's low; under ≈ 36 kΩ the BMS
  stops the charge; some chargers make it swing").
- **Charts** on one shared time axis, never one chart with two y-axes:
  - power (kW) on top;
  - insulation below, on a log scale with shaded "low" and "BMS stops charging" zones;
  - a dashed "now" line.
- Footer: cell range and spread, **charger name typed by hand ✎** (the van can't read it),
  **Share session ↗**.
- Charge state from `0x200` B0: 0x15 AC, 0x16 DC, 0x1A DC complete, 0x12 BMS stop.

### 5.5 Battery; mockup `battery-v3.html`
- 📅 **time bar** (period + snapshot time, ◀ ⊖ ▶).
- **Heatmap:**
  - 104 cells in their two banks (1–60, 61–104), numbered;
  - **colour scale A:** gray at the pack median, red below, blue above, saturating at
    ±60 mV, legend shown;
  - the lowest and highest cell outlined;
  - tap a cell → adds its line to the chart.
- **Chart:** the 10 lowest cells red and the 10 highest blue (selected at the snapshot
  time), all other cells as a gray min–max band, extreme cells bold and labelled.
- **Landscape:** heatmap on the left (larger, 15 per row, numbers visible), chart on the
  right.
- **Time picker** (bottom sheet, built natively):
  - quick ranges: 15 min / 1 h / 6 h / 24 h / 7 d / 30 d;
  - recorded sessions (charges with charger name, drives with km, "since last full
    charge");
  - absolute from → to;
  - earlier / later / zoom out ×2;
  - pinch-zoom and drag on charts.

### 5.6 Details; mockup `more.html`
- All **verified and probable** signals grouped (Battery, Driving, Vehicle). Each row:
  plain name, live value with unit, source (ID + bytes), status tag.
- Tap a row → explanation + small chart. Search. "hex" toggle shows raw bytes.

### 5.7 Export & Debug; mockup `more.html`
- **Session list** (charge / drive, date, charger, duration, kWh/km, size) with Share,
  multi-select.
- **Export formats:**
  - `.ev80` bundle (default);
  - CSV (decoded values);
  - candump log (laptop tools).
- **Status:** storage used and retention; connection health (adapter, chip, frames/s,
  errors, longest gap, CAN errors today).
- **Debug log written to a file** (rotating, survives restarts and overflow), with "Send
  debug report" and "Clear".

### 5.8 Settings; mockup `more.html`
- **General:**
  - language DE/EN;
  - theme Dark (default) / Light / System;
  - keep screen on while connected;
  - open Charging when a charge starts;
  - auto-connect when the van wakes.
- **Recording mode:** "automatic in the background" (reconnects and records charges and
  drives with the screen off, phone left in the van; asks for the battery-optimisation
  exemption) or "only while the app is open" (records after Connect until the van
  sleeps). Owner's decision: user-selectable.
- **Logging:** record full CAN traffic: always / charging only; raw-log retention.
- **Warning thresholds:** cell spread (80 mV), lowest cell (3.10 V), cold battery (3 °C),
  insulation low (180 kΩ).
- **App SOC:** usable capacity (learned, editable); "set SOC now".
- (The "Adapter (ELM327 only)" section in the mockup is dropped for v1; see §11.)

### 5.9 Experimental; mockup `more.html`
- **Mark a moment:** text + optional dash photo, saved with exact time into the session
  (the phone version of `can_live.py`'s `.marks` file). For owners to document things
  like "range dropped" or "limp mode started".
- **Recent marks.**
- **Unconfirmed signals** (e.g. `0x200` B1 stage bits, `0x550` B7, `0x302` motor temp)
  with live values and an "unknown" tag.
- **Raw monitor:** all IDs with bytes, highlighting bytes that changed after a mark and
  new IDs (like `can_live.py`).
- "Coming later" note for the cell-dip warning.

## 6. Architecture

```
CanSource (one per adapter type) ──frames──▶ FrameBus (bounded ring buffer, drop-counting)
                                              ├─▶ RawLogger    (all frames → session file, gz)
                                              ├─▶ Decoder      (signal table → VanState flow)
                                              └─▶ RawMonitor   (Experimental, on demand)
VanState ─▶ SessionManager (drive/charge detection, summaries, Room)
         ─▶ SocTracker / estimators (existing calc/)
         ─▶ UI (Compose, collects StateFlows)
```

- **Read loop:** the transport thread only reads bytes, parses lines/frames and pushes them
  into the ring buffer. Parsing beyond line splitting, decoding, database writes, file I/O
  and UI run on separate consumers. If a consumer falls behind, frames are dropped *for
  that consumer* with a counter, never at the adapter.
- **Transports (`CanSource`):**
  - **STN (OBDLink LX)** (reference): detect with `STI`/`STDI`; `ATE0 ATL0 ATS0 ATH1
    ATSP6 ATCAF0`; `STFCP` + one `STFAP <id>,7FF` per wanted ID for filtered modes; `STMA`
    for monitoring.
  - **Plain ELM327:** not supported in v1 (§11). An adapter that doesn't identify as STN
    is refused. The old app's `0100` probe transmits and must not be ported.
  - **USB-CAN via OTG:** the owner's adapter is a CANable-type **gs_usb / candleLight**
    device (USB 1d50:606f). Implement the gs_usb protocol over Android `UsbManager`
    (control requests for bit timing 500 kbit/s and mode, bulk IN for frames) with the
    **listen-only** mode flag. slcan adapters via usb-serial are optional.
  - Every source exposes: frames, frames/s, error lines, gaps, and whether it is
    "full logging" capable.
- **Error lines:** `CAN ERROR`, `BUFFER FULL`, `STOPPED` are counted and logged.
  `CAN ERROR` around wake/sleep is normal and must **not** restart monitoring. Restart
  only after `STOPPED`/`BUFFER FULL` or no frames for > 10 s while the van is known to be
  awake.
- **Van state** (from verified signals): Off (no frames); On (`0x580` B1 = 1); READY
  (`0x580` B1 = 5, DC link `0x301` > 300 V); Charging (`0x200` B0 0x15/0x16, complete
  0x1A); gear from `0x230` B0 (5 D, 6 N, 7 R, 8 P). `0x270`/`0x55C` mean "gear out of P",
  not READY.
- **Sessions:** a *charge* session runs from `0x200` B0 entering 0x15/0x16 until it leaves
  (plus 60 s); a *drive* from READY to READY-off. Short gaps (< 2 min) don't split a
  session.

## 7. Data

- **Signal table:** one data-driven definition per signal (ID, bytes, endianness, scale,
  offset, unit, name DE/EN, explanation DE/EN, group, status verified / probable /
  unknown). It is the single source for decoding, Details and Experimental. Its initial
  content comes from `/claude.md` → "merged signal table" plus the `0x520` decode in §5.4.
  Adding a signal must not need code changes elsewhere.
- **Raw log per session:** candump-text lines (`(epoch) can0 ID#DATA`) written to a
  **streaming gzip file** (multi-member, flushed every few seconds so a crash loses
  little). Laptop tools read it with `zcat`. Marks go into a sibling `.marks` file in
  `can_live.py` format. Timestamps are phone arrival times; the adapter type and its
  timing resolution are recorded in the session metadata.
- **Room (decoded):**
  - profiles;
  - sessions (type, start/end, van, adapter, charger name, kWh, km, min/max values, raw
    file path, dropped-frame count);
  - samples at 1 Hz (decoded key values);
  - cell snapshots (104 cells as a compact blob, one per complete set, decimated to e.g.
    1 per 10 s outside charging);
  - marks.

  Old app data is not migrated (its SOC column was the wrong byte). The old app stays
  installable alongside during the transition.
- **Retention:** charge-session raw logs kept until deleted; drive raw logs kept N days
  (default 90, setting); summaries and samples forever.
- **`.ev80` share bundle** (zip):
  - `session.json`: van profile without personal data, unless the owner opts in;
    session summary; app version; adapter; drops/gaps; charger name;
  - `can.log.gz`;
  - `marks`;
  - `samples.csv.gz`;
  - optional mark photos.

  Shared via Android's share sheet (Signal, Telegram, email). Size ≈ 1 MB per 20-min DC
  session.

## 8. Language

All user-visible text in `strings.xml`, English default plus German (`values-de`).
Switchable in Settings (per-app locale, AppCompat `setApplicationLocales`), independent
of the phone language. Signal names and explanations are translated as well.

## 9. Left to the builder

- The exact Room schema, compression block size, ring-buffer size.
- The chart library (or Canvas drawing), as long as the chart rules hold: one y-axis per
  chart, stacked charts on a shared time axis, legends for ≥ 2 series, status colours
  only with icon + text.
- The taper model for "to full", the app's range estimate (app SOC × capacity × pack
  voltage ÷ recent consumption), and the consumption smoothing.
- Whether the STN can stamp frames with its own timer. Use it if available; otherwise
  phone arrival time.
- Package/module structure, DI, tests (keep the existing JVM tests green; add signal-table
  tests on real-log fixtures from `CAN-logs/`).

## 10. My calls (not explicitly decided by the owner)

- Native time picker instead of embedded Grafana (no server/network in a van).
- Dark theme default; landscape and portrait both supported everywhere.
- Raw log as gzip candump text (laptop-compatible) instead of a custom binary format.
- Default retention 90 days for drive raw logs; charge logs kept.
- (Superseded by §11: the ELM327 path is dropped from v1.)
- No migration of the old app's database.
- Warning thresholds 80 mV / 3.10 V / 3 °C / 180 kΩ as editable defaults.

## 11. Decisions after the builder review

**Owner's decisions**
- **Background recording:** user-selectable in Settings (§5.8). The design must support
  both modes:
  - **automatic:** a foreground service started at boot/app start; reconnects to the
    profile's default adapter (Bluetooth retry with back-off, USB-attach intent for OTG);
    records unattended; "open Charging" becomes a notification when the app is not in
    front;
  - **only while open:** the service starts on Connect and stops when the van sleeps or
    the user stops it.
- **Distribution:** APK via GitHub release / Telegram. A permanent applicationId and a
  release signing key are created before the first public build. The app shows a hint
  when a newer release exists (a check that the user can switch off). No Play Store
  policy work in v1.
- **Build order (stages):**
  1. **Recording + Charging:**
     - transports (STN via Bluetooth, gs_usb via OTG, plus a **replay source** reading
       `CAN-logs/` files for testing);
     - frame bus, raw logger, signal table, van state, sessions;
     - Charging screen, Share/`.ev80` bundle, Export & Debug basics, DE/EN strings
       for these screens;
     - Connect screen with one profile.
  2. Live, Tiles, Battery (heatmap, time picker), Details.
  3. Profiles (multiple, recognition, backup/restore), Experimental, Settings complete,
     first-start flow.

**My calls (the builder may treat them as decided; the owner can overrule)**
- **Failed charge attempts are sessions.**
  - A "charge attempt" starts when the BMS set appears while the van is not READY
    (`0x200` B0 0x06 "awake/plugged"), or by a manual "plugging in now" mark.
  - It becomes a "charge" when B0 reaches 0x15/0x16, otherwise it ends as "attempt, no
    charge" after the BMS goes silent.
  - The raw logger keeps a **2-minute pre-roll ring buffer**, so "charging only" logging
    still captures the start.
  - Attempts are listed and shareable like charges (marked ✕).
- **Default "Record full CAN traffic":** *charging only*. Drive raw logs are opt-in
  ("always"), with a storage estimate shown next to the switch. Drives always get decoded
  samples and summaries.
- **ELM327 path dropped from v1.**
  - Generic clones transmit during their protocol search, which conflicts with the "never
    transmit" anti-goal.
  - v1 accepts only adapters that identify as STN (`STI`/`STDI`), any model, plus USB
    gs_usb.
  - An adapter that answers as plain ELM is refused with a plain message ("this adapter
    can't listen without sending; use an OBDLink or a USB-CAN adapter").
  - The Settings section "Adapter (ELM327 only)" and the "Other Bluetooth" card are
    removed; the old `elm327/` code is not ported.
- **Adapters in v1:** any STN device over classic Bluetooth SPP ("full logging"), and
  gs_usb/candleLight via OTG in listen-only mode. **Not in v1:** BLE adapters, slcan.
  Recommendation to owners: OBDLink LX/MX+ or a candleLight USB adapter.
- **State-aware warnings.**
  - The spread warning is suppressed while charging with the highest cell ≥ 3.45 V or
    SOC ≥ 95 % (normal LFP top-of-charge spread), and in the first 60 s after a load
    change.
  - Two levels: **amber** at the threshold, **red** at twice the spread threshold, below
    the lowest-cell threshold minus 0.1 V, at insulation < 36 kΩ, and at battery
    temperature < 0 °C while charging.
  - The Battery screen shows the top-of-charge spread as "normal at full" instead of
    "⚠ high".
- `0x580` B1 must be decoded by bits, not by value: it also carries the low-SOC bit 0x10
  and reads 0x05 during AC charging. The van-state rules in §6 are to be checked against
  the `CAN-logs/` corpus before use.

## 12. Test hardware

- OBDLink LX (STN, classic Bluetooth);
- a CANable-type gs_usb USB-CAN adapter;
- a Pixel 7 (USB-C, OTG capable; adb over Wi-Fi works);
- two vans.

All transports must also run against the replay source, so screens and logging can be
developed on recorded data (`CAN-logs/*.log`: DC sessions at many chargers, drives,
state changes, the 10-23 insulation trip).


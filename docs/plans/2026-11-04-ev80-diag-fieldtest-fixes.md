# EV80-diag Field-Test Fixes (0.3.0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** move the app into its own clean repository "EV80-diag" and fix what the owner found in the 2026-11-03 field test (3.6 km drive + AC charge, ET, OBDLink LX).

**Architecture:** no structural change. Signal-table edits (data), one navigation fix, small UI changes. Stage 3 (profiles, Experimental, first-start flow, update hint) follows in its own plan; after it the version becomes 1.0.

**Tech Stack:** unchanged (Kotlin, Compose/Material 3, Room 2.8, JUnit 4).

**Spec:** `docs/superpowers/specs/2026-11-03-ev80-diag-app-design.md`; field-test findings in `/claude.md` (entry "Field test 2026-11-03").

## Global Constraints

- Never transmit on CAN; nothing in this plan touches the transports.
- All UI text in `values/strings.xml` + `values-de/strings.xml` (StringsParityTest).
- **Never run `connectedDebugAndroidTest` on the owner's Pixel 7** (it uninstalls the app and wipes recordings). Phone checks use `installDebug` + replay only.
- No pushes to GitHub in this plan; the repo stays local until the owner says so.

## Review Focus

1. A tile layout saved with `TEMP_MOTOR` (removed kind) must load with that slot falling back to its default, not crash. Pinned in Task 2 (`decode_removedKind_fallsBack`).
2. Auto-jump to Charging must not hijack another tab's back stack (the reported bug). Pinned by the phone repro in Task 1.
3. Accelerator 0x380 B6 must not be read from the odometer=0 wake frame as a pedal value of something odd. Pinned in Task 2 (`accelPedal_380`).
4. Export of a long history must not load every cell snapshot at once: read page by page (Task 3).

---

### Task 0: New repository `EV80-diag`

Owner (2026-11-04): rename the package to `io.github.ev80diag`; keep the real-log fixtures out of the repo
(they live in `/home/user/VSCode/EV80-diag-testdata`, its own local git repo; fixture tests skip without it).

- [ ] **Step 1:** Create `/home/user/VSCode/EV80-diag` from the `ev80-diag-stage2` tree (`git archive ev80-diag-stage2 | tar -x -C …`), so no old history and nothing untracked comes along.
  - Leave out: `CLAUDE-MERGE-SUMMARY.md`, `can_config_obc_typ2.yaml` (EVCC config, unrelated), `canbus.md` (superseded by the signal table), `local.properties`, `build/`.
  - Add: `docs/` with the design spec and mockups, plus the stage plans (copied from `EV80-EVCC/docs/superpowers`); `tools/make_fixtures.sh` with the `CAN-logs` path as a parameter.
  - `settings.gradle.kts`: `rootProject.name = "EV80-diag"`.
  - Short `README.md` (what it is, listen-only, supported adapters, build). No licence file until the owner picks one.
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest :app:lintDebug` in the new repo. Expected: 209 tests green, 0 lint errors.
- [ ] **Step 3:** `git init -b main`, one commit `EV80-diag 0.2: recording, charging, live, tiles, battery, details`. The old repo `EV80BatteryAndroid` stays untouched as the archive. All further tasks work in the new repo on branch `fieldtest-fixes`.

### Task 1: Details opens Charging after a charge (navigation)

**Cause:** the charge-start jump (`NavGraph.kt` `LaunchedEffect(sessionType)`) and `NavRequests` call `navigate(Charging) { launchSingleTop }`, which pushes Charging on top of the current tab. Tab switches use `go()` with `saveState/restoreState`, so the tab you were on saves `[Details, Charging]` and restores it on every later tap.

- [ ] **Step 1 (RED, phone):** `installDebug`; open Details; replay `can0_ET_ABBTerra54_2026-10-29_1228.log` from Connect (Connect → Details via More while it replays). When Charging opens, tap More → Details. Expected now: Charging shows (bug reproduced). Screenshot.
- [ ] **Step 2:** All automatic jumps (charge start, `NavRequests`, after-Connect) use `go(route)`.
- [ ] **Step 3 (GREEN, phone):** repeat Step 1. Expected: Details shows; Charging tab still opens on its own tab.
- [ ] **Step 4:** Commit `fix(nav): automatic jumps switch tabs instead of stacking on the current one`.

(Ruling: navigation options aren't reachable from a JVM test, and instrumented tests are off-limits on this phone, so the phone repro is the failing test.)

### Task 2: Signals from the field test

**Files:** `signals/SignalTable.kt`, `ui/screen/LiveUi.kt`, `LiveScreen.kt`, `ui/tiles/TileKind.kt`, `TileValues.kt`, strings EN/DE. Tests: `DecoderTest`, `DetailRowsTest`, `TileLayoutTest`, `LiveUiBuilderTest`, `TileValuesTest`.

- New keys (group DRIVING, status VERIFIED):
  - `accel_pedal_pct`: `0x380` B6, unit `%`, "Accelerator pedal".
  - `brake_pedal`: `0x260` B3, unit "" (raw 0 … ~130, explanation says so), "Brake pedal travel".
- `0x302`: remove `motor_temp` and `inverter_temp`; add one `drive_temp_302` (`0x302` B3, −40, °C, status PROBABLE so Details shows it), name "Drive-unit temperature (unclear)", explanation: one value (B3 = B4), changes within a minute, not confirmed as motor or inverter.
  - Live: the "Temps" row shows battery only. Tiles: remove `TEMP_MOTOR` (old layouts fall back per slot).
- `disch_limit_kw`: rename to "Discharge power rating (BMS)"; explanation: not a hard cap (peaks reach ~90 kW), equals the BMS current limit × pack voltage and drops when the BMS cuts it at low SOC.
- `charge_counter`: shown as energy, scale 2.0, unit `kWh`, name "Lifetime energy charged"; explanation: AC and DC count, driving/regen does not, 2 kWh steps.

- [ ] **Step 1: Failing tests:**
  - `accelPedal_380`: `380#0000978F07002E00` → `accel_pedal_pct` = 46.0; the wake frame `380#0000000000000000` → 0.0.
  - `brakePedal_260`: `260#0000007A15000000` → `brake_pedal` = 122.0.
  - `chargeCounter_inKwh`: `590#…A51A…` (B5–6 = 0x1AA5) → 13642.0.
  - `noMotorOrInverterKeys`: `SignalTable.all` has no `motor_temp`/`inverter_temp`; `drive_temp_302` exists.
  - `DetailRowsTest`: Driving group contains `accel_pedal_pct` and `brake_pedal`.
  - `TileLayoutTest.decode_removedKind_fallsBack`: `"TEMP_MOTOR;…"` → slot 0 = `DEFAULT[0]`.
  - `PipelineReplayTest.drive1023_pedals`: during the 10-23 12:19 replay `accel_pedal_pct` reaches > 30 and is 0 whenever `brake_pedal` > 50.
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest` → the new tests FAIL.
- [ ] **Step 3:** Implement; fix compile breaks from the removed keys/tile.
- [ ] **Step 4:** Same command → all green.
- [ ] **Step 5:** Commit `feat(signals): pedals, honest 0x302/discharge/counter labels`.

### Task 3: Export all data of the van profile

Owner (2026-11-04): "export all data that is collected on this profile (vehicle name) at once".

- `export/ProfileExport.kt`: one zip with `profile.json`, `sessions.csv` (one row per session), `sessions/<name>.ev80` (each session's normal bundle, stored) and `cells.csv.gz` (all cell snapshots, `ts_ms,charging,c1…c104`, read in pages of 2,000).
- `SessionExporter.buildAll(ctx)`; DAO `SessionDao.listForProfile`, `CellSnapshotDao.page` (blocking, called on IO).
- Button "Export all data of <van>" under the session list, disabled while the zip is built.
- Tests `ProfileExportTest`: sessions CSV (quoting), cells CSV (106 columns), zip entries and nested bundle bytes, profile JSON quoting.

### Task 4: Version instead of clock

- `app/build.gradle.kts`: `versionName = "0.3.0"`, `versionCode = 3`, `buildFeatures.buildConfig = true`; debug `versionNameSuffix = "-dev"`.
- `TopStatusBar.kt`: replace the clock with `"v" + BuildConfig.VERSION_NAME` (e.g. `v0.3.0-dev`), same style.
- [ ] **Step 1:** Build, `installDebug`, screenshot the top bar. Expected: `v0.3.0-dev` where the time was.
- [ ] **Step 2:** Commit `feat(ui): app version in the top bar`.

### Task 5: Finish

- [ ] **Step 1:** Full `:app:testDebugUnitTest :app:lintDebug`. Expected: green, 0 lint errors.
- [ ] **Step 2 (phone, replay):** 10-23 12:19 drive → Details › Driving shows accelerator % and brake travel moving; Live "Temps" shows battery only; Export shows "All" when > 50 sessions (not reachable with real data yet — covered by the test).
- [ ] **Step 3:** Update `/claude.md` (status 0.3.0, repo location). Merge `fieldtest-fixes` into `main` in the new repo after the owner's OK.

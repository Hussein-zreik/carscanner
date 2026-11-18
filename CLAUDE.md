# CLAUDE.md — CR-V Diag (Honda OBD app)

Personal Android app that turns an Android tablet into a diagnostic tool for ONE car.
Built in phases; read this file at the start of every session.

## The car (single target — do not generalize)
- 2011 Honda CR-V EX, 4WD, US spec, built East Liberty, Ohio
- VIN: 5J6RE4H50BL017022
- Engine: 2.4L K24Z6 · Transmission: 5-speed automatic
- Networks behind the OBD port:
  - **F-CAN** (pins 6/14, ISO 15765-4, 500 kbps, **29-bit**, ELM protocol **7** — verified on the car
    2026-11-18; the earlier "11-bit" assumption was wrong). Units answering OBD: **18DAF111** (engine
    PIDs: fuel trims, MAF, O2) and **18DAF11D** (smaller set, likely transmission side).
  - **K-line** (pin 7): VSA/ABS, SRS (airbags), TPMS, immobilizer, audio
- No electronic AWD module (rear drive is mechanical).

## Hardware
- Tablet: Samsung Galaxy Tab A9+, Android 16 (API 36) — install via USB/Wireless debugging.
- Adapter now: generic ELM327 clone "V03H4", Bluetooth Classic (SPP) + Wi-Fi.
  - Treat as unreliable: may report a fake version, drop replies, or ignore some AT commands.
  - Wi-Fi mode: 192.168.0.10:35000 (adapter hotspot) — **verified 2026-11-17**.
  - Observed: `ATI` → `ELM327 v1.5` (clone version); echo is ON after `ATZ` and arrives as its
    own chunk; `0100` → `SEARCHING...` then replies (see `testdata/2026-11-17_wifi_first-contact.log`).
  - **Two ECUs answer `0100`** (support masks `BE3FA813` = engine-type set, `983A8003` = smaller set,
    likely the transmission side). Confirmed with ATH1 on 2026-11-18: 18DAF111 and 18DAF11D (29-bit).
  - Engine ECU (mask `BE3FA813`) supports 01,03,04,05,06,07,0B,0C,0D,0E,0F,10,11,13,15,1C,1F,20.
    No PID 14 (upstream narrowband O2) → upstream is probably a wideband A/F sensor; confirm via `0120`.
  - Protocol 7 is saved after the first auto-detect and later connects use `ATSP7` (no K-line probing).
  - **Never send ATSH on 29-bit CAN.** The default header after ATZ is already functional (18DB33F1);
    sending the 11-bit `ATSH7DF` made every request return NO DATA (bug 2026-11-18, fixed 0.8.2).
    Live data on 29-bit stays functional and keeps only 18DAF111's answers.
  - **Approved by owner 2026-11-17:** `ATSH7DF` and `ATSH7E0`–`ATSH7E7` only (standard OBD IDs) so
    requests can target one ECU; every other ATSH value stays refused. These only apply to 11-bit CAN,
    which this car does NOT use; on this car the app sends no ATSH at all.
- Adapter for Phase 3: OBDLink MX+ (STN chip, ELM327-compatible + extended ST commands). Not owned yet (2026-11-17).

## SAFETY RULES (non-negotiable)
1. **Read first, clear second, never write.** No coding, no configuration writes, no reflashing.
2. **Out of scope — never implement:** immobilizer/key programming, SRS commands beyond
   read + clear codes, ECU flashing/tuning, ABS bleed or any actuator test.
3. Every module has an **allow-list of request IDs/services**; the protocol layer refuses
   anything not on the list. Never bypass it, even for testing.
4. Clearing codes requires a confirmation dialog and must only run with engine OFF, ignition ON.
5. Never design screens meant to be watched while driving; logging runs in the background.
6. If unsure whether a command could change module state, **don't send it** — ask the user.

## Tech stack
- Kotlin, Jetpack Compose + Material 3 (dark theme default)
- Coroutines + Flow for polling; no blocking I/O on the main thread
- Room for local storage; Vico (or Compose Canvas) for charts
- Min SDK 26, target latest stable; Gradle Kotlin DSL; version catalog (`libs.versions.toml`)
- Tests: JUnit5 + Turbine for flows; Compose UI tests where useful
- Fully offline: no network calls, no analytics, no accounts.

## Module layout
```
app/        Compose screens, navigation, ViewModels
transport/  Transport interface + BluetoothSppTransport + WifiTcpTransport + FakeTransport
elm/        ELM327 driver: init, AT commands, headers, prompt '>' parsing, timeouts, retries
obd/        Standard OBD-II: modes 01/02/03/04/07/09, PID decoders, DTC text
honda/      Honda-specific: mode 22 over CAN (Phase 2), K-line modules (Phase 3), allow-lists
data/       Room DB: scans, logs, health baseline, maintenance; CSV export
```
Dependencies flow one way: `app → honda/obd → elm → transport`; `data` used by `app` only.

## Coding conventions
- One `Transport` interface: `connect()`, `send(String)`, `responses: Flow<String>`, `close()`.
- ELM layer returns typed results (`Ok(lines)`, `NoData`, `Timeout`, `Error(msg)`), never raw strings to UI.
- Every raw command/reply is logged with timestamp to a debug log (viewable in a Debug screen).
- Decoders are pure functions with unit tests using **recorded replies** from the real car.
- Recorded sessions live in `testdata/` as text files; `FakeTransport` replays them.
- Small commits after each working step; message says what was verified on the car.

## ELM327 init sequence (baseline)
`ATZ` → `ATE0` (echo off) → `ATL0` (linefeeds off) → `ATS0` (spaces off) → `ATH1` (headers on, debug) → `ATSP0` (auto protocol) → `0100` → read `ATDPN` and log the detected protocol.
Handle: `SEARCHING...`, `NO DATA`, `CAN ERROR`, `BUS INIT...ERROR`, `?`, `STOPPED`, partial lines, missing `>` prompt.

## Product direction (owner decisions, 2026-11-17)
- Goal: a **professional-grade tool for this one CR-V** (dealer-level reading + diagnosis). Not multi-car,
  not commercial.
- **Read-only forever:** read, diagnose, report, clear codes. No relearns, actuator tests, coding,
  SRS crash-data reset, immobilizer or flashing; those are done with Honda HDS at a shop.
- First professional feature after the car test: **full-system scan + dated PDF report**
  (pre-repair / post-repair / health check). Engine part works with the clone; SRS/VSA/TPMS need the
  OBDLink MX+ and captured Honda module addresses (TODO(capture)); until then the report lists them
  as "not scanned", never as "no codes".
- Later pro features (in order unless changed): Honda live data (Phase 2), diagnostic guidance
  (causes ranked for this car, drive-cycle guide, verified known issues), pro graphing.
- Validate values against a reference tool (Car Scanner now, Honda HDS once if possible).

## Roadmap
| Phase | Scope | Adapter | Done when |
|---|---|---|---|
| 1 Core | BT connect, read/clear engine DTCs, freeze frame, readiness, live gauges (10+ PIDs), CSV log, Wi-Fi fallback | Clone | Stable for a full 20-min drive |
| 2 Honda PCM | Mode 22 values (ATF temp, VTC, knock, etc.), performance dashboard, temp alerts | Clone / MX+ | Values match Car Scanner on same drive |
| 3 K-line | Read then clear VSA/ABS, SRS, TPMS codes; wheel speeds; health baseline | MX+ | Codes match OBDLink app |
| 4 Extras | Maintenance tracker (oil, ATF, rear diff fluid) from user's odometer, trip computer, history, reports | Any | Used weekly |

Honda-specific request IDs are **unknown until captured** from the car (sniffing Car Scanner / OBDLink app via adapter monitor). Never invent PIDs or service IDs — mark them `TODO(capture)`.

## Session workflow
1. Read this file. Do ONE task; state "done when" before starting.
2. Write/adjust tests first against recorded data where possible.
3. Run `./gradlew test` and `./gradlew assembleDebug`; fix failures before finishing.
4. Tell the user exactly what to test on the car and what log to send back.
5. Update the "Current status" section below.

## Commands
- Build: `./gradlew assembleDebug`
- Install: `./gradlew installDebug` (tablet connected, USB debugging on)
- Tests: `./gradlew test`
- Logcat: `adb logcat -s CrvDiag`
- **Releases:** `.github/workflows/android-release.yml` runs tests + lint + `assembleDebug` on every
  push (non-.md changes) and publishes `crv-diag-<version>-build<N>.apk` as a GitHub Release.
  Owner downloads from the repo's Releases page on the tablet. Bump `versionName` for real changes.
- Repo visibility: owner is making it private (2026-11-17) because CLAUDE.md contains the VIN and
  car history.

## Build notes (versions as of Session 1)
- Gradle 9.9.1 wrapper · AGP 9.5.1 · Kotlin 2.4.21 · KSP 2.3.14 · compileSdk/targetSdk 37 · JDK 17 bytecode
- AGP 9 has **built-in Kotlin**: modules must NOT apply `org.jetbrains.kotlin.android`
  (it is declared `apply false` in the root only to pin the Kotlin version).
- All 6 modules are Android modules; unit tests run on the JVM with JUnit5 via
  `testOptions.unitTests.all { it.useJUnitPlatform() }` (no extra plugin).
- All adapter traffic goes through `Elm327` (elm) → `GuardedSender` + `ConsolePolicy` (obd):
  adapter-only AT commands, `ATSH7DF`/`ATSH7E0-7`, modes 01/02/03/07/09, plus 06 (one MID) and 0A
  (read-only per SAE J1979; added 2026-11-17 for the scan report). Refused: other ATSH,
  ATCRA/ATPP/ATSD/ATMA/ATFI/ATBI, mode 04, 22, all others. `Elm327` owns the single responses collector.
- Startup: `ElmInitializer` (ATZ, ATE0, ATL0, ATS0, ATH1, ATSP, ATRV, 0100, ATDPN). First run uses
  ATSP0; a detected CAN protocol is saved (`ProtocolStore`) and later runs use ATSP<n> (no K-line probing).
  "Re-detect protocol" in the console clears it.
- Mode 04 is allowed ONLY by `ClearCodesPolicy`, which is `internal` to the obd module and used
  only by `CodeClearer` (checks 010C RPM = 0 from every ECU first, then ATSH7DF, then 04, no retry).
  `Elm327.execute(policy = …)` swaps the allow-list for one command; it never skips checking.
- Recording: `LiveViewModel` owns polling; it keeps polling off-screen only while recording;
  `RecordingService` only holds foreground status (no work). Recording stops when the link drops.
  Shared drive `.log` files are real recordings → candidates for `testdata/`.
- Live data (`LiveDataSession`, obd): polls only while the Live screen is open; on 11-bit CAN it addresses
  one unit and restores ATSH7DF afterwards, on 29-bit (this car) it never sends ATSH.
  `DiagnosticsReader` sends ATSH7DF first on 11-bit only. `LiveChecks` are guides with explicit conditions.
- UI (v0.4.0): landscape, navigation rail (Dashboard, Codes, Live data, Connection, Advanced),
  clean modern dark theme with `Status` colours (ui/theme), shared components (ui/components).
  One activity-scoped `ConnectionViewModel` + `CodesViewModel` shared by all screens; codes are read
  automatically (read-only) when the car link becomes Ready. Raw console + log live in Advanced.
- Screenshots (Robolectric, opt-in): `./gradlew :app:testDebugUnitTest -Pscreenshots` → `build/screenshots/`
  at Tab A9+ landscape size (1280×800 dp). Needs host `gcp.mirrors.maven.org` (added to the environment).
- DTC text: `HondaDtcText` (honda, only codes seen on this car, with source) then `DtcText`
  (obd, small generic SAE list); unknown codes say "not in the app's list" — never guessed.
- Tests replay `testdata/` via `RecordedLog` (transport). `ScriptedAdapter` (elm tests) mimics the clone;
  its CAN headers are SYNTHETIC until an ATH1 recording exists (marked TODO(capture)).
- Traffic log export format (future `testdata/` format): `HH:mm:ss.SSS TX|RX|INFO <text>` with `\r` escaped.
- Library modules declare their own install-time permissions (lint checks them per module).
- Room schemas are exported to `data/schemas/` and committed (needed for migrations).
- applicationId / base package: `com.crvdiag.app`, modules `com.crvdiag.<module>`.

## Current status
- [x] UI redesign (owner request 2026-11-17): dashboard, rail navigation, plain wording, app icon.
  Verified by rendered screenshots; not yet seen on the tablet.
- [x] Session 1: Android project with the 6 modules, Compose, Room, one passing test
  (verified in CI-like container: `./gradlew test assembleDebug` green; not yet installed on tablet)
- [~] Session 2: Bluetooth + Wi-Fi transports + raw console screen
  - Wi-Fi **verified on car** 2026-11-17 (ATZ/ATI/ATRV/ATDPN/0100 all answered).
  - Bluetooth **parked by user (2026-11-17)**: first attempt did not connect; cause unknown
    (single-client limit or pairing likely; iPhone Car Scanner uses the adapter over Wi-Fi, so no
    evidence of BLE-only). Resume when user re-scans; do not
    build a BLE transport without confirmation. Wi-Fi is the working link for now.
- [x] Session 3: ELM327 init + protocol detection + ECU discovery — **verified on the car 2026-11-18**
  (auto-start, protocol 7 detected and saved, ATSP7 on reconnect, both units identified).
- [~] Session 4: Live data screen: engine ECU only (ATSH7E0), multi-PID requests (≤6, auto-fallback to
  single), 0100/0120/0140 discovery, 4 dial gauges + tiles with sparklines, quick checks (trims,
  charging, upstream λ, downstream O2, coolant), units °C/°F·km/h/mph·kPa/psi (default °C, mph, psi),
  hidden while speed > 5 km/h (rule 5). Code done, tests green; **awaiting car test**.
- [~] Session 5 (done before Session 4, owner's choice): Trouble codes screen: MIL, stored (03),
  pending (07), readiness (0101) per ECU, freeze frame (02 frame 0), clear (04) behind dialog +
  engine-OFF RPM check. Code done, tests green; **awaiting car test** together with Session 3.
- [~] Session 6: drive recording: CSV (decoded, base units, UTF-8 BOM) + raw adapter log (testdata/
  format) per drive in filesDir/logs, Room `drive_logs` (DB v2, auto-migration), Logs screen with
  share (FileProvider, user-initiated) / delete, foreground service (connectedDevice) so recording
  survives screen-off; `FakeTransport` replays recordings (ReplayTest runs the real 2026-11-17 log).
  Code done, tests green; **awaiting car test**.
- [~] Phase 4 (early, owner's choice): Maintenance tracker: miles; 10 items (oil, ATF, rear diff,
  tire rotation, brake pads, brake fluid, air/cabin filters, coolant, spark plugs) with owner-editable
  "whichever comes first" intervals (defaults = starting points, NOT Honda's official schedule);
  odometer from owner readings with daily-average estimate; backwards-reading protection; Room v3
  (auto-migration 2→3). Code done, tests green; awaiting use on the tablet.
- [~] Pro Step A: scan report (Codes → Create report): Pre-repair / Post-repair / Health check;
  VIN via 0902 (check digit + match with `CarProfile.VIN`), CAL IDs 0904, module name 090A, permanent
  codes 0A, misfire counters (06 A2–A5), readiness, freeze frame; SRS/VSA/TPMS always "NOT SCANNED"
  until Step B. PDF (android PdfDocument, A4) in filesDir/reports, Room `scan_reports` (DB v4),
  listed under Logs & reports with Share/Delete. Tests green; on-screen preview rendered.
  **PDF output not verifiable under Robolectric (no native PdfDocument): verify on the tablet.**
- [ ] Pro Step B (SRS / VSA / TPMS codes) — **blocked, by design** (2026-11-17):
  - Honda's K-line protocol for these modules is not publicly documented (research found only
    engine-ECU K-line work). OBDLink's own app reads them on 2007–2019 CR-V via its paid Honda
    "enhanced diagnostics" add-on.
  - Plan (owner-approved): owner buys OBDLink MX+ and reads SRS/ABS/TPMS codes with the OBDLink app
    now (safety first); LATER a capture session (OBD Y-splitter + passive K-line sniffer while the
    OBDLink app reads codes) gives the exact request bytes. Only those captured, read-codes (and,
    behind confirmation, clear-codes) messages may ever enter the honda allow-lists.
  - Never build SRS/VSA/TPMS requests from guesses, forums or other Honda models.
- [~] Pre-test hardening (0.9.0, owner request 2026-11-18), code done + tests green, awaiting car:
  - 29-bit audit: `protocolAwareConsolePolicy` (obd) refuses ANY ATSH once the car is known to be
    29-bit (protocol layer, also blocks the console); 29-bit tests for codes, VIN multi-frame, live
    data, clearing; screenshot data uses this car's real IDs.
  - `DiagnosticsReader` retries 0101/03/07 up to 3× (300 ms apart) on NO DATA/timeout (clone drops).
  - Automatic reconnect (`ReconnectPolicy`: 3 tries, 2/5/10 s) only after a link that was Ready drops;
    a recording continues in a new file after reconnect (`LiveViewModel.attach`), service kept alive.
  - One-tap **Car test** (Dashboard → Run car test): connection, network, codes+readiness, VIN,
    permanent codes + misfires, 20 s live data, PDF report; ✅/⚠️/❌ per step; results file
    `reports/car-test_*.txt` (summary lines start with '#', followed by the raw log → valid testdata).
    `CarTestRunner` is Android-free and tested against 29-bit replay data.
- [~] Misfire check (0.10.0, owner request 2026-11-18: **rough/shaky idle reported**), code done + tests green:
  - `MisfireReader` (obd): service 06 MIDs A2–A5, TID 0C (this cycle) + 0B (10-cycle average), engine
    unit only; `MisfireAnalysis`: new misfires per cylinder (counter reset handled), RPM steadiness
    (SD > 40 rpm = unsteady, guide value), fuel trims (±10 % rich/lean), misfire codes P0300–P0304,
    "smooth data but shaky feel → mounts" hint; P145C/purge-valve link stated as a common cause, not fact.
  - Misfire screen (rail): guided 2-min parked idle test (aborts if speed > 5 km/h), counters polled
    every 10 s; drive watch: counters read at recording start/end, summary saved in
    `drive_logs.misfireSummary` (DB v5, auto-migration 4→5) and shown in Logs + Misfire screen.
  - Unknown: whether this Honda updates mode 06 counters during a cycle or only at its end; the screen
    says so if counters don't move. Verify on the car.
- Phase 1 status: all six sessions coded; Phase 1 is DONE only after a stable 20-min recorded drive.

## Notes
- Car Scanner reference (iPhone, Wi-Fi, 2026-11-17): profile was wrongly set to "CR-V RE6 / Accord
  Diesel" (car is RE4 gasoline) → its "SRS" result is not trustworthy. Pending P145C (EVAP purge flow,
  per aftermarket sources), MIL off, confirmed DTCs 0. Codes were **cleared recently** by the owner →
  Catalyst, EVAP, O2 sensor, EGR monitors incomplete. Mode 06: 0 misfires on all cylinders.
  The clone dropped Mode 06 replies between two identical reads (evidence for retries).
- **SRS (airbag) warning light reported NOT off with engine running (2026-11-17)** → real airbag
  fault likely; car has front-end salvage history. App cannot read SRS until Phase 3 (K-line, MX+).
  Owner advised to get a professional Honda-capable SRS scan.
- **Bug report 2026-11-18 (owner):** check-engine light ON, CR-V Diag showed no code, Car Scanner showed
  the EVAP code. 0.8.1 fixed the display (NO DATA ≠ "no codes", unread MIL = "Unknown"). **Root cause
  (from the owner's log, testdata/2026-11-18_wifi_29bit-codes-failed.log): the app sent the 11-bit
  ATSH7DF on this 29-bit car → every request NO DATA. Fixed in 0.8.2** (no ATSH on 29-bit); awaiting
  retest on the car. Same log: ATRV 14.0 V with the engine running → charging normal.
- **Symptom reported 2026-11-18: rough / shaky idle.** Car Scanner (2026-11-17) showed 0 misfires on all
  cylinders. Candidates: misfire, EVAP purge valve stuck open (P145C), vacuum leak, worn mounts.
- Battery (ATRV, engine off, ignition on, 2026-11-17): 12.1 V → 11.8 V. Recheck resting (≥12.4 V)
  and running (13.7–14.7 V) voltage.
- Car history: salvage-titled 2017 (front-end), repaired 2018; odometer branded "not actual" — the
  maintenance tracker uses the owner's own readings from 2018 onward.

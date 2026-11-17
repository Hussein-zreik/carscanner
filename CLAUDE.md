# CLAUDE.md — CR-V Diag (Honda OBD app)

Personal Android app that turns an Android tablet into a diagnostic tool for ONE car.
Built in phases; read this file at the start of every session.

## The car (single target — do not generalize)
- 2011 Honda CR-V EX, 4WD, US spec, built East Liberty, Ohio
- VIN: 5J6RE4H50BL017022
- Engine: 2.4L K24Z6 · Transmission: 5-speed automatic
- Networks behind the OBD port:
  - **F-CAN** (pins 6/14, ISO 15765-4, 500 kbps, 11-bit): PCM (engine + transmission in one unit)
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
    likely the transmission side). CAN IDs unconfirmed until a run with `ATH1` (Session 3).
  - Engine ECU (mask `BE3FA813`) supports 01,03,04,05,06,07,0B,0C,0D,0E,0F,10,11,13,15,1C,1F,20.
    No PID 14 (upstream narrowband O2) → upstream is probably a wideband A/F sensor; confirm via `0120`.
  - Plan: once protocol 6 is confirmed, pin `ATSP6` instead of auto-search so the adapter never
    probes the K-line (pin 7: SRS/VSA/immobilizer) during startup.
  - Decision pending (user): allow `ATSH7DF` and `ATSH7E0`–`ATSH7E7` only (standard OBD IDs) so
    requests can target the engine ECU; all other ATSH values stay refused.
- Adapter for Phase 3: OBDLink MX+ (STN chip, ELM327-compatible + extended ST commands).

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

## Build notes (versions as of Session 1)
- Gradle 9.9.1 wrapper · AGP 9.5.1 · Kotlin 2.4.21 · KSP 2.3.14 · compileSdk/targetSdk 37 · JDK 17 bytecode
- AGP 9 has **built-in Kotlin**: modules must NOT apply `org.jetbrains.kotlin.android`
  (it is declared `apply false` in the root only to pin the Kotlin version).
- All 6 modules are Android modules; unit tests run on the JVM with JUnit5 via
  `testOptions.unitTests.all { it.useJUnitPlatform() }` (no extra plugin).
- Raw console sends only via `GuardedSender` + `ConsolePolicy` (obd module): adapter-only AT
  commands + modes 01/02/03/07/09. Refused: ATSH/ATCRA/ATPP/ATSD/ATMA/ATFI/ATBI, mode 04, 22, all others.
- Traffic log export format (future `testdata/` format): `HH:mm:ss.SSS TX|RX|INFO <text>` with `\r` escaped.
- Library modules declare their own install-time permissions (lint checks them per module).
- Room schemas are exported to `data/schemas/` and committed (needed for migrations).
- applicationId / base package: `com.crvdiag.app`, modules `com.crvdiag.<module>`.

## Current status
- [x] Session 1: Android project with the 6 modules, Compose, Room, one passing test
  (verified in CI-like container: `./gradlew test assembleDebug` green; not yet installed on tablet)
- [~] Session 2: Bluetooth + Wi-Fi transports + raw console screen
  - Wi-Fi **verified on car** 2026-11-17 (ATZ/ATI/ATRV/ATDPN/0100 all answered).
  - Bluetooth **parked by user (2026-11-17)**: first attempt did not connect; cause unknown
    (possible BLE-only adapter, single-client limit, or pairing). Resume when user re-scans; do not
    build a BLE transport without confirmation. Wi-Fi is the working link for now.
- [ ] Session 3: ELM327 init + protocol detection logged
- [ ] Session 4: Mode 01 live data (10 PIDs) + gauge screen
- [ ] Session 5: DTCs (03/07), clear (04) with confirmation, freeze frame (02)
- [ ] Session 6: CSV logging + FakeTransport replay

## Notes
- Battery (ATRV, engine off, ignition on, 2026-11-17): 12.1 V → 11.8 V. Recheck resting (≥12.4 V)
  and running (13.7–14.7 V) voltage.
- Car history: salvage-titled 2017 (front-end), repaired 2018; odometer branded "not actual" — the
  maintenance tracker uses the owner's own readings from 2018 onward.

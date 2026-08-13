# App Time Limiter — Product Requirements Document

**Status:** Approved for planning
**Date:** 2026-08-12
**Platform:** Android (Kotlin, Jetpack Compose)
**Distribution:** Personal / sideload only — not intended for the Play Store

---

## 1. Summary

An Android app that lets you cap how much time you spend in a chosen app across a
day, and optionally how long any single stretch of use can run. When a cap is
reached, a full-screen block appears over the offending app and sends you back to
the launcher.

Limits are edited behind a 4-digit passcode, and any change that loosens a limit
only takes effect at the next daily reset — so the app cannot be argued with in
the moment it is most tempting to argue with it.

## 2. Problem

Android's built-in Digital Wellbeing timers are trivially dismissed: the "app
paused" screen offers a one-tap 15-minute extension, and the timer itself can be
removed instantly. The friction sits in the wrong place — at the moment of
weakest resolve rather than at the moment of planning. This app moves every
loosening decision to a time when it costs nothing to be honest.

## 3. Platform constraint (read this first)

Android provides no supported mechanism for a third-party app to kill, suspend,
or truly block another app. Every product in this category works the same way:
observe which app is in the foreground, then cover it with a screen of your own.
The user can always press home, disable the service in Android's settings, or
uninstall.

This PRD therefore specifies **reliable friction**, not enforcement. Every
requirement below should be read with that ceiling in mind, and no requirement
should be interpreted as a promise that a determined user cannot get past it.

## 4. Goals and non-goals

### Goals

- Cap daily foreground time per app, with a hard block on exhaustion.
- Cap the length of a single continuous session, with a cooldown before the next.
- Make loosening a limit require both a passcode and a wait until tomorrow.
- Keep tracked totals accurate even if the app's own service is killed or the
  phone reboots.
- Be honest about state: if tracking is not working, say so loudly.

### Non-goals

- Obstructing Android's own settings, the uninstall flow, or the accessibility
  toggle. Uninstalling is always available and always immediate.
- Guaranteeing a user cannot bypass the block.
- Play Store distribution or compliance with its accessibility-API policy.
- Any network activity, account, sync, or telemetry. The app is fully offline.

## 5. Users and scope

Single user, single device, no accounts. Android 12 (API 31) and above.

## 6. Functional requirements

### 6.1 Tracking apps

- **FR-1** The user can add an installed app to the tracked set from a picker.
  The picker lists launchable installed apps alphabetically. It does **not**
  display usage figures for untracked apps.
- **FR-2** The home screen lists only tracked apps, each showing today's usage
  against its configured caps and the state of its session cooldown, if any.
- **FR-3** The home screen shows nothing about untracked apps.

### 6.2 Limits

- **FR-4** A tracked app has a `LimitConfig` with two independently optional
  caps: a **daily budget** (total foreground minutes per day) and a **session
  cap** (maximum length of one continuous session). At least one must be set.
  An app with only a session cap and no daily budget is valid, and vice versa.
- **FR-5** A limit with a session cap also carries a **session gap** (minutes out
  of the foreground required to end a session; default 5) and a **cooldown**
  (minutes that must pass after a capped session before the app may be opened
  again; default 15). Both are editable.
- **FR-6** Every limit carries a **warning threshold** in minutes remaining,
  defaulting to 5 and editable per app. It applies to the daily budget only and
  is ignored for an app configured with a session cap and no daily budget.
- **FR-6a** Because FR-4 requires at least one cap, removing the last remaining
  cap is not a valid edit. The editor presents that action as untracking the app,
  which follows the FR-23 loosening rule.

### 6.3 Measuring usage

- **FR-7** Usage means foreground time as attributed by Android's
  `UsageStatsManager`, which is the authoritative ledger for daily totals.
- **FR-8** A live in-memory timer tracks the session currently in progress;
  displayed usage is the reconciled total plus live session elapsed time.
- **FR-9** Reconciliation against `UsageStatsManager` runs on every foreground
  change, whenever the app's UI is opened, and on a periodic tick while a tracked
  app is in the foreground. This ensures usage accrued while the service was
  down is still counted.
- **FR-10** Time spent on this app's own block screen never counts as usage of
  the blocked app.

### 6.4 The daily reset

- **FR-11** The budget day is the local calendar date. It resets at local
  midnight. There is no configurable reset hour.
- **FR-12** At the reset boundary, all daily budgets are refreshed, all
  once-per-day warnings are re-armed, and all `PendingChange` records whose
  effective date has arrived are applied.
- **FR-13** A device timezone change re-derives the current local date; usage
  already recorded against a date is not retroactively re-bucketed.

### 6.5 Blocking

- **FR-14** A block fires when any of the following becomes true while a tracked
  app is in the foreground:
  1. daily usage ≥ daily budget;
  2. current session length ≥ session cap;
  3. the app is opened during the cooldown following a capped session.
- **FR-15** Daily-budget exhaustion blocks **immediately**, mid-session, with no
  grace period and no allowance for finishing the current session.
- **FR-16** The block screen states which rule fired and the time at which it
  lifts — next local midnight for a daily budget, or the cooldown end timestamp
  for a session cap. It offers exactly one action: a button returning to the
  launcher. There is no extension, snooze, or passcode escape on the block screen.
- **FR-17** The block screen reappears every time the user re-enters the blocked
  app while the blocking condition still holds.
- **FR-18** The following are never blocked under any configuration: the default
  launcher, the default dialer and any emergency-call UI, the Android Settings
  app, the system UI package, and this app itself. This allowlist is enforced in
  code and is not user-editable.

### 6.6 Warnings

- **FR-19** When remaining daily budget for a tracked app falls to or below its
  warning threshold, post one notification for that app. At most one such
  notification per app per day. Apps with no daily budget produce no warning.
- **FR-20** No countdown overlay and no persistent per-app status notification in
  v1. The only ongoing notification is the one required by the foreground
  service, plus the tracking-is-down warning of FR-27.

### 6.7 Editing limits — passcode and delay

- **FR-21** A 4-digit passcode is set during onboarding, stored only as a salted
  hash. It gates the limit editor: adding a tracked app, changing any field of a
  `LimitConfig`, and untracking an app. It does **not** gate viewing the home
  screen, opening the app, or dismissing a block.
- **FR-22** Changes that **tighten** a limit take effect immediately. Tightening
  means: lowering a daily budget, lowering a session cap, adding a cap that did
  not exist, raising a cooldown, or lowering a session gap.
- **FR-23** Changes that **loosen** a limit are recorded as a `PendingChange` and
  applied at the next daily reset. Loosening means: raising a daily budget,
  raising a session cap, removing a cap, lowering a cooldown, raising a session
  gap, and untracking an app entirely.
- **FR-24** Pending changes are visible in the UI, showing what will change and
  when. A pending change may be cancelled at any time (cancelling is a
  tightening, so it applies immediately). Editing a limit that already has a
  pending change replaces that pending change.
- **FR-25** Adding a new tracked app takes effect immediately, since tracking a
  previously untracked app is a tightening.
- **FR-26** Forgetting the passcode: a "forgot passcode" action starts a visible
  24-hour countdown, after which the passcode can be reset. The request may be
  cancelled; re-requesting restarts the full 24 hours. No backup code, no
  security question.

### 6.8 Reliability and honesty

- **FR-27** If the accessibility service is disabled, or usage-access permission
  is revoked, the home screen shows a prominent banner and a persistent
  notification states that tracking is not running.
- **FR-28** A `BOOT_COMPLETED` receiver restores the foreground service after
  reboot. On restart, reconciliation immediately re-establishes today's totals
  from `UsageStatsManager`.
- **FR-29** No requirement anywhere in this document may be satisfied by
  preventing the user from reaching Android's settings or uninstall flow.

## 7. Non-functional requirements

- **NFR-1** Block latency: under 500 ms from foreground change to block screen
  visible, on the target device.
- **NFR-2** Battery: no continuous polling loop. Detection is event-driven;
  periodic reconciliation while a tracked app is foregrounded runs at most once
  per 30 seconds.
- **NFR-3** All data is local. The app declares no internet permission.
- **NFR-4** The rule engine contains no Android framework dependencies and is
  fully testable on the JVM.

## 8. Architecture

Five layers. The deliberate constraint is that all decision logic lives in a pure
Kotlin module with an injected clock, so the rules can be tested exhaustively
without a device.

### 8.1 `detection`

An `AccessibilityService` subscribed to `TYPE_WINDOW_STATE_CHANGED`, emitting a
stream of "foreground package is now X" events. Event-driven, near-zero latency,
no polling.

### 8.2 `ledger`

Owns the truth about time used.

- `UsageStatsReconciler` — queries `UsageStatsManager` for per-package foreground
  seconds for the current local day and writes them to `DailyUsage`.
- `LiveSessionTimer` — tracks the in-progress session in memory from detection
  events.

The hybrid split matters: accessibility events are the better *trigger* (instant,
cheap), `UsageStatsManager` is the better *ledger* (it can report what happened
during any window in which our service was not running). Using either alone
sacrifices one of those properties.

### 8.3 `rules`

`LimitEngine`: a pure function

```
(dailyUsage, liveSession, cooldownState, LimitConfig, Clock) -> Decision
```

where `Decision` is one of `Allow`, `Warn(app)`, or
`Block(reason, liftsAt)`. Also owns the pending-change resolution and the
day-rollover computation. No Android imports.

### 8.4 `enforcement`

`BlockScreenLauncher` starts the full-screen block Activity and applies the
FR-18 allowlist. Holding `SYSTEM_ALERT_WINDOW` is what makes the launch reliable
— see §10.

### 8.5 `ui`

Jetpack Compose. Screens: Home (today), app picker, limit editor, passcode setup,
passcode entry, block screen, onboarding wizard.

## 9. Data model

Room, plus DataStore for secrets and settings.

| Entity | Fields |
| --- | --- |
| `TrackedApp` | `packageName` (PK), `label`, `addedAt` |
| `LimitConfig` | `packageName` (PK/FK), `dailyBudgetMinutes?`, `sessionCapMinutes?`, `sessionGapMinutes`, `cooldownMinutes`, `warningThresholdMinutes` |
| `PendingChange` | `id`, `packageName`, `kind` (`UPDATE` \| `REMOVE`), `payload` (serialized `LimitConfig`), `effectiveDate` |
| `SessionRecord` | `id`, `packageName`, `startedAt`, `lastSeenForegroundAt`, `endedAt?`, `endedByCap` |
| `DailyUsage` | `packageName` + `localDate` (composite PK), `foregroundSeconds` |
| `WarningSent` | `packageName` + `localDate` (composite PK) |

DataStore: `passcodeHash`, `passcodeSalt`, `recoveryRequestedAt?`,
`onboardingComplete`.

## 10. Permissions

| Permission | Why | How obtained |
| --- | --- | --- |
| `PACKAGE_USAGE_STATS` | Authoritative usage ledger | Settings deep link, special access |
| `BIND_ACCESSIBILITY_SERVICE` | Instant foreground detection | Settings → Accessibility, manual toggle |
| `SYSTEM_ALERT_WINDOW` | Android 10+ restricts background activity starts; holding this permission grants the exemption that makes launching the block screen reliable | Settings deep link |
| `POST_NOTIFICATIONS` | Warnings and service notification (API 33+) | Runtime prompt |
| `FOREGROUND_SERVICE` (+ `specialUse` type) | Keeps the ledger and timers alive | Manifest |
| `RECEIVE_BOOT_COMPLETED` | Restore service after reboot | Manifest |

An onboarding wizard walks each permission in order, verifies it was actually
granted before advancing, and can be re-entered later from the home screen banner.

## 11. Testing strategy

- **Pure JVM unit tests** against `LimitEngine` with an injected clock, covering:
  every combination of daily-only / session-only / both caps; mid-session daily
  exhaustion; session gap expiry and cooldown arithmetic; the tighten-now versus
  loosen-tomorrow classification for every editable field; day rollover including
  a rollover that occurs mid-session; pending-change application and cancellation.
- **Instrumented tests** for Room DAOs and migrations.
- **Reconciliation tests** driving `UsageStatsReconciler` from synthetic usage
  event streams, including a stream containing a gap where the service was down.
- **Manual test matrix** for the device-dependent behaviour that cannot be
  honestly unit-tested: measured block latency, service survival across OEM
  battery optimisation, behaviour after reboot, behaviour when the accessibility
  service is disabled mid-session, and confirmation that the FR-18 allowlist
  holds.

## 12. Out of scope for v1

Usage history and trends beyond today; app groups with a shared budget;
per-day-of-week or schedule-based limits; a temporary pause or vacation mode;
home-screen widgets; cloud sync or multi-device; any obstruction of Android's
settings or uninstall flow; countdown overlays over the tracked app.

## 13. Open risks

- **OEM battery management.** Aggressive vendors (Xiaomi, Samsung, OnePlus) may
  kill the foreground service or disable the accessibility service. The
  reconciliation design limits the damage to lost *blocking*, not lost
  *accounting*, and FR-27 makes the failure visible — but this is the single
  largest reliability risk and should be verified early on the actual device.
- **Background activity start restrictions** continue to tighten across Android
  releases. `SYSTEM_ALERT_WINDOW` is the current mitigation; if it proves
  unreliable on the target device, the fallback is to have the accessibility
  service perform `GLOBAL_ACTION_HOME` and then surface the block screen.
- **`UsageStatsManager` attribution** does not always agree with intuition for
  split-screen, picture-in-picture, or apps that spawn activities in other
  packages. Expected to be a minor discrepancy; worth confirming against Digital
  Wellbeing's own figures during development.

# Local background orchestration

Implemented in `app/src/main/java/com/user/localagent/automation/BackgroundOrchestrator.kt`.

This feature makes local, explainable workflow recommendations. It does not train or fine-tune a model, load an LLM, install a skill, run a tool, send data, or read other applications' content. Both observation options default off.

## Integration API

Construct `AutomationController(context)` using an Activity or application context. Methods are synchronous and lightweight except the internal worker analysis.

- `settings(): AutomationSettings` reads `assistantPatternsEnabled`, `usageStatsEnabled`, and `intervalMinutes` (clamped to 15..1440).
- `updateSettings(settings): AutomationState` persists opt-ins. Disabling assistant patterns clears counters; disabling usage clears its summary. Every settings change clears old recommendations. Disabling both stops work.
- `start(): AutomationState` schedules periodic analysis and a coalesced first run. Requires at least one enabled observation setting. Call from an explicit UI action.
- `stop(): AutomationState` cancels both unique workers and stops visible mode. It preserves history; restarting requires an explicit user action.
- `state(): AutomationState` returns settings, enabled/pending, usage-access status, last run/status, outcome count, recommendations, and `visibleModeRequested`. This is a snapshot; refresh after user actions or on Activity resume. `visibleModeRequested` records intent and is not proof that Android currently hosts the service.
- `recordOutcome(toolName: String, success: Boolean)` should be called after the assistant completes a tool. Exact known tool names map to fixed categories; unknown names become `other`. No arguments, prompt, output, phone number, URL, document title, or message body is stored. Counting requires both running state and assistant-pattern opt-in.
- `hasUsageAccess(): Boolean` checks the current AppOps grant; it is independent of the app's usage opt-in.
- `usageAccessSettingsIntent(): Intent` is returned to the Activity for user-initiated settings launch. It never launches itself. Recheck access on resume.
- `startVisibleMode(): AutomationState` starts analysis plus an optional foreground service. Invoke from an explicit tap in a visible Activity; handle a launch exception by reporting that periodic mode remains enabled. Never call from boot, a worker, or background restoration.
- `stopVisibleMode(): AutomationState` stops only the visible service, retaining periodic analysis. The notification's stop action does the same.
- `clearLocalHistory(): AutomationState` removes counters, usage summaries, recommendations, and the last-run timestamp. It does not disable opted-in analysis; call `stop()` first when erasure should also end collection.

UI should explain the two separate opt-ins and show an explicit Usage access settings button. Android notification permission is relevant to drawer visibility on Android 13+, even though it is not required for starting a foreground service. Distinguish Android's permission state, app opt-in, and running state.

## Scheduling and energy

A unique periodic WorkManager job has a 15-minute minimum; execution can be delayed by Android and is not an exact schedule. A separate unique one-shot job coalesces app events, with a 15-minute analysis cooldown. Work requires battery-not-low; analysis also defers when power saver is on, battery is at or below 15%, or thermal status is moderate or worse. Deferral leaves pending work for a future periodic run and avoids an immediate retry loop. There is no network constraint because analysis uses no network.

Optional `LocalOrchestratorService` publishes an ongoing low-importance notification and listens only to user-present, power-connected, and power-saver-change broadcasts. Those events mark work pending; they do not trigger continuous inference. The service uses `START_NOT_STICKY`, no wake lock, no polling, and no boot start. Android can stop it; the UI must not promise uninterrupted execution. WorkManager is the independent periodic fallback.

## Data and recommendations

Metadata is bounded and stored in private app SharedPreferences named `local_automation_v1`; this module does not alter the encrypted Room database. Counters retain only a fixed category plus success/failure, with capped totals. They are lifetime counts since erasure, not a claim about frequency over a particular day. Five successful related assistant actions produce an explainable workflow suggestion.

With both usage opt-in and system access, the worker requests `UsageStatsManager.INTERVAL_DAILY` for a recent day. System bucket boundaries can extend outside the requested interval. Persisted usage data contains only observation time, aggregate foreground milliseconds, aggregate known-document-app milliseconds, and app count. Package identifiers are used transiently to match a fixed document-app allowlist and are not persisted. The 15-minute document threshold produces a suggestion that explicitly cites system daily buckets. Empty results are shown as unavailable, not proof that the phone was unused. No Accessibility service, notification listener, screen capture, app file access, or installed-app inventory is used.

Recommendations only suggest reviewing a workflow/skill; they do not assert that a particular skill is installed, trustworthy, free, or authorized. Installation and execution remain explicit UI decisions. Settings/erasure generation checks prevent an in-flight result from publishing after withdrawal or history erasure.

## Manifest entries for root integration

```xml
<uses-permission android:name="android.permission.PACKAGE_USAGE_STATS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<!-- Existing POST_NOTIFICATIONS declaration remains relevant. -->
<application ...>
    <service
        android:name=".automation.LocalOrchestratorService"
        android:exported="false"
        android:foregroundServiceType="specialUse">
        <property
            android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
            android:value="Explicit user-started visible local assistant event orchestration with on-device metadata analysis; no continuous model inference" />
    </service>
</application>
```

`PACKAGE_USAGE_STATS` is special system access, not a normal runtime permission dialog. The service declaration describes the implemented use; distribution review acceptance of specialUse is not established by source implementation.

## Migration and restoration review

- Existing `LocalDatabase` is version 1 with an exported schema and no registered migration. This module adds no Room entity, so it needs no database version bump. Future persisted analysis entities require a real migration and preserved v1 schema; never use destructive migration for existing notes/memories/reminders.
- Existing SQLCipher key is wrapped by Android Keystore, with app backup disabled. A DB copy without its original usable key cannot be opened; keep the existing failure behavior instead of silently recreating/deleting data. Metadata prefs are private but not independently encrypted, so do not later add content-bearing fields to them.
- WorkManager owns reboot restoration of its persisted jobs. No foreground service is restored at boot or package replacement. Force-stop or OS restrictions can prevent work until the user relaunches; the next Activity resume should refresh displayed state. App opt-ins remain persisted, but Usage access is checked each run and may be revoked separately.
- Existing reminder delivery atomically changes `pending` to `delivering`, then posts a notification and marks `delivered`. Process death between those operations can strand `delivering`, because `restoreAll()` queries only `pending`. A bounded delivery lease/recovery policy is needed before claiming crash-safe exactly-once delivery.
- Existing exact-alarm permission can change between capability checking and AlarmManager scheduling; restoration can retry failures. Notification permission denial leaves overdue reminders pending. New analysis must not change those reminder statuses or interpret a missing notification as successful delivery.
- Existing `RestoreWorker` catches `Throwable`, including cancellation; a later scheduling patch should rethrow `CancellationException`. Reminder restoration uses stored epoch milliseconds, so changing timezone does not reinterpret reminders as floating local times.

## Validation boundary

Source-level review and file existence checks only for this change; no build or device tests were run by this worker. Root integration should compile the source and verify: opt-ins default off; revoked Usage access; no-content storage; outcome coalescing; stop/erase during analysis; battery/thermal deferral; Activity-only foreground start; notification stop; reboot/force-stop behavior; and existing reminder regression tests.

Official API references: [UsageStatsManager](https://developer.android.com/reference/android/app/usage/UsageStatsManager), [Work requests](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work), [foreground service launch](https://developer.android.com/develop/background-work/services/fgs/launch), [special-use service type](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use).

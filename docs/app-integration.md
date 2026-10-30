# Optional app screen bridge

`integrations/ScreenBridge.kt` provides a narrow, user-driven Accessibility service adapter. It is disabled until the user enables the service in Android settings **and** separately opts into a supported package. It is not an autonomous UI planner and does not train a model.

## Supported package boundaries

The fixed package allowlist is `com.tionsoft.meettalk.eun`, `com.meditong.gw`, `com.kakao.talk`, `org.telegram.messenger`, `org.telegram.messenger.web`, and `com.android.chrome`. These entries identify candidate app boundaries, not verified app workflows or verified package publishers. Each package defaults off. No installed-app inventory is collected.

Inspection returns only the foreground package, installed version code, snapshot version, resource IDs, widget class names, and whether the known widget class exposes Click/SetText. It never reads Accessibility node text, content descriptions, event text, screenshots, message bodies, passwords, recipients, or app document content. No schema, action input, or token is persisted or logged. Only package opt-ins and their revision are stored in private SharedPreferences.

A snapshot contains at most 256 visited nodes, 64 children per node, and depth 16. Truncation disables all action capabilities. Unknown widget classes, missing IDs, IDs outside the app's resource namespace, duplicate IDs, password inputs, and identifiable security/dialog controls are rejected. The service requires an unlocked device and the focused foreground application window on the default display. Android user-granted Accessibility scope supplies window access; the code does not call cross-profile APIs, root, shell, or hidden user APIs. Secure Folder or another configured profile does not itself disable inspection/actions. Foreign/system focused windows and identifiable password/security/dialog controls are refused.

The metadata checks are deliberately conservative, but metadata cannot prove the semantic purpose of every custom app control. No app-specific login, message-send, download, security, or document workflow has been validated. Do not expose a generic automatic click loop or claim that unfamiliar controls are safe because their class resembles a button. Keep the inspected screen visible for the user to verify the target and consequences. Additional app-specific adapters should use inspected resource IDs and be validated on the exact installed version before advertising a workflow.

## Integration API

Create `ScreenBridgeController(context)`.

- `settingsIntent(): Intent` returns Android Accessibility settings; the Activity launches it only after explicit user action.
- `setAllowed(packageName, allowed)` changes the separate per-package opt-in. Every change advances consent revision, invalidating earlier prepared actions.
- `state(): ScreenBridgeState` returns `systemEnabled`, `connected`, and `allowedPackages`.
- `inspectVisibleSchema(): ScreenSchema` captures only the current allowed app's schema; call on the main thread. It invalidates an earlier preparation.
- `prepare(ScreenActionRequest): PreparedScreenAction` checks the current snapshot, package, installed version, ID uniqueness, enabled/visible state, ancestry, and action capability. Request fields are `packageName`, `installedVersion`, `viewId`, `snapshotVersion`, `action` (`ScreenAction.SET_TEXT` or `CLICK`), and optional `value`. SetText requires 1..2000 explicitly supplied characters; Click requires no value.
- `approve(preparedId): String` generates a random one-use approval token **only after** the user has reviewed the exact prepared action. The UI must show the package, app version, view ID, action, and exact typed SetText value. It must explain that Click may submit, send, delete, or download depending on the app. This method is an in-process authorization boundary; do not expose it to a model, extension, exported receiver, or network endpoint.
- `execute(preparedId, approvalToken): ScreenActionResult` consumes the token before validation and rechecks consent revision, 30-second expiry, foreground package/window, installed version, snapshot, unique current node, password/security guards, enabled/visible state, and capability. Failed validation requires a new inspect/prepare/approval cycle. The boolean means Android accepted the request, not that the intended app workflow completed.
- `revokePending()` clears any in-memory preparation and approval.

Inspection, prepare, approve, execute, and revoke calls must run on the main thread. Package settings and read-only state can be called separately. Service disconnection or any delivered window/content event invalidates pending actions. No callback executes actions automatically.

## User-started inspector overlay

The concrete confirmation path is now a nonfocusable `TYPE_ACCESSIBILITY_OVERLAY`, opened by the user's tap on a persistent notification action. It displays only app name/package/version, resource IDs, class names, and Click capability. It provides refresh/close controls. There is no SetText input UI, automatic input, generic planner, or background action loop.

1. The Activity calls `postInspectorNotification(): Boolean` only when the user enables the inspector entry point. A false return means notification permission or app notifications are unavailable; root UI should report that state. `cancelInspectorNotification()` removes that entry point.
2. The user puts an allowed target app in the foreground and taps the notification's **현재 앱 화면 검사** action. Private `ScreenInspectorReceiver` calls `controller.showInspector()` without launching an Activity. The initial 350ms delayed operation inspects metadata after the notification shade closes; it never prepares or executes an action. If the shade remains open, the inspector asks for manual refresh.
3. Each supported ID has **이 ID 클릭 준비**. This calls the existing strict `prepare()` using the captured package/version/ID/snapshot. A changed snapshot fails and requires refresh.
4. The overlay replaces the list with the exact package, installed version, resource ID, CLICK action, consequence warning, cancel, and an explicit **이 ID 한 번 클릭 승인** button. Only that button generates the one-use token and immediately calls `execute()`.
5. The result means Android accepted/rejected the action request. The user verifies the target app's result. Another action requires a fresh inspected snapshot and explicit approval.

The overlay has fixed bounds, `FLAG_NOT_FOCUSABLE`, and `FLAG_NOT_TOUCH_MODAL`, preserving the target app's input focus. The active-window exception applies only while **our own** Accessibility overlay is touch-active and exactly one application window retains input focus. It never substitutes a target app for a foreign/system overlay or another focused app. The ordinary package, version, snapshot, enabled-node, sensitive-control, and token checks remain enforced. Own inspector metadata events are ignored; foreign/target window events still invalidate prepared actions. Closing, interruption, or service destruction removes the overlay and pending approval.

The public Android37 `AccessibilityWindowInfo`/`AccessibilityNodeInfo` APIs were inspected in the installed SDK with `javap`; they expose no user handle or user UID. The implementation therefore uses Android's granted Accessibility window scope rather than inventing profile provenance, rejecting every phone with Secure Folder, or using hidden/cross-profile APIs.

A full-screen Activity is still unsuitable for target-screen confirmation because it takes foreground focus. Root UI should use the implemented notification/overlay path rather than weakening the target checks. The adapter does not read recipient/message text, and a resource ID does not identify the current conversation. The user must visually verify context and approve each irreversible action separately.

## Root manifest requirements

The XML is `app/src/main/res/xml/screen_bridge_service.xml`, with `isAccessibilityTool=false`, content retrieval for schema metadata, fixed package filtering, and no gesture capability.

```xml
<service
    android:name=".integrations.ScreenBridgeService"
    android:label="Local Agent optional app screen connection"
    android:exported="true"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data android:name="android.accessibilityservice" android:resource="@xml/screen_bridge_service" />
</service>
<receiver android:name=".integrations.ScreenInspectorReceiver" android:exported="false" />
```

Add these specific package visibility entries inside the existing `<queries>` element so installed version lookups are permitted; no `QUERY_ALL_PACKAGES` permission is needed:

```xml
<package android:name="com.tionsoft.meettalk.eun" />
<package android:name="com.meditong.gw" />
<package android:name="com.kakao.talk" />
<package android:name="org.telegram.messenger" />
<package android:name="org.telegram.messenger.web" />
<package android:name="com.android.chrome" />
```

Use Android's normal service consent and prominent in-app explanation. `isAccessibilityTool=false` accurately describes this optional app-control feature; it does not imply distribution-policy approval.

## Downloads and action limits

There are no HTTP clients, app storage bypasses, root/shell commands, gestures, global actions, permission grants, cross-profile APIs, or background action execution in this adapter. A download can occur only through an allowed app's ordinary visible control after a fresh user-approved Click. The bridge does not itself fetch, export, inspect, or silently install a file.

## Validation boundary

Source, receiver, XML, and overlay were written and reviewed; no build or device workflow validation was run by this worker. Before enabling direct actions in the product, verify the actual installed app version and IDs, window guards and devices with Secure Folder, duplicates/truncation, password/security rejection, token replay/expiry, opt-out during approval, version change, foreground change, visible confirmation, and the action's real outcome. Existing content/privacy guarantees must remain intact.

Official references: [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService), [AccessibilityNodeInfo](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo).


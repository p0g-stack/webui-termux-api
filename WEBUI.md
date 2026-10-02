# WebUI Termux:API (`com.webui.termux.api`)

A repackaged fork of [termux/termux-api](https://github.com/termux/termux-api) (GPLv3, by the Termux
developers). All copyright headers, the `LICENSE` file and credit to Termux are kept. The API contract is
unchanged: `TermuxApiReceiver`, the `api_method` extra, the `socket_input` / `socket_output` extras and
every remaining `apis/*API.java` behave exactly like upstream. The SMS, contacts, call log and telephony
methods are removed (see "Removed methods").

Goal: an APK that installs **alongside** any real Termux / Termux:API install, is placed by root modules as a
system app (`/system/product/app/WebuiTermuxApi/`) and is called by a **root** process:

```sh
am broadcast --user 0 -n com.webui.termux.api/com.termux.api.TermuxApiReceiver \
  --es socket_input <abstract-name-in> --es socket_output <abstract-name-out> \
  --es api_method BatteryStatus
```

- Application id: `com.webui.termux.api`
- Java package / namespace: `com.termux.api` (unchanged, so class names are unchanged)
- Receiver component: `com.webui.termux.api/com.termux.api.TermuxApiReceiver`
- App label: `WebUI Termux:API`

The receiver stays `android:exported="false"` as upstream. Root (uid 0) and system (uid 1000) callers
bypass the exported check in ActivityManager, so `am broadcast -n ...` from root still reaches it, while
ordinary apps cannot.

## Changes vs upstream and why (per `com.termux` hit)

| Where | Upstream | WebUI fork | Why |
|---|---|---|---|
| `app/build.gradle` `applicationId` | `com.termux.api` | `com.webui.termux.api` | Unique package, installs next to real Termux:API. |
| `app/build.gradle` `namespace`, all Java packages | `com.termux.api` | unchanged | Class names stay; keeps the code/merge diff with upstream small. |
| `AndroidManifest.xml` `android:sharedUserId` | `com.termux` | **removed** | Must not share a uid with Termux (signature would not match anyway); own uid, Termux not required. |
| `<permission>` | `com.termux.sharedfiles.READ_WRITE` | `com.webui.termux.api.sharedfiles.READ_WRITE` (`${applicationId}`) | Declared permission names are global; a duplicate with a different signer blocks install. |
| `ShareAPI$ContentProvider` authority / `TermuxAPIConstants.TERMUX_API_FILE_SHARE_URI_AUTHORITY` | `com.termux.sharedfiles` | `com.webui.termux.api.sharedfiles` | Provider authorities are global; a duplicate blocks install. |
| `ReportActivity` / `ReportActivityBroadcastReceiver` names (`${TERMUX_PACKAGE_NAME}.shared.activities...`) | placeholder | literal `com.termux.shared.activities.ReportActivity...` | These are termux-shared class names, not identities; written literally so the placeholder is no longer needed. |
| `manifestPlaceholders.TERMUX_PACKAGE_NAME` | `com.termux` | kept but unused by the manifest | Nothing in the manifest references it anymore. |
| `manifestPlaceholders.TERMUX_API_APP_NAME`, `strings.xml` `TERMUX_API_APP_NAME` entity | `Termux:API` | `WebUI Termux:API` | App label, distinguishes it from the real app. |
| `strings.xml` `TERMUX_PACKAGE_NAME` / `TERMUX_PREFIX_DIR_PATH` entities | `com.termux` | unchanged | Only used in help text. |
| `SocketListener.LISTEN_ADDRESS` | `com.termux.api://listen` (abstract socket) | `com.webui.termux.api://listen` | Abstract unix socket names are global; real Termux:API would otherwise fail to bind (or we would). Its uid check only accepts this app's own uid, so it is effectively unused by the root caller. |
| `NotificationAPI` reply intent `setClassName(...)` | `com.termux.api` | `com.webui.termux.api` | Must target our own receiver, not the real Termux:API. |
| `TermuxAPIMainActivity` launcher-icon toggle | `com.termux.api` | `com.webui.termux.api` | Must toggle our own launcher alias. |
| `UsbAPI.ACTION_USB_PERMISSION` | `com.termux.api.USB_PERMISSION` | `com.webui.termux.api.USB_PERMISSION` | Unique broadcast action (intent is already explicit via `setPackage`). |
| termux-shared `TermuxAPIAppSharedPreferences` | `createPackageContext("com.termux.api")` | replaced by `com.termux.api.util.WebuiAPIAppSharedPreferences` using our own context | Upstream would read the *real* Termux:API's prefs (fails or is a foreign data dir). Pref file name/keys unchanged (they live in our own data dir). |
| `ResultReturner.isPathInTermuxAppDataDirectory` (filesystem socket paths) | requires Termux installed; path must be under Termux data dir | allows this app's data dir, plus Termux's data dirs only if Termux is installed; never requires Termux | Caller is root, Termux may be absent. **Prefer abstract socket names** (no leading `/`), which need no filesystem path. |
| `TermuxAPIConstants.TERMUX_API_RECEIVER_NAME` | `com.termux.api.TermuxApiReceiver` | unchanged | It is a class name. |
| Intent extra keys (`com.termux.api.permission_extra`, `.storage.file`, `.jobscheduler_script_path`) | | unchanged | Internal intent extra keys, not global identities. |
| Notification channel ids, shared prefs file names | | unchanged | Per-app, cannot collide. |
| `JobSchedulerAPI` / `NotificationAPI` actions that run scripts via `com.termux/.app.TermuxService` | | unchanged | **Not supported in this fork**: they depend on the Termux app and on sharing its uid (TermuxService is not exported). They fail harmlessly if Termux is absent. |
| `DialogAPI.isCurrentAppTermux()` | checks foreground is `com.termux` | unchanged | Only affects keyboard behaviour of dialogs. |
| About screen / `TermuxConstants` URLs and names | | unchanged | Cosmetic, credits Termux. |
| `.github/workflows/github_release_build.yml` | builds on release publish, deletes release+tag on error | removed | Would conflict with the WebUI release workflow. |
| `.github/workflows/webui_release.yml` | | new | Builds and publishes the signed release APK + `.sha256` on tag `webui-v*`. |

## Removed methods (webui.2)

`CallLog`, `ContactList`, `SmsInbox`, `SmsSend`, `TelephonyCall`, `TelephonyCellInfo` and
`TelephonyDeviceInfo` (their `apis/*API.java` deleted) and the permissions only they used:
`READ_CALL_LOG`, `READ_CONTACTS`, `READ_SMS`, `SEND_SMS`, `READ_PHONE_STATE`, `CALL_PHONE`,
`READ_PRIVILEGED_PHONE_STATE`. No WebUI plugin uses them, and the app is shared by every module, so
Android Settings should show only permissions the app can actually be asked for. A removed method is
only logged by the receiver (as any unknown method), so the caller times out waiting for the app.

## Changes in webui.8

- `RootHelperService` never outlives the root helper, even one that is killed
  (KernelSU's manager closed, or swiped from Recents) and so never runs
  `am stopservice`. On start it listens on the abstract socket `<pkg>/hold`;
  the root helper connects right after starting it and keeps the connection
  open for its life, sending nothing. The service stops itself when the last
  holder's connection ends (EOF or error; the kernel closes a dead process's
  fds), or when no holder is connected 10 s after a start. Only uid 0 and the
  app itself may hold it. `am stopservice` still stops it at once. Start it
  with `am start-foreground-service` (Android 8+ refuses a plain
  `am startservice` for a background app).

## Changes in webui.7

- New foreground service `com.termux.api.RootHelperService` that keeps the app
  from being frozen (Android 14+ freezes cached apps; upstream's
  `KeepAliveService` is a plain background service and does not prevent it).
  The module's root helper starts it when it winds up and stops it at its
  idle shutdown:
  `am start-foreground-service --user 0 -n <pkg>/com.termux.api.RootHelperService [--ez wakelock true]`,
  `am stopservice --user 0 -n <pkg>/com.termux.api.RootHelperService`.
  Silent minimum-importance notification "<app label> is running"; without
  the notification permission it runs unshown. `wakelock` holds a partial
  wake lock until stopped (a second start only changes it). Type
  `specialUse`; adds `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`,
  `POST_NOTIFICATIONS`, `WAKE_LOCK`.

## Changes in webui.6

- `Clipboard` reads work again on Android 10+, where only the focused app may
  read the clipboard: a read starts `ClipboardReadActivity`, an invisible
  activity that reads once it has window focus, answers and finishes. Android
  12+ shows its usual "pasted from your clipboard" notice with the app's
  label. Writes stay as upstream (no focus needed). Used by `clipboard_webui`.

## Changes in webui.5

- New method `DocumentOpen`: `--es dir <folder>` `[--esa mime <types>]`
  `[--ez multiple true]` opens Android's document picker and copies what the
  user picks into the folder, answering only once the copies are complete
  with a JSON array of `{name, mime, size, path}` (empty when cancelled).
  Unlike `StorageGet`, which answers at once and keeps no name, the caller
  can use the files immediately. Used by `file_selector_webui`.

## Changes in webui.4

- New method `Permission`: `--esa permissions <names>` (full names, such as
  `android.permission.CAMERA`) answers one JSON object mapping each to
  `granted` or `denied`; with `--ez request true` it shows Android's own
  dialog for the ones not yet granted and answers after it, `denied` becoming
  `permanentlyDenied` when Android will not ask again. Only Android's answer
  is passed on; nothing is stored. Used by `permission_handler_webui`.

## Changes in webui.3

- `JobScheduler` removed: it runs scripts through the Termux app, which this fork does not need or have.
- An unknown `api_method` now gets an answer (`Unknown api_method: <name>`) instead of only a log line, so
  a caller built for a newer app fails at once.
- Package-derived names come from `getPackageName()` at startup (next section).

## Renaming per module

Each module ships its own copy of this APK under its own package, so Android's permission dialog names the
module and grants stay per module. The release APK is the base; a build tool (flutter_p0g) renames and
re-signs it:

- **Package:** `com.webui.api.<seg>`, where `<seg>` is the module id with every character outside
  `[A-Za-z0-9_]` replaced by `_`, prefixed with `m` if it starts with a digit (`demo` ->
  `com.webui.api.demo`, `my-mod.x` -> `com.webui.api.my_mod_x`). Placed at
  `system/product/app/WebuiApi_<seg>/WebuiApi_<seg>.apk`.
- **Binary manifest:** replace every string in the string pool that is exactly `com.webui.termux.api` or
  starts with `com.webui.termux.api.` by the same string with the new package as prefix. That covers the
  `package` attribute, the share provider authority (`<package>.sharedfiles`) and its signature permission
  (`<package>.sharedfiles.READ_WRITE`). Component class names are `com.termux.api.*` and are not touched.
- **Label:** set the `<application>` `android:label` to the module's name as a literal string.
- **Sign** with the module developer's key (v2), as stock Flutter signs (`key.properties`, else the
  debug keystore). versionCode is unchanged.
- **Code:** everything else that must be unique per package (the listen socket `<package>://listen`, the
  share authority in `ShareAPI`, notification reply intents, the launcher alias toggle) comes from
  `getPackageName()` at startup (`TermuxAPIConstants.WEBUI_PACKAGE_NAME`), so the code needs no rewrite.

## Signing

`app/webui-testkey.jks` is a **TEST-ONLY, publicly committed** keystore generated for this fork
(alias `webui-test`, store/key password `webui-test-only`, RSA 2048, self-signed,
`CN=WebUI Termux API TEST-ONLY key`). Certificate SHA-256:
`E8:A8:D8:CB:90:E3:CC:1E:03:CD:26:E9:8B:05:AB:B4:F2:9F:1B:9A:C8:50:24:47:D4:BD:62:7D:F1:2B:CB:84`.

Anyone can sign an APK with it; it provides no authenticity. Integrity comes from pinning the release
asset by URL + sha256. It is deliberately different from upstream's `testkey_untrusted.jks` so this app does
not share a signer with Termux debug builds. Both the `release` and `debug` build types use it.

## Version

`versionName` `0.53.0-webui.N` (semver-valid, accepted by `validateVersionName`), `versionCode` `1003`+.
Bump both for each release.

## Releasing and pinning

1. Bump `versionName` (and `versionCode`) in `app/build.gradle`, commit to `master`.
2. Either push a tag `webui-v<versionName>` (e.g. `git tag webui-v0.53.0-webui.1 && git push origin webui-v0.53.0-webui.1`),
   or run the **WebUI Release** workflow manually (Actions tab / `gh workflow run webui_release.yml --ref master`);
   the manual run builds `master` and creates the tag `webui-v<versionName from app/build.gradle>` at that commit.
3. `.github/workflows/webui_release.yml` runs `./gradlew assembleRelease`, and creates the GitHub release for
   the tag with two assets:
   - `webui-termux-api_v<versionName>.apk`
   - `webui-termux-api_v<versionName>.apk.sha256` (`sha256sum` format: `<hex>  <file>`)
4. Consumers pin:
   `https://github.com/p0g-stack/webui-termux-api/releases/download/webui-v<versionName>/webui-termux-api_v<versionName>.apk`
   plus the sha256 from the `.sha256` asset (also printed in the release notes), and verify after download.

Releases are never re-tagged; a fix gets a new `-webui.N` suffix.

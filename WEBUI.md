# WebUI Termux:API (`com.webui.termux.api`)

A repackaged fork of [termux/termux-api](https://github.com/termux/termux-api) (GPLv3, by the Termux
developers). All copyright headers, the `LICENSE` file and credit to Termux are kept. The API contract is
unchanged: `TermuxApiReceiver`, the `api_method` extra, the `socket_input` / `socket_output` extras and
every `apis/*API.java` behave exactly like upstream.

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
2. Push a tag `webui-v<versionName>`, e.g. `git tag webui-v0.53.0-webui.1 && git push origin webui-v0.53.0-webui.1`.
3. `.github/workflows/webui_release.yml` runs `./gradlew assembleRelease`, and creates the GitHub release for
   the tag with two assets:
   - `webui-termux-api_v<versionName>.apk`
   - `webui-termux-api_v<versionName>.apk.sha256` (`sha256sum` format: `<hex>  <file>`)
4. Consumers pin:
   `https://github.com/p0g-stack/webui-termux-api/releases/download/webui-v<versionName>/webui-termux-api_v<versionName>.apk`
   plus the sha256 from the `.sha256` asset (also printed in the release notes), and verify after download.

Releases are never re-tagged; a fix gets a new `-webui.N` suffix.

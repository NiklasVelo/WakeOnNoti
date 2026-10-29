# CLAUDE.md - WakeOnNoti

Android app (Kotlin, Jetpack Compose, no other libraries): watches notifications,
fires rules (alarm / webhook / AutoAI). Public repo `NiklasVelo/WakeOnNoti`.
Owner: Niklas. Sibling projects in `D:\Dev`: UmAi (whose hub/update flow this
updater copies), AutoAI (receives forwarded notifications via its relay).

## Release workflow - the repo must always carry the newest APK

After any change that should reach the phone:

```powershell
git commit ...                                     # publish.ps1 refuses a dirty tree
powershell -File scripts\publish.ps1 -Bump patch -Notes "what changed"
```

It bumps `VERSION`, builds + verifies the signed APK, commits
`release/WakeOnNoti.apk`, creates GitHub release `vX.Y.Z`, then writes and pushes
`release/version.json` LAST - that file is the update channel installed apps poll
(`BuildConfig.UPDATE_MANIFEST_URL`, raw.githubusercontent.com, ~5 min CDN cache).

Why GitHub and not UmAi's hub (hub.umaai.eu): the hub only answers clients holding
the UmAi friend token, and anything baked into this public APK is public; the hub is
also hard-wired to UmAi's file names and android channel. The client parses the same
manifest shape (version/file/sha256/size/notes, plus `url`), so moving to a public
hub channel later is a one-line change of UPDATE_MANIFEST_URL.

**Signing key**: `%APPDATA%\Secrets\WakeOnNoti\.env` + `wakeonnoti-release.jks`
(never in the repo). Losing it means no installed copy can ever update in place
again - users would have to uninstall and lose their rules. Never publish a
debug-signed build; the script checks the certificate (`CN=WakeOnNoti`).

## Play Store release (planned, decided 2026-10-29)

Goal: an install without the Play Protect block (see Hard-won details) and the easiest
install for everyone; the GitHub APK stays. What it will take - verify each against
the policy current at the time, these are from memory:

- Play Console developer account (one-time fee, identity verification). New personal
  accounts must run a closed test with a minimum number of testers for ~14 days first.
- A `play` product flavor **without the self-updater and REQUEST_INSTALL_PACKAGES**:
  Play forbids apps updating themselves outside Play. The `github` flavor keeps it.
- **Play App Signing with the existing release key** (upload it; do not let Google
  generate one). Then GitHub and Play builds are the same signer and a GitHub install
  can switch to Play in place, keeping its rules. A Google-generated key would force
  uninstall + export/import.
- Console declarations: foreground service types (mediaPlayback, specialUse - may
  need replacing), USE_FULL_SCREEN_INTENT (allowed for alarm apps, must be declared),
  the battery-optimization request intent, and whether notification-listener use needs
  a declaration.
- Privacy policy URL and the Data safety form: notification content is processed on
  the device; it leaves only through rules the user configures (webhook, AutoAI).
- targetSdk must meet Play's current minimum at submission time.

## Layout (`app/src/main/java/io/github/niklasvelo/wakeonnoti/`)

- `data/` - `Model.kt` (Rule, RuleAction, Settings, JSON via org.json),
  `Store.kt` (filesDir/config.json, AtomicFile, single writer thread),
  `EventLog.kt` (filesDir/log.json, newest first, 200 entries).
- `engine/` - `NotiEvent` (flattens a notification to title/text),
  `Matcher`, `RuleEngine` (dedupe, cooldown, dispatch, AutoAI forwarding).
- `system/` - `NotiListener` (the NotificationListenerService), `Health`
  (permission checks + fix intents + rebind), `Watchdog` (15-min job, boot
  receiver, optional keep-alive FGS), `Notifications` (channels).
- `alarm/` - `AlarmService` (mediaPlayback FGS, alarm stream, vibration,
  auto-stop), `AlarmActivity` (full-screen over lock screen).
- `actions/` - `Http`, `Webhook`, `AutoAiClient`.
- `update/Updater.kt` - check, download, sha256, PackageInstaller session.
- `ui/` - plain screen stack in `MainActivity` (no nav library).

## Testing

Emulator `umai_test` (API 35) boots headless:
`D:\Android\sdk\emulator\emulator -avd umai_test -no-window -no-audio`.
Grant access with `adb shell cmd notification allow_listener <pkg>/io.github.niklasvelo.wakeonnoti.system.NotiListener`,
fake notifications with `adb shell cmd notification post -t Title tag "'text with spaces'"`
(the inner quotes matter: adb's remote shell splits arguments again).
The debug build is `io.github.niklasvelo.wakeonnoti.debug` and debuggable, so a test
config can be pushed with `run-as <pkg> sh -c 'cat > files/config.json'`.

## Hard-won details

- **The listener can start the alarm FGS from the background even with battery
  optimization on** (Android 15 logged `PROC_STATE_BFGS`, `BFSL`): the system
  binds a notification listener like a foreground service. The fallback
  notification (`CH_FALLBACK`, FLAG_INSISTENT + full-screen intent) is for the
  devices where that does not hold.
- **Dedupe must not use the notification's `when`.** `cmd notification post`
  (and many apps) refresh it on every in-place update, so an identical re-post
  rang again. Identity is key + title + text (+ newest message time for chats);
  `onNotificationRemoved` forgets the key so a re-alert after dismissal fires.
- **Play Protect hard-blocks browser/file-manager installs of this app** ("kann Zugriff
  auf sensible Daten anfordern ... Identitätsdiebstahl und Finanzbetrug", 2026-10-29).
  That is *enhanced fraud protection*: permission-based (notification listener, SMS,
  accessibility) + install-source-based, NOT signing-key reputation - UmAi passes only
  because it declares none of those. Signing with UmAi's key would not help. Ways in:
  `adb install`, or Play Protect scanning off for the install. README "Installing".
- **Group summaries are skipped** - they repeat their children.
- **Compose text needs a Surface.** AlarmActivity drew a bare Column with a
  background, and all text came out black on black (LocalContentColor default).
- **No BOM in JSON or .env.** Windows PowerShell 5.1 `Set-Content -Encoding utf8`
  writes one; org.json then rejects version.json. publish.ps1 writes with
  `UTF8Encoding($false)`.
- **local.properties needs escaped backslashes** (`sdk.dir=D\:\\Android\\sdk`);
  an unescaped path fails with a German "Syntax für den Dateinamen" IOException.

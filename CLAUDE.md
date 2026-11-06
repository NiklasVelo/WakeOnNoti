# CLAUDE.md - WakeOnNoti

Android app (Kotlin, Jetpack Compose, no other libraries): watches notifications,
fires rules (alarm / webhook). Public repo `NiklasVelo/WakeOnNoti`.
Owner: Niklas. Sibling projects in `D:\Dev`: UmAi (whose hub/update flow this
updater copies), AutoAI (gets notifications through a rule's webhook to its server's
`/api/notify`; it has its own phone app now, see "AutoAI left in 0.3.0" below).

**Naming AutoAI here:** call it "AutoAI" and describe it as an AI advisor app that is
still in closed beta (no open access yet). Never use the name of the owner's own agent
instance: AutoAI lets every user name their agent, so the universal name is AutoAI.

## Release workflow - the repo must always carry the newest APK

After any change that should reach the phone:

```powershell
git commit ...                                     # publish.ps1 refuses a dirty tree
powershell -File scripts\publish.ps1 -Bump patch -Notes "what changed"
```

It bumps `VERSION`, builds + verifies the signed APK, commits
`release/WakeOnNoti.apk`, creates GitHub release `vX.Y.Z`, then writes and pushes
`release/version.json` LAST - that file is the update channel installed apps poll
(`BuildConfig.UPDATE_MANIFEST_URL`, raw.githubusercontent.com, ~5 min CDN cache that
ignores query strings - a fresh release can take that long to be offered).

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
  the device; it leaves only through rules the user configures (webhook). No server of
  our own and no account are involved any more.
- targetSdk must meet Play's current minimum at submission time.

## Layout (`app/src/main/java/io/github/niklasvelo/wakeonnoti/`)

- `data/` - `Model.kt` (Rule, RuleAction, Settings, JSON via org.json),
  `Store.kt` (filesDir/config.json, AtomicFile, single writer thread),
  `AutoAiLeftovers.kt` (what 0.2.9 and older left behind, no Android in it, unit-tested),
  `EventLog.kt` (filesDir/log.json, newest first, 200 entries).
- `engine/` - `NotiEvent` (flattens a notification to title/text),
  `Matcher`, `RuleEngine` (dedupe, cooldown, dispatch).
- `system/` - `NotiListener` (the NotificationListenerService; `isLive()` is the real
  binding check), `ListenerDoctor` (self-check every 2 min + on resume/boot/job, repair
  ladder, outage notification, the status the UI shows), `Health` (permission checks,
  fix intents, `rebind()`/`nudge()`), `RebindNudge` (dummy component the nudge flips),
  `Watchdog` (15-min job, boot receiver, optional keep-alive FGS), `Notifications`,
  `AutoAiCleanup` (removes what 0.2.9 and older left on the phone, once).
- `alarm/` - `AlarmService` (mediaPlayback FGS, alarm stream, vibration,
  auto-stop), `AlarmActivity` (full-screen over lock screen).
- `actions/` - `Http`, `Webhook`.
- `update/Updater.kt` - check, download, sha256, PackageInstaller session.
- `ui/` - plain screen stack in `MainActivity` (no nav library).

## Testing

Unit tests: `gradlew testDebugUnitTest` (`app/src/test`, plain JVM). JUnit 4 and org.json
are test-only dependencies; org.json is needed because the JVM sees only Android's stubs of it.
Logic worth a test goes into a class without Android in it, like `AutoAiLeftovers`.

Emulator `umai_test` (API 35) boots headless:
`D:\Android\sdk\emulator\emulator -avd umai_test -no-window -no-audio`.
Grant access with `adb shell cmd notification allow_listener <pkg>/io.github.niklasvelo.wakeonnoti.system.NotiListener`,
fake notifications with `adb shell cmd notification post -t Title tag "'text with spaces'"`
(the inner quotes matter: adb's remote shell splits arguments again). After `adb root` it posts
nothing (`NameNotFoundException: root` in logcat): `adb unroot` first.
The debug build is `io.github.niklasvelo.wakeonnoti.debug` and debuggable, so a test
config can be pushed with `run-as <pkg> sh -c 'cat > files/config.json'`.
Debug builds also carry `DebugHooks` (src/debug, never in release): `adb shell am broadcast
-a drop_listener -n <pkg>/io.github.niklasvelo.wakeonnoti.system.DebugHooks` unbinds the
listener, `-a nudge` runs the nudge alone. Watch `dumpsys notification` ("Live notification
listeners", "Snoozed notification listeners") and logcat `ListenerDoctor` /
`NotificationManagerService$NotificationListeners`.

`cmd notification post` has no flag for ongoing, and the listener ignores this app's own
notifications, so an ongoing one needs another app: a few lines calling `setOngoing(true)`, or
`startForeground` for a service. An AVD runs only once: to test beside a session that holds
`umai_test`, create a second AVD.

**The webhook to AutoAI's server without the real one:** run AutoAI's `server/autoai_server.py`
locally (`scripts/phone_e2e/server.ps1` there sets test tokens and a temp folder), and give a rule
a webhook to `http://10.0.2.2:18485/api/notify` with the header `X-AutoAI-Token: e2e-notify-token`
(the emulator reaches the PC's 127.0.0.1 as `10.0.2.2`). The server answers 503 until the target
device has polled once. Nothing touches the live instance, its memory or the real server.

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
- **github.com has no IPv6 address** (nor do its release-asset hosts); raw.githubusercontent.com
  does. On the owner's phone (IPv6-only mobile data) the update check worked and the
  download failed with "Unable to resolve host github.com". Downloads therefore come from a
  commit-pinned raw URL first, with the release asset as fallback (`urls` in version.json).
  ai.umaai.eu has no AAAA record either - a webhook to it can fail on that network the
  same way until the VPS gets one.
- **Alarm volume = stream at max + player gain.** STREAM_ALARM has only ~7 steps, so a
  rule's own volume raises the stream to max (`StreamOverride`, restored on stop) and
  sets the precise level with `MediaPlayer.setVolume` (`AlarmSound.gain`: 0.3 dB per %,
  1 % ≈ -30 dB). Verified in `dumpsys media.audio_flinger`: 40 % plays at -18 dB.
  A real alarm stops any editor preview first, or the two restores would fight.
- **A lost listener is not repaired by `requestRebind()`** (owner's Galaxy A17, 2026-11-03:
  no notifications, Repair did nothing, the rest of the app still worked). Read in AOSP
  `ManagedServices`: `setComponentState` returns early unless the component is snoozed,
  so requestRebind only undoes our own requestUnbind; after "binding died" the system
  retries once (10 s) and then stops (`mServicesRebinding`); `registerServiceLocked` skips
  anything it still believes bound. What works: static `requestUnbind(cn)` +
  `requestRebind(cn)` (API 34+) - snooze, cut, bind fresh - and, for "no binding at all",
  any PACKAGE_CHANGED of our package (`onPackagesChanged` -> `rebindServices`).
  **Never toggle the listener component itself**: `trimApprovedListsForInvalidServices`
  checks `getServiceInfo` without MATCH_DISABLED_COMPONENTS, so a change processed while
  it is disabled deletes notification access. Hence `RebindNudge`. Verified on the
  emulator; the One UI state itself is not reproducible there (stock Android restarted
  every crash and force-stop by itself).
- **`hasAccess()` must ask NotificationManager** (`isNotificationListenerAccessGranted`),
  not Settings.Secure: the setting lags, and right after access was granted the doctor saw
  a connected listener with "no access" and posted a false outage notification.
- **"Pause app activity if unused" never applies while notification access is on.**
  `isAutoRevokeWhitelisted()` (and `PackageManagerCompat.getUnusedAppRestrictionsStatus`,
  which is that call on 11+) only sees the user's or installer's exemption; PermissionController's
  `HibernationPolicy` also exempts any app with an *enabled* notification listener
  (`ExemptServicesLiveData`), and Settings then greys the switch out (One UI hides it). The old
  check stayed red on the owner's A17 with nothing to switch (2026-11-03). Verified on the
  emulator: access on -> switch disabled+off; access off -> switch enabled+on.
- **Group summaries are skipped** - they repeat their children.
- **Compose text needs a Surface.** AlarmActivity drew a bare Column with a
  background, and all text came out black on black (LocalContentColor default).
- **No BOM in JSON or .env.** Windows PowerShell 5.1 `Set-Content -Encoding utf8`
  writes one; org.json then rejects version.json. publish.ps1 writes with
  `UTF8Encoding($false)`.
- **Android backup carries only config.json** (`res/xml/data_extraction_rules.xml` for 12+,
  `backup_rules.xml` for 6-11, both as one `<include>`; `BackupRulesTest` keeps them so).
  shared_prefs/state.xml is this phone's own: `listenerHadAccess`, restored onto a new phone
  without access, would make the doctor report a lost access the phone never had. The log stays
  too. Android filters by the *installed* app's rules on restore as well, so even a backup made
  by an older version restores only config.json. Verified on the emulator (2026-11-03, `bmgr`
  with LocalTransport, `adb root` to read
  `/data/user/0/com.android.localtransport/files/1/_full/<pkg>`; device transfer with
  `settings put secure backup_local_transport_parameters is_device_transfer=true`). Copies outside
  Android's backup (root backup apps, maybe OEM transfer tools) are not covered.
- **AutoAI left in 0.3.0, and what 0.2.9 and older left behind goes at the first start.**
  Forwarding, AutoAI's inbox and its board moved to AutoAI's own phone app, in a private repo; their
  hard-won details are in its CLAUDE.md and in AutoAI's docs. Why the token had a vault
  (`TokenVault`, Keystore) is in this file as of 0.2.9, in the history. What reaches AutoAI now is an
  ordinary rule with a webhook to `/api/notify`. Its notify token sits in the rule's header and
  opens nothing else, so it is no leftover; `AutoAiLeftovers` matches the action type `autoai`
  exactly, never anything that merely reads "AutoAI".
  - config.json: `Store.load` saves at once what it read, without the fields. They would otherwise
    stay in the file until the next save. No marker: the file itself says, so a start after a
    failed write repeats it, and an old backup restored later is cleaned as well. It only ever
    saves after a read that worked: saving an empty config over rules that failed to load would
    be the one way this could lose them.
  - Everything else: `AutoAiCleanup`, until all of it worked (marker `leftoversRemoved` in the
    prefs): caches, the vault in `no_backup/` and the Keystore key `autoai_token`, the prefs, the
    channel. Deleting the channel also takes any inbox notification off the screen. It must never
    keep the app from starting, so no step throws.
  - `config.broken.json` can hold a plain token, so the token is cut out of it as text; the rules
    in it stay. A config.json that does not parse keeps its bytes until the next save, as it always
    did. Only a plain token in such a file stays until then.
  - A rule that loses its only action stays, with no action, and the log says which (kind ERROR,
    so it shows as a problem). Importing an old file logs the same.
  - Out of reach: exports the user keeps, and earlier Google backups until Android replaces them.
    Only changing the token on the server invalidates those.
  Verified on the emulator (2026-11-06, API 35, a real 0.2.9 with vault, inbox, board and a
  showing inbox notification, updated in place without opening the app; AutoAI's real server 0.2.1;
  then the signed 0.2.9 from `release/` to a signed 0.3.0, so through R8 too).
  Read the Keystore as root: `sqlite3 /data/misc/keystore/persistent.sqlite "select alias from
  keyentry"`. A deleted channel stays in `dumpsys notification` with `mDeleted=true`.
- **"Ongoing" and "shown by a foreground service" are two things.** On API 35 a foreground
  service that does not call `setOngoing(true)` has `NO_CLEAR|FOREGROUND_SERVICE` and no
  `ONGOING_EVENT`, so `sbn.isOngoing` is false and it would fire on every track change. That is
  what the rule option "Skip ongoing notifications" is for, so `Matcher` checks both
  (`NotiEvent.ongoing`, `foregroundService`).
  Verified on the emulator (2026-11-05, a catch-all webhook rule, a small test app): with the
  option, ongoing posts, their in-place updates and both kinds of service were received and did
  not fire, also after a restart; a normal post did, and so did an ongoing one that turned normal
  with new text. Without the option all of them fired.
- **Android can hide a notification's text from a listener.** On API 35 the listener got
  "Sensitive notification content hidden" for some test texts with a digit (`B1-normal`;
  `mSensitiveContent=true` in `dumpsys notification --noredact`), never for one without. Such
  notifications all read alike: keywords cannot match them, and the dedupe takes a second one
  within 30 s for a repeat. Keep digits out of test texts.
- **local.properties needs escaped backslashes** (`sdk.dir=D\:\\Android\\sdk`);
  an unescaped path fails with a German "Syntax für den Dateinamen" IOException.

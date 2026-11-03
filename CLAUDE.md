# CLAUDE.md - WakeOnNoti

Android app (Kotlin, Jetpack Compose, no other libraries): watches notifications,
fires rules (alarm / webhook / AutoAI). Public repo `NiklasVelo/WakeOnNoti`.
Owner: Niklas. Sibling projects in `D:\Dev`: UmAi (whose hub/update flow this
updater copies), AutoAI (receives forwarded notifications via its relay).

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
  the device; it leaves only through rules the user configures (webhook, AutoAI).
- targetSdk must meet Play's current minimum at submission time.

## Layout (`app/src/main/java/io/github/niklasvelo/wakeonnoti/`)

- `data/` - `Model.kt` (Rule, RuleAction, Settings, JSON via org.json),
  `Store.kt` (filesDir/config.json, AtomicFile, single writer thread),
  `EventLog.kt` (filesDir/log.json, newest first, 200 entries).
- `engine/` - `NotiEvent` (flattens a notification to title/text),
  `Matcher`, `RuleEngine` (dedupe, cooldown, dispatch, AutoAI forwarding).
- `system/` - `NotiListener` (the NotificationListenerService; `isLive()` is the real
  binding check), `ListenerDoctor` (self-check every 2 min + on resume/boot/job, repair
  ladder, outage notification, the status the UI shows), `Health` (permission checks,
  fix intents, `rebind()`/`nudge()`), `RebindNudge` (dummy component the nudge flips),
  `Watchdog` (15-min job, boot receiver, optional keep-alive FGS), `Notifications`,
  `InboxActionReceiver` (the buttons of an inbox notification; a typed note arrives through
  RemoteInput, and a note shown in the notification rides in the buttons' extras, so it
  survives a killed process).
- `alarm/` - `AlarmService` (mediaPlayback FGS, alarm stream, vibration,
  auto-stop), `AlarmActivity` (full-screen over lock screen).
- `actions/` - `Http`, `Webhook`, `AutoAiClient` (forwarding),
  `AutoAiInbox` (AutoAI's inbox on the phone: long-polls the relay as device platform
  `wakeonnoti`; message kinds in: `inbox`, `inbox_list`, `inbox_state`; out:
  `inbox_resolve` = {id, action, text}, `inbox_sync`). `text` is the answer to a question,
  or for every other kind an optional note that goes along with approve/reject/done; the
  card has a field for it, the notification types it in place (Android sends no empty
  reply, so plain buttons stay one tap). `inbox_sync` (answered by the whole open list) goes out
  after every start and reconnect plus once more 45 s later, every 15 min, every minute
  while the inbox is on screen, on resume and on Reload. The AutoAI side is in AutoAI's
  `brain/initiative.py` and `devices/relay.py`.
  `InboxDelivery` (what the inbox decides, without Android, unit-tested): the poll's
  `superseded`, the list's `full` / `complete` / `total`, how a list merges into the copy.
  `AutoAiBoard` (AutoAI's board: rides the inbox's poll and sender; out `board_sync`,
  `board_edit`; in `board_list` (in parts, any order), `board_task`, `board_ok`,
  `board_error`; cache filesDir/autoai_board.json). Contract: AutoAI's docs/board-api.md,
  PC side `brain/tickets.py`. UI in `ui/BoardScreen.kt` (list, task, add/edit form).
- `update/Updater.kt` - check, download, sha256, PackageInstaller session.
- `ui/` - plain screen stack in `MainActivity` (no nav library).

## Testing

Unit tests: `gradlew testDebugUnitTest` (`app/src/test`, plain JVM). JUnit 4 and org.json
are test-only dependencies; org.json is needed because the JVM sees only Android's stubs of it.
Logic worth a test goes into a class without Android in it, like `InboxDelivery`.

Emulator `umai_test` (API 35) boots headless:
`D:\Android\sdk\emulator\emulator -avd umai_test -no-window -no-audio`.
Grant access with `adb shell cmd notification allow_listener <pkg>/io.github.niklasvelo.wakeonnoti.system.NotiListener`,
fake notifications with `adb shell cmd notification post -t Title tag "'text with spaces'"`
(the inner quotes matter: adb's remote shell splits arguments again).
The debug build is `io.github.niklasvelo.wakeonnoti.debug` and debuggable, so a test
config can be pushed with `run-as <pkg> sh -c 'cat > files/config.json'`.
Debug builds also carry `DebugHooks` (src/debug, never in release): `adb shell am broadcast
-a drop_listener -n <pkg>/io.github.niklasvelo.wakeonnoti.system.DebugHooks` unbinds the
listener, `-a nudge` runs the nudge alone. Watch `dumpsys notification` ("Live notification
listeners", "Snoozed notification listeners") and logcat `ListenerDoctor` /
`NotificationManagerService$NotificationListeners`.

**AutoAI end to end without the real relay** (board, 2026-11-03): run AutoAI's
`server/autoai_server.py` locally (`AUTOAI_SERVER_DATA=<tmp> AUTOAI_DEVICE_TOKEN=<test>
AUTOAI_SERVER_BIND=127.0.0.1 AUTOAI_SERVER_PORT=18485`), plus a small harness that builds
AutoAI's real `RelayThread`, `Board` and `Initiative` (never `.start()`ed: no agent, no API,
no desktop actions) with `AUTOAI_DATA_DIR`, `AUTOAI_SERVER_URL` and `AUTOAI_DEVICE_TOKEN`
set to the test values before importing autoai, and a fake hull whose `stimulus()` records
what the assistant would be told. The emulator reaches the PC's 127.0.0.1 as `10.0.2.2`:
push a debug config with `autoAiUrl=http://10.0.2.2:18485`, the test token and
`autoAiReceive=true`. Nothing touches the live instance, its memory or the real server.

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
  ai.umaai.eu has no AAAA record either - AutoAI forwarding can fail on that network the
  same way until the VPS gets one.
- **Alarm volume = stream at max + player gain.** STREAM_ALARM has only ~7 steps, so a
  rule's own volume raises the stream to max (`StreamOverride`, restored on stop) and
  sets the precise level with `MediaPlayer.setVolume` (`AlarmSound.gain`: 0.3 dB per %,
  1 % ≈ -30 dB). Verified in `dumpsys media.audio_flinger`: 40 % plays at -18 dB.
  A real alarm stops any editor preview first, or the two restores would fight.
- **A lost listener is not repaired by `requestRebind()`** (owner's Galaxy A17, 2026-11-03:
  no notifications, Repair did nothing, AutoAI inbox still worked). Read in AOSP
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
- **AutoAI's inbox: measured end to end on the emulator (2026-11-01).**
  - *Setup:* the PC end was a test instance under its own device name
    (`autoai-e2e`), never `pc`. The server routes by name, so a test called
    `pc` could take the live PC's messages.
  - *Delivery:* urgent rang the alarm in 2 s, normal posted a notification,
    and low stayed silent.
  - *Settled on the PC:* the alarm stopped after 1 s.
  - *Answered on the phone:* the answer arrived as the user's instruction
    in 1 s.
  - *Deep sleep:* forced with `dumpsys deviceidle force-idle` (battery
    unplugged, screen off), the alarm rang in 2 s and woke the screen. That
    needs the battery-optimization exemption; OEM battery managers may be
    harsher than the emulator.
- **A relay message is delivered at most once - so the inbox re-asks for the whole list.**
  Inbox #28 (2026-11-03) was shown on the PC and never reached the phone: AutoAI's send
  to the server failed (`WinError 10054`, logged as "could not reach the phone inbox") and
  `_to_phones` does not retry. The server has a second hole: `Store.inbox()` marks rows
  delivered when a poll takes them, and it still holds a poll up to 30 s for a connection
  that is gone (app updated or killed, network dropped) - that poll takes what arrives and
  it is lost. Reproduced on the emulator (killed process, dead poll ate the first
  `inbox_list`; the 45 s recheck brought the item). Hence `inbox_sync` on start/reconnect
  + recheck, every 15 min and on screen. An `inbox_list` keeps an item that came by
  itself < 60 s ago (AutoAI sends each message on its own thread, so an older list can
  overtake it - it would have stopped that item's alarm), and announces items never
  announced (a lost one), except on the very first list (backlog: urgent only).
- **AutoAI 0.38.5 / relay server 0.1.2 close part of that, with three optional fields**
  (AutoAI's docs/design.md "Inbox delivery to the phone"; an older side leaves them out
  and everything reads as before). Verified end to end on the emulator (2026-11-03).
  - Its sends now go through one ordered queue and are retried (`PhoneQueue`).
  - `superseded: true` on a poll: a newer poll with the same device id took over, so a
    poll held for a dead connection takes nothing. Seen live, it means a second poller
    with this id; the loop pauses 5, 10, 20, 40, then 60 s, or the two would end each
    other in a tight loop. `allowBackup` is on, so a restore onto another phone carries
    the device id along (not verified).
  - `full: false` on a list item: its body was cut to fit 20000 characters. The copy here
    keeps its own whole text (`InboxDelivery.fuller`). A cut body of an item never seen
    is stored and marked, and the card says it is shortened.
  - `complete: false` (+ `total`): the list left items out. Nothing missing from it is
    settled; its notification and alarm stay. Only a complete list settles, and the 60 s
    overtake guard above still holds, so an item that came < 60 s ago survives even that.
- **The relay device id is created once, under a lock** (`by lazy`). The first
  poll and the first send raced, and the phone registered twice under two ids,
  one of which never polled.
- **local.properties needs escaped backslashes** (`sdk.dir=D\:\\Android\\sdk`);
  an unescaped path fails with a German "Syntax für den Dateinamen" IOException.

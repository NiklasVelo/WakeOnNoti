# WakeOnNoti

Rings an alarm – or calls a webhook, or tells [AutoAI](#autoai) – when a notification
on your Android phone matches rules you set. A small, open-source alternative to
NotiAlarm, built to keep working: the classic failure of these apps is that Android
quietly stops delivering notifications to them, and WakeOnNoti checks for exactly
that and repairs it.

**[⬇ Download the latest APK](https://github.com/NiklasVelo/WakeOnNoti/raw/main/release/WakeOnNoti.apk)**
· [all releases](https://github.com/NiklasVelo/WakeOnNoti/releases)

Android 8.0 or newer. Once installed, the app updates itself: it offers new versions
with an **Install update** button, and your rules and settings are kept.

## What it does

- **Rules**, as many as you like, each with its own outcome:
  - **from** – any app, or only the apps you pick
  - **contains** – any / all of your words, or regular expressions; in the title,
    the text, or anywhere; optionally case-sensitive or whole words only
  - **but not** – words that cancel the match
  - **cooldown** – optional minimum time between two firings
- **Actions** – one rule can do several:
  - **Alarm** – loops a sound on the *alarm* stream (so silent mode does not mute it),
    vibrates, can turn the volume up all the way, shows a full-screen Stop screen over
    the lock screen, and stops by itself after N minutes if you want
  - **Webhook** – GET/POST/PUT to any URL with your own headers and body;
    `{rule} {app} {package} {title} {text} {time}` are filled in
  - **AutoAI** – hands the notification to AutoAI, optionally with an instruction
- **Log** – every incoming notification (kept only on the phone) and every rule that
  fired. Tap a notification to build a rule from it.
- **Backup** – export / import all rules and settings as a JSON file.

For chat apps only the *newest* message is matched, and a notification that is merely
updated in place (same content) does not fire again – so one "wake up" rings once, not on
every later message in the same chat.

## Setup

1. Install the APK. Your browser or file manager asks to allow installing apps – allow it.
2. Open WakeOnNoti. A red card lists what still needs permission; each item has a
   button that takes you to the right setting:
   - **Notification access** – required. If the switch is greyed out (Android 13+ does this
     for apps installed from a file): *Settings → Apps → WakeOnNoti → ⋮ → Allow restricted
     settings*, then try again.
   - **Notification permission** – to show the alarm.
   - **Battery optimization** – set to *Unrestricted / Don't optimize*. This matters most on
     Samsung, Xiaomi, Huawei, OnePlus & co., which otherwise kill background apps.
   - **Full-screen alarms** – so the alarm can show over the lock screen.
3. Tap **New rule** (or *Start from an example*), then **Test this rule now**.

### If alarms don't fire

Open the **Log** (list icon). No *Notification* entry means Android never delivered it to
WakeOnNoti: use *Settings → Repair notification listener*, check battery optimization, and
consider turning on the **keep-alive service** (a silent permanent notification that stops
Android from killing the app). A *Notification* entry without a *Rule fired* line means the
rule did not match – compare its text with your keywords.

Under the hood WakeOnNoti asks Android to rebind the listener whenever it is dropped, and a
watchdog job re-checks every 15 minutes and re-enables the listener if it is still gone.

## Webhook examples

| Service | URL | Method | Body |
|---|---|---|---|
| [ntfy](https://ntfy.sh) push to another device | `https://ntfy.sh/your-topic` | POST | `{title}: {text}` |
| Home Assistant webhook automation | `http://homeassistant.local:8123/api/webhook/<id>` | POST | default JSON |
| Telegram bot | `https://api.telegram.org/bot<token>/sendMessage?chat_id=<id>&text={app}: {text}` | GET | – |

In a body that starts with `{` or `[` the placeholders are JSON-escaped, so quotes in a
message cannot break it; `Content-Type` is set for you unless you give one. Placeholders in
the URL are URL-encoded. A failed call is retried once after 5 seconds; the result is in the log.

## AutoAI

AutoAI ("Aris") is the author's personal desktop assistant (a separate, private project).
WakeOnNoti can forward notifications to it through AutoAI's server relay (the PC needs no
open port): *Settings → AutoAI* takes the server URL and a device token, and forwards
**rule matches** or **everything** (ongoing notifications such as music players excepted,
plus any apps you exclude). A rule's *Tell AutoAI* action can add an instruction, e.g.
"ping me on Discord", which Aris then carries out. Messages go to
`POST {server}/api/relay/send` as `kind: "notification"` with a JSON body
`{app, package, title, text, time, rule?, ask?}`.

## Privacy

Nothing leaves the phone unless one of *your* rules sends it (webhook, AutoAI). The log
and the settings live in the app's private storage. The only other network request is the
update check against this repository.

## Building

Android Studio, or: JDK 17 + Android SDK 35, then `gradlew assembleRelease`.
Without the owner's signing key the release build is signed with your debug key – it runs
fine, but cannot update an install of the published APK (Android requires the same key).

Releases: `powershell -File scripts\publish.ps1 -Bump patch -Notes "what changed"` bumps
`VERSION`, builds and signs the APK, commits it to `release/`, creates the GitHub release,
and finally publishes `release/version.json` – the manifest the app's updater reads.

## License

MIT – see [LICENSE](LICENSE).

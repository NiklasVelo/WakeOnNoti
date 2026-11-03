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

> **Expect Google Play Protect to block the install.** It blocks every app from outside
> the Play Store that reads notifications – which is what WakeOnNoti does. Turning off
> Play Protect scanning for the moment of the install gets around it:
> [how to install](#installing). A Play Store release is planned to make this go away.

## What it does

- **Rules**, as many as you like, each with its own outcome:
  - **from** – any app, or only the apps you pick
  - **contains** – any / all of your words, or regular expressions; in the title,
    the text, or anywhere; optionally case-sensitive or whole words only
  - **but not** – words that cancel the match
  - **cooldown** – optional minimum time between two firings
- **Actions** – one rule can do several:
  - **Alarm** – loops a sound on the *alarm* stream (so silent mode does not mute it),
    vibrates, shows a full-screen Stop screen over the lock screen, and stops by itself
    after N minutes if you want. Each alarm rings at its own volume (1–100 %, with a
    preview while you set it) or at the phone's alarm volume; your volume setting is put
    back afterwards
  - **Webhook** – GET/POST/PUT to any URL with your own headers and body;
    `{rule} {app} {package} {title} {text} {time}` are filled in
  - **AutoAI** – hands the notification to [AutoAI](#autoai) (an AI advisor app, closed beta),
    optionally with an instruction
- **Log** – every incoming notification (kept only on the phone) and every rule that
  fired. Tap a notification to build a rule from it.
- **Backup** – export / import all rules and settings as a JSON file.

For chat apps only the *newest* message is matched, and a notification that is merely
updated in place (same content) does not fire again – so one "wake up" rings once, not on
every later message in the same chat.

## Installing

Google Play Protect will most likely refuse the APK with
*"This app can request access to sensitive data. This can increase the risk of identity
theft or financial fraud."* (German: *„Diese App kann Zugriff auf sensible Daten anfordern.
Dadurch kann das Risiko für Identitätsdiebstahl und Finanzbetrug steigen."*)

That is Play Protect's **enhanced fraud protection**. It blocks every app that is downloaded
through a browser, messenger or file manager *and* wants notification access (or SMS or
accessibility), because banking malware uses those to read one-time codes. It goes by the
permission, not by who made the app, and there is no "install anyway" button. Reading
notifications is the whole point of WakeOnNoti, so it is affected however it is built.
Two ways around it:

- **On the phone:** Play Store → profile picture → *Play Protect* → ⚙ → turn off
  *Scan apps with Play Protect*. Install the APK, then **turn scanning back on**.
- **From a PC with adb:** turn on *Developer options → USB debugging*, connect the phone,
  then run `adb install WakeOnNoti.apk`. This also skips the "restricted settings" step
  under Setup.

You only do this once: later versions arrive through the app's own *Install update*
button (if Play Protect objects there as well, the same two ways work).

## Setup

1. Install the APK as described above.
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

The home screen always says whether the listener is connected ("Listening since …"). Under
the hood WakeOnNoti checks the connection with Android every 2 minutes, on every app start
and from a 15-minute background job. When it is gone it reconnects it by itself, and if
that fails you get a notification saying so. What it cannot fix from inside the app:

- **Notification access** turned off – the notification and the status card tell you.
- **Samsung**: One UI puts apps to sleep that you have not opened for a few days, and a
  sleeping app gets no notifications. *Settings → Battery → Background usage limits →
  Never sleeping apps → add WakeOnNoti*, and set *App info → Battery* to *Unrestricted*.
- **Pausing unused apps** (App info: *Pause app activity if unused* / *Manage app if unused*) –
  nothing to do while notification access is on: Android exempts WakeOnNoti by itself, which
  is why the switch is greyed out or, on Samsung, missing. The app only warns without access.
- Still nothing: turn notification access for WakeOnNoti off and on again.

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

AutoAI is an AI advisor app that is still in **closed beta**, so there is no open access to
it yet – if you don't have an AutoAI setup, simply ignore this part; everything else works
without it.

With access, WakeOnNoti can forward notifications to AutoAI through its server relay (the PC
needs no open port): *Settings → AutoAI* takes the server URL and a device token, and forwards
**rule matches** or **everything** (ongoing notifications such as music players excepted,
plus any apps you exclude). A rule's *Tell AutoAI* action can add an instruction, e.g.
"ping me on Discord", which AutoAI then carries out. Messages go to
`POST {server}/api/relay/send` as `kind: "notification"` with a JSON body
`{app, package, title, text, time, rule?, ask?}`.

**AutoAI's inbox on the phone** (optional, *Settings → AutoAI → Receive AutoAI's inbox*).
AutoAI can work on things by itself. Whatever only you can do – approve something it drafted,
answer a question, do something physical, go to a meeting – lands in its inbox. With this
switched on, that inbox is also here:

- **Urgent** items ring the alarm, at night too. The alarm screen has an *Open inbox* button.
- **Normal** items arrive as a quiet notification, with the answers as buttons.
- What you tap (*Approve*, *Reject*, *Done*, an answer) goes straight back, and with it a short
  note if you like ("go ahead, but not before 9"). Every item in the inbox has a text field
  for it. The notification types it in place: *Add note…* for an approval, then tap the
  answer; *Done, with note…*, *Reply…* or *Skip with note…* send it at once. A note is never
  required, only a question needs its answer.
- An answer that cannot be sent stays open, with the note you typed and the reason.
- Anything you settle at the PC disappears here, and stops the alarm if it is still ringing.
- Nothing open at the PC stays missing here: the phone also asks AutoAI for its whole open
  list – after every start and reconnect, every 15 minutes, every minute while the inbox is
  on screen, and when you tap *Reload*. Asking again never shows anything twice.

The phone long-polls its own relay inbox on the same server, about every 30 seconds while
the switch is on. For alarms in deep sleep, keep battery optimization off for WakeOnNoti.

**AutoAI's board** (with the inbox switched on, a card on the home screen). AutoAI keeps a
list of its tasks; here you see it, add tickets and change them:

- What needs you comes first, then what is open, then what closed in the last 7 days.
- Open a task for every field and its log. Change its state with one tap, or edit priority,
  owner, what it waits on, the follow-up (`in 2h`, `14:00`, `morgen 09:00`, …) and add notes.
- A ticket you add or change reaches AutoAI as your instruction. Changes made anywhere else
  – at the PC or by AutoAI itself – show up here by themselves.

## Privacy

Nothing leaves the phone unless one of *your* rules sends it (webhook, AutoAI). The log
and the settings live in the app's private storage. The only other network requests are the
update check against this repository and, if you switch it on, AutoAI's inbox on your own
AutoAI server.

## Building

Android Studio, or: JDK 17 + Android SDK 35, then `gradlew assembleRelease`.
Without the owner's signing key the release build is signed with your debug key – it runs
fine, but cannot update an install of the published APK (Android requires the same key).

Releases: `powershell -File scripts\publish.ps1 -Bump patch -Notes "what changed"` bumps
`VERSION`, builds and signs the APK, commits it to `release/`, creates the GitHub release,
and finally publishes `release/version.json` – the manifest the app's updater reads.

## Roadmap

- **Google Play release** – the only way to install without the Play Protect step, and the
  easiest install for everyone. The GitHub APK stays available alongside it.

## License

MIT – see [LICENSE](LICENSE).

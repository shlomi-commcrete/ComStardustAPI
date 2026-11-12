# Remote microphone and mute errors — app guide

Two changes to v2 PTT capture. Both need a **major** version bump: `PttRecordingError` gains three
cases, which breaks any exhaustive `when` over it.

## 1. Mute errors (all v2 PTT)

Android can *mute* a recording instead of failing it: the microphone stays "recording" and delivers
silence. Before this change such a recording was announced `STARTED` and `SENT` while nothing audible
went out. Now (API 29+) it is reported:

| `PttRecordingError` | Meaning | What the operator can do |
|---|---|---|
| `MIC_MUTED_IN_BACKGROUND` | The app was not on screen and has no microphone service (phone locked) | Arm the remote microphone (§2), or keep the app on screen |
| `MIC_TAKEN_BY_CALL` | A phone or VoIP call holds the microphone | PTT works again when the call ends |
| `MIC_ACCESS_BLOCKED` | Muted while on screen and not in a call: the system "Microphone access" toggle is off, or another app has capture priority (Android cannot tell these apart) | Turn microphone access on (Settings › Privacy) |

When: at microphone open — `ERROR` instead of `STARTED` — or mid-recording, when the mute arrives
later: `STARTED`, `STOPPED`, then `ERROR` (and no `SENT`). Audio captured before a mid-recording mute
is still transmitted. Below API 29 a mute cannot be detected.

`MIC_UNAVAILABLE` keeps its meaning: the microphone could not be opened at all.

## 2. Remote microphone (`RemoteMic`)

**Why.** An ATAK plugin runs in ATAK's process. ATAK declares no microphone foreground service, so
Android mutes a microphone opened there a few seconds after the phone locks. `RemoteMicService` —
declared in this library's manifest and merged into the consumer's APK — runs in the **consumer's own
package**, which can hold a microphone foreground service. While it is armed, every v2 PTT recording
captures through it and keeps working with the phone locked, in Doze and in the background
(device-tested on Android 14, PIN-locked).

```kotlin
// While the host is on screen (Android allows starting a microphone service only then):
val state = RemoteMic.arm(
    context = hostContext,                     // ATAK's context
    servicePackage = pluginContext.packageName,
    channel = channel,                         // built by the consumer
    notification = notification,               // built with pluginContext, shown while armed
)
// state: Armed / Arming / Unavailable(NOT_ON_SCREEN | PERMISSION_MISSING | START_REFUSED | DISCONNECTED)
RemoteMic.state // StateFlow, updated on the main thread

RemoteMic.disarm(hostContext) // back to the local microphone
```

- **Not armed → nothing changes:** PTT uses the local microphone exactly as before.
- **Permission:** the consumer's package needs its **own** `RECORD_AUDIO` grant (the host's does not
  count), and `POST_NOTIFICATIONS` for the notification to be visible (API 33+). Request them from an
  activity of the consumer's package. `arm` returns `PERMISSION_MISSING` without the first.
- **Presentation is the consumer's:** the SDK posts the given notification and creates the given channel
  in the service's process; it adds no text of its own.
- **Lost service:** if the service's process dies, `state` becomes `Unavailable(DISCONNECTED)` and PTT
  falls back to the local microphone. Arm again the next time the host is on screen.
- **Who may bind:** any `com.atakmap.app*` package, by name.
- **Manifest:** the library now adds `RemoteMicService` (exported, `foregroundServiceType="microphone"`)
  and `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MICROPHONE` to the consumer. A consumer that never arms
  can remove the service with `tools:node="remove"`.
- **Legacy pipeline:** the remote microphone exists only in v2 (`PttPipelineFeatureFlag` on).

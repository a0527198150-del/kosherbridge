# KosherBridge

A Bluetooth HFP bridge for Android: it connects an **Android player** (TV box,
tablet, car unit) to a basic **"kosher phone"**, so every call the phone
receives appears on the player — with answer / reject / dial, a built-in
contacts app, and a call log. The player behaves like a car hands-free kit;
the kosher phone stays a phone.

## Control vs. audio — the one distinction that matters

**Call control** (ring, answer, reject, dial, caller ID) works on every
channel, including the default direct RFCOMM channel, and needs nothing
special from the player. **Call audio** (the voice through the player's
speaker and microphone) needs the real HFP client profile, and that is where
players differ from each other — see below.

When the voice cannot reach the player, the bridge does not fail: the kosher
phone keeps the conversation on its own earpiece and the player stays a fully
working remote control, dialer and call screen. That outcome is a deliberate
mode, not an error — see "Audio mode".

The Hebrew user guide in [`kosherbridge/README.md`](kosherbridge/README.md) is
the primary documentation; this file is the architecture summary.

## Call audio: the three things that must all be true

### 1. The profile must be running

`getSupportedProfiles()` reports the profiles the stack actually **started**.
The profile proxy is not evidence: it binds happily to a
`HeadsetClientService` that never ran, every call then returns empty, and the
bridge used to conclude "supported" — the single confusion behind every
contradictory field report.

A package-manager lookup of
`com.android.bluetooth/.hfpclient.HeadsetClientService` separates "disabled,
possibly fixable" from "removed from the build, hopeless — not even root
helps". Both facts are read by an on-device capability probe, so a player can
be assessed without adb and without a second person holding it.

When the profile is present but dormant, a privileged channel (Shizuku over
wireless adb — **no root**) walks a ladder of routes, reporting exactly where
it stopped rather than a flat "it did not work":

1. **Writability probe** — a harmless `debug.*` write, the most permissive
   SELinux context there is. Refused? The property route is closed entirely
   and the remaining names cannot help either.
2. **Every profile property name** — the official
   `bluetooth.profile.hfp.hf.enabled` and the `persist.*` variants that sit in
   a different SELinux context shell is more often allowed to write, *plus the
   names discovered on the player itself*: the hand-written list came from
   build.prop recipes for other people's devices, while `getprop` (world
   readable, no channel needed) shows what this vendor fork actually has.
3. **`pm enable`** on the service component — some builds disable the
   component rather than the flag; that needs
   `CHANGE_COMPONENT_ENABLED_STATE`, which the shell identity holds.
4. **`bluetooth_disabled_profiles`** — a `Settings.Global` bitmask some builds
   use. Read first, cleared only when set, previous value reported so it can
   be undone.
5. **Direct service start** — on Android 12/13 a profile is started by sending
   its service the `STATE_CHANGED` intent, which is literally how
   `AdapterService` does it. Reversible with a Bluetooth restart.

Two things about that ladder are easy to get wrong, and both cost the user the
entire result:

- **The property does not survive a reboot.** A system property without a
  `persist.` prefix is wiped at every boot, and so is the audio gate. The app
  records which name actually won on this player and puts it back once per
  boot — guarded by `BootMarker`, which identifies a boot by its instant
  (`currentTimeMillis() - elapsedRealtime()`, constant within a boot) rather
  than trusting `BOOT_COMPLETED` — then restarts Bluetooth and
  reconnects. It waits up to half an hour for a privileged channel, because
  Shizuku does not survive a reboot either and is started by hand.
- **The default channel disables the profile.** `AUTO` carries calls over a raw
  RFCOMM socket and deliberately switches the player's hands-free profile off
  for the bridged phone, so the two do not fight over the phone's single
  hands-free slot. That is right on a player with no profile — nearly all of
  them — and exactly wrong once the profile exists, since the profile is the
  only path that carries voice. Enabling the profile and staying on `AUTO`
  therefore achieves nothing, silently. The capability verdict, the enable
  action's answer and the diagnostics report all say so now.

### 2. The audio gate must be open

Having the profile running is necessary but not sufficient. Verified against
AOSP: `HeadsetClientStateMachine` keeps an `mAudioRouteAllowed` field —
*"Indicates whether audio can be routed to the device"* — and on the gateway's
incoming SCO it takes this branch when the field is false:

```java
if (!mAudioRouteAllowed) {
    info("Audio is not allowed! Disconnect SCO.");
```

The phone has *already* handed the conversation to the "hands-free", and the
hands-free throws it away — so the call is audible on neither device. That is
the classic "connected, but total silence" failure. Two ways to open it:

- **Imperative**: `setAudioRouteAllowed(true)`, per device. On Android 8–12 it
  needs only `BLUETOOTH_CONNECT`, so it works from the plain app process with
  no root and no Shizuku; on 13+ it moved behind `BLUETOOTH_PRIVILEGED` and
  goes through a privileged channel.
- **Declarative**: the property the stack itself reads when it builds a state
  machine —

  ```java
  mAudioRouteAllowed = SystemProperties.getBoolean(
      "bluetooth.headset_client.initial_audio_route.enabled", mAudioRouteAllowed);
  ```

  Better when it works: it applies to every state machine built afterwards
  rather than to one device. It takes effect on the next connection, since the
  field is read at construction. Written automatically when the imperative
  call is refused.

Both states are reported in Diagnostics.

### 3. The audio HAL must route it

AOSP's HFP-client state machine does not only ask the framework to route call
audio; it pushes parameters straight to the audio HAL (`routeHfpAudio`). The
bridge sends the same ones, and this needs no privilege at all —
`AudioManager.setParameters` is a public API gated on `MODIFY_AUDIO_SETTINGS`:

| Parameter | Role |
|---|---|
| `hfp_enable=true` | makes the HAL create the SCO audio task |
| `hfp_set_sampling_rate=8000/16000` | narrowband CVSD vs wideband mSBC — the wrong rate decodes as noise |
| `BT_SCO=on`, `A2dpSuspended=true` | what AudioService sets on older HALs |

This does **not** create the SCO link — only the Bluetooth stack does that. It
closes the case where a link exists and does not reach the speakers.

### On the raw RFCOMM channel

The direct channel speaks HFP itself over a plain socket, so the stack does
not know about the link and will not open SCO for it. It does negotiate codecs
properly (`AT+BAC`, `+BCS` → `AT+BCS`) and can ask the gateway for audio
(`AT+BCC`) — sent only when the player actually exposes somewhere for the
audio to land, since asking otherwise takes the voice off the phone's own
earpiece too.

## Audio mode

Proving that a player cannot carry call audio costs a six-second window in
which the app holds its audio pipeline for a route that will not appear.
Correct the first time; a defect on every call after that, on a device where
the answer cannot change. So the verdict is proven once and remembered per
player, and Settings offers three choices: `AUTO` (try, then remember),
`PLAYER` (try every call regardless) and `PHONE` (never try — the right
setting for a player that provably cannot). In the phone modes the player
remains a full remote: answer, reject, hang up, dial, contacts, call log.

## Reliability on cheap players

The players this targets are not stock Android, and the failures have nothing
to do with Bluetooth:

- **Battery optimisation** suspends the service after a few hours — the phone
  rings and nothing happens here. Requested as a one-tap grant, with a warning
  card on the main screen while it is missing.
- **Vendor power managers** kill the process and ignore `START_STICKY`, so a
  15-minute `setAndAllowWhileIdle` alarm re-arms itself and restarts the
  service when it is gone (`BridgeWatchdog`). Inexact on purpose: no
  `SCHEDULE_EXACT_ALARM` grant needed.
- **Vendor auto-start managers** kill apps missing from their list regardless
  of every Android permission. No API can read or set these, so the setup
  screen says so and opens the vendor screen when one resolves.
- **Boot and update coverage**: `BOOT_COMPLETED`, the fast-boot broadcasts
  cheap players send instead, and `MY_PACKAGE_REPLACED` — an app update
  otherwise leaves the bridge dead until someone opens it by hand.

Settings → "התקנה ומוכנות" is one checklist of every requirement, its real
state, and the single tap that fixes it — including the honest call-audio
verdict, so a user learns before the first call what their player can do.

## Repository layout

This Gradle root contains **two unrelated applications**:

- `kosherbridge/` — the Bluetooth bridge described here. This is the
  maintained application; CI builds and tests it.
- `app/` — a **separate, unrelated** Hebrew personal-budget application
  (Firebase + Gemini) that happens to share this Gradle root. No code is
  shared between the two modules, and there is no Gradle dependency between
  them.

### Budget app (`app/`) build notes

The budget app reads its Gemini key from a `.env` file at the repository root
(see `.env.example`): create `.env` and set `GEMINI_API_KEY` before building
`app/`. It is not built by CI.

## Building KosherBridge

```bash
gradle :kosherbridge:assembleRelease   # what CI ships
gradle :kosherbridge:assembleDebug     # for development
```

Both build types are signed with the same stable key (restored in CI from the
`DEBUG_KEYSTORE_BASE64` secret), so any build installs over any earlier one
without uninstalling. Release deliberately keeps `isMinifyEnabled = false`:
this app loads classes by name from a shell command (`SpawnedBridgeMain`,
`HfpUserService`) and reaches the HFP profile entirely through reflection, so
shrinking would break the root and Shizuku channels in ways no test here would
catch.

### CI artifacts

Each run produces four downloads — plus a fifth, `kosherbridge-test-report`,
only when the tests fail:

| Artifact | Contents | Audience |
|---|---|---|
| `KosherBridge-<version>-apk` | the signed release APK, **standard** flavour | **this is the one to install.** No network permission in the manifest at all |
| `KosherBridge-<version>-apk-plus` | the same app plus the in-app ADB channel | people who want a shell identity without installing Shizuku; adds `INTERNET` (see below) |
| `KosherBridge-<version>-magisk-module` | the standard APK packaged as a Magisk module | rooted players only; needs a reboot |
| `kosherbridge-lint` | static-analysis HTML report | developers; never gates the build |

**The full no-root map** — everything considered for getting call audio onto
the player without root, and exactly where each path ends — is written up in
[`docs/no-root.md`](docs/no-root.md). Read that before proposing an idea; most
of them are in there with the reason they stop.

**Why two APKs.** The last no-root lever is a shell identity:
`com.android.shell` holds BLUETOOTH_PRIVILEGED and WRITE_SECURE_SETTINGS, which
is what the audio gate, the connection-policy repair and the profile-enable
ladder need. Android 11+ lets an app get one by speaking ADB to 127.0.0.1 with
the pairing code already on screen — no PC, no root, no second app. But Android
gates every TCP socket behind `android.permission.INTERNET`, with nothing
narrower and nothing loopback-only, and for an app whose users chose a kosher
phone precisely to have no internet, that permission in the manifest is not a
detail. So `standard` does not declare it and does not even ship the ADB
library, and `plus` is a separate, deliberate download. Same applicationId and
signing key, so either installs over the other without data loss.

The app makes no outbound connection in either flavour: no analytics, no update
check, no crash reporting, no remote server. The only IP socket it ever opens is
to loopback; the only other socket is Bluetooth RFCOMM, which is not IP.

Version is `1.0.<commit count>`, the same string the app reports in
Diagnostics, so a downloaded file says which build it is.

See [`.github/workflows/main.yml`](.github/workflows/main.yml) and
[`magisk-module/README.md`](magisk-module/README.md).

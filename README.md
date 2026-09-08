# KosherBridge

A Bluetooth HFP bridge for Android: it connects an **Android player** (TV box,
tablet, car unit) to a basic **"kosher phone"**, so every call the phone
receives appears on the player — with answer / reject / dial, a built-in
contacts app, and a call log. The player behaves like a car hands-free kit;
the kosher phone stays a phone.

The Hebrew user guide lives in [`kosherbridge/README.md`](kosherbridge/README.md).

## Control vs. audio — the one distinction that matters

**Call control** (ring, answer, reject, dial, caller ID) works on every
channel, including the default direct RFCOMM channel. **Call audio** (the
voice through the player's speaker/microphone) only flows through the real
HFP client profile — the Shizuku, root, or Magisk-module channels. On the
raw RFCOMM channel the voice stays on the kosher phone and the player acts
as a remote control, dialer, and call screen.

### The audio-route gate

Having the HFP client profile is necessary but not sufficient. AOSP's
`HeadsetClientStateMachine` keeps a per-device `mAudioRouteAllowed` flag,
initialised from a build resource that is only true on automotive builds.
While it is false the stack answers the phone's incoming SCO (voice) link
with an immediate disconnect — and because the phone has *already* handed
the conversation to the "hands-free", the call is then audible on neither
device. That is the classic "connected, but total silence" failure.

The bridge now opens that gate itself via `setAudioRouteAllowed`:

- **Android 8–12** the call needs only `BLUETOOTH_CONNECT`, so it works from
  the plain app process — **no root, no Shizuku**.
- **Android 13+** the same method moved behind `BLUETOOTH_PRIVILEGED`, so it
  is made through the privileged bridges instead (Shizuku over wireless adb,
  or root).

The gate's state is reported in Diagnostics under "ניתוב שמע השיחה", with a
"פתח ניתוב שמע לשיחה" action to retry it on demand.

### Asking the player what it can do

Diagnostics runs an on-device capability probe, so a player can be assessed
without adb and without a second person holding it. It separates the two facts
that every confusing report has conflated:

- **Is the HFP-client profile running?** `getSupportedProfiles()` reports what
  the stack actually started. The profile proxy is not evidence: it binds
  happily to a `HeadsetClientService` that never ran, and every call then
  returns empty — which is exactly why the bridge used to report a dormant
  profile as "supported".
- **Is the profile even in the build?** A package-manager lookup of
  `com.android.bluetooth/.hfpclient.HeadsetClientService` separates "disabled,
  possibly fixable" from "removed by the manufacturer, hopeless".

It also reads `bluetooth.profile.hfp.hf.enabled` and the SELinux mode, because
those two decide whether the flag can be flipped without root. When there is
something to try, a "הפעל פרופיל דיבורית" action writes the flag through
Shizuku (wireless adb, no root) and restarts the Bluetooth stack. Stock policy
reserves that property for `init` and the attempt fails cleanly; the lax
policies common to cheap players often allow it. The property is not
persistent, so it must be re-applied after each reboot — the Magisk module is
the permanent form of the same change.

### When the voice cannot reach the player

The bridge no longer assumes that asking for the route is the same as getting
it. Six seconds after claiming the player's voice pipeline it checks whether a
voice link actually exists (the SCO broadcast, or the HFP-client profile's own
audio state). If nothing is carrying the call and the player provably exposes
no SCO device, the bridge releases the claim — communication mode, audio focus
and the forced route — so the kosher phone keeps the conversation on its own
earpiece, and both the call screen and the home screen say so explicitly
instead of leaving the user in silence.

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
gradle :kosherbridge:assembleDebug
```

CI builds the debug APK and the Magisk module on every push; see
[`.github/workflows/main.yml`](.github/workflows/main.yml) and
[`magisk-module/README.md`](magisk-module/README.md).

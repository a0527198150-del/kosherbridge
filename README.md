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
voice through the player's speaker/microphone) only ever flows through the
real HFP-Client profile. On the raw RFCOMM channel the voice stays on the
kosher phone and the player acts as a remote control, dialer, and call
screen.

What decides whether a given player can carry audio is therefore one thing
only: **is the HFP-Client profile enabled in that player's Bluetooth stack?**

- **Enabled** — use the **`מערכת (Telecom)` channel**: the player connects to
  the phone as an ordinary hands-free device, AOSP's
  `HfpClientConnectionService` publishes those calls into the Telecom
  framework, and this app drives them through public `TelecomManager` API.
  Full calls including voice, with **no root, no Shizuku, no hidden API and no
  privileged permission** — only four ordinary runtime permissions. The
  system owns the SCO link, so the app never touches audio routing.
- **Disabled** (the factory default on many cheap players) — no app can turn
  it on: it is a read-only system property the Bluetooth stack reads at boot.
  That case still needs the Magisk module, and no amount of app code changes
  it. Shizuku does not help here either.

The diagnostics screen answers this directly, with no reflection: the row
**"ערוץ מערכת (Telecom)"** reports whether the platform published an HFP
phone account, which happens only when the profile is enabled and connected.

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

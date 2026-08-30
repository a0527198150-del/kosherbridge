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

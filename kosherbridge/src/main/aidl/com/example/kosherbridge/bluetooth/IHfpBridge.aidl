package com.example.kosherbridge.bluetooth;

/**
 * Runs in a separate process spawned by Shizuku under the `shell` UID
 * (via ADB / Wireless Debugging pairing, or root). That UID is pre-granted
 * BLUETOOTH_PRIVILEGED on stock AOSP and is exempt from per-app Hidden API
 * enforcement, so the same reflection calls that fail inside the normal app
 * process succeed here.
 */
interface IHfpBridge {
    boolean isAvailable();
    boolean registerProfile();
    boolean isProfileReady();
    String[] bondedDevices();
    boolean connect(String address);
    boolean disconnect(String address);
    int connectionState(String address);
    int audioState(String address);
    boolean connectAudio();
    boolean disconnectAudio();
    boolean dial(String number);
    boolean redial();
    boolean accept();
    boolean reject();
    boolean hangup();
    String currentCallSnapshot();
    void destroy();
}
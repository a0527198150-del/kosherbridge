package com.example.kosherbridge.bluetooth;

/**
 * Runs in a separate process spawned by Shizuku under the `shell` UID
 * (via ADB / Wireless Debugging pairing, or root). That UID is pre-granted
 * BLUETOOTH_PRIVILEGED on stock AOSP and is exempt from per-app Hidden API
 * enforcement, so the same reflection calls that fail inside the normal app
 * process succeed here.
 */
interface IHfpBridge {
    boolean isAvailable() = 1;
    boolean registerProfile() = 2;
    boolean isProfileReady() = 3;
    String[] bondedDevices() = 4;
    boolean connect(String address) = 5;
    boolean disconnect(String address) = 6;
    int connectionState(String address) = 7;
    int audioState(String address) = 8;
    boolean connectAudio() = 9;
    boolean disconnectAudio() = 10;
    boolean dial(String number) = 11;
    boolean redial() = 12;
    boolean accept() = 13;
    boolean reject() = 14;
    boolean hangup() = 15;
    String currentCallSnapshot() = 16;
    // Reserved "destroy" transaction code defined by the Shizuku server
    // (see the official Shizuku-API demo). Without the explicit code the
    // server cannot signal this service to shut down.
    void destroy() = 16777114;
}

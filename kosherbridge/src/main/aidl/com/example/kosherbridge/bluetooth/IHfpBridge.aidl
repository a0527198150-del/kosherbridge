package com.example.kosherbridge.bluetooth;

/**
 * Runs in a separate privileged process: either spawned by Shizuku under the
 * `shell` UID (via ADB / Wireless Debugging pairing, or root), or spawned by
 * the app itself under uid 0 via su (the "Root" channel - RootBridge +
 * RootBridgeMain). Those identities are pre-granted BLUETOOTH_PRIVILEGED on
 * stock AOSP and are exempt from per-app Hidden API enforcement, so the same
 * reflection calls that fail inside the normal app process succeed here.
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
    // Sets the HFP-client connection policy for one device back to ALLOWED
    // (100) in the privileged process. The privileged identity holds
    // BLUETOOTH_PRIVILEGED, so unlike the same call from the app process it
    // actually succeeds. Called immediately before connect(): a policy left
    // FORBIDDEN by a previous raw/AUTO session makes the stack refuse (or
    // tear down seconds later) every hands-free connection for that device.
    boolean setConnectionAllowed(String address) = 17;
    // Reads the current HFP-client connection policy for one device
    // (CONNECTION_POLICY_ALLOWED = 100, FORBIDDEN = 0, UNKNOWN = -1).
    // Returns -1000 when the policy cannot be read. Diagnostics only.
    int connectionPolicy(String address) = 18;
    // Reserved "destroy" transaction code defined by the Shizuku server
    // (see the official Shizuku-API demo). Without the explicit code the
    // server cannot signal this service to shut down.
    void destroy() = 16777114;
}

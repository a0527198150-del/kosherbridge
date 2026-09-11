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
    // Generalizes setConnectionAllowed: sets the connection policy (100 = ALLOWED,
    // 0 = FORBIDDEN) for a device and ANY guarded profile in the privileged
    // process. The privileged identity holds BLUETOOTH_PRIVILEGED, so unlike the
    // same call from the app process the write actually succeeds on a stock
    // player - letting the repair action restore not only the HFP-client profile
    // but Headset, A2DP and A2DP-Sink too. Returns false when the write is refused.
    boolean setProfilePolicy(String address, int profileId, int policy) = 19;
    // Flips HeadsetClientStateMachine.mAudioRouteAllowed for one device. On
    // Android 13+ that call is @SystemApi behind BLUETOOTH_PRIVILEGED, so the
    // app process cannot make it - but this privileged process can. Without it
    // the stack answers the phone's incoming SCO (voice) link with an
    // immediate disconnect and the conversation is audible nowhere. Returns
    // true when the gate is allowed afterwards.
    boolean setAudioRouteAllowed(String address, boolean allowed) = 20;
    // Reads the same gate: 1 = allowed, 0 = blocked, -1 = unreadable.
    int audioRouteAllowed(String address) = 21;
    // ---- device preparation (Shizuku / root), for players whose stack ships
    // the HFP-client profile but never enables it. All of these need an
    // identity the app process does not have, and none of them need root
    // specifically - the Shizuku `shell` identity is enough where the
    // platform's SELinux policy allows the write.
    //
    // Reads a system property. Readable from the app process too; exposed here
    // so the caller can prove the privileged process sees the same value.
    String getSystemProperty(String key) = 22;
    // Writes a system property. Returns the value read back on success, or a
    // string prefixed "ERR:" carrying the reason the write was refused. The
    // Bluetooth profile flags live in the `bluetooth_config_prop` SELinux
    // context, which stock policy lets only init write - so this is expected
    // to fail on a stock build and to succeed on the lax/permissive policies
    // common to cheap players. The reason matters: an SELinux denial rules out
    // every property in that context, while "property not defined" only rules
    // out that one name.
    String setSystemProperty(String key, String value) = 23;
    // Restarts the Bluetooth stack so it re-reads the profile flags. Without
    // this a successful property write has no visible effect until reboot.
    boolean restartBluetooth() = 24;
    // Android's non-SDK interface policy (0 = enforced, 1 = allow all).
    // DEVICE-GLOBAL: it relaxes the restriction for every app on the player.
    boolean setHiddenApiPolicy(int policy) = 25;
    // "Enforcing" / "Permissive" / "" when unknown. Decides whether a
    // property write has any chance of succeeding on this player.
    String selinuxMode() = 26;
    // Profile IDs the Bluetooth stack currently has ENABLED (not merely
    // present in the APK). HEADSET_CLIENT is 16; its absence is the single
    // fact that decides whether call audio can ever reach this player.
    int[] enabledProfiles() = 27;
    // Last-resort attempt to wake the dormant HFP-client profile: send the
    // profile service the same start intent AdapterService uses when it brings
    // a profile up (EXTRA_ACTION = STATE_CHANGED, EXTRA_STATE = STATE_ON).
    // On Android 12/13 that is literally how profiles are started, so a stack
    // that merely never asked can be asked by us instead. Returns a
    // human-readable result - including the refusal, which is the likely
    // outcome when the component is not exported to the shell identity.
    // Reversible: restarting Bluetooth restores the stack's own idea of which
    // profiles are running.
    String startHeadsetClientService() = 28;
    // Re-enables the profile's service COMPONENT at the package-manager level.
    // A different manufacturer choice from unsetting the property: some ship
    // the profile present but pm-disabled, and `pm enable` from the shell
    // identity undoes exactly that - no root involved. Returns "OK" or
    // "ERR:<reason>".
    String enableProfileComponent() = 29;
    // Reads Settings.Global.bluetooth_disabled_profiles, a bitmask some builds
    // use to switch profiles off independently of the build flags. "" when the
    // setting is absent, which is the normal case.
    String disabledProfilesSetting() = 30;
    // Clears that bitmask (writes 0 = nothing disabled). Reversible: the caller
    // keeps the previous value. Returns "OK" or "ERR:<reason>".
    String clearDisabledProfilesSetting() = 31;
    // Reserved "destroy" transaction code defined by the Shizuku server
    // (see the official Shizuku-API demo). Without the explicit code the
    // server cannot signal this service to shut down.
    void destroy() = 16777114;
}

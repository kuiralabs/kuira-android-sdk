// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.network

import android.os.Build

/**
 * Detects whether the app is running on an Android emulator or a physical device.
 *
 * Used by [NetworkConfig] to pick the right localhost host:
 * - Emulator: `10.0.2.2` (Android emulator's magic alias for the host machine)
 * - Physical device: `127.0.0.1` (works via `adb reverse tcp:<port> tcp:<port>`)
 *
 * **Why this matters:**
 * Localnet services (indexer, node RPC, proof server) run on the developer's
 * laptop. The emulator can reach them via `10.0.2.2`, but a physical device
 * cannot — it needs adb reverse port forwarding which only tunnels `localhost`.
 *
 * The detection uses Android Build markers that emulators set. The check is
 * conservative — false positives (treating a real device as an emulator) would
 * use `10.0.2.2` and fail to connect, which is loud and easy to diagnose.
 * False negatives (treating an emulator as physical) just need adb reverse
 * to be configured, which is the same as physical device setup.
 */
object DeviceType {

    /**
     * Returns true if the current device is an Android emulator.
     *
     * Detection is based on Build properties that emulators consistently set:
     * - generic / generic_x86 / generic_arm64 in product, brand, model, manufacturer
     * - "google_sdk" / "Emulator" / "Android SDK built for" in model
     * - "ranchu" / "goldfish" hardware (qemu-based emulators)
     *
     * Conservative checks borrowed from the widely-used isEmulator pattern.
     */
    val isEmulator: Boolean by lazy {
        // Use orEmpty() because Build fields are mocked to null in JVM unit tests
        // (testOptions { unitTests { isReturnDefaultValues = true } }).
        val brand = Build.BRAND.orEmpty()
        val device = Build.DEVICE.orEmpty()
        val fingerprint = Build.FINGERPRINT.orEmpty()
        val model = Build.MODEL.orEmpty()
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val hardware = Build.HARDWARE.orEmpty()
        val product = Build.PRODUCT.orEmpty()

        (brand.startsWith("generic") && device.startsWith("generic")) ||
            fingerprint.startsWith("generic") ||
            fingerprint.startsWith("unknown") ||
            model.contains("google_sdk") ||
            model.contains("Emulator") ||
            model.contains("Android SDK built for") ||
            manufacturer.contains("Genymotion") ||
            hardware.contains("goldfish") ||
            hardware.contains("ranchu") ||
            product.contains("sdk_google") ||
            product.contains("google_sdk") ||
            product.contains("sdk") ||
            product.contains("sdk_x86") ||
            product.contains("sdk_gphone") ||
            product.contains("vbox86p") ||
            product.contains("emulator") ||
            product.contains("simulator")
    }

    /**
     * Localhost address that resolves to the developer's machine.
     *
     * - On emulator: `10.0.2.2` (Android emulator's special alias for host loopback)
     * - On physical device: `127.0.0.1` (requires `adb reverse` port forwarding)
     */
    val localhostHost: String
        get() = if (isEmulator) "10.0.2.2" else "127.0.0.1"
}

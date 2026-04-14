# =====================================================================
# Kuira Wallet — R8 / ProGuard rules (app module)
# =====================================================================
# App-level rules only. Module-specific rules live in each module's
# `consumer-rules.pro` and propagate automatically:
#   - core:crypto      → JNI classes loading libkuira_crypto_ffi.so
#   - core:ledger      → signer, dust, fee FFI + @Serializable types
#   - core:compact-engine → QuickJS bridge + SDK public surface
#   - core:indexer     → Ktor + kotlinx.serialization models
#   - core:wallet      → BigInteger / BigDecimal preservation
#
# See each module's consumer-rules.pro for rationale.
# =====================================================================

# Stack-trace readability in release crash reports. We upload mapping
# files to Firebase Crashlytics (Phase 8B) for deobfuscation, but
# keeping line numbers + original class names helps when grepping.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------
# BouncyCastle / BitcoinJ
# ---------------------------------------------------------------------
# BitcoinJ uses reflection for algorithm discovery. Keep the crypto
# providers and the SPI classes they register. App-level because many
# modules transitively depend on BitcoinJ.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class org.bitcoinj.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.bitcoinj.**

# ---------------------------------------------------------------------
# Defensive — JNI catch-all
# ---------------------------------------------------------------------
# Mirror of the rule in core:crypto / core:ledger consumer rules so
# any native methods picked up from transitive deps (e.g. kotlinx,
# BouncyCastle JNI, etc.) also survive.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

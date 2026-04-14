# =====================================================================
# core:crypto — consumer R8 rules
# =====================================================================
# These rules propagate to any module that consumes core:crypto
# (including the app itself and future external SDK consumers).
#
# core:crypto loads `libkuira_crypto_ffi.so` and exposes it through
# several Kotlin classes that declare `external fun`. JNI resolves
# native symbols using the class's fully-qualified name — if R8
# renames the class, `System.loadLibrary` succeeds but every native
# call afterwards throws UnsatisfiedLinkError.
#
# The generic `native <methods>` rule handles most cases; the
# explicit class-level keeps below are belt + braces for the Rust FFI
# wrapper classes that also hold native state pointers (Long fields)
# the Rust side expects at known offsets.
# =====================================================================

# Keep every class that declares native methods + keep the native
# methods themselves. This is the canonical JNI protection pattern.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# FFI wrapper classes holding native pointers / called from Rust.
-keep class com.midnight.kuira.core.crypto.dust.** { *; }
-keep class com.midnight.kuira.core.crypto.shielded.** { *; }
-keep class com.midnight.kuira.core.crypto.address.** { *; }
-keep class com.midnight.kuira.core.crypto.bip32.** { *; }

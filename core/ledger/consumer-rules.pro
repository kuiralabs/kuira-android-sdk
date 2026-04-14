# =====================================================================
# core:ledger — consumer R8 rules
# =====================================================================
# Propagates to any consumer of core:ledger. Protects the Rust FFI
# classes in this module from R8 renaming.
# =====================================================================

-keep class com.midnight.kuira.core.ledger.signer.** { *; }
-keep class com.midnight.kuira.core.ledger.dust.** { *; }
-keep class com.midnight.kuira.core.ledger.fee.** { *; }
-keep class com.midnight.kuira.core.ledger.api.TransactionSerializer { *; }
-keep class com.midnight.kuira.core.ledger.api.FfiTransactionSerializer { *; }

# @Serializable types used across JSON-RPC boundaries.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.midnight.kuira.core.ledger.**$$serializer { *; }
-keepclassmembers class com.midnight.kuira.core.ledger.** {
    *** Companion;
}
-keepclasseswithmembers class com.midnight.kuira.core.ledger.** {
    kotlinx.serialization.KSerializer serializer(...);
}

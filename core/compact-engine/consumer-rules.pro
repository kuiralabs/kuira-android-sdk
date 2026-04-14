# =====================================================================
# core:compact-engine — consumer R8 rules
# =====================================================================
# The SDK module. Uses QuickJS + Rust FFI for contract execution;
# JS↔Kotlin callbacks are reflection-style.
#
# Consumer rules because this module is also destined for external
# SDK publishing — rules must propagate to whatever app uses
# `com.midnight.kuira:compact-engine`.
# =====================================================================

# Public SDK surface + FFI wrappers.
-keep class com.midnight.kuira.core.compact.** { *; }

# QuickJS Kotlin binding.
-keep class app.cash.quickjs.** { *; }
-dontwarn app.cash.quickjs.**

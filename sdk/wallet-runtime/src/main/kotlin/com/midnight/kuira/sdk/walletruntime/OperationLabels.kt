package com.midnight.kuira.sdk.walletruntime

import androidx.annotation.StringRes
import com.midnight.kuira.sdk.OperationKind
import com.midnight.kuira.sdk.OperationTerminalStatus

/**
 * Presentation-edge mapping from an [OperationKind] (domain state) to its localized
 * DEFAULT label resource — used for the ongoing foreground-service notification
 * when the caller didn't supply its own label (i.e. the SDK's own built-in send / dust
 * / contract operations). A caller-provided label always wins; this is only the
 * fallback. The exhaustive `when` fails to compile if a kind is added without a label,
 * so the centralization stays honest.
 *
 * Mirrors [SyncPhase.labelRes]; the SDK emits the enum, the host renders the string
 * (and can override these resources).
 */
@StringRes
fun OperationKind.defaultLabelRes(): Int = when (this) {
    OperationKind.Send -> R.string.kuira_op_send
    OperationKind.DustRegistration -> R.string.kuira_op_dust_register
    OperationKind.ContractCall -> R.string.kuira_op_contract
    OperationKind.Custom -> R.string.kuira_op_generic
}

/** Terminal-status → localized result word for the finalization notification. */
@StringRes
fun OperationTerminalStatus.labelRes(): Int = when (this) {
    OperationTerminalStatus.Success -> R.string.kuira_op_done
    OperationTerminalStatus.Pending -> R.string.kuira_op_submitted
    OperationTerminalStatus.Failed -> R.string.kuira_op_failed
}

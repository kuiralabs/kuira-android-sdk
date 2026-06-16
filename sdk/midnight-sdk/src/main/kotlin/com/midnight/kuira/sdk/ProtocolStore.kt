package com.midnight.kuira.sdk

import android.content.Context

/**
 * A tiny durable record of which protocol sagas are in flight (#253). NOT the source
 * of truth for step progress — the LEDGER is (via each step's `doneWhen`); this only
 * answers "is there an unfinished saga with this id to resume?" so a dApp can re-launch
 * it after process death. Closures can't be serialized, so the saga DEFINITION is code
 * the dApp re-provides; this store holds only the id + its last label.
 */
internal class ProtocolStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Record [id] as in flight (idempotent). [label] is kept for diagnostics. */
    fun markInFlight(id: String, label: String) {
        prefs.edit()
            .putStringSet(KEY_IN_FLIGHT, inFlightIds() + id)
            .putString(labelKey(id), label)
            .apply()
    }

    /** Drop [id] once its saga completed. */
    fun clear(id: String) {
        prefs.edit()
            .putStringSet(KEY_IN_FLIGHT, inFlightIds() - id)
            .remove(labelKey(id))
            .apply()
    }

    /** Ids of sagas that started but haven't completed — candidates to resume. */
    fun inFlightIds(): Set<String> =
        // getStringSet returns a shared, must-not-mutate instance — copy it.
        prefs.getStringSet(KEY_IN_FLIGHT, emptySet())?.toSet() ?: emptySet()

    private fun labelKey(id: String) = "$KEY_LABEL_PREFIX$id"

    private companion object {
        const val PREFS_NAME = "kuira_protocols"
        const val KEY_IN_FLIGHT = "in_flight_ids"
        const val KEY_LABEL_PREFIX = "label_"
    }
}

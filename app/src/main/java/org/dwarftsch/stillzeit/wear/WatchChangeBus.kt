package org.dwarftsch.stillzeit.wear

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Meldet Schreibzugriffe der Uhr an die Oberfläche, damit diese neu lädt
 * (Ersatz für den `changes`-ValueNotifier der Flutter-WatchBridge).
 */
object WatchChangeBus {
    private val zaehler = MutableStateFlow(0)

    /** Erhöht sich bei jedem Schreibzugriff der Uhr. */
    val aenderungen: StateFlow<Int> = zaehler

    fun melden() {
        zaehler.value++
    }
}

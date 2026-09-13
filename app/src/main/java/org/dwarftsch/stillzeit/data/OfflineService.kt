package org.dwarftsch.stillzeit.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import java.time.Instant
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.dwarftsch.stillzeit.Entry
import org.dwarftsch.stillzeit.FlaschenArt
import org.dwarftsch.stillzeit.Seite
import org.dwarftsch.stillzeit.TodayStats

/** Der Offline-Zustand, den die Oberfläche anzeigt. */
data class OfflineZustand(
    /** Grund der letzten gescheiterten Verbindung; null heisst „online“. */
    val grund: String? = null,
    /** Anzahl der Schreibzugriffe, die noch auf Übertragung warten. */
    val ausstehend: Int = 0,
    /** IDs, deren Stand noch nicht beim Server ist (lokale sind negativ). */
    val ausstehendeIds: Set<Long> = emptySet(),
) {
    val istOffline: Boolean get() = grund != null
}

/**
 * Legt sich über die Server-Quelle und hält die App bei einem
 * Verbindungsabbruch benutzbar.
 *
 * Lesen: Bei jedem Netzwerkfehler wird der zuletzt erfolgreiche Stand
 * gezeigt — dabei ist gleich, ob die Anfrage ankam, denn ein Lesevorgang
 * verändert nichts.
 *
 * Schreiben: In die Warteschlange darf eine Aktion **nur**, wenn sie den
 * Server nachweislich nie erreicht hat ([Netzfehler.NIE_GESENDET]). Bei einer
 * Zeitüberschreitung oder einem Abbruch mitten in der Übertragung könnte der
 * Server sie bereits ausgeführt haben; ein zweiter Versuch legte dann einen
 * zweiten Eintrag an. Solche Fälle melden wie bisher einen Fehler.
 *
 * Die Uhr benutzt diesen Umweg bewusst nicht: sie führt eine eigene Outbox
 * und würde denselben Eintrag sonst zweimal einreihen.
 */
class OfflineService(
    private val innen: EntryService,
    private val speicher: OfflineSpeicher,
) : EntryService {

    private val sperre = Mutex()
    private var warteschlange = speicher.ladeWarteschlange()

    private val zustandFlow = MutableStateFlow(
        OfflineZustand(
            ausstehend = warteschlange.anzahl,
            ausstehendeIds = warteschlange.ausstehendeIds,
        ),
    )

    /** Der Zustand für die Oberfläche. */
    val zustand: StateFlow<OfflineZustand> = zustandFlow

    override fun dispose() = innen.dispose()

    // ── Lesen ───────────────────────────────────────────────────────────────

    override suspend fun getEntries(): List<Entry> = try {
        val vomServer = innen.getEntries()
        speicher.speichereEintraege(vomServer)
        melde(grund = null)
        sperre.withLock { warteschlange.anwenden(vomServer) }
    } catch (fehler: Throwable) {
        val stand = speicher.ladeEintraege()
        if (Netzfehler.aus(fehler) == null || stand == null) throw fehler
        melde(grund = fehler.meldung())
        sperre.withLock { warteschlange.anwenden(stand) }
    }

    override suspend fun getToday(): TodayStats = try {
        val vomServer = innen.getToday()
        speicher.speichereStats(vomServer)
        melde(grund = null)
        sperre.withLock { warteschlange.anwenden(vomServer) }
    } catch (fehler: Throwable) {
        val stand = speicher.ladeStats()
        if (Netzfehler.aus(fehler) == null || stand == null) throw fehler
        melde(grund = fehler.meldung())
        sperre.withLock { warteschlange.anwenden(stand) }
    }

    // ── Schreiben ───────────────────────────────────────────────────────────

    override suspend fun createEntry(
        seite: Seite,
        menge: Int?,
        flaschenArt: FlaschenArt?,
        dauerMinuten: Int?,
        createTime: Instant?,
    ): Entry {
        // Offline steht der Erfassungszeitpunkt fest, sonst bekäme der Eintrag
        // beim Nachholen die Uhrzeit des Hochladens.
        val zeit = createTime ?: Instant.now()
        // Reihenfolge wahren: Steht schon etwas an, gehört auch das Neue
        // hinten dran, statt es am Stau vorbeizuschicken.
        if (!istLeer()) return reiheEin(seite, menge, flaschenArt, dauerMinuten, zeit)
        return try {
            innen.createEntry(seite, menge, flaschenArt, dauerMinuten, createTime)
                .also { melde(grund = null) }
        } catch (fehler: Throwable) {
            if (Netzfehler.aus(fehler) != Netzfehler.NIE_GESENDET) throw fehler
            melde(grund = fehler.meldung())
            reiheEin(seite, menge, flaschenArt, dauerMinuten, zeit)
        }
    }

    override suspend fun updateFlasche(id: Long, menge: Int, flaschenArt: FlaschenArt) =
        aendere(id, menge, flaschenArt, null) { innen.updateFlasche(id, menge, flaschenArt) }

    override suspend fun updateMenge(id: Long, menge: Int) =
        aendere(id, menge, null, null) { innen.updateMenge(id, menge) }

    override suspend fun updateDauer(id: Long, dauerMinuten: Int) =
        aendere(id, null, null, dauerMinuten) { innen.updateDauer(id, dauerMinuten) }

    override suspend fun deleteEntry(id: Long) {
        // Negative IDs kennt nur die App: der Eintrag wartet noch. Und solange
        // etwas ansteht, bleibt die Reihenfolge gewahrt.
        if (id < 0 || !istLeer()) {
            schreibe { it.loesche(id) }
            return
        }
        try {
            innen.deleteEntry(id)
            melde(grund = null)
        } catch (fehler: Throwable) {
            if (Netzfehler.aus(fehler) != Netzfehler.NIE_GESENDET) throw fehler
            melde(grund = fehler.meldung())
            schreibe { it.loesche(id) }
        }
    }

    private suspend fun aendere(
        id: Long,
        menge: Int?,
        flaschenArt: FlaschenArt?,
        dauerMinuten: Int?,
        direkt: suspend () -> Unit,
    ) {
        if (id < 0 || !istLeer()) {
            schreibe { it.aendere(id, menge, flaschenArt, dauerMinuten) }
            return
        }
        try {
            direkt()
            melde(grund = null)
        } catch (fehler: Throwable) {
            if (Netzfehler.aus(fehler) != Netzfehler.NIE_GESENDET) throw fehler
            melde(grund = fehler.meldung())
            schreibe { it.aendere(id, menge, flaschenArt, dauerMinuten) }
        }
    }

    // ── Nachholen ───────────────────────────────────────────────────────────

    /**
     * Arbeitet die Warteschlange von vorn ab.
     *
     * Bricht beim ersten Verbindungsfehler ab — der Rest bleibt in der
     * Reihenfolge stehen. Weist der Server eine Aktion inhaltlich zurück (etwa
     * ein längst gelöschter Eintrag), fliegt sie raus und wird gemeldet; sonst
     * blockierte sie die Warteschlange für immer.
     *
     * Liefert die Meldungen zu verworfenen Aktionen.
     */
    suspend fun nachholen(): List<String> {
        val verworfen = mutableListOf<String>()
        while (true) {
            val naechste = sperre.withLock { warteschlange.aktionen.firstOrNull() } ?: break
            try {
                sende(naechste)
                schreibe { it.entferneErste() }
            } catch (fehler: Throwable) {
                if (Netzfehler.aus(fehler) != null) {
                    melde(grund = fehler.meldung())
                    return verworfen
                }
                schreibe { it.entferneErste() }
                verworfen.add(fehler.meldung())
            }
        }
        melde(grund = null)
        return verworfen
    }

    private suspend fun sende(aktion: Warteaktion) = when (aktion) {
        is Warteaktion.Anlegen -> {
            innen.createEntry(
                seite = aktion.seite,
                menge = aktion.menge,
                flaschenArt = aktion.flaschenArt,
                dauerMinuten = aktion.dauerMinuten,
                createTime = aktion.createTime,
            )
            Unit
        }

        is Warteaktion.Aendern -> {
            val art = aktion.flaschenArt
            val menge = aktion.menge
            when {
                menge != null && art != null -> innen.updateFlasche(aktion.id, menge, art)
                menge != null -> innen.updateMenge(aktion.id, menge)
                aktion.dauerMinuten != null -> innen.updateDauer(aktion.id, aktion.dauerMinuten)
                else -> Unit
            }
        }

        is Warteaktion.Loeschen -> innen.deleteEntry(aktion.id)
    }

    // ── Innere Hilfen ───────────────────────────────────────────────────────

    private suspend fun istLeer(): Boolean = sperre.withLock { warteschlange.istLeer }

    private suspend fun reiheEin(
        seite: Seite,
        menge: Int?,
        flaschenArt: FlaschenArt?,
        dauerMinuten: Int?,
        createTime: Instant,
    ): Entry {
        var id = -1L
        schreibe { id = it.lege(seite, menge, flaschenArt, dauerMinuten, createTime) }
        return Entry(id, createTime, seite, menge, flaschenArt, dauerMinuten)
    }

    private suspend fun schreibe(aenderung: (Warteschlange) -> Unit) {
        val stand = sperre.withLock {
            aenderung(warteschlange)
            warteschlange
        }
        speicher.speichere(stand)
        zustandFlow.value = zustandFlow.value.copy(
            ausstehend = stand.anzahl,
            ausstehendeIds = stand.ausstehendeIds,
        )
    }

    private fun melde(grund: String?) {
        if (zustandFlow.value.grund != grund) {
            zustandFlow.value = zustandFlow.value.copy(grund = grund)
        }
    }
}

/** Lesbare Meldung einer Exception (ApiException liefert den Statuscode mit). */
fun Throwable.meldung(): String = when (this) {
    is ApiException -> toString()
    else -> message?.takeIf { it.isNotBlank() } ?: toString()
}

/**
 * Meldet, sobald wieder ein Netzwerk da ist — damit die Warteschlange nicht
 * erst beim nächsten Antippen abgearbeitet wird.
 */
class Verbindungswache(context: Context) {

    private val wiederVerbundenFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Feuert bei jedem Wechsel von „kein Netz“ zu „Netz da“. */
    val wiederVerbunden: SharedFlow<Unit> = wiederVerbundenFlow

    private val manager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private var warOffline = false

    private val rueckruf = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (warOffline) wiederVerbundenFlow.tryEmit(Unit)
            warOffline = false
        }

        override fun onLost(network: Network) {
            warOffline = true
        }
    }

    fun starten() {
        runCatching {
            manager?.registerNetworkCallback(NetworkRequest.Builder().build(), rueckruf)
        }
    }

    fun beenden() {
        runCatching { manager?.unregisterNetworkCallback(rueckruf) }
    }
}

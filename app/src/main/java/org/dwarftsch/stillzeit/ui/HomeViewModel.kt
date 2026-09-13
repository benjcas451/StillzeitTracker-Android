package org.dwarftsch.stillzeit.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.dwarftsch.stillzeit.Entry
import org.dwarftsch.stillzeit.FlaschenArt
import org.dwarftsch.stillzeit.Seite
import org.dwarftsch.stillzeit.TodayStats
import org.dwarftsch.stillzeit.data.AppSettings
import org.dwarftsch.stillzeit.data.CertSource
import org.dwarftsch.stillzeit.data.EntryService
import org.dwarftsch.stillzeit.data.OfflineService
import org.dwarftsch.stillzeit.data.Verbindungswache
import org.dwarftsch.stillzeit.data.createConfiguredEntryService
import org.dwarftsch.stillzeit.data.meldung
import org.dwarftsch.stillzeit.wear.WatchChangeBus
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class HomeUiState(
    val laedt: Boolean = true,
    val fehler: String? = null,
    val stats: TodayStats? = null,
    val eintraege: List<Entry> = emptyList(),
    /** Für die Schnell-Eingabe gewählte Uhrzeit; null = "Jetzt". */
    val schnellZeit: LocalTime? = null,
    /**
     * Server-Option „Brei & Wasser“ des aktiven Zugangs. Vor dem ersten
     * Netzwerk-Roundtrip aus dem Cache/Demo-Toggle geseedet.
     */
    val breiWasserAktiv: Boolean = false,
    /** Grund der abgebrochenen Verbindung; null heisst „online“. */
    val offlineGrund: String? = null,
    /** Anzahl der Schreibzugriffe, die noch auf Übertragung warten. */
    val ausstehend: Int = 0,
    /** IDs, deren Stand noch nicht beim Server ist – die Liste markiert sie. */
    val ausstehendeIds: Set<Long> = emptySet(),
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    val settings = AppSettings(application)
    val certSource = CertSource(application, settings)

    private val state = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = state

    private val meldungenFlow = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Snackbar-Meldungen (Fehler etc.). */
    val meldungen: SharedFlow<String> = meldungenFlow

    /** Zeigt einen Hinweis über denselben Snackbar-Kanal wie Fehler. */
    fun hinweis(text: String) {
        meldungenFlow.tryEmit(text)
    }

    /** Aktive Datenquelle: API (mTLS/API-Key/Cloudflare) oder lokale SQLite-DB. */
    private var service: EntryService? = null

    /** Beobachtet den Zustand der aktuellen Offline-Hülle; null im Demo. */
    private var zustandBeobachter: Job? = null

    private val wache = Verbindungswache(application)

    init {
        // Schreibzugriffe der Uhr lösen ein Neuladen aus.
        viewModelScope.launch {
            WatchChangeBus.aenderungen.drop(1).collect { aktualisieren() }
        }
        // Sobald wieder ein Netz da ist, die Warteschlange abarbeiten – ohne
        // dass der Nutzer etwas antippen muss.
        wache.starten()
        viewModelScope.launch {
            wache.wiederVerbunden.collect { aktualisieren() }
        }
        datenquelleNeuAufbauen()
    }

    override fun onCleared() {
        wache.beenden()
        service?.dispose()
        super.onCleared()
    }

    /**
     * Baut die Datenquelle anhand der Einstellung neu auf (z. B. nach dem
     * Verlassen der Einstellungen) und lädt anschließend neu.
     */
    fun datenquelleNeuAufbauen() {
        service?.dispose()
        zustandBeobachter?.cancel()
        val neu = createConfiguredEntryService(
            getApplication(), settings, certSource, offlineFaehig = true,
        )
        service = neu
        // Buttons sofort korrekt zeigen, bevor die erste Antwort da ist. Der
        // Offline-Hinweis des alten Zugangs darf dabei nicht stehen bleiben.
        state.value = state.value.copy(
            breiWasserAktiv = settings.breiWasserAktivFuerAktuellenZugang(),
            offlineGrund = null,
            ausstehend = 0,
            ausstehendeIds = emptySet(),
        )
        if (neu is OfflineService) {
            zustandBeobachter = viewModelScope.launch {
                neu.zustand.collect { zustand ->
                    state.value = state.value.copy(
                        offlineGrund = zustand.grund,
                        ausstehend = zustand.ausstehend,
                        ausstehendeIds = zustand.ausstehendeIds,
                    )
                }
            }
        }
        aktualisieren()
    }

    fun aktualisieren() {
        val aktiverService = service ?: return
        state.value = state.value.copy(laedt = true, fehler = null)
        viewModelScope.launch {
            // Erst das Liegengebliebene loswerden, dann laden: sonst zeigte die
            // Liste einen Serverstand ohne die eigenen Einträge.
            warteschlangeAbarbeiten(aktiverService)
            runCatching {
                coroutineScope {
                    val stats = async { aktiverService.getToday() }
                    val eintraege = async { aktiverService.getEntries() }
                    stats.await() to eintraege.await()
                }
            }.fold(
                onSuccess = { (stats, eintraege) ->
                    // Server-Stand nur noch merken (Hinweis in den
                    // Einstellungen) – über die Sichtbarkeit entscheidet
                    // allein das lokale Opt-in.
                    settings.merkeBreiWasserAktiv(stats.breiWasserAktiv)
                    val sichtbar = settings.breiWasserAktiviert
                    state.value = state.value.copy(
                        laedt = false,
                        stats = stats.copy(breiWasserAktiv = sichtbar),
                        // Nach Zeitpunkt sortiert: die Tagesüberschriften der
                        // Liste setzen eine chronologische Reihenfolge voraus.
                        // Doppelte IDs (fehlerhafte Serverantwort) fliegen
                        // raus – LazyColumn verlangt eindeutige Schlüssel.
                        eintraege = eintraege
                            .distinctBy { it.id }
                            .sortedByDescending { it.createTime },
                        breiWasserAktiv = sichtbar,
                    )
                },
                onFailure = { fehler ->
                    state.value = state.value.copy(laedt = false, fehler = fehler.meldung())
                },
            )
        }
    }

    /**
     * Schickt die offenen Schreibzugriffe zum Server. Verworfene Aktionen
     * (vom Server inhaltlich zurückgewiesen) meldet sie einmal gesammelt.
     */
    private suspend fun warteschlangeAbarbeiten(dienst: EntryService) {
        if (dienst !is OfflineService) return
        val verworfen = dienst.nachholen()
        if (verworfen.isEmpty()) return
        meldungenFlow.tryEmit(
            if (verworfen.size == 1) {
                "Eine wartende Änderung wurde vom Server abgelehnt: ${verworfen.first()}"
            } else {
                "${verworfen.size} wartende Änderungen wurden vom Server abgelehnt."
            },
        )
    }

    fun setzeSchnellZeit(zeit: LocalTime?) {
        state.value = state.value.copy(schnellZeit = zeit)
    }

    /** Gewählte Uhrzeit als heutiger Zeitpunkt, oder null für "Jetzt". */
    private fun schnellZeitpunkt(): Instant? = state.value.schnellZeit?.let { zeit ->
        LocalDate.now().atTime(zeit).atZone(ZoneId.systemDefault()).toInstant()
    }

    fun anlegen(seite: Seite, menge: Int? = null, flaschenArt: FlaschenArt? = null, dauerMinuten: Int? = null) {
        fuehreAktionAus {
            it.createEntry(
                seite = seite,
                menge = menge,
                flaschenArt = flaschenArt,
                dauerMinuten = dauerMinuten,
                createTime = schnellZeitpunkt(),
            )
        }
    }

    fun flascheAendern(eintrag: Entry, menge: Int, flaschenArt: FlaschenArt) {
        fuehreAktionAus { it.updateFlasche(eintrag.id, menge, flaschenArt) }
    }

    fun mengeAendern(eintrag: Entry, menge: Int) {
        fuehreAktionAus { it.updateMenge(eintrag.id, menge) }
    }

    fun dauerAendern(eintrag: Entry, dauerMinuten: Int) {
        fuehreAktionAus { it.updateDauer(eintrag.id, dauerMinuten) }
    }

    fun loeschen(eintrag: Entry) {
        fuehreAktionAus { it.deleteEntry(eintrag.id) }
    }

    /**
     * Führt eine schreibende Aktion aus und lädt danach neu. Eine gewählte
     * Schnell-Eingabe-Zeit wird danach auf "Jetzt" zurückgesetzt.
     */
    private fun fuehreAktionAus(aktion: suspend (EntryService) -> Any?) {
        val aktiverService = service ?: return
        viewModelScope.launch {
            runCatching { aktion(aktiverService) }.fold(
                onSuccess = {
                    if (state.value.schnellZeit != null) {
                        state.value = state.value.copy(schnellZeit = null)
                    }
                    aktualisieren()
                },
                onFailure = { meldungenFlow.tryEmit("Fehler: ${it.meldung()}") },
            )
        }
    }
}

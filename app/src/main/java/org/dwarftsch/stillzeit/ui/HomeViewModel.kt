package org.dwarftsch.stillzeit.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import org.dwarftsch.stillzeit.data.createConfiguredEntryService
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

    /** Aktive Datenquelle: API (mTLS/API-Key) oder lokale SQLite-DB. */
    private var service: EntryService? = null

    init {
        // Schreibzugriffe der Uhr lösen ein Neuladen aus.
        viewModelScope.launch {
            WatchChangeBus.aenderungen.drop(1).collect { aktualisieren() }
        }
        datenquelleNeuAufbauen()
    }

    override fun onCleared() {
        service?.dispose()
        super.onCleared()
    }

    /**
     * Baut die Datenquelle anhand der Einstellung neu auf (z. B. nach dem
     * Verlassen der Einstellungen) und lädt anschließend neu.
     */
    fun datenquelleNeuAufbauen() {
        service?.dispose()
        service = createConfiguredEntryService(getApplication(), settings, certSource)
        // Buttons sofort korrekt zeigen, bevor die erste Antwort da ist.
        state.value = state.value.copy(
            breiWasserAktiv = settings.breiWasserAktivFuerAktuellenZugang(),
        )
        aktualisieren()
    }

    fun aktualisieren() {
        val aktiverService = service ?: return
        state.value = state.value.copy(laedt = true, fehler = null)
        viewModelScope.launch {
            runCatching {
                coroutineScope {
                    val stats = async { aktiverService.getToday() }
                    val eintraege = async { aktiverService.getEntries() }
                    stats.await() to eintraege.await()
                }
            }.fold(
                onSuccess = { (stats, eintraege) ->
                    settings.merkeBreiWasserAktiv(stats.breiWasserAktiv)
                    // Sichtbar nur, wenn auch das lokale Opt-in aktiv ist –
                    // die Server-Option allein blendet nichts ein.
                    val sichtbar = stats.breiWasserAktiv && settings.breiWasserAktiviert
                    state.value = state.value.copy(
                        laedt = false,
                        stats = stats.copy(breiWasserAktiv = sichtbar),
                        eintraege = eintraege,
                        breiWasserAktiv = sichtbar,
                    )
                },
                onFailure = { fehler ->
                    state.value = state.value.copy(laedt = false, fehler = fehler.meldung())
                },
            )
        }
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

/** Lesbare Meldung einer Exception (ApiException liefert Statuscode mit). */
internal fun Throwable.meldung(): String = when (this) {
    is org.dwarftsch.stillzeit.data.ApiException -> toString()
    else -> message ?: toString()
}

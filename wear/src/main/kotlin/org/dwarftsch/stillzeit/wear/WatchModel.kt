package org.dwarftsch.stillzeit.wear

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * Zustand der Uhr-Oberfläche und Wegewahl zwischen den zwei Betriebsarten:
 *
 * - **Direkt**: Ist die Server-Verbindung des Telefons übernommen, spricht die
 *   Uhr die REST-API selbst an. Scheitert schon der Verbindungsaufbau, wird
 *   automatisch über das Telefon ausgewichen.
 * - **Über das Telefon**: Standard, solange nichts übernommen wurde — die
 *   Telefon-App führt die Anfrage auf der dort gewählten Datenquelle aus.
 */
class WatchModel(context: Context) {

    private val phone = PhoneConnection(context)
    private val store = ServerConnectionStore(context)
    private val hintergrund = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    var eintraege by mutableStateOf<List<WatchEntry>>(emptyList())
        private set

    var laedt by mutableStateOf(false)
        private set

    var fehler by mutableStateOf<String?>(null)
        private set

    /** Kurzer Hinweis unterhalb der Liste (Import, Ausweichen aufs Telefon). */
    var hinweis by mutableStateOf<String?>(null)
        private set

    /** Übernommene Server-Verbindung; null = alles läuft über das Telefon. */
    var verbindung by mutableStateOf<ServerConnection?>(null)
        private set

    val letzterEintrag: WatchEntry? get() = eintraege.firstOrNull()

    /** Statuszeile: „Direkt · API-Key“, „Direkt · mTLS“ oder „Über Handy“. */
    val statusText: String get() = verbindung?.beschreibung ?: "Über Handy"

    init {
        verbindung = store.laden()
    }

    fun schliessen() {
        hintergrund.shutdown()
    }

    // --- Aktionen ------------------------------------------------------------

    fun aktualisieren() = ausfuehren(Aktion.Laden)

    /** Legt einen Eintrag an; [zeit] null bedeutet „Jetzt“. */
    fun anlegen(
        seite: String,
        zeit: LocalTime?,
        menge: Int? = null,
        flaschenArt: String? = null,
    ) = ausfuehren(Aktion.Anlegen(seite, zeit, menge, flaschenArt))

    /** Ändert Menge/Flaschenart bzw. die Stilldauer eines bestehenden Eintrags. */
    fun aendern(eintrag: WatchEntry, wert: Int, flaschenArt: String? = null) =
        ausfuehren(Aktion.Aendern(eintrag, wert, flaschenArt))

    // --- Verbindung übernehmen ----------------------------------------------

    /**
     * Holt die auf dem Telefon eingerichtete Server-Verbindung und speichert
     * sie auf der Uhr. Danach laufen alle Anfragen direkt.
     */
    fun verbindungImportieren() {
        laedt = true
        fehler = null
        hinweis = null
        phone.request(
            action = "getConnection",
            arguments = null,
            onSuccess = ::uebernehmen,
            onError = { meldung ->
                laedt = false
                fehler = meldung
            },
        )
    }

    /** Verwirft die übernommene Verbindung; alles läuft wieder über das Telefon. */
    fun verbindungEntfernen() {
        store.loeschen()
        verbindung = null
        fehler = null
        aktualisieren()
    }

    private fun uebernehmen(daten: JSONObject) {
        val neu = ServerConnection.ausAntwort(daten)
        if (neu == null) {
            laedt = false
            fehler = "Auf dem Handy ist keine Server-Verbindung eingerichtet."
            return
        }
        if (!neu.istMtls) {
            speichern(neu)
            return
        }
        // Das Client-Zertifikat sofort prüfen: ein unbrauchbarer Schlüssel soll
        // beim Import auffallen, nicht erst beim Speichern eines Eintrags.
        hintergrund.execute {
            val ergebnis = runCatching {
                ClientCertificates.socketFactory(neu.clientCertPem!!, neu.clientKeyPem!!)
            }
            main.post {
                ergebnis.fold(
                    onSuccess = { speichern(neu) },
                    onFailure = { fehlgeschlagen ->
                        laedt = false
                        fehler = fehlgeschlagen.message ?: "Client-Zertifikat unbrauchbar."
                    },
                )
            }
        }
    }

    private fun speichern(neu: ServerConnection) {
        store.speichern(neu)
        verbindung = neu
        fehler = null
        // Eine eigene Erfolgsmeldung erübrigt sich: die Statuszeile zeigt ab
        // jetzt „Direkt · …“ statt „Über Handy“.
        aktualisieren()
    }

    // --- Wegewahl ------------------------------------------------------------

    private sealed interface Aktion {
        data object Laden : Aktion

        data class Anlegen(
            val seite: String,
            val zeit: LocalTime?,
            val menge: Int?,
            val flaschenArt: String?,
        ) : Aktion

        data class Aendern(
            val eintrag: WatchEntry,
            val wert: Int,
            val flaschenArt: String?,
        ) : Aktion
    }

    private fun ausfuehren(aktion: Aktion) {
        laedt = true
        fehler = null
        val aktuelle = verbindung
        if (aktuelle == null) {
            ueberTelefon(aktion)
        } else {
            direkt(aktion, aktuelle)
        }
    }

    private fun direkt(aktion: Aktion, verbindung: ServerConnection) {
        hintergrund.execute {
            val api = DirectApi(verbindung)
            try {
                when (aktion) {
                    Aktion.Laden -> {
                        val liste = api.eintraege()
                        main.post { fertig(liste) }
                    }

                    is Aktion.Anlegen -> {
                        api.anlegen(
                            seite = aktion.seite,
                            menge = aktion.menge,
                            flaschenArt = aktion.flaschenArt,
                            dauerMinuten = null,
                            zeitstempel = aktion.zeit?.let(::zeitstempel),
                        )
                        // Bewusst als eigener Vorgang: schlägt das Nachladen
                        // fehl, darf keinesfalls der Eintrag erneut entstehen.
                        main.post { ausfuehren(Aktion.Laden) }
                    }

                    is Aktion.Aendern -> {
                        api.aendern(
                            id = aktion.eintrag.id,
                            seite = aktion.eintrag.seite,
                            wert = aktion.wert,
                            flaschenArt = aktion.flaschenArt ?: aktion.eintrag.flaschenArt,
                        )
                        main.post { ausfuehren(Aktion.Laden) }
                    }
                }
            } catch (_: VerbindungsFehler) {
                // Der Server war gar nicht erreichbar — nichts wurde gesendet,
                // also ist der Umweg über das Telefon gefahrlos.
                main.post {
                    ueberTelefon(aktion, "Server nicht direkt erreichbar — über das Handy erledigt.")
                }
            } catch (fehlgeschlagen: Exception) {
                main.post { fehlschlag(fehlgeschlagen.message ?: "Unerwarteter Fehler") }
            }
        }
    }

    private fun ueberTelefon(aktion: Aktion, hinweisText: String? = null) {
        laedt = true
        phone.request(
            action = aktion.relayAction,
            arguments = aktion.relayArgumente(),
            onSuccess = { daten ->
                fertig(WatchEntry.listeAusJson(daten.optJSONArray("entries")), hinweisText)
            },
            onError = ::fehlschlag,
        )
    }

    private val Aktion.relayAction: String
        get() = when (this) {
            Aktion.Laden -> "getDashboard"
            is Aktion.Anlegen -> "createEntry"
            is Aktion.Aendern -> "updateEntry"
        }

    private fun Aktion.relayArgumente(): JSONObject? = when (this) {
        Aktion.Laden -> null

        is Aktion.Anlegen -> JSONObject().apply {
            put("seite", seite)
            menge?.let { put("menge", it) }
            flaschenArt?.let { put("flaschen_art", it) }
            zeit?.let { put("create_time", zeitstempel(it)) }
        }

        is Aktion.Aendern -> JSONObject().apply {
            put("id", eintrag.id)
            put("seite", eintrag.seite)
            if (eintrag.istFlasche) {
                put("menge", wert)
                put("flaschen_art", flaschenArt ?: eintrag.flaschenArt ?: FlaschenArt.PRE)
            } else {
                put("dauer_minuten", wert)
            }
        }
    }

    private fun fertig(liste: List<WatchEntry>, hinweisText: String? = null) {
        laedt = false
        fehler = null
        hinweis = hinweisText
        eintraege = liste
    }

    private fun fehlschlag(meldung: String) {
        laedt = false
        fehler = meldung
    }

    /**
     * Eine auf der Uhr gewählte Uhrzeit meint immer den heutigen Tag. Der
     * Offset wird mitgeschickt, damit Telefon und Server den Zeitpunkt
     * eindeutig einordnen können.
     */
    private fun zeitstempel(zeit: LocalTime): String =
        LocalDate.now()
            .atTime(zeit.withSecond(0).withNano(0))
            .atZone(ZoneId.systemDefault())
            .format(ZEITSTEMPEL_FORMAT)

    private companion object {
        val ZEITSTEMPEL_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
    }
}

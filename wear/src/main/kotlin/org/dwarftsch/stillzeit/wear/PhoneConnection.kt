package org.dwarftsch.stillzeit.wear

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject

/**
 * Transport zur Handy-App: schickt eine Anfrage per Data-Layer-RPC und liefert
 * die ausgepackte Antwort zurück.
 *
 * Die Uhr besitzt bewusst keine eigene API-Konfiguration und keine Datenbank —
 * genau wie die Apple-Watch-Variante. Lesen und Schreiben laufen immer über die
 * Handy-App, die dabei die dort gewählte Datenquelle verwendet.
 */
class PhoneConnection(context: Context) {

    private val context = context.applicationContext

    /**
     * Ruft [action] auf der Handy-App auf. Callbacks laufen auf dem Main-Thread,
     * da die Play-Services-Tasks standardmäßig dorthin zurückrufen.
     */
    fun request(
        action: String,
        arguments: JSONObject?,
        onSuccess: (JSONObject) -> Unit,
        onError: (String) -> Unit,
    ) {
        val payload = JSONObject().put("action", action)
        if (arguments != null) payload.put("arguments", arguments)
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)

        Wearable.getCapabilityClient(context)
            .getCapability(PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener { info ->
                // Bevorzugt das direkt gekoppelte Handy; sonst irgendeinen Knoten,
                // der die App gemeldet hat.
                val node = info.nodes.firstOrNull { it.isNearby } ?: info.nodes.firstOrNull()
                if (node == null) {
                    onError(NICHT_ERREICHBAR)
                    return@addOnSuccessListener
                }
                Wearable.getMessageClient(context)
                    .sendRequest(node.id, REQUEST_PATH, bytes)
                    .addOnSuccessListener { antwort -> auswerten(antwort, onSuccess, onError) }
                    .addOnFailureListener { onError(NICHT_ERREICHBAR) }
            }
            .addOnFailureListener { onError(NICHT_ERREICHBAR) }
    }

    private fun auswerten(
        antwort: ByteArray,
        onSuccess: (JSONObject) -> Unit,
        onError: (String) -> Unit,
    ) {
        val json = runCatching { JSONObject(String(antwort, Charsets.UTF_8)) }.getOrNull()
        if (json == null) {
            onError("Ungültige Antwort der Handy-App.")
            return
        }
        if (!json.optBoolean("ok")) {
            onError(json.optString("error").takeIf { it.isNotEmpty() } ?: "Unbekannter Fehler")
            return
        }
        onSuccess(json.optJSONObject("data") ?: JSONObject())
    }

    companion object {
        /**
         * Muss mit `android_wear_capabilities` in der Handy-App
         * (android/app/src/main/res/values/wear.xml) übereinstimmen.
         */
        const val PHONE_CAPABILITY = "stillzeit_phone_app"

        /**
         * Muss zum `pathPrefix` des Intent-Filters von `WearRequestService`
         * in der Handy-App passen.
         */
        const val REQUEST_PATH = "/stillzeit/request"

        private const val NICHT_ERREICHBAR =
            "Handy nicht erreichbar. Bitte Stillzeit dort kurz öffnen."
    }
}

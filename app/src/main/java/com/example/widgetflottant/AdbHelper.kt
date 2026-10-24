package com.example.widgetflottant

import com.flyfishxu.kadb.Kadb
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Parle au « Débogage sans fil » de l'appareil lui-même (association + commande shell),
 * pour accorder à l'appli le droit de lier des widgets, sans ordinateur.
 */
object AdbHelper {

    /** Adresse IP Wi-Fi de l'appareil (celle affichée dans « Adresse IP et port » du Débogage sans fil). */
    fun localIp(): String {
        return try {
            val addresses = NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .filter { it is Inet4Address && !it.isLoopbackAddress }
            addresses.firstOrNull()?.hostAddress ?: "127.0.0.1"
        } catch (e: Throwable) {
            "127.0.0.1"
        }
    }

    /** Associe l'appli à l'appareil avec le code à 6 chiffres. Retourne null si tout va bien, sinon un message. */
    fun pair(host: String, port: Int, code: String): String? {
        return try {
            val result: Any? = Kadb.pair(host, port, code)
            if (result == false) {
                "Association refusée : code ou port incorrect, ou fenêtre d'association fermée."
            } else {
                null
            }
        } catch (e: Throwable) {
            "Association impossible : ${describe(e)}"
        }
    }

    /** Se connecte au port du Débogage sans fil et accorde le droit de lier des widgets. Null si OK. */
    fun grantBind(host: String, port: Int, pkg: String): String? {
        return try {
            Kadb.create(host, port).use { kadb ->
                val response = kadb.shell("cmd appwidget grantbind --package $pkg")
                if (response.exitCode == 0) {
                    null
                } else {
                    "La commande a échoué (code ${response.exitCode}) : ${response.output.trim()}"
                }
            }
        } catch (e: Throwable) {
            "Connexion impossible : ${describe(e)}"
        }
    }

    private fun describe(e: Throwable): String {
        val msg = e.message?.takeIf { it.isNotBlank() }
        return if (msg != null) "${e.javaClass.simpleName} — $msg" else e.javaClass.simpleName
    }
}

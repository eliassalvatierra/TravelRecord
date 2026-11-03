package com.tp.viajeinteligente

import android.content.Context
import android.os.Build

/**
 * Registro liviano de lo que ve el servicio de accesibilidad, para poder revisarlo desde la app
 * (sin Logcat) y copiar el informe cuando algo no se detecta.
 */
object Diagnostico {
    private const val PREFS = "diagnostico"
    private var ultimoEventoMs = 0L
    private var ultimoEventoUtilMs = 0L

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun ahora() = System.currentTimeMillis()

    fun conectado(c: Context) {
        p(c).edit().putLong("conectado_ms", ahora()).apply()
    }

    /** Cualquier evento recibido (limitado a 1 por segundo). */
    fun evento(c: Context, pkg: String, util: Boolean) {
        val t = ahora()
        if (t - ultimoEventoMs >= 1000) {
            ultimoEventoMs = t
            val prefs = p(c)
            val lista = prefs.getString("pkgs", "")!!.split(",").filter { it.isNotBlank() && it != pkg }.toMutableList()
            lista.add(0, pkg)
            prefs.edit().putLong("evento_ms", t).putString("pkgs", lista.take(8).joinToString(",")).apply()
        }
        if (util && t - ultimoEventoUtilMs >= 1000) {
            ultimoEventoUtilMs = t
            p(c).edit().putLong("util_ms", t).putString("util_pkg", pkg).apply()
        }
    }

    fun mensaje(c: Context, msg: String) {
        p(c).edit().putString("diag", msg).putLong("diag_ms", ahora()).apply()
    }

    fun oferta(c: Context, resumen: String) {
        p(c).edit().putString("oferta", resumen).putLong("oferta_ms", ahora()).apply()
    }

    fun volcado(c: Context, origen: String, lineas: List<String>) {
        p(c).edit()
            .putString("volcado_origen", origen)
            .putLong("volcado_ms", ahora())
            .putString("volcado", lineas.take(60).mapIndexed { i, l -> "[$i] $l" }.joinToString("\n"))
            .apply()
    }

    private fun hace(ms: Long): String {
        if (ms <= 0) return "nunca"
        val s = (ahora() - ms) / 1000
        return when {
            s < 60 -> "hace $s s"
            s < 3600 -> "hace ${s / 60} min"
            else -> "hace ${s / 3600} h"
        }
    }

    fun informe(c: Context, permisoOverlay: Boolean, accesibilidadHabilitada: Boolean): String {
        val pr = p(c)
        val sb = StringBuilder()
        sb.appendLine("Equipo: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("Permiso de superposición: ${if (permisoOverlay) "sí" else "NO"}")
        sb.appendLine("Accesibilidad habilitada en Ajustes: ${if (accesibilidadHabilitada) "sí" else "NO"}")
        sb.appendLine("Servicio conectado: ${hace(pr.getLong("conectado_ms", 0))}")
        sb.appendLine("Ventana flotante en ejecución: ${if (OverlayService.activo) "sí" else "NO"}")
        sb.appendLine("Último evento recibido: ${hace(pr.getLong("evento_ms", 0))}")
        sb.appendLine("Último evento de una app útil: ${hace(pr.getLong("util_ms", 0))} · ${pr.getString("util_pkg", "-")}")
        sb.appendLine("Paquetes vistos (recientes): ${pr.getString("pkgs", "-")}")
        sb.appendLine("Último estado de lectura: ${pr.getString("diag", "-")} (${hace(pr.getLong("diag_ms", 0))})")
        sb.appendLine("Última oferta detectada: ${pr.getString("oferta", "ninguna")} (${hace(pr.getLong("oferta_ms", 0))})")
        val v = pr.getString("volcado", null)
        if (v != null) {
            sb.appendLine()
            sb.appendLine("Último texto leído sin éxito (${pr.getString("volcado_origen", "-")}, ${hace(pr.getLong("volcado_ms", 0))}):")
            sb.append(v)
        }
        return sb.toString()
    }
}

package com.tp.viajeinteligente

import android.content.Context
import android.content.SharedPreferences

/**
 * Métrica que el conductor eligió usar para decidir si un viaje conviene.
 */
enum class Metrica {
    POR_KM,
    POR_HORA,
    AMBAS
}

/**
 * Resultado del semáforo: decide si conviene aceptar, es dudoso, o conviene rechazar.
 */
enum class Decision {
    ACEPTAR,
    DUDOSO,
    RECHAZAR
}

/**
 * Guarda y lee la configuración del conductor (costos del auto y umbrales de rentabilidad)
 * usando SharedPreferences, para que persista entre usos de la app.
 */
class Configuracion(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("viaje_inteligente_prefs", Context.MODE_PRIVATE)

    // --- Costos del vehículo ---

    /** Cuántos km hace el auto por cada litro de combustible. */
    var consumoKmPorLitro: Double
        get() = prefs.getFloat(KEY_CONSUMO, 12f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_CONSUMO, value.toFloat()).apply()

    /** Precio actual del litro de combustible, en pesos. */
    var precioCombustible: Double
        get() = prefs.getFloat(KEY_PRECIO_COMB, 1250f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_PRECIO_COMB, value.toFloat()).apply()

    /** Si se debe incluir el tramo desde tu ubicación hasta buscar al pasajero en el cálculo. */
    var incluirTramoHastaPasajero: Boolean
        get() = prefs.getBoolean(KEY_INCLUIR_TRAMO, true)
        set(value) = prefs.edit().putBoolean(KEY_INCLUIR_TRAMO, value).apply()

    // --- Umbrales de decisión ---

    /** $/km por encima del cual el viaje se considera "aceptar" (verde). */
    var umbralVerdeKm: Double
        get() = prefs.getFloat(KEY_UMBRAL_VERDE_KM, 500f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_UMBRAL_VERDE_KM, value.toFloat()).apply()

    /** $/km por debajo del cual el viaje se considera "rechazar" (rojo). Entre medio es "dudoso". */
    var umbralRojoKm: Double
        get() = prefs.getFloat(KEY_UMBRAL_ROJO_KM, 300f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_UMBRAL_ROJO_KM, value.toFloat()).apply()

    /** $/hora por encima del cual el viaje se considera "aceptar" (verde). */
    var umbralVerdeHora: Double
        get() = prefs.getFloat(KEY_UMBRAL_VERDE_HORA, 6000f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_UMBRAL_VERDE_HORA, value.toFloat()).apply()

    /** $/hora por debajo del cual el viaje se considera "rechazar" (rojo). */
    var umbralRojoHora: Double
        get() = prefs.getFloat(KEY_UMBRAL_ROJO_HORA, 3500f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_UMBRAL_ROJO_HORA, value.toFloat()).apply()

    /** Métrica elegida para decidir: $/km, $/hora, o ambas a la vez. */
    var metrica: Metrica
        get() = Metrica.valueOf(prefs.getString(KEY_METRICA, Metrica.AMBAS.name) ?: Metrica.AMBAS.name)
        set(value) = prefs.edit().putString(KEY_METRICA, value.name).apply()

    /** Modo diagnóstico: la ventana flotante queda visible siempre (si no, solo con viaje o Centro de viajes). */
    var mostrarSiempre: Boolean
        get() = prefs.getBoolean(KEY_MOSTRAR_SIEMPRE, false)
        set(value) = prefs.edit().putBoolean(KEY_MOSTRAR_SIEMPRE, value).apply()

    /** Suma de km (hasta el pasajero + viaje) de los viajes aceptados. Se reinicia a mano. */
    var kmAceptados: Double
        get() = prefs.getFloat(KEY_KM_ACEPTADOS, 0f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_KM_ACEPTADOS, value.toFloat()).apply()

    companion object {
        private const val KEY_KM_ACEPTADOS = "km_aceptados"
        private const val KEY_MOSTRAR_SIEMPRE = "mostrar_siempre"
        private const val KEY_CONSUMO = "consumo_km_litro"
        private const val KEY_PRECIO_COMB = "precio_combustible"
        private const val KEY_INCLUIR_TRAMO = "incluir_tramo_pasajero"
        private const val KEY_UMBRAL_VERDE_KM = "umbral_verde_km"
        private const val KEY_UMBRAL_ROJO_KM = "umbral_rojo_km"
        private const val KEY_UMBRAL_VERDE_HORA = "umbral_verde_hora"
        private const val KEY_UMBRAL_ROJO_HORA = "umbral_rojo_hora"
        private const val KEY_METRICA = "metrica"
    }
}

package com.tp.viajeinteligente

data class DatosViaje(val pago: Double, val km: Double)
data class ResultadoViaje(val pesosPorKm: Double, val decision: Decision)

/** Decide solo por $/km: verde si es igual o mayor al umbral verde, rojo si es igual o menor al rojo. */
object CalculadoraViaje {
    fun calcular(datos: DatosViaje, config: Configuracion): ResultadoViaje {
        val porKm = if (datos.km > 0) datos.pago / datos.km else 0.0
        val decision = when {
            porKm >= config.umbralVerdeKm -> Decision.ACEPTAR
            porKm <= config.umbralRojoKm -> Decision.RECHAZAR
            else -> Decision.DUDOSO
        }
        return ResultadoViaje(porKm, decision)
    }
}

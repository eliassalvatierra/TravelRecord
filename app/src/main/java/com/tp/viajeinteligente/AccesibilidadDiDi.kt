package com.tp.viajeinteligente

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale

/**
 * Lee la oferta de DiDi Conductor.
 * 1) Intenta con el árbol de accesibilidad (texto real de las vistas).
 * 2) Si DiDi no expone el texto (tarjeta dibujada a mano / vistas ocultas), saca una captura
 *    con AccessibilityService.takeScreenshot (Android 11+) y aplica OCR local con ML Kit.
 */
class AccesibilidadDiDi : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private var lecturaProgramada = false
    private var ocrEnCurso = false
    private var ultimoOcrMs = 0L
    private var ultimoDumpMs = 0L
    private var ultimoDiag = ""
    private var ultimaDeteccionMs = 0L
    private var ultimoPkg = ""

    private var ultimoPago = 0.0
    private var ultimoKm = 0.0
    private var ultimoMin = 0.0
    private var ultimoTiempoMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Servicio conectado. SDK=${Build.VERSION.SDK_INT}")
        Diagnostico.conectado(this)
        diag("Servicio de accesibilidad conectado")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg != packageName) Diagnostico.evento(this, pkg, !esIgnorado(pkg))
        if (esIgnorado(pkg)) return

        // Toque en "Aceptar por $…" poco después de leer una oferta: se suma al total de km aceptados
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED &&
            System.currentTimeMillis() - ultimaDeteccionMs < 25_000
        ) {
            val t = buildString {
                event.text?.forEach { append(it).append(' ') }
                event.contentDescription?.let { append(it).append(' ') }
                event.source?.text?.let { append(it) }
            }.lowercase(Locale.ROOT)
            if (t.contains("aceptar")) {
                sendBroadcast(Intent(ACTION_ACEPTADO).setPackage(packageName))
            }
        }

        if (pkg != ultimoPkg) {
            ultimoPkg = pkg
            Log.d(TAG, "Paquete en pantalla: $pkg")
        }
        programarLectura()
    }

    /** Paquetes que nunca contienen ofertas: nuestra app, sistema, teclado, launcher. */
    private fun esIgnorado(pkg: String): Boolean {
        if (pkg == packageName) return true
        val p = pkg.lowercase(Locale.ROOT)
        return p == "android" || p.startsWith("com.android.systemui") || p.startsWith("com.android.settings") ||
                p.contains("launcher") || p.contains("inputmethod") || p.contains("keyboard") ||
                p.startsWith("com.sec.android.app.launcher") || p.startsWith("com.samsung.android.honeyboard")
    }

    /** Agrupa ráfagas de eventos: una lectura cada ~200 ms como máximo. */
    private fun programarLectura() {
        if (lecturaProgramada) return
        lecturaProgramada = true
        mainHandler.postDelayed({
            lecturaProgramada = false
            leerPantalla()
        }, 200)
    }

    private fun leerPantalla() {
        val lineas = mutableListOf<String>()
        var nodos = 0
        contadorNodos = 0
        val raices = mutableListOf<AccessibilityNodeInfo>()

        try {
            windows?.forEach { w -> w.root?.let { raices.add(it) } }
        } catch (e: Exception) {
            Log.e(TAG, "Error leyendo ventanas", e)
        }
        rootInActiveWindow?.let { raices.add(it) }

        var pkgLeido = ""
        for (root in raices) {
            val p = root.packageName?.toString() ?: continue
            if (esIgnorado(p)) continue
            val antes = lineas.size
            nodos += recorrer(root, lineas)
            if (lineas.size > antes && pkgLeido.isEmpty()) pkgLeido = p
        }

        val textos = lineas.distinct()
        val oferta = if (textos.isEmpty()) null else parsear(textos)
        if (oferta != null) {
            publicar(oferta, "árbol")
            return
        }

        if (esCentroDeViajes(textos)) {
            publicarCentro()
            return
        }

        diag("Pkg: $ultimoPkg · ${textos.size} textos, sin oferta → OCR")
        if (textos.isNotEmpty()) volcarLog("ARBOL", textos)
        intentarOcr()
        reintentarSiActivo()
    }

    private fun esCentroDeViajes(lineas: List<String>): Boolean =
        lineas.any { it.lowercase(Locale.ROOT).contains("centro de viaje") }

    /**
     * Mientras hubo una detección reciente, vuelve a leer cada ~1,3 s aunque DiDi no mande eventos
     * (el contador de la oferta es una animación y no siempre genera eventos de accesibilidad).
     */
    private fun reintentarSiActivo() {
        if (System.currentTimeMillis() - ultimaDeteccionMs < 4000) {
            mainHandler.postDelayed({ programarLectura() }, 1300)
        }
    }

    private var contadorNodos = 0

    private fun recorrer(node: AccessibilityNodeInfo?, out: MutableList<String>, prof: Int = 0): Int {
        if (node == null || prof > 40 || contadorNodos++ > 2500) return 0
        var n = 1
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        for (i in 0 until node.childCount) {
            n += recorrer(node.getChild(i), out, prof + 1)
        }
        return n
    }

    // ---------------------------------------------------------------- OCR

    private fun intentarOcr() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            diag("OCR requiere Android 11+")
            return
        }
        val ahora = System.currentTimeMillis()
        if (ocrEnCurso || ahora - ultimoOcrMs < 1200) return
        ocrEnCurso = true
        ultimoOcrMs = ahora

        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                ContextCompat.getMainExecutor(this),
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        try {
                            val hw = result.hardwareBuffer
                            val hwBmp = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)
                            val bmp = hwBmp?.copy(Bitmap.Config.ARGB_8888, false)
                            hw.close()
                            if (bmp == null) {
                                ocrEnCurso = false
                                diag("No pude convertir la captura")
                                return
                            }
                            recognizer.process(InputImage.fromBitmap(bmp, 0))
                                .addOnSuccessListener { texto ->
                                    ocrEnCurso = false
                                    val lineas = texto.textBlocks.flatMap { b -> b.lines }.map { it.text.trim() }
                                    val oferta = parsear(lineas)
                                    if (oferta != null) {
                                        publicar(oferta, "OCR")
                                    } else if (esCentroDeViajes(lineas)) {
                                        publicarCentro()
                                    } else {
                                        diag("OCR: ${lineas.size} líneas, no encuentro pago + km + min")
                                        volcarLog("OCR", lineas)
                                        reintentarSiActivo()
                                    }
                                }
                                .addOnFailureListener {
                                    ocrEnCurso = false
                                    diag("OCR falló: ${it.message}")
                                }
                        } catch (e: Exception) {
                            ocrEnCurso = false
                            Log.e(TAG, "Error procesando captura", e)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        ocrEnCurso = false
                        // 1 = error interno, 2 = sin permiso, 3 = muy seguido, 4 = ventana segura, 5 = display inválido
                        diag("Captura falló (código $errorCode)")
                    }
                }
            )
        } catch (e: Exception) {
            ocrEnCurso = false
            Log.e(TAG, "takeScreenshot", e)
            diag("takeScreenshot: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- Parseo

    private data class Oferta(val pago: Double, val km: Double, val min: Double)

    private fun parsear(lineasCrudas: List<String>): Oferta? {
        // Quitar ruido: nuestro propio overlay (si sale en la captura) y la "tarifa base dinámica"
        val lineas = lineasCrudas.filterNot {
            val l = it.lowercase(Locale.ROOT)
            l.contains("/km") || l.contains("/h") || l.contains("viaje inteligente") ||
                    l.contains("leída automáticamente") || l.contains("esperando oferta") ||
                    l.contains("tarifa base") || l.contains("base dinámica") || l.contains("buscando viaje")
        }
        val texto = lineas.joinToString(" ")

        // Señales propias de la tarjeta de oferta (evita falsos positivos en mapas, chats, etc.)
        val tl = texto.lowercase(Locale.ROOT)
        val esOferta = listOf("aceptar", "precio", "pago en efectivo", "pago con tarjeta", "punto", "parada", "viajes")
            .any { tl.contains(it) }
        if (!esOferta) return null

        val pago = extraerPago(texto) ?: return null
        val km = extraerKm(texto) ?: return null
        val min = extraerMinutos(texto) ?: 0.0
        return Oferta(pago, km, min)
    }

    private fun extraerPago(texto: String): Double? {
        Regex("""aceptar\s*(?:por)?\s*\$?\s*([0-9][0-9.,]*)""", RegexOption.IGNORE_CASE)
            .find(texto)?.let { m ->
                convertirNumero(m.groupValues[1])?.takeIf { it in 200.0..500000.0 }?.let { return it }
            }
        // Primer monto con $ (el precio grande aparece antes que los botones de contraoferta)
        for (m in Regex("""\$\s*([0-9][0-9.,]*)""").findAll(texto)) {
            convertirNumero(m.groupValues[1])?.takeIf { it in 200.0..500000.0 }?.let { return it }
        }
        return null
    }

    private fun extraerKm(texto: String): Double? {
        var total = 0.0
        var hay = false
        for (m in Regex("""([0-9]+(?:[.,][0-9]+)?)\s*(km|kms|m)\b""", RegexOption.IGNORE_CASE).findAll(texto)) {
            var v = convertirNumero(m.groupValues[1]) ?: continue
            if (m.groupValues[2].lowercase(Locale.ROOT) == "m") v /= 1000.0
            if (v in 0.05..300.0) { total += v; hay = true }
        }
        return if (hay) total else null
    }

    private fun extraerMinutos(texto: String): Double? {
        var total = 0.0
        var hay = false
        // Horas: "1 h 5 min", "2 h", "1 hora 20 minutos"
        val reHoras = Regex("""([0-9]+)\s*(?:h|hr|hrs|hora|horas)\b(?:\s*([0-9]+)\s*(?:min|mins|minutos)\b)?""", RegexOption.IGNORE_CASE)
        for (m in reHoras.findAll(texto)) {
            val h = m.groupValues[1].toDoubleOrNull() ?: continue
            val mi = m.groupValues[2].toDoubleOrNull() ?: 0.0
            if (h in 1.0..12.0) { total += h * 60 + mi; hay = true }
        }
        val sinHoras = reHoras.replace(texto, " ")
        for (m in Regex("""([0-9]+)\s*(?:min|mins|minutos)\b""", RegexOption.IGNORE_CASE).findAll(sinHoras)) {
            val v = m.groupValues[1].toDoubleOrNull() ?: continue
            if (v in 1.0..300.0) { total += v; hay = true }
        }
        return if (hay) total else null
    }

    /** "5.300" -> 5300 ; "1,6" -> 1.6 ; "10.6" -> 10.6 ; "1.234,5" -> 1234.5 */
    private fun convertirNumero(str: String): Double? {
        val s = str.trim().trimEnd('.', ',')
        if (s.isEmpty()) return null
        val punto = s.lastIndexOf('.')
        val coma = s.lastIndexOf(',')
        return when {
            punto >= 0 && coma >= 0 ->
                if (coma > punto) s.replace(".", "").replace(',', '.').toDoubleOrNull()
                else s.replace(",", "").toDoubleOrNull()
            punto >= 0 || coma >= 0 -> {
                val sep = if (punto >= 0) '.' else ','
                val partes = s.split(sep)
                // Miles: grupos de exactamente 3 dígitos (5.300 / 12.500 / 1.234.567)
                if (partes.size > 1 && partes.drop(1).all { it.length == 3 } && partes[0].length in 1..3)
                    s.replace(sep.toString(), "").toDoubleOrNull()
                else s.replace(',', '.').toDoubleOrNull()
            }
            else -> s.toDoubleOrNull()
        }
    }

    // ---------------------------------------------------------------- Salida

    private fun publicar(o: Oferta, origen: String) {
        ultimaDeteccionMs = System.currentTimeMillis()
        Log.d(TAG, "OFERTA ($origen): $${o.pago} ${o.km} km ${o.min} min")
        Diagnostico.oferta(this, "$%.0f · %.1f km · %s".format(o.pago, o.km, origen))
        sendBroadcast(
            Intent(ACTION_OFERTA).setPackage(packageName)
                .putExtra("pago", o.pago).putExtra("km", o.km).putExtra("minutos", o.min)
        )
        reintentarSiActivo()
    }

    private fun publicarCentro() {
        ultimaDeteccionMs = System.currentTimeMillis()
        Log.d(TAG, "Centro de viajes detectado")
        sendBroadcast(Intent(ACTION_CENTRO).setPackage(packageName))
        reintentarSiActivo()
    }

    private fun diag(msg: String) {
        if (msg == ultimoDiag) return
        ultimoDiag = msg
        Log.d(TAG, "DIAG: $msg")
        Diagnostico.mensaje(this, msg)
        sendBroadcast(Intent(ACTION_DIAGNOSTICO).setPackage(packageName).putExtra("msg", msg))
    }

    private fun volcarLog(origen: String, lineas: List<String>) {
        val ahora = System.currentTimeMillis()
        if (ahora - ultimoDumpMs < 2000) return
        ultimoDumpMs = ahora
        Diagnostico.volcado(this, origen, lineas)
        Log.d(TAG_DUMP, "---- $origen (${lineas.size} líneas) ----")
        lineas.take(60).forEachIndexed { i, l -> Log.d(TAG_DUMP, "[$i] $l") }
    }

    override fun onInterrupt() {}

    companion object {
        private const val TAG = "AccesibilidadDiDi"
        private const val TAG_DUMP = "DiDiDump"
        const val ACTION_OFERTA = "com.tp.viajeinteligente.OFERTA_DETECTADA"
        const val ACTION_ACEPTADO = "com.tp.viajeinteligente.VIAJE_ACEPTADO"
        const val ACTION_CENTRO = "com.tp.viajeinteligente.CENTRO_DE_VIAJES"
        const val ACTION_DIAGNOSTICO = "com.tp.viajeinteligente.DIAGNOSTICO"
    }
}

package com.tp.viajeinteligente

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * Burbuja chica flotante sobre DiDi.
 * - Izquierda: $/km del viaje. Derecha: total de km de los viajes aceptados.
 * - El contorno es verde / amarillo / rojo según el $/km.
 * - Solo se ve con una oferta de viaje o en el Centro de viajes.
 * - Tocar la burbuja suma los km del viaje que se ve; mantenerla apretada ~1,2 s reinicia el total.
 * - Se arrastra para moverla.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var vistaOverlay: View? = null
    private lateinit var config: Configuracion

    private val receptores = mutableListOf<BroadcastReceiver>()
    private val handler = Handler(Looper.getMainLooper())
    private var restablecer: Runnable? = null

    // Oferta que se está mostrando y cuál ya se sumó al total de km aceptados
    private var ofertaKm = 0.0
    private var ofertaId: String? = null
    private var ultimoIdRegistrado: String? = null

    override fun onCreate() {
        activo = true
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        config = Configuracion(this)
        crearCanalNotificacion()
        startForeground(ID_NOTIFICACION, construirNotificacion())
        mostrarOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        activo = false
        restablecer?.let { handler.removeCallbacks(it) }
        receptores.forEach {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {}
        }
        receptores.clear()
        vistaOverlay?.let {
            try {
                windowManager.removeView(it)
            } catch (e: IllegalArgumentException) {
                // La vista ya no estaba agregada
            }
        }
        vistaOverlay = null
    }

    @SuppressLint("InflateParams")
    private fun mostrarOverlay() {
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val vista = inflater.inflate(R.layout.overlay_viaje, null)
        vistaOverlay = vista

        val tipoVentana = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            tipoVentana,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 24
        params.y = 220

        vista.visibility = if (config.mostrarSiempre) View.VISIBLE else View.GONE
        windowManager.addView(vista, params)
        habilitarToques(vista, params)
        conectarLogica(vista)
    }

    /** Arrastrar = mover. Toque corto = sumar km del viaje. Mantener apretado = reiniciar total. */
    @SuppressLint("ClickableViewAccessibility")
    private fun habilitarToques(vista: View, params: WindowManager.LayoutParams) {
        var xInicial = 0
        var yInicial = 0
        var downX = 0f
        var downY = 0f
        var downT = 0L
        var movido = false

        val pulsacionLarga = Runnable {
            if (!movido) {
                Configuracion(this).kmAceptados = 0.0
                ultimoIdRegistrado = null
                vista.findViewById<TextView>(R.id.tvTotalKm).text = textoTotalKm(false)
                Toast.makeText(this, "Total de km reiniciado", Toast.LENGTH_SHORT).show()
            }
        }

        vista.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    xInicial = params.x
                    yInicial = params.y
                    downX = e.rawX
                    downY = e.rawY
                    downT = System.currentTimeMillis()
                    movido = false
                    handler.postDelayed(pulsacionLarga, 1200)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!movido && (abs(dx) > 16 || abs(dy) > 16)) {
                        movido = true
                        handler.removeCallbacks(pulsacionLarga)
                    }
                    if (movido) {
                        params.x = xInicial + dx.toInt()
                        params.y = yInicial + dy.toInt()
                        windowManager.updateViewLayout(vista, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(pulsacionLarga)
                    if (!movido && System.currentTimeMillis() - downT < 1000) registrarAceptado(vista)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(pulsacionLarga)
                    true
                }
                else -> false
            }
        }
    }

    private fun conectarLogica(vista: View) {
        val detalle = vista.findViewById<TextView>(R.id.tvDetalle)
        val totalKm = vista.findViewById<TextView>(R.id.tvTotalKm)
        totalKm.text = textoTotalKm(false)

        registrar(AccesibilidadDiDi.ACTION_OFERTA) { intent ->
            val pago = intent.getDoubleExtra("pago", -1.0)
            val km = intent.getDoubleExtra("km", -1.0)
            val ms = intent.getLongExtra("mantener_ms", 4000L)
            if (pago <= 0 || km <= 0) {
                detalle.text = "Sin datos"
                detalle.setTextColor(ContextCompat.getColor(this, R.color.texto_claro))
                aplicarContorno(vista, ContextCompat.getColor(this, R.color.contorno_neutro))
                programarOcultar(vista, ms)
                return@registrar
            }
            config = Configuracion(this)
            val resultado = CalculadoraViaje.calcular(DatosViaje(pago, km), config)
            val color = when (resultado.decision) {
                Decision.ACEPTAR -> ContextCompat.getColor(this, R.color.verde)
                Decision.DUDOSO -> ContextCompat.getColor(this, R.color.amarillo)
                Decision.RECHAZAR -> ContextCompat.getColor(this, R.color.rojo)
            }
            detalle.text = "$%.0f/km".format(resultado.pesosPorKm)
            detalle.setTextColor(color)
            aplicarContorno(vista, color)

            ofertaKm = km
            ofertaId = "%.0f".format(pago)
            totalKm.text = textoTotalKm(ofertaId == ultimoIdRegistrado)
            programarOcultar(vista, ms)
        }

        // Toque detectado en "Aceptar por $…": suma los km de la oferta que se está mostrando
        registrar(AccesibilidadDiDi.ACTION_ACEPTADO) { registrarAceptado(vista) }

        // Centro de viajes: burbuja gris mientras estés en esa pantalla
        registrar(AccesibilidadDiDi.ACTION_CENTRO) {
            detalle.text = "Centro"
            detalle.setTextColor(ContextCompat.getColor(this, R.color.texto_claro))
            aplicarContorno(vista, ContextCompat.getColor(this, R.color.contorno_neutro))
            ofertaKm = 0.0
            ofertaId = null
            totalKm.text = textoTotalKm(false)
            programarOcultar(vista, 4000)
        }

        // Cambio de configuración (modo diagnóstico: visible siempre)
        registrar(ACTION_CONFIG) {
            vista.visibility = if (Configuracion(this).mostrarSiempre) View.VISIBLE else View.GONE
        }

        // Confirmación al iniciar: se ve unos segundos y después queda oculta hasta que llegue un viaje
        detalle.text = "Lista"
        programarOcultar(vista, 6000)
    }

    private fun registrar(accion: String, alRecibir: (Intent) -> Unit) {
        val receptor = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent != null) alRecibir(intent)
            }
        }
        ContextCompat.registerReceiver(this, receptor, IntentFilter(accion), ContextCompat.RECEIVER_NOT_EXPORTED)
        receptores.add(receptor)
    }

    private fun textoTotalKm(sumado: Boolean): String =
        (if (sumado) "✓ " else "") + "%.1f km".format(Configuracion(this).kmAceptados)

    /** Suma los km de la oferta actual al total, una sola vez por oferta. */
    private fun registrarAceptado(vista: View) {
        val id = ofertaId ?: return
        if (ofertaKm <= 0 || id == ultimoIdRegistrado) return
        val c = Configuracion(this)
        c.kmAceptados = c.kmAceptados + ofertaKm
        ultimoIdRegistrado = id
        vista.findViewById<TextView>(R.id.tvTotalKm).text = textoTotalKm(true)
    }

    private fun aplicarContorno(vista: View, color: Int) {
        val fondo = vista.background?.mutate()
        if (fondo is GradientDrawable) {
            fondo.setStroke((5 * resources.displayMetrics.density).toInt(), color)
        }
    }

    /** Muestra la burbuja y la oculta sola si pasan [ms] sin señal nueva. Al ocultar vuelve a gris. */
    private fun programarOcultar(vista: View, ms: Long) {
        vista.visibility = View.VISIBLE
        restablecer?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            aplicarContorno(vista, ContextCompat.getColor(this, R.color.contorno_neutro))
            val detalle = vista.findViewById<TextView>(R.id.tvDetalle)
            detalle.setTextColor(ContextCompat.getColor(this, R.color.texto_claro))
            detalle.text = "$/km —"
            vista.findViewById<TextView>(R.id.tvTotalKm).text = textoTotalKm(false)
            ofertaKm = 0.0
            ofertaId = null
            ultimoIdRegistrado = null
            if (!Configuracion(this).mostrarSiempre) vista.visibility = View.GONE
        }
        restablecer = r
        handler.postDelayed(r, ms)
    }

    private fun crearCanalNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                CANAL_ID,
                "Overlay de Viaje Inteligente",
                NotificationManager.IMPORTANCE_LOW
            )
            canal.description = "Mantiene activo el overlay mientras trabajás con Didi u otra app"
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(canal)
        }
    }

    private fun construirNotificacion(): android.app.Notification {
        val intentAbrir = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intentAbrir,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CANAL_ID)
            .setContentTitle("Viaje Inteligente activo")
            .setContentText("El overlay está disponible sobre tus apps de viajes")
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        @Volatile var activo = false

        const val ACTION_CONFIG = "com.tp.viajeinteligente.CONFIG_CAMBIADA"
        private const val ID_NOTIFICACION = 1001
        private const val CANAL_ID = "overlay_viaje_inteligente"

        fun iniciar(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun detener(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}

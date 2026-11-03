package com.tp.viajeinteligente

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tp.viajeinteligente.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var config: Configuracion

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        config = Configuracion(this)

        cargarConfigEnPantalla()

        binding.btnGuardar.setOnClickListener {
            guardarConfigDesdePantalla()
            Toast.makeText(this, "Configuración guardada", Toast.LENGTH_SHORT).show()
        }

        binding.btnPedirPermiso.setOnClickListener {
            pedirPermisoOverlay()
        }
        binding.btnAccesibilidad.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnIniciarOverlay.setOnClickListener {
            if (tienePermisoOverlay()) {
                guardarConfigDesdePantalla()
                OverlayService.iniciar(this)
                val msg = if (esServicioAccesibilidadHabilitado()) {
                    "Ventana lista: aparece sola con un viaje o en el Centro de viajes."
                } else {
                    "Overlay iniciado. ¡Activá también el Servicio de Accesibilidad para leer DiDi automáticamente!"
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Primero habilitá el permiso de superposición", Toast.LENGTH_LONG).show()
                pedirPermisoOverlay()
            }
        }

        binding.btnDetenerOverlay.setOnClickListener {
            OverlayService.detener(this)
            Toast.makeText(this, "Overlay detenido", Toast.LENGTH_SHORT).show()
        }

        binding.btnSimular.setOnClickListener { simularViaje() }

        binding.btnActualizarDiag.setOnClickListener { actualizarDiagnostico() }
        binding.btnCopiarDiag.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("diagnostico", textoInforme()))
            Toast.makeText(this, "Informe copiado", Toast.LENGTH_SHORT).show()
        }

        binding.btnReiniciarKm.setOnClickListener {
            config.kmAceptados = 0.0
            actualizarKmAceptados()
            Toast.makeText(this, "Total reiniciado", Toast.LENGTH_SHORT).show()
        }

        binding.swMostrarSiempre.isChecked = config.mostrarSiempre
        binding.swMostrarSiempre.setOnCheckedChangeListener { _, marcado ->
            config.mostrarSiempre = marcado
            sendBroadcast(Intent(OverlayService.ACTION_CONFIG).setPackage(packageName))
        }
    }

    override fun onResume() {
        super.onResume()
        actualizarEstadoPermiso()
        actualizarKmAceptados()
        actualizarDiagnostico()
    }

    /** Manda una oferta falsa al overlay para ver el contorno verde / amarillo / rojo sin DiDi. */
    private fun simularViaje() {
        if (!tienePermisoOverlay()) {
            Toast.makeText(this, "Primero habilitá el permiso de superposición", Toast.LENGTH_LONG).show()
            pedirPermisoOverlay()
            return
        }
        val pago = binding.etPruebaPago.text.toString().replace(',', '.').toDoubleOrNull()
        val km = binding.etPruebaKm.text.toString().replace(',', '.').toDoubleOrNull()
        if (pago == null || km == null || pago <= 0 || km <= 0) {
            Toast.makeText(this, "Completá pago y km", Toast.LENGTH_SHORT).show()
            return
        }
        guardarConfigDesdePantalla()
        OverlayService.iniciar(this)
        // Pequeña espera por si el overlay recién se está creando
        Handler(Looper.getMainLooper()).postDelayed({
            sendBroadcast(
                Intent(AccesibilidadDiDi.ACTION_OFERTA).setPackage(packageName)
                    .putExtra("pago", pago).putExtra("km", km)
                    .putExtra("mantener_ms", 15_000L)
            )
        }, 600)
    }

    private fun cargarConfigEnPantalla() {
        binding.etUmbralVerdeKm.setText(config.umbralVerdeKm.toString())
        binding.etUmbralRojoKm.setText(config.umbralRojoKm.toString())
    }

    private fun guardarConfigDesdePantalla() {
        config.umbralVerdeKm = binding.etUmbralVerdeKm.text.toString().toDoubleOrNull() ?: config.umbralVerdeKm
        config.umbralRojoKm = binding.etUmbralRojoKm.text.toString().toDoubleOrNull() ?: config.umbralRojoKm
    }

    private fun textoInforme(): String =
        Diagnostico.informe(this, tienePermisoOverlay(), esServicioAccesibilidadHabilitado())

    private fun actualizarDiagnostico() {
        binding.tvDiagnostico.text = textoInforme()
    }

    private fun actualizarKmAceptados() {
        binding.tvKmAceptados.text = "%.1f km".format(config.kmAceptados)
    }

    private fun tienePermisoOverlay(): Boolean {
        return Settings.canDrawOverlays(this)
    }

    private fun esServicioAccesibilidadHabilitado(): Boolean {
        val expectedComponentName = ComponentName(this, AccesibilidadDiDi::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val stringSplitter = TextUtils.SimpleStringSplitter(':')
        stringSplitter.setString(enabledServices)
        while (stringSplitter.hasNext()) {
            val componentNameString = stringSplitter.next()
            val enabledComponent = ComponentName.unflattenFromString(componentNameString)
            if (enabledComponent != null && enabledComponent == expectedComponentName) {
                return true
            }
        }
        return false
    }

    private fun pedirPermisoOverlay() {
        if (!tienePermisoOverlay()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun actualizarEstadoPermiso() {
        val overlay = if (tienePermisoOverlay()) "Superposición: concedido ✓" else "Superposición: FALTA HABILITAR ✕"
        val accesibilidad = if (esServicioAccesibilidadHabilitado()) "Accesibilidad: activa ✓" else "Accesibilidad: FALTA ACTIVAR ✕"
        binding.tvEstadoPermiso.text = "$overlay\n$accesibilidad"
    }
}

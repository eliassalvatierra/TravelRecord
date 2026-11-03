# Viaje Inteligente

App Android en Kotlin con ventana flotante arrastrable para usar sobre DiDi. La ventana intenta leer automáticamente los textos accesibles de la oferta y mostrar rentabilidad bruta por kilómetro y por hora, sin datos de combustible ni carga manual.

## Diagnóstico
La tarjeta "Diagnóstico de lectura" de la app muestra qué ve el servicio (último evento, paquetes, oferta detectada y último texto leído) y permite copiar el informe.

## Interfaz
Tema oscuro fijo; título de la pantalla: "Viajando con el Negro".

## Cuándo aparece la ventana
Queda oculta y aparece sola cuando se detecta una oferta de viaje o la pantalla "Centro de viajes" de DiDi. Se oculta 4 s después de la última detección. El interruptor "Mostrar siempre (modo diagnóstico)" la deja visible todo el tiempo para depurar la lectura.

## Solo $/km y km aceptados
La decisión (contorno verde / amarillo / rojo) se toma únicamente por $/km. En la ventana, al lado del $/km se muestra el total de km de los viajes aceptados. Se suma al tocar "Aceptar" en DiDi (si el servicio detecta el toque) o con el botón "Acepté este viaje" de la ventana. Se reinicia desde la app o con una pulsación larga sobre el total.

## Semáforo
La ventana flotante cambia el contorno según el viaje: verde = aceptar (igual o mayor al umbral verde), amarillo = dudoso, rojo = rechazar (igual o menor al umbral rojo). En la app hay una tarjeta "Probar el semáforo" para simular un viaje sin DiDi. Tras 20 s sin oferta nueva, el contorno vuelve a gris.

## Abrir y probar
1. Abrí esta carpeta en Android Studio y sincronizá Gradle.
2. Instalá en un teléfono Android.
3. En la app, habilitá **superposición** y **lectura automática (Accesibilidad)** desde los ajustes del sistema.
4. Iniciá el overlay y abrí DiDi. Arrastrá la barra superior para mover la ventana.
5. Cuando DiDi exponga los datos de oferta como texto accesible, el overlay intentará calcular `$ / km` y `$ / hora`.

## Lectura: árbol de accesibilidad + OCR
Si DiDi no expone el texto por accesibilidad, el servicio saca una captura (`takeScreenshot`, Android 11+) y aplica OCR local con ML Kit. El overlay muestra en la línea de estado qué está pasando (diagnóstico). Logcat: filtrá por `AccesibilidadDiDi` y `DiDiDump`.

## Limitación importante
La lectura automática usa el árbol de accesibilidad, no OCR de píxeles. Solo funciona si la versión/pantalla de DiDi expone pago, distancia y tiempo como texto accesible. Si la oferta está dibujada como imagen o DiDi oculta esos textos, no se detectará; para eso hace falta agregar captura de pantalla con consentimiento de Android y OCR, y ajustar el reconocimiento con capturas reales de esa pantalla. La app no acepta ni rechaza viajes por el usuario.

## Decisión
Se compara el pago bruto dividido por kilómetros totales detectados y por el tiempo estimado. Los umbrales se configuran en la pantalla principal. No descuenta combustible ni otros costos.

## Burbuja
La ventana es una burbuja chica: $/km a la izquierda y total de km aceptados a la derecha, con contorno verde/amarillo/rojo. Tocarla suma los km del viaje que se ve; mantenerla apretada ~1,2 s reinicia el total; se arrastra para moverla.

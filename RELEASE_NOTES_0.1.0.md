# TurnoControl 0.1.0

Primera versión de TurnoControl, una app Android para organizar turnos programados. Versión inicial de portfolio: no registra horas trabajadas ni calcula nóminas.

## Novedades
- Calendario mensual y semanal con días de trabajo, libres y sin configurar.
- Turnos normales, partidos y nocturnos, con extras declaradas por intervalo.
- Datos guardados solo en el dispositivo (Room), sin cuentas ni conexión a Internet.
- Importación desde una fotografía con OCR local (ML Kit): las propuestas se revisan y solo se guardan tras confirmarlas.
- Configuración opcional de perfil y tarifas (sin cálculo salarial) y tema claro/oscuro.

## Requisitos
- Android 8.0 (API 26) o superior. El APK ocupa unos 50 MB.
- No usa Internet: el reconocimiento de texto se hace en el dispositivo con un modelo incluido.

## Instalación limpia
1. Descargue `TurnoControl-0.1.0-release-signed.apk` y `SHA256SUMS.txt` de esta Release.
2. Compruebe la suma: `sha256sum -c SHA256SUMS.txt` (en Windows, `certutil -hashfile TurnoControl-0.1.0-release-signed.apk SHA256`).
3. Opcional: `apksigner verify --print-certs` debe mostrar la huella SHA-256 del certificado indicada más abajo.
4. Instale el APK (Android pedirá permitir la instalación desde el origen que lo abra, porque no procede de una tienda).
5. Si el dispositivo ya tiene `com.turnocontrol` firmado con otra clave, Android rechazará la instalación; vea «Instalación, firma y actualización».

## Estado del OCR
Importación de tablas con trabajadores en filas y fechas en columnas, probada con imágenes sintéticas y, en una validación manual anterior (15/10/2026), con un formato de tabla real concreto. Siempre se revisa y confirma antes de guardar. No se ha probado sobre el APK Release ni con una matriz amplia de fotografías reales; otros formatos o fotografías pueden requerir corrección manual.

## Estado de accesibilidad
Las etiquetas del calendario, el detalle del día y el selector de hora están expuestas al servicio de accesibilidad. **TalkBack no se ha probado.** El selector de fila sobre la fotografía tiene carencias conocidas: algunos controles sin nombre y delimitación de banda solo por arrastre; existen alternativas sin arrastre (elegir fila de la lista o añadir días manualmente).

## Qué se ha probado, y sobre qué
La cobertura depende de la variante probada; no todo se ejecutó sobre el APK Release.

| Comprobación | Variante / entorno | Resultado |
|---|---|---|
| 98 pruebas unitarias | Debug (mismas fuentes que Release) | Correctas |
| Lint | Debug | 0 errores, 22 advertencias de mantenimiento |
| Suites instrumentales completas auditadas de forma independiente (2 ejecuciones) | Verification, emulador Android 15 (API 35) | 19/19, pero sobre una versión anterior de un ajuste de las pruebas (`assertLaunched`) |
| 7 pruebas OCR revisadas tras ese ajuste | Verification, emulador API 35 | Correctas, sobre el commit publicado `e4f1271` |
| Suite completa tras el ajuste (2 ejecuciones del autor) | Verification, emulador API 35 | 19/19; no re-auditada de forma independiente |
| Validación manual | Verification, un Samsung Galaxy A53 con Android 16, datos sintéticos | Correcta en arranque, calendario, persistencia, OCR sobre imagen sintética desde el selector de archivos; selector de galería abierto y cancelado, sin elegir imagen |
| APK Release firmado, instalación limpia | Release, emulador nuevo con Android 15 (API 35) | Instala como `com.turnocontrol` 0.1.0; arranque, navegación, calendario, crear y recuperar un turno sintético, cierre y reapertura y `force-stop` correctos; sin cierres inesperados |

Lo que **no** se ha probado sobre el APK Release firmado: dispositivos físicos, otras versiones de Android, importación por OCR ni accesibilidad. Esas pruebas se hicieron, en su caso, sobre otras variantes.

## Limitaciones
- OCR sensible a perspectiva extrema, texto pequeño y desenfoque; nunca se inventan horas. Las pruebas automáticas usan imágenes sintéticas; la validación con una fotografía real se limitó a un formato concreto (15/10/2026).
- La elección de imagen desde la galería no se completó en el dispositivo físico.
- El selector de fila sobre la fotografía tiene carencias de accesibilidad conocidas: controles sin nombre y delimitación de banda solo por arrastre. **TalkBack no se ha probado.**
- Los totales son minutos nominales de planificación y no resuelven el cambio de horario de verano.
- Un único dispositivo físico (Android 16) y emuladores con Android 15.
- Un fallo gráfico (SIGSEGV en `libhwui`) visto una vez por un auditor en un emulador no se reprodujo y **sigue sin explicar**.

## Instalación, firma y actualización
- Se distribuye `TurnoControl-0.1.0-release-signed.apk`, firmado con una clave de release nueva y dedicada a TurnoControl. SHA-256 del APK: `80b203306d3c2a71059d5773a4a3fa209ed3d31f711202f2b8fa598fa54cbd47` (también en `SHA256SUMS.txt`). Huella SHA-256 del certificado: `99bdeac5119708ccf25221d596fd437a48f8797e02e007ee72ed51daa3b3c2ec`.
- Firma verificable con `apksigner verify --print-certs`: esquemas v2 y v3, más una firma v1 heredada que Android ignora con minSdk 26. El APK tiene 580 entradas ZIP; 577 son las del Release sin firmar y las 3 restantes son los archivos de firma de `META-INF`.
- **No puede actualizar directamente una instalación 0.0.1** firmada con la clave de depuración: Android exige la misma firma. Solo puede instalarse en un dispositivo que no tenga ya `com.turnocontrol` con otra firma.
- Desinstalar la versión anterior elimina sus datos. No se recomienda hacerlo sin una migración comprobada ni una copia propia verificada: no existe una copia restaurable de esos datos.
- La migración de datos de 0.0.1 a 0.1.0 no se ha probado en un dispositivo con datos reales.
- Compruebe el SHA-256 antes de instalar: `sha256sum -c SHA256SUMS.txt`.

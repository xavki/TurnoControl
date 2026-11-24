# TurnoControl

Aplicación Android nativa para organizar turnos programados en un calendario. Permite introducirlos manualmente o preparar propuestas desde una fotografía mediante OCR local, revisarlas y confirmar su guardado.

**Versión 0.2.0 — rediseño de interfaz (en preparación, sin publicar).** No registra horas realmente trabajadas ni calcula nóminas reales: la única cifra económica es una estimación bruta con las tarifas configuradas actualmente. Requiere Android 8.0 (API 26) o superior. Notas de esta versión: [RELEASE_NOTES_0.2.0.md](RELEASE_NOTES_0.2.0.md); versión anterior: [RELEASE_NOTES_0.1.0.md](RELEASE_NOTES_0.1.0.md). Trabajo local de 0.3.0, sin publicar: [RELEASE_NOTES_0.3.0.md](RELEASE_NOTES_0.3.0.md) (borrador).

> Los datos de 0.2.0 (días de vacaciones y baja médica) no son legibles por 0.1.0: no se admite volver a la versión anterior conservando esos datos.

## Funcionalidades

- Dos pestañas: **Calendario** (calendario, resumen del mes, horas extras del mes y del año, estimación de nómina y acceso a la importación OCR) y **Resumen** (vista por mes y año con selectores propios, horas extra del mes y acumuladas del año, historial de extras por mes, desglose visual y extras declaradas). Ajustes (perfil, contrato, tarifas, tema, importación OCR, copia de seguridad y cuenta) se abre desde el icono de engranaje.
- Calendario mensual y semanal, navegación entre meses/años y acceso a Hoy.
- Días de trabajo, libres, **vacaciones**, **baja médica** y sin configurar; detalle, edición y eliminación. Vacaciones y baja médica se marcan por día o por rango de fechas, con confirmación cuando sustituyen turnos; se pueden quitar por periodo contiguo.
- Contadores de horas normales (total menos extras declaradas), extras, total, días trabajados, libres, vacaciones y baja médica, por mes y por año natural (1 de enero a 31 de diciembre). Los días se asignan al mes de su fecha de inicio; las continuaciones nocturnas no se cuentan dos veces.
- Estimación bruta de nómina: horas normales × tarifa base + extras × tarifa de hora extra (ambas opcionales en Ajustes). Si falta una tarifa, la cifra aparece como «No disponible». Siempre con la nota «Estimación con las tarifas actuales»: no es salario neto ni nómina histórica. Vacaciones y baja médica no generan ningún cálculo.
- Turnos normales y partidos, con precisión de minutos y cruces de medianoche. Las continuaciones pertenecen al día de inicio y no duplican registros.
- Extras declaradas por intervalo, incluidas en el total programado y mostradas también como subtotal. Los descansos entre intervalos no cuentan como trabajo.
- Persistencia local sin exigir contrato ni tarifas para usar el calendario.
- Photo Picker y selector de archivos Android, vista previa y ML Kit local.
- Interpretación de tablas con trabajadores en filas y fechas en columnas. Coincidencia exacta y selección explícita mediante lista o sobre la fotografía.
- Revisión y corrección manual antes de guardar, resolución de conflictos sin sobrescritura silenciosa y comprobación transaccional de cambios concurrentes.
- Configuración opcional de perfil, contrato y seis tarifas (base, hora extra y cuatro pluses mostrados sin cálculo automático); tema claro/oscuro persistente con acento azul Material 3.

## Tecnologías y arquitectura

Kotlin, Jetpack Compose, Material 3, Navigation Compose, MVVM, StateFlow, coroutines, Hilt, Room, DataStore y Google ML Kit Text Recognition con modelo incluido.

Un módulo `app` con separación por responsabilidad:

```text
app/src/main/java/com/turnocontrol/
  ui/       Pantallas Compose y ViewModels
  domain/   Modelos, validación e intérpretes independientes de la interfaz
  data/     Room, repositorios, preferencias y reconocimiento local
  di/       Inyección mediante Hilt
app/schemas/          Esquemas Room 1–4
app/src/test/         JUnit y Robolectric
app/src/androidTest/  Interfaz, OCR y persistencia en Android
app/src/verification/ Soporte exclusivo de la variante aislada de pruebas
```

El OCR permanece provisional en memoria hasta confirmar los días. La planificación está separada de futuros fichajes reales. Las migraciones no destructivas preservan configuración y turnos existentes.

## Capturas con datos ficticios

**Capturas de 0.1.0, pendientes de regenerar con la interfaz de 0.2.0** (requieren un dispositivo o emulador y `PortfolioScreenshotsTest`). Las imágenes proceden de la interfaz real de `verification`, con planificación sintética, sin fotografías laborales, identidades ni horarios personales. La fecha corresponde al mes de generación.

| Calendario | Detalle del día |
| --- | --- |
| ![Calendario ficticio](docs/screenshots/calendar.png) | ![Detalle ficticio](docs/screenshots/day-detail.png) |
| Editor | Horas extras declaradas |
| ![Editor ficticio](docs/screenshots/shift-editor.png) | ![Extras ficticias](docs/screenshots/extra-hours.png) |

Generación y verificación: [preparación de v0.1.0](docs/PREPARACION_V0_1_0.md).

## Compilar desde un clon limpio

Requisitos: JDK 17 y Android SDK con `platforms;android-35`, `build-tools;35.0.0` y `platform-tools`. El Wrapper incluido utiliza Gradle 8.11.1; no hace falta Gradle global. Android mínimo: 8.0 / API 26.

Configura `JAVA_HOME` con tu JDK y `ANDROID_HOME` con tu SDK, o crea `local.properties` en la raíz (ignorado por Git):

```properties
sdk.dir=/ruta/al/Android/Sdk
```

En Windows puede usarse `sdk.dir=C:/ruta/al/Android/Sdk`. Instala los componentes y acepta sus licencias con Android Studio o las herramientas oficiales. La primera compilación necesita conexión para descargar Gradle y dependencias; no requiere secretos, cuentas ni servicios propios.

Windows:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 assembleDebug testDebugUnitTest lintDebug lintVerification assembleVerificationAndroidTest
```

Linux/macOS:

```bash
./gradlew --no-daemon --max-workers=1 assembleDebug testDebugUnitTest lintDebug lintVerification assembleVerificationAndroidTest
```

APK: `app/build/outputs/apk/debug/app-debug.apk`, firmado con la clave de depuración de tu entorno. No se versionan APK ni claves. Un worker evita una carrera lint/kapt observada durante el desarrollo.

## Pruebas

Con un dispositivo o emulador autorizado y desbloqueado:

```bash
./gradlew --no-daemon --max-workers=1 connectedVerificationAndroidTest
```

En Windows sustituye `./gradlew` por `.\gradlew.bat`. Las pruebas usan `com.turnocontrol.verification`, separado de `com.turnocontrol`. Algunas sustituyen datos de la variante aislada por fixtures sintéticas: no deben redirigirse al paquete privado.

Para comprobar un reinicio real, compila `assembleVerification assembleVerificationAndroidTest` y ejecuta:

```powershell
pwsh -File ./scripts/verify-restart.ps1 -AdbPath /ruta/al/Android/Sdk/platform-tools/adb
```

En Windows también sirve Windows PowerShell y la ruta a `adb.exe`. Sin `-AdbPath`, se busca en `ANDROID_HOME`, `ANDROID_SDK_ROOT`, PATH y finalmente `.tools/android-sdk`. Requiere exactamente un dispositivo autorizado. Instala solo los APK aislados y verifica perfil, contrato, cinco tarifas, partidos, nocturnos y extras con PID distinto.

**Estado de validación de 0.1.0 (10/11/2026, commit `e4f1271`).** 98 pruebas unitarias correctas (Debug) y lint sin errores (22 advertencias de mantenimiento). Las dos ejecuciones instrumentales completas auditadas de forma independiente (19/19, variante Verification, emulador API 35) corresponden a una versión anterior de un ajuste de las pruebas (`assertLaunched`); los 7 tests OCR revisados después sí corresponden al commit publicado. La validación física (Samsung Galaxy A53, Android 16) se hizo con la variante Verification y datos sintéticos, no con el APK Release. El APK Release firmado se instaló en limpio en un emulador (API 35) y superó arranque, calendario, creación de un turno sintético y persistencia tras cierre y `force-stop`; no se ha probado en un dispositivo físico. No se ha evaluado la accesibilidad con TalkBack y un fallo gráfico (SIGSEGV en `libhwui`) visto una vez por un auditor no se reprodujo y sigue sin explicar. Pendientes y límites: [PENDIENTES_FUTUROS.md](PENDIENTES_FUTUROS.md).

*Cifras históricas (preparación del 15/10/2026, anteriores a las correcciones posteriores):* **69 tests locales y 19 Android aprobados**, compilación correcta y lint sin errores, con 43/47 advertencias de mantenimiento. La prueba manual OCR del 15/10/2026 fue aprobada por el usuario para el formato probado. Resultados, incidencias y límites de la verificación: [PREPARACION_V0_1_0.md](docs/PREPARACION_V0_1_0.md).

**Pruebas locales sin dispositivo (0.3.0):**

```bash
./gradlew --max-workers=1 --offline test                                   # JVM + Robolectric: debug, release y verification
./gradlew --max-workers=1 --offline :app:lintDebug
./gradlew --max-workers=1 --offline :app:compileVerificationAndroidTestKotlin  # compila las instrumentadas sin ejecutarlas
```

Las pruebas de interfaz con Compose (`AccountUiTest`, `LinkingUiTest`, `SummaryCardsTest`, `AppNavigationTest`, `AccessibilityChecksTest`, `ConflictsUiTest`) se excluyen de `testReleaseUnitTest`, porque la variante release no incluye la actividad de prueba, y se ejecutan en debug y verification. `AppNavigationTest` recorre el grafo de navegación real. `AccessibilityChecksTest` comprueba el contraste, el ajuste de línea con fuente al 200 %, los nombres accesibles y las zonas táctiles de 48 dp. Ninguna de las dos sustituye a TalkBack en un dispositivo.

Se prueban migraciones 1→4, 2→4 y 3→4, conservación de configuración, edición/eliminación, medianoche, extras, conflictos y regresión OCR. Las imágenes se generan con Canvas y datos ficticios; las respuestas de los selectores se simulan con Espresso Intents.

## Privacidad

- Sin cuentas activas ni backend: la infraestructura de cuentas y sincronización existe en el código, pero el inicio de sesión está desactivado (no hay ningún proveedor real) y la app no se conecta a ningún servidor. El manifiesto elimina los permisos de Internet y estado de red.
- Sin permisos generales de almacenamiento: se utilizan URI de los selectores. Un proveedor externo puede descargar un archivo antes de entregarlo a la aplicación.
- Fotografías y documentos OCR completos no se persisten; el estado provisional se pierde al terminar el proceso.
- Datos en almacenamiento privado de Android; backup automático y transferencia desactivados.
- Copia de seguridad local bajo control del usuario (Ajustes → Copia de seguridad): archivo JSON versionado con comprobación de integridad, validado por completo antes de restaurar, con copia automática previa y recuperación de restauraciones interrumpidas. Véase [docs/COPIAS_DE_SEGURIDAD.md](docs/COPIAS_DE_SEGURIDAD.md).
- Al arrancar se comprueba que los datos guardados son compatibles con la versión instalada; si proceden de una versión más nueva o contienen valores desconocidos, se muestran como no compatibles y no se modifican.
- No se afirma cifrado adicional de la base de datos ni protección frente a un dispositivo comprometido.

## Limitaciones

- OCR sensible a perspectiva extrema, texto pequeño, desenfoque y geometría incompleta. No se adivinan horas ni fechas. Límite: 20 MiB por imagen; se procesan hasta 12 megapíxeles con la misma resolución en vertical y horizontal, y las imágenes pequeñas se amplían 2×. Una captura de pantalla muy alta con la tabla diminuta puede seguir siendo ilegible: amplía antes de capturar.
- La estrategia semanal requiere siete fechas completas; la estrategia inicial admite otros diseños. No hay coincidencia aproximada automática de identidad.
- `LIB` es libre; `VAC` es ausencia provisional no guardable en Room 4. Celdas ambiguas requieren corrección. El OCR no infiere extras por colores o anotaciones.
- Precisión de minutos, desplazamientos de 0/1 día y máximo técnico de 24 horas por intervalo y sumadas por día de inicio; no son reglas laborales.
- Totales nominales de planificación, sin resolver automáticamente cambios de horario de verano. Sin fichajes, notificaciones ni PDF. La estimación de nómina es bruta, nominal y con las tarifas actuales; no aplica pluses, convenios, retenciones ni compensación de bajas o vacaciones.
- Un perfil local, sin sincronización ni historial de vigencias de configuración: cambiar una tarifa cambia también la estimación de meses anteriores.
- Cobertura manual limitada al formato probado y a un Samsung A53; pendiente una matriz de dispositivos y la evaluación de accesibilidad con TalkBack. El selector de fila sobre la fotografía tiene carencias de accesibilidad conocidas.

## Estado y documentación

La versión inicial reúne calendario persistente, Room 4, rediseño e importación OCR revisada. Los informes históricos conservan el contexto de sus validaciones; las referencias a revisiones pendientes describen aquellas etapas.

- [Corrección OCR y aprobación manual](docs/CORRECCION_OCR_TABLAS.md)
- [Calendario y persistencia](docs/CALENDARIO.md)
- [Rediseño y Room 4](docs/REDISENO_INTERFAZ.md)
- [Preparación y cierre local de v0.1.0](docs/PREPARACION_V0_1_0.md)
- [Notas de versión 0.1.0](RELEASE_NOTES_0.1.0.md)
- [Pendientes para futuras versiones](PENDIENTES_FUTUROS.md)
- [Decisiones de nómina pendientes (0.3.0)](docs/DECISIONES_NOMINA_0.3.0.md)
- [Cuentas y sincronización: arquitectura y estado local](docs/ARQUITECTURA_0.3.0_CUENTAS_Y_SINCRONIZACION.md)
- [Lista para la integración con el servidor](docs/INTEGRACION_ORACLE_CHECKLIST.md)
- [Plan de validación en dispositivo 0.3.0](docs/QA_DISPOSITIVO_0.3.0.md)

## Licencia

El código propio de TurnoControl está bajo la [licencia MIT](LICENSE). Copyright (c) 2026 TurnoControl. Las dependencias y el Gradle Wrapper conservan sus licencias y condiciones propias; véase la revisión de terceros en [PREPARACION_V0_1_0.md](docs/PREPARACION_V0_1_0.md).

# TurnoControl 0.2.0 — notas de versión (en preparación)

Estado: desarrollo local en la rama `feature/0.2.0-redesign`. Sin publicar, sin tag, sin APK ni release. `versionCode 2`, `versionName 0.2.0`.

## Novedades

- **Interfaz rediseñada** con Material 3, acento azul y tema claro/oscuro. Dos pestañas: **Calendario** y **Resumen**. Ajustes (perfil, contrato, tarifas, tema e importación OCR) se abre desde el icono de engranaje de la barra superior.
- **Calendario**: cuadrícula mensual/semanal con indicadores de trabajo, libre, vacaciones, baja médica, extras declaradas y continuación nocturna; tarjetas de resumen del mes; tarjeta «Horas extra del mes» y tarjeta destacada «Horas extra acumuladas de AAAA» (1 de enero a 31 de diciembre, también en Resumen); estimación bruta de nómina.
- **Resumen**: selector de año y de mes, estadísticas del mes y del año, bruto estimado de ambos, desglose visual (normales frente a extras y horas por mes) y lista de extras declaradas del mes con acceso al día.
- **Vacaciones** y **Baja médica** como estados de día. Se marcan desde el detalle del día para un solo día o «Hasta una fecha…» (máximo 366 días), con aviso del número de días que se sustituyen. «Quitar … del periodo» elimina la racha contigua. No cuentan como horas, libres ni entre sí.
- **Tarifa de hora extra** opcional en Ajustes. La estimación bruta = horas normales × tarifa base + extras declaradas × tarifa de hora extra. Cualquier tarifa ausente deja la cifra correspondiente como «No disponible».
- Detalle del día con entrada, salida, descansos, horas normales y extras.
- **OCR: fotos verticales y orientación.** La imagen se limita por píxeles (12 MiPx) en lugar de por lado, así que una foto vertical o una captura alta ya no se reduce a la mitad. Las imágenes pequeñas se amplían 2×. El texto girado 90°/180°/270° se corrige automáticamente según el ángulo que informa ML Kit, y hay botones para girar a mano. El archivo original nunca se modifica.
- **OCR: revisar sin editar.** Cada día detectado muestra su estado («Pendiente de revisar», «Revisado» o «Error de validación») y puede marcarse como revisado directamente. Editar es opcional y nunca marca el día como revisado. «Confirmar y guardar turnos» exige todos los días incluidos revisados, muestra días y turnos, distingue nuevos, ya guardados (no se duplican) y conflictos (hay que elegir sustituir o conservar), y pide una confirmación final antes de escribir.

## Límites

- La estimación es bruta y nominal, **con las tarifas actuales**: no es salario neto, no aplica pluses (se listan «sin cálculo automático»), convenios ni retenciones, y no refleja nóminas históricas reales. Cambiar una tarifa cambia la estimación de todos los meses.
- Vacaciones y baja médica no generan ningún cálculo ni deducción; no se inventan reglas de compensación.
- Las horas extras siguen siendo las declaradas explícitamente al editar intervalos; no se infieren por exceso sobre el contrato.
- Sin notas por turno, sin historial de vigencias de tarifas (requerirían migración y se posponen).
- `VAC` sigue siendo ausencia provisional no guardable desde el OCR (empieza excluida en la revisión).
- Una captura de pantalla muy alta con la tabla diminuta puede seguir siendo ilegible; el OCR con fotos reales lo ha validado manualmente el usuario, no una prueba automática.

## Compatibilidad y datos

- Room permanece en la versión 4: **no hay migración**. Actualizar desde 0.1.0 conserva perfil, contrato, tarifas y turnos. Los nuevos estados se guardan como texto en la columna existente `ScheduledDay.kind`.
- **No se admite volver a 0.1.0** con datos de 0.2.0: la versión anterior no conoce los valores `VACATION` y `SICK_LEAVE`.
- La tarifa de hora extra es una fila `PayRule` adicional (`EXTRA_HOUR`); las cinco anteriores conservan sus identificadores.

## Estado de pruebas (21/11/2026)

- Pruebas unitarias (JUnit + Robolectric): 143 correctas, 0 fallos. `lintDebug`: 0 errores.
- Pruebas instrumentadas en Samsung Galaxy A53 (Android 16), variante `verification`: **26/26 correctas**, incluidas orientación OCR (vertical/horizontal, EXIF, giro automático y manual), revisión sin editar con confirmación final, regresión de navegación tras Ajustes y tarjetas de horas extra.
- Validación manual en el mismo dispositivo con datos TEST (eliminados después). Las capturas de `docs/screenshots` siguen siendo de 0.1.0.
- La tarea Gradle `connectedVerificationAndroidTest` desinstala el paquete `verification` al terminar (borra sus datos sintéticos); para conservarlos, añadir `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` o instalar con `adb install -r` y lanzar `am instrument`.

## Compilar y probar

```powershell
.\gradlew.bat --no-daemon --max-workers=1 assembleDebug testDebugUnitTest lintDebug lintVerification assembleVerificationAndroidTest
.\gradlew.bat --no-daemon --max-workers=1 connectedVerificationAndroidTest   # con dispositivo o emulador
```

APK de depuración: `app/build/outputs/apk/debug/app-debug.apk`. Requisitos y configuración del SDK: véase el README.

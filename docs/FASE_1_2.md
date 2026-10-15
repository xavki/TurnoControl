# Fase 1.2: interpretación y revisión provisional

> Registro de la implementación inicial, anterior al rediseño autorizado. Las referencias de este documento a ausencia de persistencia, Room sin cambios y falta de autorización ADB describen aquella validación. El estado actual del calendario, Room 3 y las comprobaciones posteriores se documentan en [CALENDARIO.md](CALENDARIO.md).

Base: `dbc65171ad7ca242289b28c35473ce1398c39e27`. Implementación pendiente de revisión; sin commit, push, merge ni publicación. No se implementa Fase 1.3.

## Uso y arquitectura

Calendario → Importar imagen → Galería o Seleccionar archivo → Revisar turnos del trabajador. El nombre y alias se precargan desde el borrador de configuración existente y pueden ajustarse para esta revisión sin guardar cambios en el perfil. Indicar el nombre completo o alias exacto de la celda. Si falta el contexto de fechas, introducir un mes conocido como `2026-10`; nunca se utiliza automáticamente la fecha actual.

`ScheduleInterpreter` es una interfaz Kotlin independiente de Android. `WorkerRowsInterpreter` implementa la estrategia inicial: trabajadores en filas y fechas en columnas. Consume directamente los bloques, líneas, elementos y coordenadas de `OcrDocument`, utilizando las hojas más detalladas y heredando la confianza disponible. Agrupa filas visuales y cabeceras, delimita columnas mediante sus centros y limita cada trabajador por la siguiente etiqueta de fila. Hilt proporciona la estrategia; `ScheduleReviewViewModel` ejecuta la interpretación en `Dispatchers.Default` y expone `StateFlow` a Compose. No hay dependencias nuevas.

Los modelos provisionales conservan fecha nullable, texto de cabecera y celda original, intervalos, desplazamientos de día, estado y advertencias. La pantalla permite corregir fecha y rangos, marcar LIBRE o añadir una entrada manual. Las correcciones no sobrescriben el texto OCR original y conservan las advertencias iniciales por separado. Las fechas duplicadas y los rangos inválidos se rechazan al aplicar correcciones. Todo permanece en memoria: sobrevive a recreación de Activity, pero se descarta al cambiar/descartar la imagen o al morir el proceso. No existe importación definitiva ni escritura de turnos en Room.

## Identidad, fechas y horas

- La coincidencia exige la etiqueta completa normalizada: mayúsculas, espacios y tildes. No se usan prefijos, distancias de edición ni coincidencia aproximada. Nombre y alias son alternativas exactas; si coinciden con varias filas, se exige confirmación explícita de una fila. La geometría dudosa o confianza baja en el nombre también requiere confirmación.
- Cabeceras admitidas: fecha ISO, día/mes/año con cuatro dígitos, día/mes con contexto compatible, o número de día con mes/año explícito. Se reconocen días de semana españoles y abreviaturas, juntos o en una fila adyacente. Un título inequívoco como OCTUBRE 2026 puede aportar contexto. Fechas inválidas, duplicadas, días de semana discordantes y meses contradictorios producen advertencias; la información insuficiente deja la fecha pendiente. No se adivina un cambio de año.
- Los intervalos exigen minutos explícitos (`08:00-16:00`), con separadores de rango admitidos y varios rangos separados por `/`, espacios u otros separadores soportados. Se conserva el orden. `22:00-02:00` termina al día siguiente; `24:00` solo se acepta como salida. Se rechazan solapamientos, horas inválidas, basura adicional y celdas que implicarían varios cambios de medianoche.
- LIBRE, DESCANSO y OFF son días libres explícitos. Una celda vacía o códigos como L/D/F no se convierten en libre. Tampoco se completan minutos ausentes ni se reparan caracteres OCR de forma silenciosa.
- Texto que cruza límites de columna impide asignar automáticamente sus horas. Desplazamientos, continuación en varias líneas, filas próximas, baja confianza y columnas extra requieren revisión. Si falta geometría de alguna hoja con texto se bloquea la reconstrucción, porque podría corresponder a otro trabajador.

## Límites conocidos y revisión pendiente

La estrategia inicial soporta variantes horizontales con cabeceras completas, separadas, contexto mensual y celdas multilínea. No interpreta tablas transpuestas, rotadas, con varias tablas simultáneas, cabeceras fusionadas complejas o formatos arbitrarios. Esos formatos pueden requerir corrección manual o una estrategia futura detrás de la misma interfaz. Las tolerancias geométricas son heurísticas conservadoras, no una garantía para todos los diseños. Dos nombres diferentes que solo se distingan por tildes quedan normalizados igual y requieren confirmación si aparecen ambos.

La revisión sigue siendo necesaria incluso cuando el estado indica interpretado. Un desplazamiento completo de una columna puede ser indistinguible de una celda bien alineada sin líneas de cuadrícula adicionales. No se identifica al trabajador fuera de la selección explícita del usuario. No se deducen reglas laborales, descansos, remuneración ni clasificación salarial del cambio de día.

El límite OCR permanece en 2048 píxeles; `ImageOcr` y `OcrDocument` no cambian. Tampoco cambian Room, esquemas, migraciones, contratos, tarifas ni cálculos. La aplicación sigue procesando localmente, sin permiso de Internet. No se han añadido capturas reales ni datos personales. La validación manual anterior de Fase 1.1 no constituye una validación manual de esta nueva interpretación con horarios reales.

## Comandos y resultados

Ejecutado el 13 de octubre de 2026 con JDK 17, SDK 35 y Gradle 8.11.1. Desde la raíz:

```powershell
.\gradlew.bat --no-daemon assembleDebug testDebugUnitTest lintDebug lintVerification assembleVerificationAndroidTest
.\gradlew.bat --no-daemon connectedVerificationAndroidTest
```

En esta máquina se utilizó el ejecutable Gradle 8.11.1 de la distribución del wrapper ya instalada y `JAVA_HOME` apuntando al JDK 17 disponible. No se actualizaron versiones.

| Comprobación | Resultado |
| --- | --- |
| Compilación debug y APK de tests verification | BUILD SUCCESSFUL |
| Tests locales | 43, 0 fallos, 0 errores |
| Tests Android | 10, 0 fallos, 0 errores; informe conectado en Samsung A53 / Android 16 |
| lintDebug | 0 errores, 43 advertencias |
| lintVerification | 0 errores, 47 advertencias |

Los 43 tests locales incluyen 23 del intérprete, 5 del parser horario, 3 del nuevo ViewModel y 12 de regresión. Cubren distintos diseños sintéticos, fechas ausentes/contradictorias, medianoche, turnos partidos, coincidencias exactas, duplicados, geometría insuficiente, columnas desplazadas, filas próximas y correcciones manuales. Durante el desarrollo un test de celda desconocida falló porque la protección de nombres ahora exigía confirmar la fila; se ajustó su expectativa para comprobar el bloqueo seguro, y la ejecución completa posterior pasó.

Los 10 tests Android incluyen los 9 de regresión y un flujo nuevo: una tabla generada con Canvas, etiquetas ficticias ALFA/BETA, OCR real de ML Kit, selección exacta de ALFA, revisión, corrección a un intervalo nocturno y conservación tras recrear Activity. Los selectores devuelven URI sintéticos mediante Espresso Intents; no se afirma haber repetido manualmente la navegación por el proveedor del sistema. Las fixtures se generan en caché de `com.turnocontrol.verification` y se eliminan; los datos del APK privado quedan separados.

El informe XML conectado registra los 10 casos completados sin fallos. Tras reanudar la sesión, ADB mostró el dispositivo como unauthorized, por lo que no se pudo hacer una comprobación adicional del ajuste temporal de pantalla: antes de los tests era 0, el comando lo cambió a 2 y programó su restauración a 0 mediante finally. La restauración no se confirma desde esta sesión. No afecta al resultado registrado de los tests; si la pantalla permanece encendida al cargar, revisar ese ajuste cuando se autorice ADB.

Las advertencias lint mantienen los totales previos: versiones de AGP/dependencias, traslado de versiones a TOML y recomendación de KSP frente a kapt. No se añadieron supresiones ni baseline. Los informes generados permanecen ignorados en `app/build/`.

## Archivos de esta fase

Nuevos: `domain/ScheduleInterpretation.kt`, `domain/WorkerRowsInterpreter.kt`, `di/ScheduleModule.kt`, `ui/ScheduleReviewViewModel.kt`, `ui/ScheduleReviewScreen.kt`, tests locales `TimeCellParserTest.kt`, `WorkerRowsInterpreterTest.kt`, `ScheduleReviewViewModelTest.kt` y este documento.

Modificados: `MainActivity.kt`, `ui/ImportScreen.kt`, test Android `OcrImportTest.kt` y `README.md`. Las rutas Kotlin están bajo `app/src/main/java/com/turnocontrol/`, las de tests bajo sus source sets equivalentes.

La revisión de archivos versionados y nuevos no ignorados no encontró imágenes/documentos, binarios generados, secretos conocidos ni datos personales reales entre los candidatos. Las coincidencias del patrón de secretos fueron únicamente parámetros Kotlin llamados `token`, revisados como falsos positivos. `git diff --check` terminó correctamente; Git avisó de su conversión habitual LF→CRLF. Se comprobó que no hay cambios en datos, configuración de dominio, esquemas, manifiesto o dependencias. No hay cambios preparados en el índice: 4 archivos modificados y 9 nuevos, incluido este informe. HEAD permanece en el commit base y no se crea un commit hasta la revisión del usuario.

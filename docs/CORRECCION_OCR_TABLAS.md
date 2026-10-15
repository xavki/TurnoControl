# Corrección OCR de tablas semanales — validación manual aprobada

Corrección autorizada sobre el trabajo pendiente de TurnoControl. Sin commit, push, merge o publicación. No se modifican calendario, Room 4, contratos, tarifas, lógica de extras ni motor ML Kit. Fecha de verificación: 13 de octubre de 2026.

## Diagnóstico confirmado

Se reprodujo localmente el rechazo de un informe semanal privado: ML Kit reconocía texto, pero el intérprete devolvía UNSUPPORTED_LAYOUT antes de elegir trabajador. La detección anterior exigía que todos los elementos de la cabecera fueran fechas o etiquetas admitidas; una columna auxiliar impedía reconocer la tabla.

La perspectiva también desplazaba columnas respecto a sus cabeceras, especialmente la primera columna en la parte inferior. Las bandas x fijas podían incluir contenido del día en la etiqueta del trabajador. Las bandas verticales comenzaban junto al nombre y podían excluir la primera línea de un turno partido. El parser rechazaba anotaciones de función y desconocía LIB/VAC. Cuando fallaba la coincidencia exacta, no se ofrecían otros trabajadores.

La imagen privada solo se procesó mediante un diagnóstico temporal y aislado en Android. Se transmitió directamente desde el archivo original a la caché privada de la variante verification, sin copia intermedia en el repositorio o en almacenamiento compartido. Se eliminaron la copia de caché y el código diagnóstico temporal; el archivo original no se modificó. Solo se emitieron estadísticas y códigos de error, sin texto reconocido, nombres ni horas. La imagen no forma parte de fixtures, documentación ni tests permanentes.

## Cambios

- `WeeklyReportInterpreter` añade una estrategia de siete fechas completas con trabajadores en filas. La estrategia anterior se conserva para otros formatos ya soportados. El periodo del título no se confunde con la cabecera de siete columnas.
- Las columnas auxiliares no se importan. Las coordenadas de los totales pueden servir para proyectar filas y columnas inclinadas, pero sus valores nunca se convierten en turnos. Si el rótulo inferior no es legible, un conjunto inequívoco de siete duraciones de totales aporta únicamente geometría y obliga a revisar la asignación.
- Las filas se delimitan entre etiquetas vecinas, incluyendo líneas por encima y debajo del nombre. Se recomponen códigos fragmentados en una segunda línea. Se normalizan espacios alrededor de puntos y paréntesis existentes, manteniendo letras, puntos y código completos. No hay coincidencia aproximada de identidad ni corrección automática de letras/dígitos.
- Si no hay coincidencia exacta, se muestran todas las filas detectadas. Una selección manual siempre requiere confirmación explícita. La vista previa ofrece ampliación, selección sobre polígonos de fila y delimitación de banda junto a los nombres; la lista y la corrección de días permanecen como alternativas.
- `ReportCellParser` admite LIB y las anotaciones conocidas (COC), (GER), (Sala), (Rep), también en celdas con dos intervalos. El texto original permanece visible. Una anotación desconocida, una hora incompleta, un cruce de columna o una geometría insuficiente no se reparan inventando valores.
- Se conserva la validación de medianoche, salidas 00:00/24:00 y turnos partidos. Los intervalos reconocidos se pasan a la confirmación en formato horario canónico para que las anotaciones no invaliden el guardado existente.
- VAC queda como ausencia/vacaciones provisional, distinta de FREE y sin intervalos. Se muestra entre los siete días, pero se excluye de los candidatos a guardar. No se añade estado a Room. La corrección manual explícita permite cambiar su interpretación; no se convierte automáticamente en libre.
- Todos los intervalos procedentes del OCR conservan isExtra=false. No se analizan colores ni resaltados para clasificar extras. Se mantienen la revisión obligatoria, confirmación final y resolución de conflictos existentes.

## Límites

La estrategia nueva requiere siete cabeceras de fecha completas y geometría suficiente. Si faltan fechas o no hay una reconstrucción fiable, se conserva la alternativa manual; no se rellenan días a partir de la fecha actual. La perspectiva extrema, filas fusionadas o anotaciones manuscritas pueden requerir delimitar la banda y corregir celdas.

En la reproducción privada ya se recuperan las filas y siete propuestas para la selección, incluidos los intervalos multilínea. Permanecen códigos que ML Kit no transcribe con seguridad: se muestran como pendientes, sin sustituir letras dudosas por LIB. El usuario aprobó la validación manual del formato probado el 15/10/2026, incluida la corrección manual de horas dudosas. No se afirma una lectura perfecta de cualquier fotografía.

VAC no es persistible en Room 4 y no se guarda como libre. Los códigos distintos de los admitidos, por ejemplo otras ausencias o destinos escritos como texto libre, requieren revisión. El límite de 2048 píxeles y la orientación EXIF del motor OCR permanecen sin cambios; no se incorporan dependencias ni permisos.

## Pruebas reproducibles

```powershell
.\gradlew.bat --no-daemon --max-workers=1 assembleDebug assembleVerificationAndroidTest testDebugUnitTest lintDebug lintVerification
.\gradlew.bat --no-daemon --max-workers=1 connectedVerificationAndroidTest
```

Los tests permanentes utilizan únicamente tablas sintéticas, identidades inventadas y fechas ajenas a la reproducción privada. Cubren columnas auxiliares, totales, perspectiva vertical/horizontal, nombres con puntuación/códigos fragmentados, selección explícita, bandas manuales, celdas partidas con anotaciones, LIB/VAC, medianoche, códigos desconocidos y exclusión de vacaciones del guardado. La prueba Android genera su imagen con Canvas y ejecuta ML Kit real, selección visual y revisión de siete días; no utiliza la imagen privada.

| Comprobación final | Resultado |
| --- | --- |
| Compilación debug y APK de tests | BUILD SUCCESSFUL |
| Tests locales | 69, 0 fallos, 0 errores |
| Tests Android en Samsung A53 / Android 16 | 18, 0 fallos, 0 errores, sin diagnóstico temporal ni tests omitidos |
| lintDebug | 0 errores, 43 advertencias existentes |
| lintVerification | 0 errores, 47 advertencias existentes |
| Regresión | Calendario, extras, configuración, persistencia, migraciones hasta Room 4 y OCR anterior incluidas en las suites |
| Privacidad | Diagnóstico y copia privada temporal eliminados; no hay imagen ni identidades reales en candidatos Git |
| Ajuste temporal de pantalla | Restaurado y comprobado en 0 |
| APK privado | Actualizado mediante install -r y abierto con Status: ok, sin desinstalar ni borrar datos |
| Permisos | Sin Internet, estado de red ni almacenamiento general; solo permiso interno de receptores |

Incidencias durante desarrollo: lint detectó lectura de StateFlow.value en composición al pasar la vista previa; se sustituyó por observación con ciclo de vida, sin suprimir lint. La automatización de selección se adaptó al diálogo independiente: los botones fijos del diálogo no necesitan performScrollTo. La fixture Android se centró dentro de sus columnas para evitar que su propio texto generado invadiera la celda adyacente; se conserva la aserción de ambos intervalos y las pruebas independientes de columnas cruzadas/perspectiva.

Los candidatos a Git se revisaron sin encontrar imágenes reales, datos personales o secretos conocidos. Los informes y APK generados permanecen ignorados. No hay cambios preparados ni commit nuevo; HEAD sigue en la base estable. El estado incluye el trabajo pendiente anterior, que se conserva.

## Archivos

Nuevos de esta corrección: `domain/WeeklyReportInterpreter.kt`, `domain/ReportCellParser.kt`, `ui/OcrRowSelector.kt`, test local `domain/WeeklyReportTest.kt` y este informe.

Actualizados: `domain/WorkerRowsInterpreter.kt`, `domain/ScheduleInterpretation.kt`, `ui/ScheduleReviewViewModel.kt`, `ui/ScheduleReviewScreen.kt`, únicamente el enlace de revisión en `MainActivity.kt`, `ScheduleReviewViewModelTest.kt`, `OcrImportTest.kt` y referencias documentales. No se descarta trabajo pendiente anterior.

## Resultado de la prueba manual — 15/10/2026

**Estado: APROBADO. Validación manual comunicada por el usuario**, realizada en su Samsung A53 con una fotografía real de su horario laboral. Estos resultados proceden de su confirmación; no constituyen una nueva ejecución automatizada por Codex.

1. Detección de la tabla y las siete fechas: correcto.
2. Identificación de la fila del trabajador: correcto.
3. Selección directa de la fila sobre la fotografía: correcto.
4. Reconocimiento de turnos partidos y salidas a medianoche: correcto.
5. Diferenciación entre LIB y VAC: correcto.
6. Corrección manual de horas dudosas: correcto.
7. Ausencia de clasificación automática de horas extras: correcto.
8. Confirmación de los turnos del trabajador y respeto de los registros existentes: correcto.

**Observaciones del usuario: ninguna.** La corrección OCR queda cerrada funcionalmente para el formato probado. Se mantienen las limitaciones documentadas para otros formatos y lecturas ambiguas. No se incorpora la fotografía, nombres ni horarios reales al repositorio.

Esta validación no implica publicación de la aplicación ni autoriza cambios adicionales fuera del alcance acordado. El commit del trabajo acumulado sigue pendiente de autorización explícita.

## Pasos de la prueba manual

1. Ajustes → Importar horario → seleccionar la fotografía desde el dispositivo.
2. Abrir la revisión. Probar el nombre/alias completo; si no coincide exactamente o tiene baja confianza, elegir explícitamente la fila detectada.
3. Abrir Seleccionar otra fila o fotografía → Seleccionar en fotografía. Ampliar si hace falta, tocar la fila y confirmar. Alternativamente usar la lista o delimitar la banda junto a los nombres.
4. Comparar los siete días con la imagen. Comprobar las dos líneas de los partidos, salidas al día siguiente, libres y ausencias. En las celdas dudosas introducir los rangos o LIB y pulsar Aplicar corrección; no aceptar una lectura incierta.
5. Verificar que VAC se distingue como no guardable y queda fuera de la confirmación. Revisar los conflictos con días existentes antes de confirmar cualquier guardado.
6. Comprobar que no se ha marcado ninguna extra por color o anotación. La clasificación de extras sigue siendo una decisión manual independiente.

La prueba manual fue aprobada por el usuario el 15/10/2026. No se crea commit sin autorización expresa.

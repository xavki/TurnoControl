# Fase 1.1: importación de imágenes y OCR local

Base: `a02e0bb24d27f52be0147cda53d8661ec4d43fa5`. Cierre de Fase 1.1 mediante un único commit local, con validación automatizada y validación manual comunicada por el usuario. Sin push, merge o publicación. No se implementa Fase 1.2.

## Alcance y arquitectura

Un módulo `app`. Calendario abre la nueva pantalla «Importar imagen · OCR». Galería utiliza `PickVisualMedia(ImageOnly)` de AndroidX Activity; AndroidX elige Photo Picker, su alternativa disponible o `ACTION_OPEN_DOCUMENT`. El botón «Seleccionar archivo» utiliza directamente `OpenDocument(image/*)`. Las imágenes guardadas desde WhatsApp se pueden seleccionar si están accesibles mediante esos proveedores. No se accede directamente a carpetas de WhatsApp ni se solicitan permisos de almacenamiento general.

`ImportViewModel` expone un `StateFlow` con carga, vista previa, resultado y mensaje. Hilt enlaza `ImageOcr` con `LocalImageOcr`. La lectura y la decodificación se ejecutan en IO; ML Kit procesa su bitmap con rotación ya aplicada. El modelo latino se incluye en el APK (`com.google.mlkit:text-recognition:16.0.1`), sin descarga del modelo al primer uso. No hay backend. El manifiesto elimina `INTERNET` y `ACCESS_NETWORK_STATE` de dependencias transitivas, también para impedir el transporte de telemetría del SDK. Esto se verifica en el APK y en Android.

Solo se admiten URI `content://` del selector. No se conserva el URI ni se solicita un permiso persistente. Imagen y resultado viven en memoria del ViewModel: sobreviven a recreación de Activity, pero no al cierre del proceso. «Descartar» elimina el estado. Cancelar reconocimiento impide mostrar resultados tardíos; una operación nativa ya iniciada termina antes de cerrar su reconocedor. Un mutex limita el reconocimiento nativo a una imagen a la vez. No se registran imágenes, URI ni texto en logs propios. ML Kit puede mantener sus recursos y preferencias internas en el almacenamiento privado; la aplicación no escribe una copia de la imagen ni un documento OCR.

Un proveedor externo de archivos puede descargar una imagen de su nube antes de entregarla; esa operación pertenece al selector/proveedor. TurnoControl procesa el contenido localmente y carece de permiso de Internet. Para una prueba completamente local, seleccionar una imagen guardada en el dispositivo.

## Geometría para una fase futura

`OcrDocument` conserva el texto, dimensiones del bitmap procesado y bloques en el orden del SDK. Cada `OcrRegion` contiene texto, rectángulo opcional, esquinas, idioma y sus hijos: líneas dentro de bloques, elementos dentro de líneas. Líneas y elementos conservan confianza y ángulo suministrados por ML Kit; no se calculan puntuaciones propias. La confianza de bloque es `null` porque el SDK no la proporciona. Una confianza baja o cero se conserva tal como llega.

El origen es la esquina superior izquierda de la **vista previa orientada y reducida**, en píxeles; no son coordenadas de pantalla ni del archivo original. `ImportedImage` conserva también ancho/alto originales y orientación EXIF. Las esquinas pueden exceder los límites de la imagen según el SDK, por lo que no se recortan. No hay reconstrucción de tablas, identificación de personas, interpretación de celdas, horarios o turnos.

## Errores y límites

Lectura limitada a 20 MiB aunque el proveedor no informe del tamaño. Decodificación con muestreo hasta un máximo de 2048 píxeles por lado y corrección de orientación/reflejo EXIF. La reducción puede perder texto pequeño: conviene recortar o guardar una versión más nítida antes de seleccionarla. No se afirma que todas las imágenes o formatos sean legibles; se utiliza la capacidad de decodificación de Android y EXIF de AndroidX. No hay importación de PDF, cámara, OCR por lotes, editor de texto ni exportación.

La pantalla distingue selección cancelada, carga, fallo de acceso/decodificación/reconocimiento y ausencia de texto. Muestra la vista previa mientras reconoce y permite descartar o volver a seleccionar. Reemplazar una imagen limpia el resultado anterior para evitar asociar textos a imágenes distintas.

Room, sus esquemas/migración, DataStore, configuración de perfil/contrato y cinco tarifas no cambian. No hay reglas nuevas de nómina, notificaciones ni persistencia de turnos.

## Comandos reproducibles

JDK 17, SDK Android 35 y Gradle Wrapper 8.11.1, como en Fase 0. Las dependencias de compilación necesitan red; el OCR instalado no.

```powershell
.\gradlew.bat --no-daemon assembleDebug lintDebug lintVerification testDebugUnitTest
.\gradlew.bat --no-daemon connectedVerificationAndroidTest
# Comprobación adicional de regresión de Fase 0:
powershell -NoProfile -File .\scripts\verify-restart.ps1
```

Las pruebas Android usan `com.turnocontrol.verification`; el APK privado del usuario queda separado. El `FileProvider` de fixtures solo existe en `src/verification`, nunca en el APK debug privado. Las imágenes se generan durante las pruebas con `Canvas`, contienen únicamente «PRUEBA OCR / ALFA BETA 123», se guardan en una subcarpeta de caché de la variante aislada y se eliminan al terminar. No se añaden imágenes binarias ni datos reales a Git.

## Verificación de la implementación previa al cierre

Ejecutado el 13 de octubre de 2026, con JDK 17 y el SDK local indicado en el README:

| Comprobación | Resultado |
| --- | --- |
| `assembleDebug` | Correcto; APK privado de depuración generado |
| `testDebugUnitTest` | 12 tests, 0 fallos: 6 previos y 6 nuevos del ViewModel |
| `connectedVerificationAndroidTest` | 9 tests, 0 fallos en Samsung A53 / Android 16: 4 previos y 5 nuevos |
| `lintDebug` | 0 errores, 43 advertencias de mantenimiento |
| `lintVerification` | 0 errores, 47 advertencias de mantenimiento, incluyendo fuentes de pruebas Android |
| `scripts/verify-restart.ps1` | Correcto: seed y verify con `OK (1 test)`, proceso relanzado y perfil, contrato y cinco tarifas conservados |
| Permisos del APK | `aapt dump permissions` y test Android: sin Internet, estado de red ni almacenamiento general; solo permiso interno de receptores |
| Proveedor de fixtures en APK privado | Ausente del manifiesto fusionado debug |
| Revisión de candidatos Git | Sin imágenes/documentos reales, secretos conocidos, datos personales, APK, bases o archivos generados; `git diff --check` correcto |

Los tests nuevos Android comprueban importación por ambos contratos, vista previa/texto, recreación de Activity, descarte, cancelación de selección, archivo dañado, OCR sin texto, bloques/líneas/elementos, coordenadas/confianza, orientación EXIF y rechazo de archivos mayores de 20 MiB. Los tests unitarios comprueban carga con vista previa, resultado, cancelación, reemplazo, descarte, error de reconocimiento y pérdida de permiso del URI.

**Límite de las pruebas automatizadas de los selectores:** Espresso Intents simula la respuesta de Photo Picker/`ACTION_OPEN_DOCUMENT` con un URI de la imagen sintética. La pantalla, la lectura del proveedor, la decodificación y ML Kit se ejecutan realmente en Android, sin un OCR simulado. La prueba manual complementaria del usuario se recoge a continuación. No se han probado todos los formatos, fabricantes ni versiones Android.

## Validación manual comunicada por el usuario

El usuario ha comunicado una prueba satisfactoria en su Samsung A53 con una captura real de un horario laboral guardada desde WhatsApp. Estos resultados son su confirmación manual; el agente no ha inspeccionado la captura ni su contenido:

| Comprobación manual | Resultado comunicado |
| --- | --- |
| Galería | Importación y vista previa correctas |
| Seleccionar archivo | Importación y vista previa correctas |
| Nombres, días y horas | Reconocidos correctamente |
| Turnos partidos | Ambos intervalos reconocidos correctamente como texto |
| Texto pequeño | Reconocido sin omisiones |
| Dimensiones procesadas | 2048 × 1536 píxeles |
| Errores u observaciones | Ninguno |

Se conserva el límite actual de 2048 píxeles por lado, sin cambios de implementación. La prueba valida lectura de texto en esa captura; no supone interpretación de la tabla, identificación de trabajadores ni creación de turnos. No se ha recibido, copiado ni incorporado a Git la captura, nombres, horas u otros datos personales. Este informe conserva únicamente los resultados agregados comunicados.

## Comprobaciones finales de cierre

Repetidas el 13 de octubre de 2026, sin cambios funcionales respecto al APK validado manualmente:

| Comprobación final | Resultado |
| --- | --- |
| `--rerun-tasks assembleDebug testDebugUnitTest lintDebug lintVerification` | `BUILD SUCCESSFUL`; 90 tareas ejecutadas |
| Tests locales | 12 ejecutados, 0 fallos y 0 errores |
| `lintDebug` | 0 errores, 43 advertencias de mantenimiento |
| `lintVerification` | 0 errores, 47 advertencias de mantenimiento |
| `connectedVerificationAndroidTest` | `BUILD SUCCESSFUL`; 9 tests ejecutados en Samsung A53 / Android 16, 0 fallos y 0 omitidos |
| Permisos del APK recompilado | Solo permiso interno de receptores; sin Internet ni almacenamiento general |
| Candidatos Git | Fuentes, tests sintéticos y documentación; sin capturas reales, datos personales, secretos detectados ni archivos de ejecución generados |
| Alcance conservado | Límite de 2048 píxeles sin cambios; Room, esquemas, contratos, tarifas y reglas salariales sin modificaciones |

Durante el cierre, ADB requirió una nueva autorización. El primer intento conectado tuvo 4 fallos de interfaz mientras Android confirmaba el bloqueo de pantalla; las otras 5 pruebas pasaron. Tras desbloquear el dispositivo, se repitió la suite completa y pasaron los 9 tests. Para evitar otro bloqueo se activó temporalmente mantener la pantalla encendida por USB; al finalizar se restauró y comprobó el valor original `0`. No se modificó código para resolver esta incidencia del entorno ni se ocultaron los fallos del primer intento.

La auditoría abarcó los 42 archivos candidatos, incluidos los 15 archivos de esta fase, con revisión de tipos, contenido, patrones de credenciales y `git diff --check`. Los esquemas Room y la configuración laboral permanecen iguales a la base. Los informes, APK, imágenes sintéticas temporales y logcat quedan fuera del commit. El cierre se registra en un único commit local sobre la base indicada; su hash se obtiene con `git log -1 --format=%H` y se entrega al usuario. No se han ejecutado push, merge ni publicación.

## Historial de comprobaciones y auditoría

La primera ejecución conectada tuvo 3 fallos de test, corregidos y repetidos: el formulario previo concatenaba valores sobre los datos ficticios de una ejecución anterior, y dos aserciones OCR exigían «OCR» cuando ML Kit devolvía «0CR». `NavigationTest` ahora sustituye los campos y establece explícitamente el tema, para ser repetible. Las aserciones OCR verifican fragmentos estables («PRUEBA» y «ALFA BETA 123») y geometría; el texto bruto del SDK se conserva sin normalizar ni corregir. Dos ejecuciones conectadas posteriores completas pasaron los 9 tests.

Las 43 advertencias debug se distribuyen en `AndroidGradlePluginVersion` (2), `GradleDependency` (19), `UseTomlInstead` (21) y `KaptUsageInsteadOfKsp` (1). En verification son respectivamente 3, 22, 21 y 1. La base tenía 38 en debug; las nuevas dependencias aportan 3 recomendaciones de catálogo y 2 avisos de versiones más recientes (EXIF y Espresso). No se han ocultado advertencias ni actualizado en bloque la cadena de herramientas. Las versiones de pruebas se mantienen compatibles con la base. Gradle también informa de la diferencia de versiones XML del SDK, de opciones de procesadores no utilizadas en pruebas y de que empaqueta la biblioteca nativa OCR sin retirar símbolos; estas advertencias no impidieron compilar ni ejecutar.

Se revisaron los candidatos obtenidos con `git ls-files --cached --others --exclude-standard`, sus tipos y patrones de credenciales/identificadores. Los informes y logcat del dispositivo quedan únicamente en `app/build/`, ignorado; no se incorporan a Git. Room, configuración y esquemas no presentan diferencias respecto al commit base.

## Archivos de esta fase

- Modificados: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/turnocontrol/MainActivity.kt`, `app/src/androidTest/java/com/turnocontrol/NavigationTest.kt`, `README.md`.
- Nuevos: `domain/OcrDocument.kt`, `data/ImageOcr.kt`, `di/OcrModule.kt`, `ui/ImportViewModel.kt`, `ui/ImportScreen.kt` dentro del paquete principal; `src/test/.../ui/ImportViewModelTest.kt`; `src/androidTest/.../OcrImportTest.kt`; `src/verification/AndroidManifest.xml`, `src/verification/res/xml/ocr_fixture_paths.xml`; este informe.

## Limitaciones y alcance posterior

No quedan incidencias funcionales comunicadas en la prueba manual. Una captura satisfactoria no garantiza la precisión con otras imágenes, fotos borrosas o todos los fabricantes/versiones de Android. Recorte/zoom y evaluación con otras imágenes autorizadas quedan para una decisión futura. No se decide ahora cómo interpretar tablas o asociar trabajadores. No se ha publicado ni validado una distribución Google Play. La Fase 1.2 requiere nueva autorización.

Referencias de implementación: [Photo Picker de Android](https://developer.android.com/training/data-storage/shared/photo-picker), [ML Kit OCR en Android](https://developers.google.com/ml-kit/vision/text-recognition/v2/android), [metadatos de líneas](https://developers.google.com/android/reference/com/google/mlkit/vision/text/Text.Line).

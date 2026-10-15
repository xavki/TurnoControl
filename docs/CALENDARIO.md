# Rediseño autorizado: calendario y planificación persistente

> Registro del primer calendario persistente con Room 3. El rediseño visual posterior, la rueda y Room 4 se documentan en [REDISENO_INTERFAZ.md](REDISENO_INTERFAZ.md); las pantallas y resultados descritos aquí corresponden a la validación anterior.

Base estable: `dbc65171ad7ca242289b28c35473ce1398c39e27`. Se conserva el trabajo pendiente de interpretación de Fase 1.2. Implementación del calendario, almacenamiento y migración 2→3 autorizada por el usuario; sin commit, push, merge ni publicación. Fecha de validación: 13 de octubre de 2026.

## Flujo funcional

Calendario es la pantalla inicial, con mes anterior/siguiente, Hoy y selección de día. Se distingue Trabajo (T), Libre (L) y Sin configurar (·), con etiquetas de accesibilidad; ↳ indica continuación nocturna. Inicio y las demás rutas se conservan. Fichajes y Nómina siguen sin funciones reales.

Seleccionar un día abre un editor con uno o varios campos HH:mm-HH:mm. Permite añadir/quitar intervalos, marcar libre, guardar o eliminar el día con confirmación. Quitar el último intervalo no convierte el día en libre: hay que marcarlo explícitamente o eliminar el día para dejarlo sin configurar. Cancelar no guarda. Recargar descarta el borrador y obtiene una nueva versión del calendario.

La planificación no exige contrato ni tarifas. En el primer guardado, si no hay perfil, se crea únicamente un perfil local «Mi calendario», alias vacío y zona del dispositivo. No se generan contratos, horas pactadas ni precios. La configuración laboral existente y sus reglas de validación no cambian. El nombre/alias de selección OCR puede indicarse directamente en la revisión aunque no se haya configurado un contrato.

## Horarios, medianoche y validación

Cada registro pertenece a su fecha de inicio. `22:00-02:00` se guarda una sola vez con salida desplazada un día; el día siguiente muestra una continuación y remite al día de inicio para editarla. Los intervalos deben introducirse en orden cronológico. Después de cruzar medianoche, los siguientes intervalos de esa misma entrada continúan en el día siguiente, por ejemplo `22:00-02:00 / 03:00-05:00`. `24:00` se admite únicamente como salida.

Límites técnicos: precisión de minutos, duración positiva, máximo 24 horas por intervalo y máximo 24 horas sumadas por día de inicio. Solo se admiten desplazamientos de 0 o 1 día. No hay segundos, pausas inferidas ni turnos que crucen varias medianoches. Estos límites delimitan el formato de planificación, no establecen jornada legal o remuneración. El validador detecta duplicados, fechas duplicadas y solapamientos dentro del día y entre días, incluidos cambios de mes/año. Los extremos contiguos son válidos.

La zona horaria queda asociada al día guardado. Se conservan horas de reloj local, no instantes ni horas realmente trabajadas. No se calcula duración salarial ni se resuelven automáticamente horas ambiguas/inexistentes de los cambios de horario de verano.

Libre significa que no hay turnos iniciados en esa fecha. Puede coexistir con una continuación nocturna del día anterior, que se muestra con ↳ y no se duplica ni se borra. Esto mantiene la pertenencia al día de inicio y evita deducir reglas laborales sobre descansos.

## Fotografías y conflictos

Importar imagen reutiliza Photo Picker/OpenDocument, vista previa, ML Kit local y el límite de 2048 píxeles. No hay dependencias nuevas ni permisos generales de almacenamiento/red.

El intérprete reutilizado sigue siendo conservador y admite trabajadores en filas y fechas en columnas. Nombre o alias completo, duplicados con confirmación explícita, fechas pendientes y advertencias se mantienen. Si no interpreta una tabla, se pueden añadir días manuales a revisión o volver al calendario e introducirlos allí. Si no hay texto legible, se puede cancelar la importación y continuar manualmente.

«Revisar conflictos y confirmar» captura una instantánea del calendario. Cada propuesta muestra el existente, texto propuesto y advertencias. Inicialmente todas conservan el calendario y ninguna está confirmada. Se puede Conservar, Añadir/Sustituir o Editar; la edición permite corregir fecha y rangos, incluso cuando el OCR fue incompleto. Cambiar contenido o decisión desmarca la confirmación. Solo se guardan los días seleccionados mediante «He revisado este día y confirmo guardarlo» y la acción final «Guardar días confirmados».

Una propuesta idéntica no modifica el registro ni incrementa revisión. Una sustitución afecta únicamente al día elegido. El guardado del lote es atómico: vuelve a comparar la instantánea y valida todos los turnos resultantes, también los adyacentes. Si algo cambió o hay un conflicto no resuelto, no guarda ningún día y pide volver a revisar. El control es conservador: cualquier cambio del calendario completo invalida una instantánea abierta, aunque corresponda a otro mes. No hay fusión automática ni sobrescritura silenciosa.

## Room 3

- `ScheduledDay`: id autogenerado, profileId, fecha ISO, WORK/FREE, zona, origen MANUAL/OCR_REVIEWED y revisión. Índice único (profileId, date) y clave foránea a Profile.
- `ScheduledInterval`: id autogenerado, dayId, posición, minutos de inicio/fin y desplazamientos de día. Índice único (dayId, position), clave foránea al día y eliminación en cascada.
- `ScheduleDao`: lectura observable y operaciones internas. `ScheduleRepository` centraliza validación, comparación de instantáneas y transacciones. Las restricciones de intervalos y WORK/FREE se validan en el repositorio; no se presentan los DAO internos como API para escribir horarios arbitrarios.
- `MIGRATION_2_3` crea únicamente tablas/índices. No modifica datos de Profile, Contract o PayRule. `MIGRATION_1_2` permanece registrada; no hay migración destructiva ni datos de muestra en producción. El esquema 3 exportado por Room se incorpora como fuente de migración.

Los turnos son programados. No se crean tablas de fichajes reales, importaciones persistentes, imágenes ni texto OCR completo.

## Verificación reproducible

JDK 17, SDK 35, Gradle 8.11.1. Se usó la distribución ya instalada del wrapper con JAVA_HOME apuntando al JDK 17, sin actualizar dependencias.

```powershell
.\gradlew.bat --no-daemon --max-workers=1 assembleDebug assembleVerificationAndroidTest testDebugUnitTest lintDebug lintVerification
.\gradlew.bat --no-daemon --max-workers=1 connectedVerificationAndroidTest
powershell -NoProfile -File .\scripts\verify-restart.ps1
```

El script se ejecuta únicamente en `com.turnocontrol.verification`, instala con `-r`, usa datos ficticios y comprueba PID diferente tras detener/relanzar el proceso. Incluye perfil, contrato, cinco tarifas, turno partido y turno nocturno. No borra datos del paquete privado `com.turnocontrol`.

| Comprobación final | Resultado |
| --- | --- |
| Compilación debug y APK de tests | Correcta; validación final completa BUILD SUCCESSFUL |
| Tests locales | 54, 0 fallos, 0 errores |
| Tests Android en Samsung A53 / Android 16 | 14, 0 fallos, 0 errores |
| Migraciones 1→3 y 2→3 | Correctas en Robolectric y Android; perfil, contrato y cinco tarifas íntegros |
| Esquemas Profile/Contract/PayRule entre 2 y 3 | Estructuras idénticas; esquemas 1 y 2 sin cambios |
| Reinicio real del proceso | Script correcto: seed y verify con OK (1 test), PID distinto y configuración + turnos partidos/nocturnos conservados |
| lintDebug | 0 errores, 43 advertencias |
| lintVerification | 0 errores, 47 advertencias |
| Ajuste temporal de pantalla | Restaurado y comprobado en 0; también comprobado después de actualizar el APK privado |
| APK debug privado | Actualizado con install -r: Success; apertura COLD con Status: ok; sin desinstalación ni borrado de datos |
| Permisos del APK | Sin Internet, acceso al estado de red ni almacenamiento general; solo permiso interno de receptores |

Los tests locales incluyen migraciones, reapertura, edición/eliminación, transacciones, conflictos, protección de configuración, ViewModels y regresión OCR. La suite Android incluye el flujo de calendario manual y la importación sintética con OCR real, revisión, confirmación explícita y guardado. Los selectores siguen simulando su respuesta mediante Espresso Intents: la navegación manual por proveedores del sistema y la evaluación visual del rediseño por el usuario quedan pendientes. No se afirma haber repetido manualmente una fotografía real.

Las advertencias de lint mantienen los totales previos y corresponden a mantenimiento de versiones, catálogo TOML y kapt/KSP. No se introdujeron supresiones ni baseline. La advertencia de herramientas SDK sobre versiones XML y las opciones de procesador no reconocidas no impidieron la validación.

La revisión final de archivos versionados y nuevos no ignorados no detectó imágenes/documentos reales, datos personales, secretos conocidos, APK, bases de datos ni cachés entre los candidatos. El esquema 3 es un artefacto fuente exportado de Room autorizado para control de versiones. `git diff --check` correcto; únicamente avisos habituales LF→CRLF. No hay archivos preparados en el índice ni commit nuevo. HEAD sigue en la base estable.

Incidencias investigadas: dos tests de interfaz fallaron inicialmente porque el punto de entrada Hilt para acceder a la base compartida estaba declarado en el APK de tests. Se movió únicamente a `src/verification`, para que forme parte de la aplicación aislada y no del APK privado. Después quedó un fallo de automatización al buscar el botón de confirmación fuera del área compuesta de LazyColumn; el test ahora desplaza la lista hasta ese elemento. También se observó una carrera interna lint/kapt al regenerar stubs en paralelo: la validación con `--max-workers=1` completó lint correctamente, sin suprimir análisis ni advertencias.

## Prueba manual en Samsung A53

1. El APK debug ya está actualizado y abierto en el Samsung, sin borrar datos. Para repetir la actualización: `adb install -r app/build/outputs/apk/debug/app-debug.apk`; no desinstalar ni borrar almacenamiento. Abrir TurnoControl, paquete `com.turnocontrol`.
2. Comprobar que abre Calendario. Navegar entre meses y volver con Hoy.
3. Elegir un día sin configurar. Introducir `08:00-12:00`, Añadir intervalo y `16:00-20:00`; Guardar día. Debe aparecer Trabajo.
4. Reabrirlo, quitar el segundo intervalo y cambiar el primero a `22:00-02:00`. Guardar: el siguiente día debe mostrar ↳; la continuación se edita desde el día original.
5. Cerrar el proceso y volver a abrir: los intervalos deben permanecer. Editar o eliminar con confirmación; marcar otro día como libre y distinguirlo de uno vacío.
6. Importar imagen desde Galería o Seleccionar archivo. Revisar trabajador, fechas y horas. En confirmación, comprobar que nada está seleccionado inicialmente. Comparar un día existente y probar Conservar, Sustituir o Editar; marcar solo los días revisados y guardar.
7. Probar un intervalo inválido o solapado: debe mostrarse un error sin guardar parcialmente. Si el OCR falla, continuar con la entrada manual.

Usar datos ficticios para verificaciones automatizadas. No incorporar capturas reales, nombres de personas ni texto de horarios al repositorio o al informe. La prueba manual del rediseño por el usuario queda pendiente de su revisión.

## Archivos

Nuevos del rediseño: `data/ScheduleStorage.kt`, `data/ScheduleRepository.kt`, `domain/PlannedSchedule.kt`, `ui/CalendarViewModel.kt`, `ui/CalendarScreen.kt`, esquema `app/schemas/com.turnocontrol.data.TurnoDatabase/3.json`, tests `ScheduleMigrationTest`, `ScheduleRepositoryTest`, `PlannedScheduleTest`, `CalendarViewModelTest`, `CalendarTest`, `SchedulePersistenceTest`, soporte aislado `src/verification/java/com/turnocontrol/CalendarTestDatabase.kt` y este informe.

Modificados: `MainActivity.kt`, `data/Database.kt`, `di/StorageModule.kt`, `ui/ImportScreen.kt`, `ui/ScheduleReviewScreen.kt`, `NavigationTest.kt`, `OcrImportTest.kt`, `RestartPersistenceTest.kt`, `RoomTest.kt`, `scripts/verify-restart.ps1`, `README.md` y la nota histórica de `docs/FASE_1_2.md`.

Se conservan los archivos pendientes útiles de Fase 1.2: intérprete, modelos provisionales, inyección, ViewModel/pantalla de revisión y sus tests. No cambian `ImageOcr`, `OcrDocument`, ConfigurationRepository, contratos, tarifas, dependencias ni los esquemas exportados 1 y 2. Los informes y APK generados permanecen ignorados por Git.

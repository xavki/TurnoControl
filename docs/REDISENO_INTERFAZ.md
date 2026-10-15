# Rediseño visual y horas extras declaradas — registro histórico

> Contexto del cierre local de v0.1.0 (15/10/2026): este informe conserva las condiciones, fechas y resultados de su etapa. Las menciones a trabajo sin commit o revisiones pendientes describen ese momento; el trabajo acumulado ya está consolidado en la base pública saneada `d76842e727cdca4f853531b5ee7995a151a94867`. Los hashes de base se refieren al historial saneado. La preparación actual se documenta en [PREPARACION_V0_1_0.md](PREPARACION_V0_1_0.md).

Base estable: `28861ff3fe4ce4382f07e2b2d973c3dce1fc674f`. Se conservan los cambios pendientes de OCR, interpretación y calendario persistente. El usuario autorizó este rediseño y expresamente Room 3→4. No se crea commit, push, merge ni publicación. La aceptación visual y funcional por el usuario sigue pendiente.

## Pantallas y navegación

Cuatro pestañas: Calendario, Horas extras, Nómina y Ajustes. Se retiran las rutas vacías Inicio/Fichajes porque la primera duplicaba el acceso al calendario y la segunda no tenía funcionalidad real. No se elimina lógica de fichajes ni datos: nunca se habían implementado.

Calendario ocupa la pantalla inicial con mes/año, flechas, Hoy y Mes/Semana. La semana comienza en lunes. Cambiar de mes conserva el número de día cuando existe, ajustándolo al último del mes cuando es necesario. Se distinguen selección (fondo), Hoy (borde), trabajo (punto sólido), libre (anillo) y continuación nocturna (↳); las etiquetas de accesibilidad incluyen fecha y estado. Los días adyacentes completan la cuadrícula y se pueden seleccionar. Con altura insuficiente la cuadrícula permite desplazamiento, manteniendo celdas de al menos 48 dp de altura.

El detalle del día es una pantalla dedicada con fecha completa, estado, intervalos, desplazamientos, pausas, total programado, subtotal extra y + inferior. Los totales se asignan al día de inicio, sin volver a contar continuaciones al día siguiente. Libre implica que no hay intervalos iniciados en esa fecha y puede mostrar una continuación anterior. Sin configurar sigue siendo ausencia de ScheduledDay; no se añade un tercer estado persistido.

Marcar libre sobre trabajo exige confirmación. Eliminar día o intervalo también exige confirmación. Al eliminar el último intervalo se ofrece Sin configurar o Libre. Añadir un registro a un día libre lo convierte en trabajo al guardar. Las continuaciones remiten al día de inicio para editarse.

Horas extras muestra exclusivamente los intervalos declarados extra, agrupados por fecha de inicio y con total, incluido el acceso a su detalle. Nómina conserva su marcador previo. Ajustes agrupa importación, perfil, configuración laboral y apariencia; el modo oscuro queda abajo. No se exige configurar contrato o tarifas para utilizar el calendario.

## Editor y rueda

Normal crea un intervalo y Partido crea dos; cada intervalo tiene su propio indicador de extra. Un intervalo existente se edita por separado. Se conserva la representación común de intervalos y el validador de planificación; el editor visual ya no serializa datos a texto para guardar.

Los campos de entrada/salida abren un diálogo de dos ruedas Compose, sin dependencias nuevas: horas 00–23 y minutos 00–59, desplazamiento con ajuste al centro, selección destacada y Confirmar/Cancelar. Los números son pulsables y existen botones y acciones de accesibilidad para aumentar/reducir. Los campos nuevos permanecen sin valor hasta que se confirma una selección; no se guardan horarios de muestra ni valores ficticios. Cancelar la rueda conserva el campo y cancelar el registro no escribe datos.

Cada entrada y salida tiene Mismo día / Día siguiente. Para 18:00–00:00 normal y 00:00–01:00 extra: el primero empieza en Mismo día y termina en Día siguiente; el segundo empieza y termina en Día siguiente, dentro del mismo día de inicio. No se decide automáticamente que un intervalo es extra.

Los errores conservan el borrador. Se mantienen duración positiva, precisión de minutos, máximo de 24 horas sumadas por día de inicio, desplazamientos de 0/1 día, rechazo de duplicados y solapamientos internos/entre fechas, y transacciones atómicas. No hay cálculos salariales ni fichajes reales. Las pausas son la diferencia entre intervalos consecutivos y se excluyen del total. Las extras forman parte del total programado y se muestran también como subtotal: no se suman de nuevo.

## Room 4 y conservación

Única ampliación: `ScheduledInterval.isExtra: Boolean = false`, con `@ColumnInfo(defaultValue = "0")`.

```sql
ALTER TABLE ScheduledInterval ADD COLUMN isExtra INTEGER NOT NULL DEFAULT 0;
```

`MIGRATION_3_4` se registra junto con 1→2 y 2→3. No hay migración destructiva, nuevas tablas, reclasificación automática ni cambios en Profile, Contract, PayRule o ScheduledDay. El esquema 4 se exporta mediante Room; los esquemas 1–3 se conservan. Los intervalos antiguos quedan con isExtra=false y mantienen identificadores, orden, horas y desplazamientos.

La marca se incluye en lectura, guardado, comparación de igualdad y revisión de conflictos. Cambiar solo la clasificación incrementa revisión e invalida una instantánea anterior. Se prueba la conservación completa de perfil, contrato y cinco tarifas en 1→4, 2→4 y 3→4; la última incluye turnos partidos/nocturnos existentes.

## OCR

Ajustes → Importar horario conserva Photo Picker/OpenDocument, vista previa, ML Kit local, trabajador exacto, revisión, exclusión, conflictos y confirmación explícita. El OCR produce siempre intervalos sin marca extra; nunca deduce esa clasificación. La confirmación muestra las marcas existentes y advierte que una sustitución también las reemplaza. Se puede conservar el día existente o reclasificar manualmente después de guardar. Imágenes y texto completo siguen siendo temporales, sin persistencia ni Internet; se mantiene el límite de 2048 píxeles.

## Validación reproducible

```powershell
.\gradlew.bat --no-daemon --max-workers=1 assembleDebug assembleVerificationAndroidTest testDebugUnitTest lintDebug lintVerification
.\gradlew.bat --no-daemon --max-workers=1 connectedVerificationAndroidTest
powershell -NoProfile -File .\scripts\verify-restart.ps1
```

Entorno: JDK 17, SDK 35, Gradle 8.11.1. Se utiliza un solo worker para evitar la carrera lint/kapt observada en la ejecución anterior. Las pruebas Android y el script usan exclusivamente `com.turnocontrol.verification` con datos sintéticos. Verificación final del 13 de octubre de 2026:

| Comprobación | Resultado |
| --- | --- |
| Compilación debug y tests verification | BUILD SUCCESSFUL |
| Tests locales | 59, 0 fallos, 0 errores |
| Tests Android, Samsung A53 / Android 16 | 17, 0 fallos, 0 errores |
| Migraciones 1→4, 2→4 y 3→4 | Correctas en Robolectric y Android; configuración íntegra y turnos antiguos sin marca extra |
| Estructura de Profile, Contract, PayRule y ScheduledDay entre 3 y 4 | Idéntica; únicamente se añade isExtra a ScheduledInterval |
| Reinicio real | seed y verify con OK (1 test), PID distinto; perfil, contrato, cinco tarifas, turno partido, nocturno y marca extra conservados |
| lintDebug | 0 errores, 43 advertencias |
| lintVerification | 0 errores, 47 advertencias |
| Ajuste temporal de pantalla | Restaurado y comprobado en 0 al terminar |
| APK debug privado | Actualizado con install -r: Success; apertura COLD con Status: ok; sin desinstalar ni borrar datos |
| Permisos APK | Sin Internet, acceso al estado de red ni almacenamiento general; solo permiso interno de receptores |

Las advertencias lint son las de mantenimiento existentes, sin nuevas supresiones o baseline. La suite Android utiliza la rueda real, verifica precisión de un minuto y cancelación, detalle, extras, cambio de año, eliminación y regresión de ML Kit. Los selectores de imágenes mantienen respuestas sintéticas mediante Espresso Intents; no se afirma haber repetido la importación manual de una captura real ni la evaluación visual/táctil del usuario.

La revisión de candidatos Git no encontró imágenes reales, datos personales, secretos conocidos, APK, bases de datos o cachés. Los esquemas exportados son fuentes de migración autorizadas para versionar. `git diff --check` correcto, con avisos habituales LF→CRLF. Estado acumulado, incluyendo trabajo anterior: 10 archivos modificados y 28 nuevos, sin preparar. HEAD permanece en la base estable, sin commit nuevo. El ajuste de pantalla se volvió a comprobar en 0 después de abrir el APK privado.

Incidencias corregidas durante implementación: importación de la propiedad de accesibilidad `selected` ausente y orden de operandos del cálculo de tamaños Dp. Ambas fueron errores de compilación detectados antes de instalar el rediseño. No se suprimieron comprobaciones para resolverlos. También se ajustó el retorno OCR para abrir siempre el mes importado y la recuperación de un detalle sin selección en memoria para volver al calendario.

Las pruebas nuevas cubren navegación mensual/semanal y año, selector con minutos y cancelación, turnos partidos, marca por intervalo, extra posterior a medianoche, subtotal sin doble conteo, pausas, eliminación del último intervalo, conservación del borrador inválido, cambio de clasificación como conflicto y migraciones hasta 4. Se mantienen los tests previos de OCR y configuración, adaptando únicamente las rutas y controles que han cambiado.

## Límites y revisión manual pendiente

- Los totales son minutos nominales de reloj local, no duración salarial ni tiempo fichado. No se resuelven automáticamente horas ambiguas/inexistentes por cambio de horario de verano.
- Si se elimina el único intervalo que ancla el día de inicio y quedan intervalos +1, se rechaza la operación para evitar un registro huérfano. Hay que editar esos intervalos para asignarlos a su fecha de inicio correcta o eliminarlos primero.
- Normal/Partido son modos de entrada. Se persisten intervalos, no grupos de turnos; las pausas se muestran entre intervalos consecutivos del día.
- El intérprete mantiene su soporte de trabajadores en filas y fechas en columnas. Otros formatos pueden completarse manualmente.
- La rueda no es circular infinita: respeta límites 00–23 y 00–59. La comprobación automática no sustituye la evaluación táctil, visual y con TalkBack por el usuario.

## Pasos de prueba en Samsung A53

1. El APK privado ya está actualizado y abierto en el Samsung. Comprobar que solo aparecen las cuatro pestañas. Revisar Mes/Semana, flechas entre diciembre/enero, Hoy, selección y modo oscuro desde Ajustes.
2. Elegir un día sin configurar → + → Normal. Usar las ruedas, cancelar una elección y verificar que el campo no cambia. Guardar un intervalo válido.
3. Crear un Partido 08:00–12:00 / 16:00–20:00, marcando solo el segundo como extra. Debe mostrar 8 h programadas, 4 h extra y una pausa de 4 h excluida del total.
4. En otro día, crear 18:00–00:00 con salida Día siguiente. Añadir 00:00–01:00 extra con entrada y salida Día siguiente. Debe mostrar 7 h programadas y 1 h extra, con continuación en el día siguiente y un solo día de inicio persistido.
5. Editar un intervalo y probar un solapamiento: el error debe conservar el borrador. Cancelar sin guardar. Probar Marcar libre sobre trabajo, cancelar la confirmación y comprobar que no cambió.
6. Eliminar intervalos y comprobar la elección Libre/Sin configurar al quitar el último. Abrir Horas extras y comprobar que solo contiene los declarados y permite ir a su día.
7. Cerrar el proceso y volver a abrir: horarios, clasificación y configuración deben permanecer.
8. Ajustes → Importar horario. Revisar OCR y conflictos; no debe inferir extras ni guardar sin confirmación. Si no interpreta la imagen, volver al calendario o completar la revisión manualmente.

La validación del usuario es necesaria antes de cerrar el trabajo. No añadir capturas ni horarios reales al repositorio o a documentación.

## Archivos de este rediseño

Nuevos: `ui/TimeWheel.kt`, `ui/DayDetailScreen.kt`, `ui/ExtrasScreen.kt`, esquema `app/schemas/com.turnocontrol.data.TurnoDatabase/4.json` y este documento.

Actualizados: `MainActivity.kt`, `ui/CalendarScreen.kt`, `ui/CalendarViewModel.kt`, `domain/ScheduleInterpretation.kt`, `domain/PlannedSchedule.kt`, `data/ScheduleStorage.kt`, `data/ScheduleRepository.kt`, `data/Database.kt`, `di/StorageModule.kt`, tests `CalendarTest`, `NavigationTest`, `OcrImportTest`, `SchedulePersistenceTest`, `RestartPersistenceTest`, `CalendarViewModelTest`, `PlannedScheduleTest`, `ScheduleRepositoryTest`, `ScheduleMigrationTest`, `RoomTest`, documentación y script de reinicio.

No cambian el motor OCR, OcrDocument, contratos, tarifas, cálculos laborales, dependencias o permisos. Los cambios pendientes anteriores siguen presentes.

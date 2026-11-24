# TurnoControl 0.3.0: plan de validación en dispositivo (preparación, sin ejecutar)

Estado: **solo plan.** Nada de lo que se describe aquí se ha ejecutado en un dispositivo.

Para ejecutar las pruebas en dispositivo hace falta tu **autorización expresa**, porque obligan a:
- construir la app de pruebas `com.turnocontrol.verification` (un APK);
- instalarla con ADB en un emulador o en un dispositivo de pruebas.

**Nunca** se usa la app real `com.turnocontrol` ni los datos del Samsung A53, y siempre con datos **sintéticos**.

Leyenda:
- **AHORA**: se puede ejecutar sin APK (JVM, Robolectric, lint, SQL local);
- **APROBACIÓN**: requiere construir o instalar la app de pruebas;
- **MANUAL**: comprobación visual en el emulador o dispositivo de pruebas, con aprobación.

## 0. Preparación (APROBACIÓN)
1. Un emulador con API 34 y Google Play services, y otro con API 26 (el `minSdk`). Un dispositivo de pruebas solo si lo autorizas, distinto del A53 con datos reales.
2. Construir e instalar únicamente `com.turnocontrol.verification`.
3. Copia de seguridad previa del emulador; datos sintéticos de `ScheduleFixtures`.
4. Registrar la versión, el commit y la fecha de cada ejecución.

## 1. Calendario
| Caso | Pasos y resultado esperado | Cobertura actual | Tipo |
|---|---|---|---|
| Turno normal | Crear 08:00–16:00: total 8 h y persiste | `CalendarViewModelTest`, `ScheduleRepositoryTest` | AHORA (JVM) + MANUAL |
| Turno partido | 08:00–12:00 y 16:00–20:00: dos intervalos, 8 h | `CalendarViewModelTest`, `PlannedScheduleTest` | AHORA + MANUAL |
| Turno nocturno | 22:00–06:00 (+1 día): asignado al día de inicio; el día siguiente rechaza turnos solapados | `PlannedScheduleTest`, `ScheduleSnapshotWindowTest` | AHORA + MANUAL |
| Libre, vacaciones y baja | Marcar días y rangos que cruzan de mes y de año | `CalendarViewModelTest` | AHORA + MANUAL |
| Revisar sin editar | Importar y pulsar «Revisado» sin abrir el editor | `ScheduleReviewViewModelTest` | AHORA + MANUAL |
| Persistencia tras reiniciar | Guardar, cerrar la app por completo y volver a abrir | `RestartPersistenceTest` (instrumentada) | APROBACIÓN |

## 2. Horas extra y nómina
| Caso | Esperado | Cobertura | Tipo |
|---|---|---|---|
| Total mensual de extras | Suma de los intervalos declarados como extra, al minuto | `ScheduleSummaryTest` | AHORA |
| Acumulado anual y cambio de año | Un nocturno del 31/12 cuenta en el año de inicio | `ScheduleSummaryTest` | AHORA |
| Precisión al minuto | 07:45–16:10 = 8 h 25 min | `ScheduleSummaryTest`, `TimeCellParserTest` | AHORA |
| Bruto estimado | Tarifa × horas, con 2 decimales y redondeo `HALF_UP` | `EarningsEstimatorTest` | AHORA |
| Falta una tarifa | «Configura la tarifa…» y ningún importe inventado | `EarningsEstimatorTest`, `SummaryViewModelTest` | AHORA + MANUAL |

## 3. OCR
Imágenes **sintéticas** de `OcrImportTest`/`ScheduleFixtures`; nunca fotos reales.

| Caso | Esperado | Cobertura | Tipo |
|---|---|---|---|
| Foto vertical y horizontal | Filas reconocidas iguales | `OcrOrientationTest` (instrumentada) | APROBACIÓN |
| Rotación EXIF y espejo | Imagen enderezada antes del OCR; el archivo original no se modifica | `OcrOrientationTest`, `UprightCorrectionTest`, `DecodePlanTest` | AHORA (parcial) + APROBACIÓN |
| Rotación manual | Gira en memoria; la selección no cambia | `ImportViewModelTest` | AHORA + MANUAL |
| Baja resolución | Rechazo con mensaje claro, sin OCR de mala calidad | `DecodePlanTest` | AHORA |
| Presupuesto de memoria | Reducción de escala o mensaje de memoria insuficiente | `DecodePlanTest`, `OcrMemoryTest` (instrumentada) | AHORA + APROBACIÓN |
| Turnos parciales y nocturnos | Interpretados sin inventar horas | `WorkerRowsInterpreterTest`, `TimeCellParserTest`, `WeeklyReportTest` | AHORA |
| Corrección manual y guardado solo de días revisados y válidos | Lo pendiente o inválido bloquea el guardado | `ScheduleReviewViewModelTest`, `OcrImportReviewTest` | AHORA + MANUAL |
| Importaciones duplicadas | Los días iguales aparecen como «Ya guardado» y no se escriben | `CalendarViewModelTest`, `OcrImportReviewTest` | AHORA |
| Persistencia | Tras guardar y reiniciar | `OcrImportTest` (instrumentada) | APROBACIÓN |
| OCR en un proceso aislado (prueba de concepto) | Mismo resultado que el motor actual; sin red | `IsolatedOcrServiceTest` (instrumentada, solo compilada) | APROBACIÓN |

## 4. Copias de seguridad y restauración
| Caso | Esperado | Cobertura | Tipo |
|---|---|---|---|
| Exportar | Archivo verificado mediante relectura | `BackupRepositoryTest` | AHORA + MANUAL (selector de archivos) |
| Copia dañada | Rechazada sin escribir nada | `BackupCodecTest`, `LegacyBackupCompatibilityTest` | AHORA |
| Restaurar una copia válida (también de 0.2.0) | Todos los datos; las tareas posteriores se ejecutan una vez | `LegacyBackupCompatibilityTest`, `BackupRestoreUiTest` (instrumentada) | AHORA + APROBACIÓN |
| Restauración interrumpida | Recuperación al arrancar | `RecoveryViewModelTest`, `BackupRepositoryTest` | AHORA |
| Restaurar en una base vinculada a una cuenta | Cola vacía y sincronización en `REVIEW_REQUIRED` | `SyncStoreTest`, `LegacyBackupCompatibilityTest` | AHORA |

## 5. Cuentas
| Caso | Esperado | Cobertura | Tipo |
|---|---|---|---|
| Aislamiento del invitado | El invitado nunca ve los datos de una cuenta | `AccountStorageTest`, `StorageLifecycleTest` | AHORA |
| Aislamiento entre A y B | Ni en el dispositivo ni en el servidor | Los anteriores, más `backend/` (72 pruebas SQL) | AHORA |
| Sesión caducada | Sin subidas ni descargas; aviso para iniciar sesión de nuevo | `AuthManagerTest`, `AccountUiTest` | AHORA |
| Cifrado de la sesión | Keystore real: clave por cuenta, alteraciones detectadas, borrado al cerrar sesión, fuera de las copias | `SessionVaultTest` (JVM, con un sustituto del Keystore); `KeystoreSessionVaultTest` (instrumentada, solo compilada) | AHORA (parcial) + APROBACIÓN |
| Vista previa de la vinculación y conflictos | «Combinar» por defecto; sustituir datos exige una segunda confirmación; cancelar no cambia nada | `LinkingUiTest`, `AccountLinkingTest` | AHORA |
| Los datos del invitado se conservan | La base de invitado no cambia | `AccountLinkingTest`, `LinkingUiTest` | AHORA |
| Cambiar de cuenta y cerrar sesión | Bloqueado mientras hay operaciones en curso; la cuenta se conserva | `StorageLifecycleTest`, `AccountUiTest`, `AuthCoordinatorTest` (fase 5E.1) | AHORA; en producción todavía no hay cuentas |
| Sincronización pendiente | Las operaciones sobreviven a reinicios y no se duplican | `SyncStoreTest`, `AuthCoordinatorTest` (a través de inicio/cierre de sesión) | AHORA |
| Coordinación entre autenticación y almacenamiento | Inicio de sesión, cambio de cuenta y cierre de sesión mantienen siempre de acuerdo el estado de `AuthManager` y la base de datos activa; nunca una cuenta con los datos de otra | `AuthCoordinatorTest` (fase 5E.1, 13 pruebas) | AHORA |
| Inicio de sesión real (Google y correo) | — | No implementado | Bloqueado: proveedor real y proyecto Supabase (fase 5F.1) |

## 6. Accesibilidad
| Caso | Esperado | Tipo |
|---|---|---|
| TalkBack en Calendario, Resumen, Ajustes, Cuenta e Importar | Todos los controles tienen nombre; botones desactivados anunciados como «todavía no disponible» | MANUAL (APROBACIÓN) |
| Zonas táctiles de al menos 48 dp y nombre accesible | Calendario, Resumen, Ajustes y Detalle a 360 y 320 dp (`AppNavigationTest`), y las pantallas de cuenta y vinculación (`AccountUiTest`, `LinkingUiTest`). Las celdas del calendario miden unos 44 dp de ancho visible a 360 dp (cálculo a partir del diseño) y la prueba mide 48 dp de zona táctil gracias al tamaño mínimo interactivo de Material | AHORA (Robolectric) + MANUAL |
| Temas claro y oscuro | Texto con contraste AA de al menos 4,5:1 e indicadores de día de al menos 3:1, en los dos temas (`AccessibilityChecksTest`) | AHORA (Robolectric) + MANUAL |
| Girar la pantalla | Borradores y decisiones conservados; nada se repite | AHORA (parcial: `LinkingUiTest`) + MANUAL |
| Texto grande (200 %) | Sin cortes ni solapes en 320 dp. Comprobado de forma automática solo que las estadísticas no limitan líneas (`AccessibilityChecksTest`); Robolectric no mide los glifos como un dispositivo | AHORA (parcial) + MANUAL |
| Mensajes de error | En español, con una acción clara y anunciados por TalkBack (región viva, `AppNavigationTest.aRejectedSaveIsAnnouncedToScreenReaders`). En el dispositivo, comprobar que con el editor de turnos abierto el mismo error no se lee dos veces (aparece en el detalle y en el diálogo) | AHORA (semántica) + MANUAL (lectura real) |

## 7. Medición del tráfico de red (cuando se autorice un entorno)
**Hoy el permiso `INTERNET` está eliminado**, y `NetworkPrivacyTripwireTest` falla si alguien lo añade. Las mediciones solo tienen sentido en la build en la que se active.

**Método:**
- emulador con `-tcpdump` y registro DNS;
- `TrafficStats` y `NetworkStatsManager` por UID: el de la app y, con la prueba de concepto, el UID efímero del proceso aislado;
- StrictMode `detectUntaggedSockets()` en la build de pruebas.

**Escenarios separados:**
1. arranque;
2. modo invitado, 30 min de uso;
3. OCR (vertical, horizontal, rotación, cancelación y tiempo límite);
4. segundo plano, forzando los trabajos de JobScheduler **solo en el emulador**;
5. sesión iniciada sin sincronizar;
6. sincronización en segundo plano;
7. telemetría de fondo, con la app inactiva 30 min.

**Aceptación:**
- invitado y OCR: **0 conexiones** del UID de la app;
- con sesión: solo el dominio del proyecto Supabase aprobado;
- ninguna imagen ni texto reconocido en el tráfico.

**No se afirmará «cero tráfico»** sin estas mediciones archivadas.

### 7.1 Ejecutadas (fase 4B.5, 2026-11-23, emulador `TurnoControl_Verify_API35`, serie `emulator-5554`)

Dos variantes de compilación desechables, ninguna la build por defecto: `networkBaselineVerification` (permisos de red, sin tocar los componentes de telemetría) y `networkStrategyBVerification` (mismos permisos, con `TransportBackendDiscovery`, `JobInfoSchedulerService` y `AlarmManagerSchedulerBroadcastReceiver` eliminados con `tools:node="remove"`). Ambas parten de `initWith(verification)`; su propio `AndroidManifest.xml` decide los permisos, nunca `app/src/main` ni `app/src/verification`.

**Procedimiento para repetir la medición** (los dos tipos de compilación solo existen con la propiedad; sin ella, `assemble`/`test` nunca los ven):
1. `./gradlew :app:assemble<Variante> :app:assembleAndroidTest -Pturnocontrol.netExperiment=<Variante> --offline`, con `<Variante>` = `networkBaselineVerification` o `networkStrategyBVerification` (la propiedad fija `testBuildType` a esa misma variante para esa invocación, así que el motor de tests se compila contra ella).
2. Instalar ambos APK con `adb -s <serie> install -r …` y ejecutar `com.turnocontrol.NetworkExperimentOcrTest`, el único de la suite que no exige el paquete `.verification` (el resto tiene una comprobación de seguridad deliberada que lo impide).
3. Medir (ver resultados abajo).
4. Desinstalar los paquetes de la variante. No hay nada que revertir en el archivo: sin la propiedad, la siguiente invocación de Gradle vuelve a `testBuildType = "verification"` por sí sola. `NetworkPrivacyTripwireTest.networkExperimentBuildTypesStayGatedBehindAProperty` falla si alguien sustituye la condición por un valor fijo.

**Resultados:**

| Medición | Referencia (telemetría intacta) | Estrategia B |
|---|---|---|
| Eventos en `com.google.android.datatransport.events` tras el OCR | sesión 1 (5 llamadas, antes de forzar nada): 9. Sesión 2, instalación distinta (5 llamadas + 1 apaisada): 4, leído después de la subida automática descrita más abajo | 0 |
| Trabajos de `JobInfoSchedulerService` creados | 2 en la sesión 1 | 0 |
| Resultado al forzar los trabajos (`cmd jobscheduler run -f`, sesión 1) | 2 peticiones HTTPS reales a `https://firebaselogging.googleapis.com/v0cc/log/batch`, ambas con código 200 (visto en logcat); el trabajo `DEFAULT` llevaba `TIME=-27s` (vencido) sin haberse disparado solo 56 s después del OCR, posiblemente porque `am instrument` mata el proceso al terminar la prueba | — (no hay trabajos que forzar) |
| `dumpsys netstats detail`, UID de la app | sin entrada en ninguna de las dos ejecuciones, pese a las peticiones confirmadas por logcat | sin entrada |
| Captura `tcpdump -U -i any` (OCR + reinicio + 60 s inactivo + HOME) | **sin control positivo**: no se repitió con la telemetría intacta en esta sesión | 3328 bytes; solo mDNS/adb; **0** apariciones de `firebaselogging` o `googleapis`. Corrobora la ausencia de trabajos/eventos, pero sin una captura equivalente de referencia no se puede afirmar que «ni siquiera se intentó la resolución DNS»: la ausencia de otro tráfico esperado (FCM, comprobación de conectividad, visto en la fase 5D.1) es también parte de lo no explicado |
| Contenido de los eventos (`events.db` crudo) | sin «PRUEBA», «ALFA», «BETA», «GAMMA», «DELTA», «123» ni «456» en los 4 eventos leídos; sí aparece el UUID `ml_sdk_instance_id` en los 4. **Solo cubre los eventos que quedaban sin enviar** (ver más abajo: uno ya se había enviado solo antes de esta lectura); no dice nada del contenido del que ya salió | — |
| OCR (texto reconocido, vertical y apaisado) | idéntico en ambas variantes, 5 repeticiones + 1 apaisada | idéntico |
| Errores (`ClassNotFoundException`, `FATAL EXCEPTION`) | ninguno | ninguno |
| `com.google.android.gms/.common.stats.GmsCoreStatsService` | registra a la app como `recentCallingPackage` | sin coincidencia en el volcado tomado (no concluyente; la ventana de enlace puede ser breve) |

**Observado una vez (sesión 2 de dos), no confirmado como regla general:** el logcat de la sesión 2, capturado sin haber forzado ningún trabajo todavía, muestra una única petición real `https://firebaselogging.googleapis.com/…` con código 200 a las 16:32:27, unos 26–33 s después de las llamadas de reconocimiento (16:31:55–16:32:01): coincide con el retraso de 30000 ms que `JobInfoScheduler` registró para los eventos de prioridad `DEFAULT`. Esto explica el recuento de eventos más bajo de la sesión 2 (4, frente a 9 en la sesión 1). **Pero en la sesión 1 ese mismo trabajo no se disparó solo**: seguía vencido (`TIME=-27s`) 56 s después del OCR y hubo que forzarlo. El desencadenante exacto (qué hace que se dispare solo en un caso y no en otro; `am instrument` matando el proceso al terminar la sesión 1 es la hipótesis más plausible, sin confirmar) no está establecido. No se afirma que la telemetría «se envíe sola» como comportamiento garantizado, solo que se ha observado una vez sin forzar nada. Los eventos de prioridad `VERY_LOW` (86 400 000 ms, 24 h) no se han observado enviarse sin forzarlos en ningún caso.
**Limitaciones del método:**
- `dumpsys netstats detail` no refleja el tráfico de la app en ninguna de las dos variantes, ni siquiera cuando logcat confirma dos peticiones HTTPS con código 200. No se investigó la causa (posiblemente el intervalo de sondeo del emulador); logcat es la evidencia más fuerte disponible, no netstats.
- El canal de Google Play services (otro UID) no se puede atribuir con estas herramientas; `GmsCoreStatsService` es una instancia concreta y nombrada de ese canal, que la estrategia B no está diseñada para tocar.
- Solo se ha medido en API 35 x86_64, igual que en la fase 5D.1.
- La entrada de 2,87 MB sin atribuir de la fase 5D.1 sigue sin explicación.
- Los escenarios de calendario en uso real (invitado navegando) y de terminación de la app no se midieron en ninguna variante esta sesión.

**Conclusión:** la estrategia B detiene de forma medible la subida de telemetría de ML Kit (0 eventos en la base cruda, 0 trabajos de `JobInfoSchedulerService`, 0 apariciones del dominio de telemetría en la única captura de paquetes tomada) sin romper el OCR ni producir errores. **No se afirma «cero tráfico» ni «cero telemetría»**: la captura de paquetes no tuvo control positivo, el canal de Google Play services queda fuera del alcance de esta estrategia, y el UUID persistente de ML Kit se sigue guardando igual con o sin ella. Con red habilitada en producción, el veredicto de privacidad de la app en conjunto sería **`PRIVACY NOT VERIFIED`**.

### 7.5 Repetición con control positivo y escenarios 2/9 (fase 5D.4, 2026-11-24)
Repite la medición de §7.1 cerrando su principal hueco de evidencia: la sesión anterior nunca capturó la variante de referencia con telemetría intacta en la misma sesión que la estrategia B, así que una captura vacía en estrategia B no podía distinguirse de un método ciego. Esta vez ambas variantes se midieron con el **mismo guion de escenario, en el mismo orden y con las mismas duraciones**, cada una en un emulador `TurnoControl_Verify_API35` recién arrancado con `-wipe-data` (arranque en frío, sin estado de sesiones anteriores), con un único paquete `com.turnocontrol.<variante>(.test)` instalado cada vez.

**Guion (idéntico en ambas variantes):** `am start` (arranque, escenario 1) → `monkey -p <pkg> -s 42 --throttle 300 --pct-touch 100 30` a los 15 s (navegación de invitado con semilla fija para una carga de trabajo equivalente, escenario 2, **no medido en ninguna variante hasta ahora**) → `am force-stop` + `am instrument -e class NetworkExperimentOcrTest` (OCR vertical/apaisado/repetido, escenarios 3–6) → `am start` + `HOME` + 60 s (segundo plano real, escenario 7) → `am force-stop` + `am start` (reinicio, escenario 8) → comprobación/forzado de trabajos de `JobInfoSchedulerService` → `am force-stop` + 15 s de espera (terminación, escenario 9, **no medido en ninguna variante hasta ahora**). Captura continua con `tcpdump -U -i any` y `logcat -v threadtime` durante todo el guion, más `dumpsys netstats --poll` (no solo `detail`) y `dumpsys jobscheduler` al final.

**Resultado — control positivo obtenido (referencia, telemetría intacta):**
- A los 53 s del arranque, sin forzar nada, durante la ventana de segundo plano (escenario 7): `logcat` registra `TransportRuntime.CctTransportBackend: Making request to: https://firebaselogging.googleapis.com/v0cc/log/batch?format=json_proto3` seguido de `Status Code: 200`.
- El `pcap` de esa misma sesión, en el mismo segundo, muestra la resolución DNS (`A?`/`AAAA? firebaselogging.googleapis.com`), el `SYN`/`SYN-ACK` TLS a una de las IPs resueltas y el intercambio de datos completo.
- `dumpsys netstats --poll` (a diferencia de `detail` solo, que de nuevo no mostró nada) sí atribuyó bytes al UID de la app: `uid=10209 ... tag=0x0` con un valor de bytes thread-level distinto de cero.
- `com.google.android.datatransport.events` tenía 4 filas al final (de los 5+1 reconocimientos), consistente con que 1 evento ya había salido — mismo patrón que §7.1, ahora con la petición real capturada en la misma sesión que generó la medición de estrategia B.

**Resultado — estrategia B, mismo guion, mismo orden, mismas duraciones:**
- **0** apariciones de `firebaselogging` o `CctTransportBackend` en el logcat completo (113 076 paquetes/144 MB de captura, revisados por completo con `tcpdump -r`).
- **0** filas en `com.google.android.datatransport.events`.
- **0** trabajos de `com.google.android.datatransport` en `dumpsys jobscheduler` (frente a la referencia, que sí los tenía).
- 4 coincidencias de `ClassNotFoundException` en el logcat, ninguna de la app (proceso `SliceDataConverter` del sistema, PID ajeno, sobre clases de la app de Ajustes); **0** errores de la app o de ML Kit.
- OCR idéntico al de la referencia (mismo texto reconocido).

**Como antes, la mayor parte del tráfico observado en ambas capturas no es atribuible al UID de la app:** la captura de estrategia B (144 MB) está dominada por tráfico a IPs de Google (`216.58.205.123`, `172.217.171.46`, …) consistente con el primer arranque de Google Play Services/Play Store en un emulador recién borrado, no con la app (que no tiene `JobInfoSchedulerService` para programar nada). Confirma, a mayor escala que la fase 5D.1 (cuyos 2,87 MB sin atribuir siguen sin explicación), que el canal de Google Play services permanece fuera del alcance de estas herramientas.

**Lo que este control positivo cambia respecto al veredicto de §7.1:** la ausencia de telemetría en la captura de estrategia B ya no depende de un método potencialmente ciego — la misma captura, con el mismo guion, mostró la petición real cuando el sujeto era la variante de referencia. **Lo que no cambia:** el canal de Google Play services sigue sin poder atribuirse, el UUID persistente de ML Kit se sigue guardando en ambas variantes, los escenarios 2 y 9 ya están medidos pero solo en esta sesión (no se han repetido ni variado la carga de trabajo), y la medición sigue limitada a API 35 x86_64. **Veredicto de esta sesión, sin cambios respecto a §7.1: `PRIVACY NOT VERIFIED`** con red habilitada en producción — ahora con evidencia de control positivo, no solo con una captura vacía sin referencia.

### 7.2 Ubicación de las bases de datos de cuenta (fase 0.3.0, 2026-11-23)
Las bases de cuenta (`turnocontrol-acct-<clave>.db`, §7.1 del documento de arquitectura) ya no viven en el directorio por defecto de Android (`databases/`, compartido con `turnocontrol.db`), sino en un directorio propio por cuenta: `filesDir/accounts/<clave>/`. Motivo: una intermitencia de `SQLITE_CANTOPEN` en `AccountLinkingTest` (JVM/Robolectric en Windows), eliminada de forma medible tras este cambio; ver el informe de la sesión que lo introdujo para el detalle de la investigación. Consecuencias:
- El borrado de una base de cuenta debe hacerse por su `databasePath` (`Context.databaseFile`/`deleteStorageDatabase` en `AccountStorage.kt`), nunca con `Context.deleteDatabase(nombre)`, que ya no la encuentra.
- Verificado que `backup_rules.xml` y `data_extraction_rules.xml` siguen excluyendo la nueva ubicación: ambos excluyen `domain="file" path="."` (todo `filesDir`) por completo, así que la cobertura no cambia.
- Esta ruta todavía no tiene ningún llamador de producción (las cuentas no están conectadas a la navegación); el cambio solo afecta a los tests y a la futura implementación.

### 7.3 Defecto conocido: `testVerificationUnitTest` falla `NetworkPrivacyTripwireTest` — RESUELTO (fase 5D.4)
Causa raíz (sin cambios respecto a lo descrito originalmente): el manifiesto fusionado real de `verification` declara `IsolatedOcrService` en su propio proceso (`:ocr`, isolated), y tres aserciones de `NetworkPrivacyTripwireTest` (`telemetryComponentInventoryIsUnchanged`, `productionManifestHasNoNetworkConfigurationOrExtraProcesses`, `isolatedOcrProofOfConceptStaysInTheVerificationBuildWithoutNetwork`) asumían que ningún build type tiene un servicio aislado ni un proceso extra, lo que es cierto en `debug`/`release` pero deliberadamente falso en `verification`.

**Corrección aplicada:** las tres aserciones ahora distinguen el build type actual por `context.packageName` (`"com.turnocontrol.verification"` ↔ `isVerificationBuildType`) y usan una lista exacta con un único componente permitido (`com.turnocontrol.ocr.IsolatedOcrService`), nunca una desactivación genérica de la comprobación. Fuera de `verification`, las tres siguen exigiendo cero procesos/servicios aislados, igual que antes. `noNetworkPermissionIsRequested` se endureció de una lista negra (solo `INTERNET`/`ACCESS_NETWORK_STATE`) a una lista exacta (`setOf("<packageName>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")`), verificada leyendo los tres manifiestos fusionados reales (`app/build/intermediates/merged_manifest/<variante>/.../AndroidManifest.xml`): ningún otro permiso, de red o no, pasa desapercibido en adelante.

**Segundo defecto descubierto al perseguir este (no reportado hasta ahora):** `androidx.compose.ui:ui-test-manifest`, que `AccountUiTest`/`LinkingUiTest` necesitan (`createComposeRule()`, Robolectric) para que el manifiesto cargado tenga el `ComponentActivity` de pruebas, solo estaba declarado como `debugImplementation`. `initWith(debug)` en la definición del build type `verification` no hace que herede esa configuración de dependencias (cada build type tiene las suyas), así que `testVerificationUnitTest` fallaba esos 2×8=16 tests con `RuntimeException` en `RoboMonitoringInstrumentation`, y lo mismo le pasaba a `testReleaseUnitTest`.
- **`verification`:** se añadió `add("verificationImplementation", "androidx.compose.ui:ui-test-manifest")`. Sin riesgo: `verification` nunca se publica, es desechable.
- **`release`:** **no** se replicó el mismo cambio ahí. A diferencia de `verification`, `release` sí puede llegar a convertirse en un APK real algún día; añadir ese artefacto ahí metería infraestructura de pruebas en el manifiesto de producción de forma permanente, solo para que un `./gradlew test` sin cualificar estuviera en verde. En su lugar, `AccountUiTest` y `LinkingUiTest` se excluyen explícitamente de `testReleaseUnitTest` (`tasks.withType<Test>().configureEach { if (name == "testReleaseUnitTest") filter { excludeTestsMatching(...) } }`), con el motivo documentado en el propio `build.gradle.kts`.

**Resultado, confirmado con una ejecución limpia (`cleanTestDebugUnitTest cleanTestReleaseUnitTest cleanTestVerificationUnitTest test`, sin tareas `UP-TO-DATE`):**

| Tarea | Resultado |
|---|---|
| `:app:testDebugUnitTest` | 347/347 |
| `:app:testVerificationUnitTest` | 347/347 |
| `:app:testReleaseUnitTest` | 331/331 (347 menos los 16 de `AccountUiTest`/`LinkingUiTest`, excluidos a propósito, no omitidos por error) |
| `:app:test` (agregado) | BUILD SUCCESSFUL |

**`./gradlew test` sin cualificar ya está en verde.** Verificado también que el gate de §7.1 sigue intacto tras estos cambios: sin la propiedad, `:app:tasks --all` no contiene ninguna tarea `network*`, `:app:assembleNetworkBaselineVerification --dry-run` falla con `Cannot locate tasks`, y `:app:test --dry-run` solo programa `testDebugUnitTest`, `testReleaseUnitTest` y `testVerificationUnitTest`; con `-Pturnocontrol.netExperiment=networkBaselineVerification` sí aparece el árbol completo (control positivo).

### 7.4 `IsolatedOcrServiceTest` — RESUELTO (fase 5D.4)
Fallaba con `DeadObjectException` sin que el test lo esperara: aserbaba que el motor aislado devolvía el mismo resultado que el motor en proceso, cuando la fase 5D.1 ya había determinado que ML Kit 16.0.1 no funciona en un proceso aislado (NNAPI `SIGSEGV` bajo `isolated_app`). **Corrección:** el test ahora afirma explícitamente ese fallo documentado (`assertThrows(DeadObjectException::class.java) { ... }`), además de comprobar que el motor en proceso (producción) sigue funcionando con la misma imagen síntetica. No se ha intentado arreglar el OCR aislado: sigue sin ser viable.

**Verificación en el emulador `TurnoControl_Verify_API35` (serie `emulator-5554`, sin dispositivo físico):**
- 10/10 ejecuciones consecutivas de `IsolatedOcrServiceTest` en solitario: todas pasan.
- `logcat -d -b crash` y el buffer principal, tras `logcat -c`, muestran exactamente el mecanismo documentado en la fase 5D.1: `Fatal signal 11 (SIGSEGV)` en el proceso `com.turnocontrol.verification:ocr:com.turnocontrol.ocr.IsolatedOcrService`, con la pila `ANeuralNetworks_getRuntimeFeatureLevel → DeviceManager::get() → getDevices → getHidlDevicesForVersion → getAllHalInstanceNames`, precedido por denegaciones SELinux (`avc: denied` sobre `hwbinder`, `device_config_nnapi_native_prop` y `android.hardware.neuralnetworks.IDevice/*`) para `scontext=u:r:isolated_app`. No es un fallo distinto ni una regresión: es la misma causa raíz, confirmada de nuevo, no solo asumida.
- Suite completa: **43 ejecutadas, 43 superadas, 0 fallidas** (antes: 42/43).

## 8. Lo que se puede ejecutar ahora
```bash
./gradlew --max-workers=1 --offline test --rerun   # JVM + Robolectric en debug, release y verification
./gradlew :app:testDebugUnitTest --offline --rerun   # solo debug (suite completa)
./gradlew :app:lintDebug --offline
./gradlew :app:compileVerificationAndroidTestKotlin --offline   # compila las instrumentadas sin ejecutarlas
bash backend/scripts/run-local-tests.sh   # SQL/RLS en un contenedor local sin red (requiere Docker)
```
Con aprobación: `connectedVerificationAndroidTest` en el emulador. Instala la app `.verification`; **nunca** la app real.

## 9. Coordinador de autenticación y almacenamiento (fase 5E.1, 2026-11-24)

### 9.1 Qué existía ya y qué faltaba
Antes de esta fase, `AuthManager` (dominio, máquina de estados de sesión), `EncryptedSessionVault` (persistencia cifrada por cuenta) y `AccountStorageCoordinator`/`StorageSessionHolder` (cambio seguro de base de datos activa) ya existían, probados por separado, pero **nada los conectaba entre sí**. Un inicio de sesión real podía, en teoría, dejar `AuthManager` diciendo "autenticado como X" sin que la base de datos activa fuera realmente la de X. Esta fase añade esa pieza que faltaba: `AuthCoordinator` (`app/src/main/java/com/turnocontrol/data/auth/AuthCoordinator.kt`).

### 9.2 Cómo coordina
- **Inicio de sesión y cambio de cuenta:** primero se confirma la identidad con el proveedor (`AuthManager`), después se cambia el almacenamiento (`StorageSessionHolder`). Si el cambio de almacenamiento falla o se rechaza, se deshace el lado de autenticación: un inicio de sesión fallido nunca cambia la base de datos activa.
- **Cerrar sesión:** al revés — el destino (invitado) ya se conoce de antemano, así que el almacenamiento cambia primero; `AuthManager` solo se actualiza si ese cambio se confirma. Si el almacenamiento está ocupado (un guardado, una restauración o una sincronización en curso), la sesión se queda exactamente como estaba.
- **Cambio de cuenta A→B con fallo de almacenamiento:** `AuthManager.switchAccount` ya ha revocado la sesión de A y guardado la de B en el momento en que se intenta el cambio de almacenamiento; no hay forma limpia de "volver a A". El coordinador cae hasta el invitado en ambas capas — una decisión deliberada y conservadora: cuesta un inicio de sesión adicional, nunca expone los datos de una cuenta bajo la identidad de otra.
- **La caída al invitado puede fallar también** (el mismo bloqueo que impidió el cambio a B también puede impedir volver al invitado). En ese caso, el estado publicado nunca miente: comprueba directamente a qué cuenta está vinculada la base de datos activa, no solo lo que dice `AuthManager`, y muestra "vuelve a iniciar sesión" para esa cuenta — nunca "Sin cuenta" (que ocultaría que se están mostrando datos de alguien) ni la identidad de la cuenta a la que nunca se llegó a cambiar.
- **Reinicio del proceso:** `restoreOnStart()` solo mira el espacio de almacenamiento ya confirmado (sin cambiarlo) y, si está vinculado a una cuenta, intenta restaurar la sesión cifrada de esa cuenta exactamente.

### 9.3 Defecto real encontrado y corregido durante las pruebas
`AuthManager.state` es un único campo compartido. Un segundo intento de inicio de sesión que es rechazado (porque la app ya está autenticada) también escribe su propio `Failed(InvalidState, …)` en ese campo, como efecto secundario. La primera versión de `AuthCoordinator.publish()` volvía a leer ese campo compartido después de que el propio cambio de almacenamiento terminara con éxito — así que, si el intento rechazado se colaba justo en medio, su escritura podía "tapar" el resultado correcto del primer intento, y la interfaz acababa mostrando "Sin cuenta" tras un inicio de sesión que en realidad había tenido éxito. `AuthCoordinatorTest.concurrentSignInAttemptsOnlyOneSucceeds` lo reprodujo de forma determinista (no por una carrera real de hilos, sino forzando el intercalado con un proveedor de prueba que se pausa a propósito). **Corregido:** la rama de éxito publica el estado exacto que ella misma obtuvo, no el que haya en el campo compartido en ese instante.

### 9.4 Disponible en producción, y qué no
- `AccountViewModel`'s constructor de producción (`@Inject`) ya usa el `AuthCoordinator` real: `restoreOnStart()` se ejecuta al crear la pantalla, y `signOut` llama de verdad al coordinador. **Esto es intencional y seguro**, no una activación oculta: el `AuthProvider` de producción (`AuthModule.UnconfiguredAuthProvider`) siempre falla cualquier intento de inicio de sesión, así que en la práctica la app sigue mostrando "Sin cuenta" de forma incondicional, exactamente como antes de esta fase.
- `providersAvailable` sigue en `false` en producción: los botones de "Iniciar sesión" y "Crear cuenta" siguen deshabilitados. Esto es un interruptor de interfaz independiente del backend, que seguirá en `false` hasta que exista un proveedor real (Google o correo, fase 5F.1 en adelante).
- No se ha añadido ninguna dependencia nueva, no se ha tocado el manifiesto, el permiso `INTERNET` sigue eliminado.

### 9.5 Pruebas (fase 5E.1)
`AuthCoordinatorTest` (13 pruebas nuevas, `app/src/test/java/com/turnocontrol/data/auth/`): invitado inicial, inicio de sesión correcto, credenciales inválidas, intentos concurrentes, sesión caducada, sesión revocada (con limpieza de la sesión persistida, datos intactos), cerrar sesión, cambio de cuenta sin exponer datos cruzados, fallo de almacenamiento durante el inicio de sesión (reversión a invitado), fallo de almacenamiento durante el cambio de cuenta (caída a invitado o, si ese propio intento también falla, "vuelve a iniciar sesión" honesto), reinicio del proceso con restauración de la sesión, sesión ilegible con los datos de la cuenta todavía vinculados, y operaciones de sincronización pendientes que sobreviven a un ciclo completo de cierre e inicio de sesión.

También se actualizó `AccountUiTest.productionViewModelIsAlwaysAGuestWithoutWorkingSignIn` para usar el cableado real de producción (antes probaba un `AccountViewModel()` sin ningún coordinador detrás).

**Resultado, ejecución completa (`./gradlew --no-daemon --max-workers=1 test`):** `testDebugUnitTest` 360/360, `testVerificationUnitTest` 360/360, `testReleaseUnitTest` 344/344 (360 menos los 16 de Compose excluidos a propósito desde la fase 5D.4). Lint: 0 errores, 26 avisos, sin cambios.

### 9.6 Limitaciones que quedan
- No hay ningún proveedor real (Google o correo): todo lo anterior está probado con `FakeAuthProvider`, nunca con una cuenta real.
- `pendingChanges`/`conflicts` en la pantalla de cuenta se calculan leyendo la cola de sincronización real de la sesión activa, pero nunca hay nada que sincronizar de verdad todavía (sin backend).
- El flujo completo de primera vinculación (combinar datos del invitado con una cuenta) existe (`AccountLinkingTest`, `LinkingUiTest`) pero sigue sin un disparador conectado desde la interfaz de producción: sigue siendo una decisión explícita pendiente, no automática, tal como exige el diseño.
- Reautenticación tras una sesión caducada: el estado `Expired` ya se detecta y se muestra, pero no hay ningún proveedor real con el que reautenticarse todavía.

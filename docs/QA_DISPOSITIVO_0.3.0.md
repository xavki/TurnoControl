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
| Cambiar de cuenta y cerrar sesión | Bloqueado mientras hay operaciones en curso; la cuenta se conserva | `StorageLifecycleTest`, `AccountUiTest` | AHORA; en producción todavía no hay cuentas |
| Sincronización pendiente | Las operaciones sobreviven a reinicios y no se duplican | `SyncStoreTest` | AHORA |
| Inicio de sesión real (Google y correo) | — | No implementado | Bloqueado: proveedor real y proyecto Supabase |

## 6. Accesibilidad
| Caso | Esperado | Tipo |
|---|---|---|
| TalkBack en Calendario, Resumen, Ajustes, Cuenta e Importar | Todos los controles tienen nombre; botones desactivados anunciados como «todavía no disponible» | MANUAL (APROBACIÓN) |
| Zonas táctiles de al menos 48 dp | Comprobado en las pantallas de cuenta y vinculación (`AccountUiTest`, `LinkingUiTest`); el resto, a revisar | AHORA (parcial) + MANUAL |
| Temas claro y oscuro | Contraste legible | AHORA (Robolectric) + MANUAL |
| Girar la pantalla | Borradores y decisiones conservados; nada se repite | AHORA (parcial: `LinkingUiTest`) + MANUAL |
| Texto grande (200 %) | Sin cortes ni solapes en 320 dp | MANUAL |
| Mensajes de error | En español y con una acción clara | AHORA (textos) + MANUAL |

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

### 7.2 Ubicación de las bases de datos de cuenta (fase 0.3.0, 2026-11-23)
Las bases de cuenta (`turnocontrol-acct-<clave>.db`, §7.1 del documento de arquitectura) ya no viven en el directorio por defecto de Android (`databases/`, compartido con `turnocontrol.db`), sino en un directorio propio por cuenta: `filesDir/accounts/<clave>/`. Motivo: una intermitencia de `SQLITE_CANTOPEN` en `AccountLinkingTest` (JVM/Robolectric en Windows), eliminada de forma medible tras este cambio; ver el informe de la sesión que lo introdujo para el detalle de la investigación. Consecuencias:
- El borrado de una base de cuenta debe hacerse por su `databasePath` (`Context.databaseFile`/`deleteStorageDatabase` en `AccountStorage.kt`), nunca con `Context.deleteDatabase(nombre)`, que ya no la encuentra.
- Verificado que `backup_rules.xml` y `data_extraction_rules.xml` siguen excluyendo la nueva ubicación: ambos excluyen `domain="file" path="."` (todo `filesDir`) por completo, así que la cobertura no cambia.
- Esta ruta todavía no tiene ningún llamador de producción (las cuentas no están conectadas a la navegación); el cambio solo afecta a los tests y a la futura implementación.

### 7.3 Defecto conocido: `testVerificationUnitTest` falla `NetworkPrivacyTripwireTest` (preexistente)
`:app:testVerificationUnitTest --tests com.turnocontrol.NetworkPrivacyTripwireTest` falla 3 de 6 pruebas (`telemetryComponentInventoryIsUnchanged`, `productionManifestHasNoNetworkConfigurationOrExtraProcesses`, `isolatedOcrProofOfConceptStaysInTheVerificationBuildWithoutNetwork`): el manifiesto fusionado real de `verification` declara `IsolatedOcrService` en su propio proceso, y esas tres aserciones no lo esperan ahí. Ninguna de las tres se modificó en la sesión que detectó esto (confirmado con `git log -p`), así que el fallo es anterior, no introducido por el trabajo de esa sesión. **`./gradlew test` sin cualificar no está en verde.** Pendiente decidir si esas pruebas deben excluir `verification`, o si `IsolatedOcrService` debe declararse de otra forma.

## 8. Lo que se puede ejecutar ahora
```bash
./gradlew :app:testDebugUnitTest --offline --rerun   # JVM + Robolectric (suite completa)
./gradlew :app:lintDebug --offline
./gradlew :app:compileVerificationAndroidTestKotlin --offline   # compila las instrumentadas sin ejecutarlas
bash backend/scripts/run-local-tests.sh   # SQL/RLS en un contenedor local sin red (requiere Docker)
```
Con aprobación: `connectedVerificationAndroidTest` en el emulador. Instala la app `.verification`; **nunca** la app real.

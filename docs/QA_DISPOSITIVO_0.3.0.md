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

## 8. Lo que se puede ejecutar ahora
```bash
./gradlew :app:testDebugUnitTest --offline --rerun   # JVM + Robolectric (suite completa)
./gradlew :app:lintDebug --offline
./gradlew :app:compileVerificationAndroidTestKotlin --offline   # compila las instrumentadas sin ejecutarlas
bash backend/scripts/run-local-tests.sh   # SQL/RLS en un contenedor local sin red (requiere Docker)
```
Con aprobación: `connectedVerificationAndroidTest` en el emulador. Instala la app `.verification`; **nunca** la app real.

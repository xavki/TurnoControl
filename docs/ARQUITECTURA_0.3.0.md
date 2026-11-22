# TurnoControl 0.3.0: especificación técnica (planificación)

Estado: **propuesta, sin implementar**. Parte del código tras las correcciones posteriores a 0.2.0: copia de seguridad, protección de compatibilidad y OCR con límite de memoria.

Principios:
- El horario planificado y el trabajo real son datos distintos.
- Ninguna diferencia se califica automáticamente como hora extra compensable.
- No se inventan reglas fiscales ni laborales.
- No se añaden servicios en la nube, ubicación ni seguimiento continuo en segundo plano.

## 1. Arquitectura actual (0.2.x)

- **Estructura:** un módulo `app` con las capas `ui` (Compose y ViewModels con ámbito de Activity), `domain` (lógica pura) y `data` (Room v4, DataStore, ML Kit). La inyección de dependencias es con Hilt.
- **Persistencia:** las tablas `Profile`, `Contract` y `PayRule` usan identificadores fijos (perfil 1). `ScheduledDay` y `ScheduledInterval` son el horario **planificado**.
- **Escritura del calendario:** pasa siempre por `ScheduleRepository.save`, una transacción con comparación de instantánea y validación de los días resultantes.
- **Cálculos:**
  - `ScheduleSummary` saca totales por mes natural y año natural.
  - `EarningsEstimator` estima el bruto con la tarifa actual.
- **Salvaguardas:**
  - `CompatibilityChecker` actúa como compuerta de arranque, con el marcador `PRAGMA application_id` y `DataFormat.CURRENT = 2`.
  - `BackupRepository` gestiona la copia y restauración con diario.
  - `memoryAwarePlan` limita la memoria del OCR.
- **Deudas que afectan a 0.3.0:**
  - `CalendarViewModel` mezcla el calendario y la importación OCR.
  - `PayRule` usa identificadores fijos por tipo y `ConfigurationDao.rate()` lee `id = 1`.
  - Los resúmenes se recalculan durante la composición.

## 2. Arquitectura propuesta

```
ui/
  calendar/   CalendarViewModel (solo planificación)
  import/     OcrImportViewModel (sale de CalendarViewModel), ScheduleReview…
  tracking/   TimeTrackingViewModel (fichaje manual y en vivo)
  summary/    SummaryViewModel (planificado frente a real)
  payroll/    PayrollViewModel (estimación frente a nómina)
  settings/   ConfigurationViewModel, RatesViewModel, BackupViewModel
domain/
  WorkEntry/WorkBreak (modelos puros), WorkValidator, ComparisonCalculator, RateResolver, PayrollComparison, CsvExporter
data/
  WorkRepository, PayslipRepository, RateRepository, (existentes) ScheduleRepository, BackupRepository v2
```

Reglas: la lógica nueva va en `domain`, es pura y está testeada; cada repositorio tiene un único punto de escritura transaccional; los ViewModels no se comparten entre funciones.

## 3. Entidades nuevas (Room v5)

Se evaluó el modelo de la auditoría (WorkEntry, WorkBreak, EntryCorrection, PayslipRecord, HistoricalPayRule). Se adopta con dos ajustes:

1. **`HistoricalPayRule` no es una tabla nueva.** Se reconstruye `PayRule` con identificadores autogenerados y vigencias. Las columnas `validFrom` y `validUntil` ya existen; una segunda tabla de tarifas crearía dos fuentes de verdad.
2. **Las horas reales se guardan como un instante UTC más la zona horaria de inicio**, no solo como minutos locales. Así el fichaje en vivo se recupera tras reiniciar o cambiar de zona horaria.

### `WorkEntry`: turno real
| Columna | Tipo | Notas |
|---|---|---|
| `id` | INTEGER PK autogenerado | |
| `profileId` | INTEGER NOT NULL | FK → `Profile(id)`, ON DELETE CASCADE |
| `workDate` | TEXT NOT NULL | Día al que se asigna (día de inicio), AAAA-MM-DD |
| `startUtc` | INTEGER NOT NULL | Milisegundos desde época, UTC |
| `endUtc` | INTEGER NULL | NULL = turno en curso (solo uno a la vez) |
| `zoneId` | TEXT NOT NULL | Zona en la que se fichó |
| `plannedDayId` | INTEGER NULL | FK → `ScheduledDay(id)`, ON DELETE SET NULL: vínculo opcional |
| `isExtra` | INTEGER NOT NULL DEFAULT 0 | Extra **declarada** por el usuario; nunca se deduce |
| `source` | TEXT NOT NULL | `MANUAL` o `LIVE` |
| `note` | TEXT NULL | Máximo 500 caracteres |
| `revision` | INTEGER NOT NULL DEFAULT 1 | |
| `updatedUtc` | INTEGER NOT NULL | |

Índices:
- `(profileId, workDate)`.
- Un índice parcial que admite como máximo una fila con `endUtc IS NULL`. Room no genera índices parciales, así que se crea en la migración y se refuerza en el repositorio.

`CHECK` mediante validación en el repositorio:
- `endUtc > startUtc`;
- duración máxima de 24 h;
- sin solaparse con otras entradas del perfil.

### `WorkBreak`: pausas de un turno real
| Columna | Tipo | Notas |
|---|---|---|
| `id` | INTEGER PK autogenerado | |
| `entryId` | INTEGER NOT NULL | FK → `WorkEntry(id)`, ON DELETE CASCADE |
| `startUtc` / `endUtc` | INTEGER / INTEGER NULL | NULL = pausa en curso |
| `paid` | INTEGER NOT NULL DEFAULT 0 | Solo informativo; no se aplica ninguna regla |

Índice `(entryId, startUtc)` único. Validación: cada pausa queda dentro de su turno, sin solaparse con otras.

### `EntryCorrection`: historial de ediciones
| Columna | Tipo | Notas |
|---|---|---|
| `id` | INTEGER PK autogenerado | |
| `entryId` | INTEGER NOT NULL | **Sin FK**: el historial sobrevive al borrado de la entrada |
| `changedUtc` | INTEGER NOT NULL | |
| `action` | TEXT NOT NULL | `CREATE`, `EDIT`, `DELETE` o `FORGOTTEN_FIX` |
| `before` / `after` | TEXT NULL | JSON canónico de la entrada y sus pausas |
| `reason` | TEXT NULL | Opcional, lo escribe el usuario |

Índice `(entryId, changedUtc)`. Se escribe en la misma transacción que el cambio.

### `PayslipRecord`: nómina introducida por el usuario
| Columna | Tipo | Notas |
|---|---|---|
| `id` | INTEGER PK autogenerado | |
| `profileId` | INTEGER NOT NULL | FK → `Profile(id)`, ON DELETE CASCADE |
| `yearMonth` | TEXT NOT NULL | AAAA-MM; índice único `(profileId, yearMonth)` |
| `grossDecimal` | TEXT NULL | Cadena decimal, como en `PayRule` |
| `netDecimal` | TEXT NULL | |
| `note` | TEXT NULL | |
| `updatedUtc` | INTEGER NOT NULL | |

Al menos uno de los importes es obligatorio. No se calculan retenciones.

### `PayRule` reconstruida (tarifas con vigencia)
La tabla mantiene sus columnas, pero `id` pasa a ser autogenerado y se añade el índice único `(profileId, type, validFrom)`.

- **Regla:** no puede haber dos vigencias solapadas del mismo tipo. `validUntil` es inclusivo y vale NULL si sigue abierta.
- **Edición de una tarifa vigente:** el usuario elige «cambia desde la fecha F». Se cierra la vigencia anterior en F−1 y se crea una nueva.
- **Tarifas pasadas:** solo se modifican con una edición explícita de esa vigencia.

## 4. Migración 4 → 5

> **Actualización (fase 3, 2026-11-22):** por la decisión D3, el esquema 5 ya está ocupado por las tablas locales de sincronización (ver `ARQUITECTURA_0.3.0_CUENTAS_Y_SINCRONIZACION.md`, §8). Lo descrito en esta sección se aplicará como migración **5 → 6**, con `DataFormat.CURRENT = 3` y la copia de seguridad en formato 2.

En una sola `Migration(4, 5)`, dentro de la transacción de Room:

1. `CREATE TABLE` de `WorkEntry`, `WorkBreak`, `EntryCorrection` y `PayslipRecord`, con sus índices. El SQL se copia del esquema exportado `5.json`.
2. Reconstrucción de `PayRule`:
   - se crea `PayRule_new`;
   - `INSERT INTO PayRule_new SELECT …`, conservando los ids;
   - se eliminan `PayRule` y se renombra `PayRule_new` a `PayRule`;
   - se recrean los índices y se ejecuta `PRAGMA foreign_key_check`.
3. Si había filas de un mismo tipo con vigencias solapadas, no se pierden. Se conserva la de `validFrom` más reciente y se ajusta `validUntil` de las anteriores. Hoy no ocurre: hay una fila por tipo.
4. `PRAGMA application_id` pasa a `DataFormat.applicationId(3)`, con `DataFormat.CURRENT = 3` (valores nuevos: `source`, `action`).

No se usa ninguna migración destructiva.

**Vuelta atrás:**
- Una 0.2.x con la compuerta muestra «datos de una versión más nueva» y no modifica nada. Room lanza la excepción de bajada de versión y la compuerta la captura; ya hay test para la v5.
- 0.1.0 no tiene protección (documentado).
- Antes de migrar, la app no puede copiar la base de datos por sí sola sin riesgo, así que la mitigación es pedir al usuario una copia de seguridad (formato 1) antes de actualizar, tal como indica la lista de publicación.

**Tests:**
- `MigrationTestHelper` con fixtures v4 sintéticas: vacía; perfil completo con 6 tarifas; vacaciones y bajas; nocturnos y extras.
- Comprobar las filas después de migrar, que los ids de `PayRule` se conservan, `foreign_key_check` vacío y `application_id = 3`.
- Una prueba con una copia **anonimizada** del esquema de la base real (sin datos personales, solo su forma).
- Restaurar una copia de formato 1 en el esquema 5: las tablas nuevas quedan vacías.

## 5. Cambios en los repositorios

- **`RateRepository`:**
  - `rateAt(type, date)`, `ratesFor(period)` y `changeRate(type, amount, from)`, con validación de solapes;
  - `ConfigurationDao.rate()` desaparece en favor de `rateAt`.
- **`WorkRepository`:** `start()`, `startBreak()`, `endBreak()`, `end()`, `saveManual(entry, breaks, reason)` y `delete(id, reason)`. Cada operación es una transacción que escribe también `EntryCorrection` y valida solapes.
- **`PayslipRepository`:** `upsert(yearMonth, gross, net, note)` y `delete(yearMonth)`.
- **`BackupRepository` v2 (formato 2):** incluye las tablas nuevas. Lee el formato 1 restaurando las tablas nuevas vacías. El formato 2 se rechaza en versiones antiguas, lo que ya está implementado.

## 6. ViewModels

| ViewModel | Estado | Notas |
|---|---|---|
| `OcrImportViewModel` | Propuestas, conflictos, guardado | Se extrae de `CalendarViewModel` sin cambiar su lógica (los tests de recuperación se mueven con él) |
| `TimeTrackingViewModel` | Turno en curso, pausas, borradores manuales | Calcula la duración con `Clock` y el instante guardado; no mantiene temporizadores en segundo plano |
| `SummaryViewModel` | Añade planificado, real y diferencia | `ComparisonCalculator` en `domain` |
| `PayrollViewModel` | Estimación con `RateResolver` frente a nómina | Muestra la diferencia; no la interpreta |
| `RatesViewModel` | Vigencias por tipo | Edición «desde la fecha F» |

## 7. Navegación

- **Pestañas:** Calendario · **Fichaje** (nueva) · Resumen.
- **Rutas secundarias:**
  - Ajustes → Tarifas (vigencias), Nóminas y Copia de seguridad;
  - Detalle del día, que muestra planificado y real, y permite fichar a mano o corregir.
- Se mantiene la regla actual (`popUpTo(start)`, sin guardar ni restaurar estado), con un test por pestaña nueva.

## 8. Gestión de estado

- **Fuente de verdad:** Room. La interfaz observa flujos (`Flow`).
- **Borradores de edición:** se guardan en `SavedStateHandle`, porque el fichaje manual no debe perderse si el sistema cierra la app.
- **Fichaje en vivo:** el estado se escribe en Room en cuanto se pulsa el botón. Si el proceso muere, al volver el turno sigue «en curso» con su hora real de inicio.

## 9. Protección de datos

- Toda escritura es transaccional e incluye su entrada de historial.
- **Copia de seguridad:** antes de migrar a v5 y tras cada actualización se recuerda exportar una copia. El formato 2 incluye fichajes y nóminas.
- **CSV y PDF:**
  - se guardan solo mediante `ACTION_CREATE_DOCUMENT`;
  - antes se avisa de que el archivo contiene datos personales;
  - sin compartir automáticamente ni subir nada;
  - no se escribe nada en almacenamiento compartido sin elección del usuario.
- Sin permisos nuevos: ni ubicación, ni alarmas exactas, ni servicio en primer plano. La única opcional es `POST_NOTIFICATIONS`, si se añade una notificación de turno en curso (fase posterior y opcional).

## 10. Recuperación de errores

- **Fichaje olvidado:** si un turno sigue abierto más de 24 h, al abrir la app se pide indicar la hora real de salida (`FORGOTTEN_FIX`). Nunca se cierra solo con una hora inventada.
- **Reinicio del dispositivo:** no hace falta ningún receptor de arranque. El estado está en Room y la duración se calcula al abrir.
- **Cambio de hora o de zona horaria:** las duraciones se calculan entre instantes UTC. La asignación al día usa la zona guardada en la entrada.
- **Escrituras inciertas:** se aplica el patrón existente de `CalendarViewModel.write`: no se repiten, se reconcilian leyendo.

## 11. Estrategia de pruebas

- **Dominio:**
  - `WorkValidator`: solapes, pausas fuera del turno, más de 24 h, un solo turno abierto;
  - `ComparisonCalculator`: límites de mes y año, nocturnos que cruzan de mes o de año, cambio de hora;
  - `RateResolver`: vigencias, huecos («No disponible») y fechas futuras;
  - `PayrollComparison`: importes ausentes;
  - `CsvExporter`: escape de comas y comillas, UTF-8 con BOM opcional, ningún dato que no se haya pedido.
- **Datos:** la migración 4→5 con fixtures; las transacciones de `WorkRepository` con el historial; la copia en formato 1 y 2, en ambos sentidos y con rechazos.
- **ViewModels:** muerte del proceso con un turno abierto (estado reconstruido desde Room) y borradores en `SavedStateHandle`.
- **Instrumentadas (app `verification`):** fichaje en vivo, reinicio de la app, exportación CSV por el selector de documentos y navegación con la pestaña nueva.
- **Regresión:** se mantienen las suites actuales (176 unitarias y 30 instrumentadas compiladas).

## 12. Fases de implementación (orden recomendado)

| # | Fase | Complejidad | Depende de |
|---|---|---|---|
| 1 | Separar `OcrImportViewModel` de `CalendarViewModel` (sin cambio funcional) | BAJA | — |
| 2 | Esquema v5 completo y `Migration(4, 5)` con tests; copia de seguridad en formato 2 | ALTA | 1 |
| 3 | Tarifas con vigencia (`RateRepository`, `RateResolver`, interfaz de vigencias) | MEDIA | 2 |
| 4 | Fichaje manual (WorkEntry, WorkBreak, EntryCorrection, edición y correcciones) | MEDIA | 2 |
| 5 | Planificado frente a real (resúmenes de mes y año) | MEDIA | 4 |
| 6 | Exportación CSV (mes, extras del año, historial de turnos) | BAJA | 4, 5 |
| 7 | Fichaje en vivo (iniciar y terminar, pausas, recuperación) | MEDIA | 4 |
| 8 | Comparación con nóminas (`PayslipRecord`) | BAJA-MEDIA | 3 |
| 9 | Exportación PDF | MEDIA | 6 |
| 10 | Accesibilidad (fuentes grandes, 320 dp, TalkBack) | BAJA | — |
| 11 | Rendimiento (resúmenes fuera de la composición) | BAJA | 5 |

**Ajuste respecto al orden sugerido:** la separación del ViewModel de importación va primero, porque reduce el riesgo de tocar `CalendarViewModel` durante la migración. Además, **una sola migración v5** incluye todas las tablas. Así el usuario actualiza el esquema una única vez, con su copia de seguridad hecha.

## 13. Dependencias

No se prevén bibliotecas nuevas:
- **CSV:** escritura propia.
- **PDF:** `android.graphics.pdf.PdfDocument`.
- **JSON:** `org.json`.
- **Tiempo:** `java.time`.

Cualquier dependencia nueva requiere justificarla y comprobar que no añade permisos de red.

## 14. Riesgos

| Riesgo | Impacto | Mitigación |
|---|---|---|
| La migración v5 falla en datos reales | Alto | Fixtures v4, prueba con copia anonimizada, copia de seguridad obligatoria antes, sin destructiva |
| Confundir planificado, real y extra compensable | Alto (credibilidad) | Textos explícitos; diferencias sin calificar; las extras solo las declara el usuario |
| Fichaje en vivo perdido por el ciclo de vida | Medio | Estado en Room al pulsar, instantes UTC, aviso de turno abierto más de 24 h |
| Tarifas históricas mal migradas | Medio | Ids conservados, test de vigencias y de estimaciones pasadas estables |
| Exportaciones con datos personales | Medio | Solo selector de documentos y aviso previo; sin compartir automáticamente |
| Volver a una versión anterior | Medio | Compuerta en 0.2.x o superior; documentar que 0.1.0 no está protegida |
| Clave de firma de depuración perdida | Alto | Copia de la clave fuera del repositorio (lista de publicación) |

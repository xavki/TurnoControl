# TurnoControl 0.3.0: cuentas opcionales y sincronización entre dispositivos

Estado: **auditoría y diseño, sin implementar**. Fecha: 2026-11-22.
Rama verificada: `feature/0.2.0-redesign`, commit `221cfe2`, árbol limpio.

Alcance de este documento:
- No modifica código, no crea proyectos externos y no despliega nada.
- Todo el SQL es una **propuesta, no aplicada**.
- Complementa `docs/ARQUITECTURA_0.3.0.md` sin sustituirlo. La sección 14 enumera los cambios que ese plan necesitaría.

---

## 0. Resumen ejecutivo

- **Viabilidad:** sincronizar con Supabase es viable, pero no a coste cero sobre el código actual.
- **Bloqueos que hay que resolver antes de escribir código de red:**
  1. La app hoy promete y aplica "sin Internet". El manifiesto elimina `INTERNET` y `ACCESS_NETWORK_STATE`, y lo repiten el README, la lista de publicación y el propio plan 0.3.0.
  2. Los identificadores locales no sirven entre dispositivos:
     - `Profile` y `Contract` usan `id = 1`;
     - las tarifas tienen ids fijos por tipo;
     - los intervalos se borran y se reinsertan en cada guardado;
     - `revision` es un contador local.
  3. Tras la migración a v5, `BackupCodec.decode` rechazaría **todas** las copias actuales, porque exige `schemaVersion == 4`.
- **Diseño propuesto:**
  - Room sigue siendo la fuente de verdad de la interfaz.
  - La sincronización trabaja por **agregados con clave natural**: un día de calendario completo por fecha, una tarifa por (tipo, vigencia), una nómina por mes, etc.
  - El servidor asigna las versiones y valida con comparar-y-escribir.
  - La lectura usa un cursor monotónico por usuario.
  - Las tablas laterales de sincronización no afectan a la copia de seguridad.
  - Un conflicto en un dato valioso **nunca** se resuelve en silencio con "gana la última escritura".
- **Aislamiento:** un archivo de base de datos por cuenta, vinculado a ella. Los datos de la cuenta A nunca se suben a la cuenta B sin una acción explícita.
- **Primera fase recomendada (sección 15):** sin red y sin cambios de esquema. Consiste en la extracción de `OcrImportViewModel` (ya planificada) y en el motor de combinación y conflictos en `domain`, en Kotlin puro y con tests.

---

## 1. Auditoría de la arquitectura actual

### 1.1 Build y estructura
- Un solo módulo `app` con las capas `ui`, `domain` y `data`, e inyección con Hilt.
- Versiones:
  - AGP 8.9.0, Kotlin 2.1.20, Room 2.7.1 (kapt), Hilt 2.56.2;
  - Compose BOM 2025.04.01, DataStore 1.1.4, ML Kit text-recognition 16.0.1;
  - minSdk 26, targetSdk 35, `versionName 0.2.0`, `versionCode 2`.
- Tipo de build `verification`, con sufijo `.verification`. Es el `testBuildType`, de modo que los tests instrumentados **nunca** tocan `com.turnocontrol`. Esto se mantiene obligatoriamente.
- Manifiesto:
  - `INTERNET` y `ACCESS_NETWORK_STATE` se eliminan con `tools:node="remove"`. El comentario explica que así se bloquea la telemetría transitiva de los SDK.
  - `allowBackup=false`, y las reglas excluyen todos los dominios de la copia en la nube y de la transferencia entre dispositivos.
- Tests: se cuentan **178 `@Test` unitarios y 31 instrumentados**. Se contaron anotaciones, no se ejecutaron. El plan 0.3.0 cita 176/30.

### 1.2 Room v4: tablas, relaciones y migraciones (`app/schemas/.../4.json`)

| Tabla | PK | Relaciones y unicidad | Observación para sincronizar |
|---|---|---|---|
| `Profile` | `id` (por defecto 1, sin autogenerar) | — | Las consultas fijan `WHERE id = 1` |
| `Contract` | `id` = 1 | FK `profileId` → Profile, CASCADE | Una sola fila; `ConfigurationDao.contract()` lee `id = 1` |
| `PayRule` | `id` fijo por tipo (1..6, o el siguiente libre) | FK `profileId`, CASCADE | Solo es única por tipo en la validación de la copia, no en el esquema. El plan v5 la reconstruye |
| `ScheduledDay` | `id` autoincremental | UNIQUE `(profileId, date)`, FK CASCADE | **La fecha es la clave natural.** `revision` es local |
| `ScheduledInterval` | `id` autoincremental | UNIQUE `(dayId, position)`, FK CASCADE | **Ids inestables:** `ScheduleRepository.save` ejecuta `clearIntervals` + `insertIntervals` |

- Migraciones: `1→2` (moneda), `2→3` (calendario) y `3→4` (`isExtra`). Ninguna es destructiva.
- `PRAGMA application_id` lleva la marca `0x5443` y el formato de datos (`DataFormat.CURRENT = 2`).

### 1.3 Repositorios y ViewModels
- **`ScheduleRepository.save`:**
  - es el único punto de escritura del calendario;
  - es transaccional;
  - compara con una instantánea esperada (`ScheduleChangedException`);
  - valida el conjunto final con `PlannedScheduleValidator`, que incluye los solapes entre días por turnos nocturnos;
  - registra el origen: `MANUAL` u `OCR_REVIEWED`.
- **`ConfigurationRepository`:**
  - lee y escribe perfil, contrato y tarifas mediante `saveConfiguration`;
  - conserva los tipos de tarifa desconocidos.
- **`BackupRepository` y `CompatibilityChecker`:** se describen en el apartado 1.6.
- **ViewModels:**
  - `CalendarViewModel` contiene todavía las propuestas de importación (`ImportProposal`, `ImportStatus` NEW/UNCHANGED/CONFLICT) y la reconciliación de escrituras inciertas (`WriteReconciliation`);
  - `ImportViewModel` solo gestiona imagen y OCR;
  - `ScheduleReviewViewModel` gestiona los borradores de revisión, que **no se persisten**;
  - el resto son `SummaryViewModel`, `ConfigurationViewModel`, `BackupViewModel` y `StartupViewModel`.

### 1.4 DataStore
Hay una única clave, `dark_theme`. Es preferencia del dispositivo y **no se sincroniza**.

### 1.5 Dominio
- **Calendario:** `PlannedDay(date, kind, intervals)`, con los tipos `WORK`, `FREE`, `VACATION` y `SICK_LEAVE`. Las vacaciones y las bajas son **tipos de día**, sin tabla propia.
- **Horas extra:** son intervalos con `isExtra`, declarados por el usuario. `ScheduleSummary` totaliza por mes y año naturales, asignando cada turno a su día de inicio.
- **Nómina:** `EarningsEstimator` aplica las tarifas actuales, guardadas como cadena decimal.
- **OCR:** el resultado no se guarda. Solo persisten los días confirmados, con `origin = OCR_REVIEWED`. El "estado de revisión" del calendario se reduce a ese campo.

### 1.6 Copia de seguridad y compatibilidad
- **Formato 1 (JSON con SHA-256 canónico):**
  - incluye las 5 tablas y `darkTheme`;
  - exige un solo perfil y un solo contrato, ambos con `id = 1`;
  - rechaza los tipos desconocidos;
  - rechaza `schemaVersion` distinto de 4.
- **Restauración:**
  1. copia de seguridad previa automática y diario;
  2. reemplazo de todas las tablas en una transacción;
  3. escritura de DataStore;
  4. verificación de que la instantánea completa coincide (`stored != wanted`);
  5. recuperación tras una interrupción.
- **Compuerta de arranque:** no escribe cuando rechaza y marca `application_id` solo cuando acepta los datos.

### 1.7 Plan 0.3.0 existente (resumen)
- Una sola `Migration(4,5)` con `WorkEntry`, `WorkBreak`, `EntryCorrection` y `PayslipRecord`, y la reconstrucción de `PayRule` con ids autogenerados y vigencias.
- Copia en formato 2.
- Fichaje manual y en vivo, comparación de planificado frente a real, CSV y PDF.
- Principios explícitos: "sin servicios en la nube" y "sin bibliotecas nuevas".

---

## 2. Riesgos y bloqueos principales

| # | Riesgo o bloqueo | Gravedad | Evidencia | Mitigación propuesta |
|---|---|---|---|---|
| B1 | Volver a permitir Internet contradice la promesa de privacidad y **reabre la telemetría transitiva de ML Kit** | Bloqueo (decisión) | `AndroidManifest.xml`, README §Privacidad, `LISTA_PUBLICACION.md`, plan §0 y §13 | Decisión D1. Si se acepta: eliminar con `tools:node="remove"` los componentes de `datatransport`/Firelog, comprobar en un test el manifiesto fusionado y hacer una prueba de tráfico que solo admita el host de Supabase. Alternativa: dos *flavors* (`offline` y `sync`) |
| B2 | Los ids locales no son estables entre dispositivos | Bloqueo técnico | §1.2 | Agregados con clave natural y UUID solo para entidades de tipo "evento" (§5.2) |
| B3 | Tras v5, todas las copias actuales serían irrestaurables | Alto | `BackupCodec.decode`: `schema < ROOM_SCHEMA_VERSION` → excepción | Ruta de actualización del formato 1 (schema 4) a v5 en la fase de migración (§8) |
| B4 | La verificación de la restauración compara la instantánea completa | Alto | `restore()`: `stored != wanted` | Los metadatos de sincronización van en tablas laterales fuera de `BackupSnapshot` |
| B5 | Dos dispositivos editan días distintos y el resultado combinado es inválido (nocturno del día D solapado con el D+1) | Alto | `PlannedScheduleValidator` valida entre días | Validar **después** de combinar; si el resultado es inválido, se registra un conflicto de invariante y no se aplica (§9.4) |
| B6 | La v5 plantea un índice parcial para "un único turno abierto": dos dispositivos pueden iniciar un fichaje sin conexión | Alto | Plan §3 `WorkEntry` | Al bajar los datos, el segundo turno abierto se trata como conflicto, no como error de inserción |
| B7 | Un dispositivo más antiguo recibe valores que no conoce (nuevo `kind` o nuevo tipo de tarifa) | Alto | Regla de `DataFormat`: no convertir valores desconocidos | `data_format` en cada fila remota y cuarentena local de lo que no se entiende: nunca se sobrescribe ni se descarta |
| B8 | Las fechas de baja médica pueden considerarse **datos de salud** (RGPD art. 9), aunque no lleven diagnóstico | Alto (legal) | `DayKind.SICK_LEAVE` | D9 revisada (§7.4): se sincroniza solo fecha y estado. Antes de la nube real: base jurídica y consentimiento, región UE, política de privacidad, Data safety, evaluación de E2EE (D6) |
| B9 | Plan Free de Supabase: el proyecto se pausa tras 7 días de poca actividad y no tiene copias automáticas | Medio | Documentación de Supabase (fuentes) | Pro para producción (D4). La nube **no** sustituye a la copia local |
| B10 | `TurnoDatabase` es un singleton de Hilt y las consultas fijan `profileId = 1` | Medio | `StorageModule`, DAOs | Un archivo por cuenta (§7) y reinicio controlado del proceso al cambiar de cuenta |
| B11 | Bibliotecas nuevas (supabase-kt, Ktor, kotlinx-serialization, Credential Manager, WorkManager) | Medio | Plan §13 | Justificarlas (D2) y fijar versiones exactas |
| B13 | La comparación de instantánea del calendario entero rechazaría ediciones en curso cada vez que llegue un día de otro dispositivo; las escrituras incondicionales de configuración generarían mutaciones falsas | Alto | `ScheduleRepository.save`, `ConfigurationDao.saveConfiguration` | Comparación limitada a las fechas afectadas ±1; captura por diferencias de hash (§5.3, §5.4) |
| B12 | La firma de la app: el inicio de sesión con Google en Android depende del SHA-1 del certificado | Medio | `PENDIENTES_FUTUROS.md` (custodia de la clave) | Clientes OAuth para la firma de release **y** para `.verification`. Custodia de claves |

---

## 3. Viabilidad de Supabase

**A favor:**
- Postgres con RLS.
- Supabase Auth admite el ID token de Google mediante `signInWithIdToken` y contraseña con verificación y PKCE.
- Hay cliente Kotlin (`supabase-kt`, de supabase-community, referenciado en la documentación oficial; las últimas versiones vistas son de la serie 3.x).
- Edge Functions para operaciones privilegiadas.
- Regiones de la UE.

**En contra o límites:**
- **No trae sincronización sin conexión.** Hay que construir el protocolo (§6), que es pequeño porque el volumen es mínimo: unos 365 días al año por usuario y cientos de bytes por día.
- El plan Free pausa los proyectos inactivos (7 días) y no incluye copias automáticas.
- El cliente Kotlin depende de Ktor y kotlinx-serialization, y es mantenido por la comunidad.

**Alternativas evaluadas:**

| Opción | Ventaja | Inconveniente |
|---|---|---|
| Firebase (Firestore) | Modo sin conexión integrado | NoSQL; la combinación sin conexión es "gana la última escritura" por campo, justo lo que hay que evitar; más SDK de Google con telemetría |
| PowerSync sobre Supabase | Sincronización sin conexión ya hecha | Otro proveedor más, coste y complejidad desproporcionados para este volumen |
| Backend propio | Control total | Coste de operación y seguridad muy superior |

**Coste (verificar al decidir):**
- Free: suficiente para desarrollo con datos sintéticos.
- Pro: unos 25 USD al mes por organización, sin pausas y con copias diarias de 7 días. Es lo adecuado si hay usuarios reales.

**Veredicto:** **viable.** Recomendación: Supabase Auth, con Postgres accedido **solo mediante funciones RPC** y un protocolo de sincronización propio y fino.

---

## 4. Arquitectura de autenticación

### 4.1 Principios
- **"Continuar sin cuenta" no hace ninguna llamada de red.** No se usa el inicio de sesión anónimo de Supabase.
- En la app solo va la clave **publicable/anon**, que es pública por diseño y solo funciona con RLS. Se inyecta desde `local.properties` o una propiedad de Gradle hacia `BuildConfig`, sin confirmarla en el repositorio.
- La clave **secret/service_role** vive solo en los secretos de una Edge Function. Nunca en la app ni en el repositorio.

### 4.2 Flujos
| Flujo | Implementación | Notas |
|---|---|---|
| Google | Credential Manager (`androidx.credentials` + `googleid`) con `GetGoogleIdOption` / `GetSignInWithGoogleOption`, `serverClientId` = ID de cliente **web** y nonce: SHA-256 hacia Google, el valor en claro hacia Supabase → `auth.signInWith(IDToken) { provider = Google; idToken; nonce }` | No se usa `GoogleSignInClient` (obsoleto) ni el flujo OAuth en navegador, salvo como alternativa |
| Registro con correo | `signUpWith(Email)` con confirmación obligatoria | Contraseña de 10 caracteres como mínimo; límites de frecuencia de Supabase; CAPTCHA opcional |
| Verificación de correo y recuperación | `FlowType.PKCE`; enlace que vuelve a la app (preferible un **App Link https verificado**; como alternativa `turnocontrol://auth/callback`) → `handleDeeplinks(intent)`. En la recuperación: sesión de recuperación → `updateUser(password)` | PKCE evita que la interceptación del enlace sirva sin el verificador local |
| Sesión persistente | `SessionManager` propio: archivo privado cifrado con AES-GCM y una clave de **Android Keystore** | **No** se usa `security-crypto` / `EncryptedSharedPreferences`, que están obsoletos desde 2025. Ya está excluido de las copias del sistema |
| Renovación y caducidad | `autoRefresh`; JWT de 1 h; rotación de refresh token con detección de reutilización | Si la renovación falla, el estado pasa a `AUTH_REQUIRED`: la sincronización se pausa, los datos y la cola se conservan y se muestra el aviso "Vuelve a iniciar sesión" |
| Cerrar sesión | `signOut(scope = LOCAL)` y borrado del archivo de sesión | Los datos **no** se borran (§7.3) |
| Cambio de cuenta | Cerrar sesión de A → iniciar sesión con B → se abre el archivo de B y se reinicia el proceso de forma controlada | Nunca se mezclan las bases |
| Reautenticación | Se exige un inicio de sesión reciente (≤ 5 min) para borrar la cuenta, cambiar el correo o la contraseña, o "sobrescribir la nube". **La comprobación válida es la del servidor** (en la Edge Function o la RPC), con la marca de tiempo del método en el claim `amr` del JWT o con `last_sign_in_at` del usuario (**sin verificar** cuál expone Supabase de forma fiable). `iat` **no** sirve, porque se renueva en cada *refresh*. La comprobación en el cliente es solo una barrera de interfaz | Cambio de correo con confirmación en ambas direcciones |
| Borrado de cuenta | Edge Function `delete-account`: verifica el JWT y la reautenticación reciente, borra las filas y llama a `auth.admin.deleteUser(uid)` (CASCADE) | Google Play exige una ruta **dentro de la app** y un **enlace web**. Se ofrece conservar la copia local desvinculada o borrarla |

### 4.3 Configuración que hará el propietario (más adelante, no ahora)

**Supabase** (dos proyectos: `turnocontrol-dev`, con datos sintéticos, y `turnocontrol-prod`):
- Región de la UE.
- Proveedor de correo con confirmación obligatoria y cambio de correo seguro.
- SMTP propio para producción. El servicio de correo integrado tiene límites bajos; verificarlo.
- Proveedor Google con el ID y el secreto del cliente web, y la comprobación de nonce **activada**.
- Lista de URL de redirección permitidas, limitada al enlace de la app.
- Inicio de sesión anónimo **desactivado**.
- Esquemas expuestos: solo `public`; el esquema `turno` **no** se expone.
- Rotación de refresh token activada.
- Aplicar las migraciones SQL del repositorio con la CLI.
- Secreto `SUPABASE_SERVICE_ROLE_KEY` solo en las Edge Functions.

**Google Cloud:**
- Pantalla de consentimiento OAuth de tipo External, con los ámbitos `openid`, `email` y `profile`, la URL de la política de privacidad y el dominio.
- Un cliente **Web**.
- Clientes **Android** para `com.turnocontrol` (SHA-1 de release) y `com.turnocontrol.verification` (SHA-1 de depuración).

**Google Play:**
- Página web de borrado de cuenta.
- Actualizar el formulario Data safety y la política de privacidad.

---

## 5. Arquitectura de sincronización

### 5.1 Principios
1. La interfaz lee **solo** de Room. La sincronización es un proceso aparte que escribe en Room con las mismas validaciones.
2. **El servidor decide el orden.** Los relojes de los dispositivos solo sirven como información.
3. Cada escritura local de un registro sincronizable genera, **en la misma transacción**, una entrada en la cola de salida (*outbox*). Solo ocurre si la base está vinculada a una cuenta.
4. Comparar-y-escribir por registro: el cliente envía `base_version` y el servidor rechaza si no coincide.
5. Idempotencia: cada mutación lleva un `mutation_id` (UUID) que el servidor recuerda.
6. Ningún dato valioso se pierde en silencio. El lado perdedor de un conflicto se conserva localmente (`SyncConflict`) y en el historial remoto.

### 5.2 Unidades de sincronización (agregados)
| Entidad remota | Clave | Contenido (`payload`) | Origen local |
|---|---|---|---|
| `profile` | `"profile"` (única por cuenta) | `displayName`, `alias`, `zoneId` | `Profile` |
| `contract` | `"contract"` (con vigencias en el futuro: `validFrom`) | tipo, minutos, unidad, vigencia | `Contract` |
| `pay_rule` | `"{type}@{validFrom}"` | importe, modo, acumulación, `validUntil`, moneda | `PayRule` (v5) |
| `scheduled_day` | `"AAAA-MM-DD"` | `kind`, `zoneId`, `origin` y la **lista completa de intervalos** | `ScheduledDay` + `ScheduledInterval` |
| `work_entry` (v5) | UUID del cliente | turno real con sus **pausas incluidas** | `WorkEntry` + `WorkBreak` |
| `entry_correction` (v5) | UUID | historial de solo añadido; no genera conflictos | `EntryCorrection` |
| `payslip` (v5) | `"AAAA-MM"` | bruto, neto, nota | `PayslipRecord` |
| `setting` | nombre del ajuste | solo los ajustes de **cuenta** que se aprueben (p. ej. el inicio de semana, en el futuro) | DataStore |

Notas:
- **Clave natural frente a UUID.** Los días, tarifas y nóminas usan la clave natural. Así, dos dispositivos que crean el mismo día sin conexión producen un **conflicto detectable** y no un duplicado. Los turnos reales son eventos y usan UUID; los posibles duplicados (solape del mismo turno introducido dos veces) se detectan después de combinar.
- **Ids que no se sincronizan:** los ids locales de intervalos y la `revision` local nunca salen del dispositivo.

**Lo que no se sincroniza:**
- `dark_theme`;
- los borradores de OCR y de revisión, y las imágenes;
- el estado de navegación;
- los archivos de recuperación de la copia (`backup-recovery/`);
- la sesión;
- los diagnósticos.

### 5.3 Tablas locales nuevas (laterales, sin FK hacia las tablas de dominio)
| Tabla | Columnas clave | Uso |
|---|---|---|
| `SyncState` (una fila) | `accountId`, `deviceId` (UUID), `cursor`, `lastSyncAt`, `status`, `linkedAt` | Vínculo de **esta base** con una cuenta |
| `SyncRecordState` | `(entity, key)` PK, `serverVersion`, `syncedHash` | Última versión conocida del servidor por registro |
| `SyncOutbox` | `mutationId` PK, `entity`, `key`, `op` (UPSERT/DELETE), `payload`, `baseVersion`, `state` (PENDING/IN_FLIGHT), `attempts`, `createdAt` | Cola de salida |
| `SyncConflict` | `(entity, key)`, `kind` (VERSION/INVARIANT/DUPLICATE/UNSUPPORTED), `localPayload`, `remotePayload`, `remoteVersion`, `detectedAt` | Conflictos pendientes de decisión |
| `SyncQuarantine` | `(entity, key)`, `dataFormat`, `rawPayload`, `remoteVersion` | Filas de versiones más nuevas, que se aplicarán tras actualizar la app |

- **Agrupación en la cola:** solo hay una mutación `PENDING` por `(entity, key)`. Una edición posterior sustituye el contenido y conserva el `baseVersion` original.
- **Mutaciones en vuelo:** una mutación `IN_FLIGHT` es inmutable, porque la respuesta pudo perderse. Una edición nueva crea otra fila detrás.
- **Versión base de esa fila posterior:** se resuelve **al enviarla**, con la versión `APPLIED` que devuelva su predecesora. Nunca se copia la base de la predecesora, porque provocaría un conflicto consigo misma.
- **Un lote nunca contiene dos mutaciones de la misma clave.** La segunda espera al lote siguiente.
- **Captura por diferencias, no por llamadas de escritura.** Hoy hay escrituras incondicionales:
  - `ConfigurationDao.saveConfiguration` siempre hace *upsert* de perfil y contrato, y borra y reinserta todas las tarifas gestionadas;
  - `ScheduleRepository.save` crea un perfil provisional «Mi calendario».

  Por eso, al confirmar una transacción, se compara el hash canónico de cada agregado afectado con `SyncRecordState.syncedHash` y solo se encolan los agregados que han cambiado. El perfil provisional nunca se encola: el perfil remoto prevalece sobre él, así que no aparece un conflicto falso en cada dispositivo nuevo.

### 5.4 Algoritmo de una pasada de sincronización
Se ejecuta con un `Mutex` de proceso y un trabajo único de WorkManager.

1. **Vínculo.** Si `session.user.id` es distinto de `SyncState.accountId`, se aborta. Es el invariante de aislamiento.
2. **Subida.** Se toman lotes de hasta 50 mutaciones, se marcan `IN_FLIGHT` (persistido) y se llama a `sync_push`. Según el resultado:
   - `APPLIED` → en una transacción: se borra la entrada de la cola y se actualiza `SyncRecordState`.
   - `CONFLICT` → si el `payload` remoto es igual al local (mismo hash), se adopta la versión remota sin preguntar. Si no, se crea `SyncConflict` y el valor local se mantiene visible con una marca.
   - `REJECTED` (validación) → `SyncConflict` de tipo error. Nunca se descarta.
   - Error de red o tiempo agotado → la mutación se queda `IN_FLIGHT` con el **mismo** `mutation_id`. El reintento es idempotente.
   - Error 401 → se intenta renovar la sesión una vez; si falla, `AUTH_REQUIRED` y se detiene.
3. **Bajada.** Se llama a `sync_pull(cursor)` por páginas. Cada página se procesa en **una transacción de Room**, en la que se aplican las filas y se guarda el cursor.
   - Si `remote.version <= SyncRecordState.serverVersion` → se ignora. Es un eco de una subida propia o una versión ya conocida.
   - Si `data_format` es mayor que el soportado o el valor es desconocido → `SyncQuarantine`.
   - Si existe una mutación local pendiente para la misma clave y `remote.version > pending.baseVersion` → mismo `payload`: se adopta; distinto: `SyncConflict`.
   - En otro caso → se aplica mediante la ruta interna del repositorio, que **no** genera cola y **valida el conjunto resultante** (B5 y B6). Si el resultado es inválido → `SyncConflict` de tipo INVARIANT y no se aplica.
4. Se actualiza `lastSyncAt` y el estado visible.

**Riesgo de regresión con el código existente:** `ScheduleRepository.save` compara la instantánea **del calendario entero** (`current != expected.canonical()`). Cualquier día que llegue de otro dispositivo, aunque sea de otra fecha, rechazaría con `ScheduleChangedException` una edición o una importación OCR que el usuario tenga en curso.

Cambio necesario en la fase 4, con dos opciones:
- **(a)** limitar la comparación a las fechas modificadas ±1 día, por las continuaciones nocturnas, manteniendo la validación del conjunto final;
- **(b)** aplazar la aplicación de la bajada mientras haya un editor o una revisión abiertos.

Se recomienda (a), con (b) como refuerzo para las revisiones de OCR largas.

### 5.5 Casos exigidos

| Caso | Comportamiento |
|---|---|
| 1-3. Crear, editar o borrar sin conexión | Escritura local más una entrada en la cola, en la misma transacción. El borrado se envía como `DELETE` con `baseVersion` |
| 4. Reconexión | WorkManager con la restricción `CONNECTED`, al abrir la app y desde "Sincronizar ahora" |
| 5. Repetir sin duplicar | Las claves naturales y el `mutation_id` lo garantizan; repetir la bajada es idempotente gracias al cursor |
| 6. Registros distintos en dos dispositivos | Ambos se aplican, y después se valida el invariante entre días |
| 7. El mismo registro en dos dispositivos | El segundo recibe `CONFLICT`; si el contenido es igual, se resuelve solo; si no, decide el usuario |
| 8. Propagación de borrados | Lápidas (*tombstones*) en el servidor, conservadas indefinidamente en v1 (el volumen es mínimo) |
| 9. Sincronización interrumpida | Cursor y aplicación en la misma transacción; las mutaciones `IN_FLIGHT` se reenvían con su id |
| 10. Reintentos | WorkManager con backoff exponencial (base de 30 s; máximo de la plataforma de unas 5 h) |
| 11. Peticiones duplicadas | Tabla `applied_mutations`: el servidor devuelve el resultado guardado |
| 12. Sesión caducada en mitad de la sincronización | Renovar una vez → `AUTH_REQUIRED`; sin pérdida de cola |
| 13. App cerrada en mitad de la sincronización | Todo el estado está en Room. En la siguiente pasada se continúa |
| 14. Semanas sin conexión | La cola se agrupa por clave y la bajada va por páginas. En v1 **no se podan** las lápidas, así que cualquier cursor sigue siendo válido. Si en el futuro se introduce la poda, `sync_pull` deberá comprobar `p_since < min_cursor` y devolver `RESYNC_REQUIRED` → reconciliación completa con revisión, nunca sobrescritura. Esa comprobación no está en el SQL propuesto |

### 5.6 Política de conflictos (concreta)
| Datos | Política |
|---|---|
| Contenido idéntico | Resolución automática: se adopta la versión del servidor |
| Día de calendario, turno real, tarifa, nómina, contrato | **Decisión explícita.** Mientras no hay decisión, se muestra el valor local con la marca "En conflicto", y el Resumen indica "Hay N conflictos sin resolver; los totales pueden cambiar" |
| Perfil (`displayName`, `alias`) | Explícita, con la opción "usar siempre el más reciente" para este tipo de dato |
| `zoneId` del perfil | Explícita, porque afecta a la asignación de días |
| `entry_correction` | Solo se añade y nunca hay conflicto |
| Invariante roto (solape entre días o dos turnos abiertos) | Explícita, con una vista de los dos registros implicados |
| Valor desconocido (versión más nueva) | Cuarentena y aviso "Actualiza TurnoControl en este dispositivo" |

- **Opciones de resolución:** "Mantener este dispositivo", "Usar el otro dispositivo" y, para días, "Editar". Se reutiliza el modelo `ImportProposal` (NEW/UNCHANGED/CONFLICT) y la pantalla de comparación de importación, para que el usuario aprenda **un solo** modelo de revisión.
- **Recuperación:** toda sobrescritura en el servidor guarda la versión anterior en `turno.record_history` durante 90 días.

---

## 6. Esquema remoto propuesto (propuesta, no aplicada)

Se usa una tabla genérica con un `payload` JSONB validado por entidad. Las razones:
- un solo protocolo para todas las entidades;
- volumen mínimo;
- permite añadir más adelante cifrado de extremo a extremo (el `payload` pasaría a ser texto cifrado) sin cambiar el protocolo.

```sql
-- PROPUESTA, NO APLICADA
create schema if not exists turno;          -- NO se expone en la API (solo "public")
revoke all on schema turno from public, anon;
grant usage on schema turno to authenticated;

create table turno.user_state (
  user_id   uuid primary key references auth.users(id) on delete cascade,
  last_seq  bigint not null default 0,
  min_cursor bigint not null default 0         -- horizonte de poda (0 = sin poda)
);

create table turno.records (
  user_id      uuid   not null references auth.users(id) on delete cascade,
  entity       text   not null check (entity in ('profile','contract','pay_rule','scheduled_day',
                                                 'work_entry','entry_correction','payslip','setting')),
  record_key   text   not null check (length(record_key) between 1 and 100),
  version      bigint not null check (version >= 1),   -- por registro, la asigna el servidor
  seq          bigint not null,                        -- secuencia por usuario = cursor
  deleted      boolean not null default false,
  data_format  int    not null check (data_format between 1 and 1000),
  payload      jsonb,
  updated_by_device uuid not null,
  updated_at   timestamptz not null default now(),    -- informativo
  primary key (user_id, entity, record_key),
  check ((deleted and payload is null) or (not deleted and payload is not null)),
  check (payload is null or pg_column_size(payload) <= 16384)
);
create unique index records_user_seq on turno.records (user_id, seq);

create table turno.record_history (
  user_id uuid not null references auth.users(id) on delete cascade,
  entity text not null, record_key text not null, version bigint not null,
  deleted boolean not null, data_format int not null, payload jsonb,
  replaced_at timestamptz not null default now(),
  primary key (user_id, entity, record_key, version)
);

create table turno.applied_mutations (
  user_id     uuid not null references auth.users(id) on delete cascade,
  mutation_id uuid not null,
  result      jsonb not null,
  applied_at  timestamptz not null default now(),
  primary key (user_id, mutation_id)
);
```

**Validación por entidad** (`turno.validate_mutation`, en `plpgsql`):
- `scheduled_day`:
  - la clave sigue el formato `^\d{4}-\d{2}-\d{2}$` y coincide con `payload->>'date'`;
  - `kind` pertenece al conjunto conocido para el `data_format` indicado;
  - hay como máximo 4 intervalos;
  - cada intervalo tiene minutos 0..1439 y desfases 0..1.
- Importes: cadena decimal ≥ 0 con 4 decimales como máximo.
- Límites de tamaño.
- El servidor se actualiza **antes** que los clientes cuando aparece un `data_format` nuevo.
- Las reglas entre registros (solapes) se validan en el cliente: el servidor no conoce la semántica del calendario.

```sql
-- PROPUESTA, NO APLICADA: escritura con comparar-y-escribir, idempotente y serializada por usuario.
create or replace function public.sync_push(p_device_id uuid, p_mutations jsonb)
returns jsonb language plpgsql security invoker set search_path = '' as $$
declare
  v_uid uuid := auth.uid();
  v_m jsonb; v_prev jsonb; v_res jsonb; v_results jsonb := '[]'::jsonb;
  v_cur turno.records%rowtype; v_seq bigint; v_new bigint;
begin
  if v_uid is null then raise exception 'not authenticated' using errcode = '28000'; end if;
  if jsonb_typeof(p_mutations) <> 'array' or jsonb_array_length(p_mutations) > 100 then
    raise exception 'invalid batch' using errcode = '22023'; end if;
  insert into turno.user_state(user_id) values (v_uid) on conflict do nothing;
  perform 1 from turno.user_state where user_id = v_uid for update;   -- orden de seq = orden de commit
  for v_m in select value from jsonb_array_elements(p_mutations) loop
    select result into v_prev from turno.applied_mutations
     where user_id = v_uid and mutation_id = (v_m->>'mutation_id')::uuid;
    if found then v_results := v_results || jsonb_build_array(v_prev); continue; end if;
    perform turno.validate_mutation(v_m);
    v_cur := null;
    select * into v_cur from turno.records
     where user_id = v_uid and entity = v_m->>'entity' and record_key = v_m->>'key' for update;
    if v_cur.version is distinct from (v_m->>'base_version')::bigint then
      v_res := jsonb_build_object('mutation_id', v_m->'mutation_id', 'status', 'CONFLICT',
               'remote', case when v_cur.version is null then null
                              else jsonb_build_object('version', v_cur.version, 'deleted', v_cur.deleted,
                                     'data_format', v_cur.data_format, 'payload', v_cur.payload) end);
    else
      if v_cur.version is not null then
        insert into turno.record_history(user_id, entity, record_key, version, deleted, data_format, payload)
        values (v_uid, v_cur.entity, v_cur.record_key, v_cur.version, v_cur.deleted, v_cur.data_format, v_cur.payload);
      end if;
      update turno.user_state set last_seq = last_seq + 1 where user_id = v_uid returning last_seq into v_seq;
      v_new := coalesce(v_cur.version, 0) + 1;
      insert into turno.records(user_id, entity, record_key, version, seq, deleted, data_format, payload, updated_by_device)
      values (v_uid, v_m->>'entity', v_m->>'key', v_new, v_seq, (v_m->>'op') = 'DELETE',
              (v_m->>'data_format')::int,
              case when (v_m->>'op') = 'DELETE' then null else v_m->'payload' end, p_device_id)
      on conflict (user_id, entity, record_key) do update
        set version = excluded.version, seq = excluded.seq, deleted = excluded.deleted,
            data_format = excluded.data_format, payload = excluded.payload,
            updated_by_device = excluded.updated_by_device, updated_at = now();
      v_res := jsonb_build_object('mutation_id', v_m->'mutation_id', 'status', 'APPLIED', 'version', v_new, 'seq', v_seq);
    end if;
    insert into turno.applied_mutations(user_id, mutation_id, result)
    values (v_uid, (v_m->>'mutation_id')::uuid, v_res);
    v_results := v_results || jsonb_build_array(v_res);
  end loop;
  return v_results;
end $$;

-- PROPUESTA, NO APLICADA: lectura incremental.
create or replace function public.sync_pull(p_since bigint, p_limit int default 500)
returns table(entity text, record_key text, version bigint, seq bigint, deleted boolean, data_format int, payload jsonb)
language sql stable security invoker set search_path = '' as $$
  select r.entity, r.record_key, r.version, r.seq, r.deleted, r.data_format, r.payload
    from turno.records r
   where r.user_id = (select auth.uid()) and r.seq > p_since
   order by r.seq
   limit least(greatest(p_limit, 1), 1000);
$$;

revoke execute on function public.sync_push(uuid, jsonb), public.sync_pull(bigint, int) from public, anon;
grant  execute on function public.sync_push(uuid, jsonb), public.sync_pull(bigint, int) to authenticated;
```

Límites de transacción y comportamiento:
- **Atomicidad del lote:** cada llamada a `sync_push` es una transacción. El lote se aplica entero o no se aplica nada. Los `CONFLICT` no abortan el lote: se devuelven por mutación.
- **Por qué no se usa `updated_at` como cursor:** con transacciones concurrentes de un mismo usuario, una marca de tiempo puede confirmarse "en el pasado" y la bajada se la saltaría. El bloqueo por usuario hace que el orden de `seq` coincida con el orden de confirmación.
- **Creación sobre una lápida:** si un dispositivo nunca vio el registro (`base_version` nulo) y existe una lápida, se devuelve `CONFLICT`. El cliente lo resuelve automáticamente solo si el contenido coincide.
- **Mantenimiento:** una tarea `pg_cron` purga `record_history` (más de 90 días) y `applied_mutations` (más de 30 días). La configuración corresponde al propietario.

---

## 7. Modelo de seguridad (RLS) y aislamiento

```sql
-- PROPUESTA, NO APLICADA
alter table turno.records           enable row level security;  alter table turno.records           force row level security;
alter table turno.record_history    enable row level security;  alter table turno.record_history    force row level security;
alter table turno.applied_mutations enable row level security;  alter table turno.applied_mutations force row level security;
alter table turno.user_state        enable row level security;  alter table turno.user_state        force row level security;

create policy own_select on turno.records for select to authenticated using (user_id = (select auth.uid()));
create policy own_insert on turno.records for insert to authenticated with check (user_id = (select auth.uid()));
create policy own_update on turno.records for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
-- Sin política DELETE: los borrados son lápidas; el borrado físico solo ocurre por CASCADE al borrar la cuenta.
-- Las mismas políticas own_* (select/insert/update según corresponda) en record_history, applied_mutations y user_state.

grant select, insert, update on turno.records, turno.user_state, turno.applied_mutations to authenticated;
grant select, insert on turno.record_history to authenticated;
```

**Defensa en profundidad:**
1. El esquema `turno` no está expuesto en PostgREST. La única entrada son las RPC de `public`.
2. Las RPC son `security invoker`, de modo que la RLS se aplica dentro de ellas.
3. `user_id` sale siempre de `auth.uid()` y nunca del `payload`.
4. La PK incluye `user_id`, así que no puede haber colisión de claves entre cuentas.
5. Ninguna clave privilegiada en la app.
6. `search_path` vacío en las funciones.

**Ataques cubiertos:**
- **Propiedad falsificada:** imposible, por el punto 3 y `with check`.
- **Escritura no autorizada:** sin JWT válido, error 28000. `anon` no tiene permiso de ejecución.
- **Fuga entre cuentas:** cubierta en servidor por la RLS y en el dispositivo por el apartado 7.1.

**Tests:** pgTAP en un Supabase local, con un usuario A que intenta leer o escribir filas de B por RPC y directamente. Requiere permiso del propietario (D8).

### 7.1 Aislamiento en el dispositivo (personas distintas)
- **Un archivo de Room por cuenta:**
  - `turnocontrol.db` se queda como está: **nunca se renombra**, por seguridad de los datos. Es la base local y será la base vinculada si el usuario la vincula;
  - cada cuenta adicional usa `turnocontrol-acct-<sha256(uid)[0..16]>.db`.
- **Registro de cuentas:** un registro local del dispositivo guarda la correspondencia uid → archivo y la base activa.
- **Consultas:** las de `profileId = 1` siguen siendo válidas, porque cada archivo contiene un solo perfil.
- **Vínculo:** se guarda **dentro** de la base (`SyncState.accountId`), de modo que viaja con los datos.
- **Antes de subir:** cada subida comprueba ese vínculo frente a la sesión.
- **Datos desvinculados:** solo se suben a una cuenta mediante el flujo explícito del apartado 9.
- **Límite:** en un teléfono compartido, los datos que quedan tras cerrar sesión son visibles para quien use el teléfono. Es una consecuencia del requisito "no borrar al cerrar sesión" y se explica en la interfaz. El bloqueo de la app con biometría queda fuera de alcance y como posible mejora.

### 7.2 Datos sensibles
- **Naturaleza:** horarios, nóminas y tarifas son datos personales y laborales. Las fechas de baja pueden ser datos de salud (B8); se sincronizan según §7.4.
- **Minimización:** no se envían imágenes, texto de OCR, diagnósticos ni el identificador del dispositivo más allá de un UUID aleatorio propio.
- **Protección:** TLS; cifrado en reposo del proveedor; región de la UE; contrato de encargo de tratamiento (DPA) de Supabase.
- **Copias del sistema:** la sesión y la base siguen excluidas de las copias de Android.
- **Opción D6 (E2EE):** cifrar el `payload` con una clave derivada de una frase de paso del usuario. Protege incluso frente al operador del backend, pero si se pierde la frase se pierden los datos de la nube, y con Google hace falta una frase aparte. **No se recomienda para la primera versión**, aunque el formato de sobre lo permite más adelante.

### 7.3 Cerrar sesión
1. **Cerrar sesión (por defecto):** los datos se quedan en el dispositivo, vinculados a A. Siguen visibles y editables, y los cambios se acumulan en la cola hasta que A vuelva a entrar. Si hay cambios sin subir, se avisa ("3 cambios se subirán cuando vuelvas a entrar").
2. **"Cerrar sesión y quitar los datos de este dispositivo":**
   - solo se habilita con la cola vacía y una última sincronización correcta;
   - antes sugiere exportar una copia JSON;
   - exige confirmación;
   - la propia app crea una copia de seguridad previa privada.

---

### 7.4 Baja médica como estado sincronizado (decisión D9 revisada, 2026-11-22)

El propietario revisó la decisión D9: **los días de baja médica se sincronizan** para que todos los dispositivos de una cuenta muestren el mismo calendario. Sustituye el diseño anterior, que guardaba la baja solo en el dispositivo y enviaba un borrado cuando un día sincronizado pasaba a baja.

1. **Qué se sincroniza.** Solo lo necesario para representar el estado del día:
   - la fecha, que también es la clave del registro;
   - el estado `SICK_LEAVE`;
   - los metadatos del protocolo: versión del registro, identificador de operación, secuencia y formato de datos.

   El contenido canónico de un día de baja es exactamente `1|AAAA-MM-DD|SICK_LEAVE|MANUAL|`:
   - no lleva intervalos, porque las reglas del día los prohíben en un día que no es de trabajo;
   - el origen **no se sincroniza** en los días de baja: se escribe siempre `MANUAL`. Un día guardado localmente con origen `OCR_REVIEWED` (las copias de seguridad lo admiten) conserva esa procedencia en el dispositivo, y el contenido enviado sigue siendo solo fecha y estado;
   - no lleva ningún otro campo.
2. **Qué está prohibido.** Diagnóstico, motivo, síntomas, informes, partes o certificados médicos, datos del médico, notas clínicas y adjuntos con información médica.
   - Ninguno de estos campos existe en el modelo sincronizado (`DayRecord` = día + origen; `PlannedDay` = fecha, tipo e intervalos).
   - La serialización es una **lista cerrada de campos**: no se serializan objetos de dominio arbitrarios.
   - Los decodificadores y el servidor rechazan los campos extra, el texto libre, las horas en un día de baja y los orígenes distintos de `MANUAL`.
   - Si un dato así llega en una descarga, se pone en cuarentena y no se aplica.
   - Esta aprobación cubre solo el estado mínimo del calendario. Cualquier otra información de salud exigiría una revisión aparte y una aprobación explícita.
3. **Por qué es un estado válido.** La baja es un tipo de día igual que trabajo, libre o vacaciones:
   - existe en `DayKind` y en `DataFormat.persistedDayKinds`;
   - cuenta en los resúmenes (`sickDays`);
   - participa en las mismas reglas de validación, las mismas versiones y el mismo mecanismo de conflictos.
4. **Por qué no se representa con un borrado.** Una lápida significa "este día ya no tiene datos", y los demás dispositivos lo mostrarían como "sin configurar". Usarla para la baja produciría un calendario distinto en cada dispositivo y confundiría un borrado real con un cambio de estado. Por eso:
   - un día de trabajo que pasa a baja es una **actualización** (`UPSERT`) con su versión base;
   - las lápidas solo se usan para borrados reales.
5. **Sensibilidad y protecciones pendientes.** Quitar el diagnóstico **no** convierte el dato en no sensible: la mera fecha de una baja puede revelar información de salud. Este cambio, por sí solo, **no** hace que la futura nube cumpla la legislación de protección de datos. Antes de la sincronización real hará falta:
   - una base jurídica y un consentimiento explícito e informado al vincular la cuenta, que mencione las bajas;
   - una evaluación de impacto (EIPD) si procede;
   - un contrato de encargo con Supabase en una región de la UE;
   - TLS y RLS probados con pgTAP (fase 6);
   - revisar cifrado de extremo a extremo para este tipo de día (D6);
   - actualizar la política de privacidad y el formulario Data safety de Google Play;
   - que ningún registro, mensaje de error ni diagnóstico técnico incluya contenido de días;
   - borrado de cuenta completo (fase 11).
6. **Efecto en la primera vinculación y en los conflictos:**
   - En la revisión previa a vincular, los días de baja se cuentan y se muestran igual que los demás tipos. Así el usuario ve que van a subir antes de confirmar.
   - Al combinar datos, una baja frente a otro estado del mismo día (trabajo, libre, vacaciones o un borrado real) es un **conflicto explícito**. Dos bajas idénticas se resuelven solas.
   - Un rango de bajas solo genera conflicto en los días editados también en otro dispositivo.
   - La versión que pierde se conserva 90 días, como en cualquier otro conflicto.

## 8. Estrategia de migración de Room

**Decisión aplicada (D3, fase 3, 2026-11-22):** migraciones separadas. El esquema **5 contiene solo las tablas locales de sincronización** (`SyncState`, `SyncRecordState`, `SyncOutbox`, `SyncConflict`, `SyncQuarantine`, `SyncRecovered`), sin claves foráneas hacia los datos del usuario y vacías hasta vincular una cuenta. La migración de las funciones nuevas (`WorkEntry`, `WorkBreak`, `EntryCorrection`, `PayslipRecord`, `PayRule` con vigencias) pasa a ser **5 → 6**.

El plan original proponía:

Contenido adicional respecto al plan 4→5:
1. Tablas laterales `SyncState`, `SyncRecordState`, `SyncOutbox`, `SyncConflict` y `SyncQuarantine`:
   - son puramente aditivas y quedan vacías hasta que se vincule una cuenta;
   - no tienen FK hacia el dominio, de modo que la restauración, que borra y reinserta, no las arrastra.
2. `WorkEntry.uuid TEXT NOT NULL UNIQUE`, además del id autogenerado.
3. `EntryCorrection.entryUuid TEXT NOT NULL`: la referencia sobrevive al borrado y no depende de ids locales.
4. `PayRule`: índice único `(profileId, type, validFrom)`, que es la clave de sincronización. Se conserva lo ya planificado: ids autogenerados y comprobación de solapes.
5. `PayslipRecord`: índice único `(profileId, yearMonth)`, ya previsto.
6. **Turno abierto (`endUtc IS NULL`):**
   - se mantiene el índice parcial;
   - la ruta de bajada **comprueba antes de insertar** y convierte el choque en `SyncConflict` (B6).
7. `DataFormat.CURRENT = 3` y `application_id` con formato 3.
8. `ScheduledDay` e `ScheduledInterval` **no cambian**: la clave es la fecha y los intervalos van dentro del agregado.

Requisitos que se mantienen o se añaden:
- **Sin migraciones destructivas.** `PRAGMA foreign_key_check` después de migrar.
- **Bajada de versión:** una 0.2.x abre la base v5 → la compuerta la bloquea sin escribir (ya está probado). 0.1.0 sigue sin protección (documentado).
- **Bases antiguas:** v1, v2 y v3 pasan por la cadena existente hasta v4 y después a v5. Hay que probar cada punto de partida.
- **Tests con fixtures sintéticas** de cada esquema:
  - perfil completo con las 6 tarifas;
  - vacaciones, bajas, nocturnos y extras;
  - base vacía;
  - copia anonimizada **solo de la forma** del esquema real, sin datos.
- **Copia de seguridad (corrige B3):**
  - `BackupCodec.decode` debe aceptar `formatVersion 1` / `schemaVersion 4` y convertirlo a v5, con las tablas nuevas vacías;
  - debe rechazar los esquemas futuros, como ya hace.

---

## 9. Vinculación de datos locales y resolución de conflictos

### 9.1 Puntos de entrada
- Primer arranque, con tres opciones: "Continuar con Google", "Correo y contraseña" y "Continuar sin cuenta" (la predeterminada, sin red).
- Ajustes → Cuenta, en cualquier momento. La cuenta nunca es obligatoria.

### 9.2 Flujo de primera vinculación
1. **Revisión local.** Se muestra lo que hay en el dispositivo: número de días, rango de fechas, turnos reales, tarifas y perfil.
2. **Protección previa:**
   - botón "Exportar copia de seguridad" (recomendado y destacado);
   - una copia privada automática `pre-link.json`, con el mismo mecanismo que `pre-restore.json`.
3. **Autenticación.**
4. **Lectura de la nube.** Se descarga toda la cuenta **sin escribir nada en local**. `LinkMergePlanner` (dominio puro) clasifica cada clave:
   - solo local;
   - solo en la nube;
   - idéntica;
   - distinta (conflicto);
   - inválida tras combinar (solapes);
   - desconocida (versión más nueva).
5. **Opciones, según el caso:**

   | Situación | Opciones |
   |---|---|
   | Nube vacía | "Subir mis datos a esta cuenta" (predeterminada) · Cancelar |
   | Nada en local, datos en la nube | "Descargar los datos de la cuenta" · Cancelar |
   | Datos en ambos lados | **"Combinar"** (predeterminada): lo idéntico se une, lo exclusivo de cada lado se añade y los conflictos se revisan uno a uno o con "usar el dispositivo / la cuenta para todos" · "Usar solo los datos de la cuenta" · "Usar solo los datos de este dispositivo" · Cancelar |

   - Las dos opciones "solo" son las únicas que descartan datos. Exigen reautenticación, mostrar las cantidades afectadas ("se descartarán 143 días de este dispositivo") y una confirmación escrita.
   - Lo descartado en local queda en `pre-link.json`. Lo descartado en la nube queda en `record_history` durante 90 días.
6. **Aplicación:**
   - una transacción de Room que vincula la cuenta (`SyncState`), aplica los valores remotos elegidos y encola los locales elegidos con el `base_version` de la instantánea;
   - después, una subida normal;
   - si otro dispositivo escribe mientras se revisa, se produce un `CONFLICT` normal (apartado 5.6) y nunca una sobrescritura.
7. **Cancelar** en cualquier punto no cambia nada en local. La sesión puede quedar abierta sin vincular, sin sincronizar.

### 9.3 Duplicados
- **Días, tarifas y nóminas:** no puede haber duplicados, gracias a la clave natural.
- **Turnos reales:** si después de combinar se solapan, se marcan como "posible duplicado", con la opción "Unir" (conservar uno y registrar la corrección) o "Mantener ambos" si de verdad no se solapan.

### 9.4 Conflictos de invariante
Los aplica el cliente al combinar, al bajar datos y al restaurar:
- `PlannedScheduleValidator` sobre el conjunto final;
- solapes de vigencias de `PayRule`;
- un solo turno abierto;
- solapes de `WorkEntry`.

Si una fila remota rompe alguna de estas reglas, no se aplica y se crea un conflicto con los dos registros implicados.

---

## 10. Comportamiento sin conexión
- **Uso:** la app funciona completa sin conexión, con o sin cuenta.
- **Estado visible en Ajustes → Cuenta, y como icono discreto en el Resumen:**
  - "Sincronizado hace X";
  - "N cambios pendientes";
  - "Sin conexión";
  - "Vuelve a iniciar sesión";
  - "N conflictos";
  - "Actualiza la app para ver algunos datos".
- **Disparadores de la sincronización:**
  - escritura local (con 5 s de espera para agrupar);
  - arranque;
  - "Sincronizar ahora";
  - opcionalmente, un periodo de 12 h.
- **Sin servicio en primer plano ni alarmas exactas.**
- **Permisos nuevos:** `INTERNET`, y `ACCESS_NETWORK_STATE` si se usa la restricción de red de WorkManager. Ambos dependen de D1.

---

## 11. Compatibilidad con la copia de seguridad y la restauración
| Aspecto | Diseño |
|---|---|
| Formato 2 | Incluye las tablas nuevas y los UUID de `WorkEntry`. **Excluye** las tablas `Sync*` y la sesión, de modo que la copia sigue siendo portable entre cuentas y dispositivos |
| Formato 1 en v5 | Se acepta y se convierte (B3) |
| Verificación de la restauración | No cambia, porque `BackupSnapshot` no contiene metadatos de sincronización |
| Restaurar con una cuenta vinculada | Equivale a una edición masiva más un borrado masivo. Se muestra el aviso "Esto cambiará N días en **todos** tus dispositivos" y la opción "Restaurar y desvincular". Tras confirmar, se calcula la diferencia con el estado actual y se generan las mutaciones (incluidos los `DELETE`) en la misma transacción |
| La nube como copia | **No.** La copia local sigue siendo la copia de referencia. El plan Free no tiene copias automáticas, y la sincronización propaga los borrados |
| Recuperación tras una interrupción | `recoverInterrupted` no cambia. Las mutaciones generadas por una restauración solo se confirman si la transacción de Room se confirma |

---

## 12. Estrategia de pruebas
Todo con datos sintéticos. Los tests instrumentados se ejecutan solo sobre `com.turnocontrol.verification`.

**Infraestructura:**
- `SyncGateway`, una interfaz con:
  - `FakeSyncServer`: una implementación en memoria con la **misma** semántica de comparar-y-escribir, `seq` e idempotencia que el SQL;
  - inyección de fallos: respuesta perdida *después* de aplicar, error 401, tiempo agotado, corte entre páginas.
- Dos o tres bases de Room en memoria (Robolectric) que simulan dispositivos.
- Pruebas de convergencia con secuencias aleatorias de operaciones y semilla fija: tras sincronizar, todos los dispositivos sin conflictos pendientes tienen el mismo estado y los totales coinciden.

| Área | Tests |
|---|---|
| Autenticación (unitarios con `AuthGateway` falso) | Inicio con Google (el token llega con su nonce); correo y contraseña correctos e incorrectos; correo sin verificar; sesión caducada → `AUTH_REQUIRED` sin pérdida de cola; arranque sin conexión con sesión guardada; cambio de cuenta (abre otro archivo, nunca sube A a B); cerrar sesión conserva los datos; "quitar datos" bloqueado con cola pendiente; la sesión cifrada no se lee sin la clave de Keystore |
| Autenticación (instrumentados o manuales en `.verification` contra `turnocontrol-dev`) | Credential Manager real, enlace de verificación y de recuperación, borrado de cuenta |
| Sincronización | Primera sincronización; vinculación con la nube vacía o con datos; combinación con conflicto; cambios en dos dispositivos con registros distintos o el mismo; mismo contenido → resolución automática; altas simultáneas del mismo día; turnos duplicados; borrado contra edición; lápidas; corte a mitad de página; respuesta perdida → reenvío idempotente; reintentos con backoff; reinicio del proceso con `IN_FLIGHT`; un mes sin conexión; `RESYNC_REQUIRED`; cuarentena por `data_format` más nuevo; solape nocturno entre días combinados; dos turnos abiertos |
| Integridad de datos | Los días, intervalos, extras, vacaciones y bajas se conservan tras ida y vuelta (codec del `payload` ↔ entidades, hash estable); totales mensuales y anuales idénticos antes y después de sincronizar; tarifas y configuración preservadas; tipos desconocidos preservados |
| Copia de seguridad | Formato 1 → v5; formato 2 en ambos sentidos; restaurar con cuenta vinculada genera las mutaciones correctas; la copia no contiene `Sync*` |
| Migración | `MigrationTestHelper` desde v1, v2, v3 y v4 con fixtures; `foreign_key_check`; `application_id = 3`; bajada de versión bloqueada |
| Servidor (pgTAP, Supabase local; requiere D8) | RLS: A no lee ni escribe lo de B, ni por RPC ni directamente; `anon` sin acceso; `user_id` falsificado en el `payload` ignorado; `base_version` incorrecta → CONFLICT; `mutation_id` repetido → mismo resultado; `seq` monotónico con dos sesiones concurrentes; validación por entidad |
| Privacidad y red | Test del manifiesto fusionado (sin componentes de telemetría; solo los permisos aprobados); prueba de tráfico: sin cuenta no hay **ninguna** conexión, y con cuenta solo al host de Supabase |

---

## 13. Hoja de ruta integrada
"Aut." indica si la fase requiere autorización explícita del propietario. Ninguna fase genera APK, publica ni despliega.

| # | Fase | Objetivo y cambios | Depende de | Riesgos | Tests y criterio de aceptación | Vuelta atrás | Aut. |
|---|---|---|---|---|---|---|---|
| 0 | Decisiones | Resolver D1-D9 | — | — | Decisiones registradas en `docs/` | — | **Sí** |
| 1 | `OcrImportViewModel` (fase 1 del plan) | Sacar las propuestas de importación de `CalendarViewModel` sin cambio funcional | — | Bajo | Las suites actuales en verde; tests de recuperación movidos | `git revert` | Sí (cambio de código) |
| 2 | Motor de sincronización en `domain` | Codecs canónicos de agregados con hash, `LinkMergePlanner`, detector de conflictos e invariantes, `FakeSyncServer`, pruebas de convergencia. **Sin red, sin Room y sin dependencias** | — | Bajo | Todos los casos de §5.5 con el servidor falso | Borrar el paquete | Sí |
| 3 | Room v5 + copia de seguridad en formato 2 | Plan 4→5 más lo indicado en §8; decode del formato 1 → v5 | 1, D3 | **Alto** (datos reales) | Migraciones desde v1-v4 con fixtures, copias de ida y vuelta, compuerta | No publicar; la migración es solo hacia delante, de ahí la copia obligatoria antes de actualizar | **Sí** |
| 4 | Captura en la cola | Repositorios que, si la base está vinculada, escriben en `SyncOutbox` dentro de su transacción y por diferencias de hash; ruta interna de aplicación remota; comparación de `ScheduleRepository.save` limitada a las fechas afectadas ±1 (§5.4) | 2, 3 | Medio | Una mutación por agregado cambiado; ninguna si nada cambia o si no hay vínculo; una bajada en otra fecha no rechaza una edición en curso | Bandera que lo desactiva | Sí |
| 5 | Tarifas con vigencia y fichaje manual (fases 3-4 del plan) | Según el plan existente, con claves de sincronización | 3 | Medio | Según el plan, más el codec del agregado | `git revert` | Sí |
| 6 | Backend como código | `supabase/migrations/*.sql`, RPC, RLS y pgTAP en el repositorio; Supabase local con la CLI y Docker | 2 | Medio | pgTAP en verde (§12) | Borrar los archivos | **Sí** (Docker y stack local) |
| 7 | Capa de red | Permiso `INTERNET` (D1), supabase-kt, Ktor, serialización, `SyncGateway` real, cierre de la telemetría. Todo detrás de `BuildConfig.SYNC_ENABLED = false` | 4, 6, D1, D2 | Alto (privacidad) | Test del manifiesto; pruebas de tráfico; los tests del servidor falso pasan también contra el gateway real en local | Bandera desactivada o revertir el permiso | **Sí** |
| 8 | Autenticación | Ajustes → Cuenta; correo con PKCE y enlaces; Google con Credential Manager; sesión con Keystore; cerrar sesión; reautenticación | 7; el propietario crea `turnocontrol-dev` y los clientes OAuth | Alto | Tests de autenticación (§12); pruebas manuales en `.verification` con cuentas sintéticas | Bandera | **Sí** |
| 9 | Vinculación y bases por cuenta | Flujo de §9; registro de cuentas; reinicio controlado | 4, 8 | **Alto** | Todos los casos de vinculación; nunca A → B | Bandera; `pre-link.json` | **Sí** |
| 10 | Sincronización en segundo plano y conflictos | WorkManager, estado visible, pantalla de conflictos (reutilizando el modelo de importación), cuarentena | 9 | Medio | Convergencia y fallos inyectados sobre Room real | Bandera | Sí |
| 11 | Borrado de cuenta y cumplimiento | Edge Function, página web, política de privacidad, Data safety, consentimiento para datos de baja | 8 | Medio (legal) | Borrado completo verificado en `dev` | — | **Sí** (despliegue) |
| 12 | Resto del plan 0.3.0 (fases 5-11) | Planificado frente a real, CSV, fichaje en vivo, nóminas, PDF, accesibilidad, rendimiento; cada entidad nueva con su codec de agregado | 5, 10 | Según el plan | Según el plan, más la sincronización de cada entidad | Según el plan | Sí |
| 13 | Validación final | Pruebas de extremo a extremo con 2 dispositivos y cuentas sintéticas en `dev`; la preparación del APK queda fuera y requiere una autorización aparte | Todas | — | Lista de publicación actualizada | — | **Sí** |

Las fases 5 y 12 (funcionalidades) pueden avanzar en paralelo con las fases 6-11 (cuentas) una vez terminada la fase 3.

---

## 14. Decisiones del propietario y cambios en el plan existente

### 14.1 Decisiones
| # | Decisión | Recomendación |
|---|---|---|
| D1 | ¿Se acepta añadir `INTERNET` (y `ACCESS_NETWORK_STATE`), cambiando la promesa de privacidad? ¿Una sola variante o dos *flavors* (`offline` y `sync`)? | Una variante con red, pero **sin ninguna conexión sin cuenta**, con la telemetría bloqueada y probada. Los *flavors* solo si se quiere mantener una edición "sin Internet" garantizada |
| D2 | Bibliotecas nuevas: supabase-kt, Ktor, kotlinx-serialization, Credential Manager con googleid, WorkManager | Aceptarlas con versiones fijadas |
| D3 | ¿La sincronización entra en la v5 única o en una v6 posterior? | En la v5 única: tablas aditivas vacías y una sola actualización |
| D4 | Plan de Supabase y región | Free para `dev`; Pro para `prod` si hay usuarios reales; región de la UE |
| D5 | ¿Un archivo por cuenta o varios perfiles en una sola base? | Un archivo por cuenta |
| D6 | ¿Cifrado de extremo a extremo? | No en la primera versión; formato preparado para añadirlo |
| D7 | ¿Se sincroniza el perfil (nombre y alias)? ¿Qué ajustes de DataStore? | Perfil sí; `dark_theme` no |
| D8 | ¿Se autoriza Docker y la CLI de Supabase en local para los tests de RLS? | Sí, en la fase 6 |
| D9 | ¿Tratamiento de las fechas de baja como datos de salud? | **Revisada y aprobada (2026-11-22):** la baja se sincroniza como estado mínimo del día (fecha + `SICK_LEAVE`), sin ningún dato médico; las protecciones legales y técnicas previas a la nube real están en §7.4 |

### 14.2 Cambios que necesitaría `docs/ARQUITECTURA_0.3.0.md` (no editado)
1. Principios: sustituir "sin servicios en la nube" por "nube opcional, solo con cuenta".
2. §3:
   - `WorkEntry.uuid`;
   - `EntryCorrection.entryUuid`;
   - clave única de `PayRule` `(profileId, type, validFrom)` como clave de sincronización;
   - nota sobre el índice parcial y los conflictos al bajar datos;
   - las tablas `Sync*`.
3. §4: la copia de formato 1 debe poder restaurarse en v5. Hoy el código la rechaza, así que hay que añadir el test.
4. §9: los permisos nuevos dependen de D1.
5. §11: la regresión real cuenta 178 tests unitarios y 31 instrumentados (anotaciones).
6. §12: insertar las fases de cuentas (§13 de este documento).
7. §13: lista de dependencias nuevas.
8. §14: riesgos B1-B13.

También habría que actualizar el README (§Privacidad) y `LISTA_PUBLICACION.md` (punto "sin permisos de Internet") si se aprueba D1.

---

## 15. Recomendación para la primera fase de implementación

**Fases 1 y 2 juntas, con las decisiones D1-D9 resueltas en paralelo:**

1. **Fase 1:** extraer `OcrImportViewModel`. Ya estaba planificada, reduce el riesgo en `CalendarViewModel` y el modelo de propuestas que se libera se reutilizará para la revisión de conflictos.
2. **Fase 2:** el motor de sincronización en `domain`, en Kotlin puro:
   - codecs canónicos de agregados (`scheduled_day`, `profile`, `contract`, `pay_rule`);
   - `LinkMergePlanner`;
   - el detector de conflictos e invariantes;
   - `FakeSyncServer` con la semántica exacta del SQL propuesto;
   - las pruebas de convergencia.

Por qué estas fases primero:
- no añaden permisos, red ni dependencias;
- no cambian el esquema ni tocan datos reales;
- se revierten por completo;
- validan con tests lo más arriesgado del diseño (conflictos, invariantes entre días e idempotencia) **antes** de tomar decisiones irreversibles como la migración v5 o el permiso de Internet.

**Me detengo aquí a la espera de aprobación explícita.** No se ha modificado código ni creado ningún recurso externo, y no se ha confirmado nada en git.

---

### Fuentes externas consultadas (2026-11-22)
- Supabase, pausa de proyectos Free: https://supabase.com/docs/guides/platform/free-project-pausing
- Supabase, precios: https://supabase.com/pricing
- Supabase, inicio de sesión con Google: https://supabase.com/docs/guides/auth/social-login/auth-google
- Supabase, flujo PKCE: https://supabase.com/docs/guides/auth/sessions/pkce-flow
- Supabase Kotlin, inicialización y `SessionManager`: https://supabase.com/docs/reference/kotlin/initializing · https://supabase-community.github.io/supabase-kt/auth-kt/io.github.jan.supabase.auth/index.html
- Versiones de supabase-kt: https://github.com/supabase-community/supabase-kt/releases
- Android, Sign in with Google con Credential Manager: https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation
- `androidx.security:security-crypto` obsoleto: https://developer.android.com/reference/androidx/security/crypto/package-summary
- Google Play, requisitos de borrado de cuenta: https://support.google.com/googleplay/android-developer/answer/13327111

**Sin verificar** (comprobar al implementar):
- la versión exacta y estable de supabase-kt y su API de Google nativo (Compose Auth frente a Credential Manager directo);
- los límites actuales del correo integrado de Supabase;
- la disponibilidad de la protección contra contraseñas filtradas según el plan;
- el coste exacto de cómputo de Pro.

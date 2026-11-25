# TurnoControl 0.3.0: arquitectura de autenticación, aislamiento por cuenta y privacidad de red (fase 4A)

Estado: **diseño, sin implementar**. Fecha: 2026-11-22.
Rama verificada: `feature/0.2.0-redesign`, HEAD `c737983`, árbol limpio.

Alcance:
- No se ha modificado código, Gradle, el manifiesto ni los esquemas.
- No se ha creado ningún proyecto externo ni se ha hecho ninguna petición de red de autenticación.

Cada afirmación lleva una de estas marcas:
- **[V]**: verificado en el repositorio o en documentación primaria (ver §16);
- **[P]**: decisión de diseño propuesta;
- **[NV]**: sin verificar.

---

## 1. Estado del repositorio (verificado)

| Aspecto | Hallazgo | Marca |
|---|---|---|
| Línea base | 279 tests JVM en verde (ejecutados con `--rerun` el 2026-11-22); esquema Room 5; lint con 0 errores en la fase 3.1 | [V] |
| Arranque | `MainActivity` crea 7 ViewModels con `by viewModels()` (ámbito de Activity). `StartupViewModel` actúa como compuerta (`Checking` / `Blocked` / `Recovery` / `Ready`) antes de crear los ViewModels del calendario | [V] |
| Navegación | `NavHost` con las pestañas Calendario y Resumen, y rutas secundarias Detalle, Ajustes, Importar, Revisar y Confirmar. Ajustes se abre desde el icono de engranaje | [V] |
| Base de datos | `StorageModule` fija `Room.databaseBuilder(..., "turnocontrol.db")` como `@Singleton` en `SingletonComponent`, con `ALL_MIGRATIONS` | [V] |
| DataStore | Un único `preferencesDataStore("preferences")` global con la clave `dark_theme` | [V] |
| Copias | `BackupRepository` usa un directorio global `filesDir/backup-recovery` (diario, copia previa a restaurar, destino) | [V] |
| Compatibilidad | `CompatibilityChecker` revisa la base única al arrancar (`application_id`, esquema, tipos de día) | [V] |
| Sincronización | `SyncState` (una fila por base) guarda `deviceId` (UUID), `accountId`, cursor y contador. `SyncStore.link()` no tiene llamador en producción. `AccountDatabases.fileName()` existe pero no está conectado | [V] |
| Errores | Los mensajes al usuario son en español. Los tipos explícitos (`ConfigurationValidationException`, `SyncConflictPendingException`) llevan mensaje mostrable; los errores de almacenamiento usan un mensaje genérico | [V] |
| Tests | Robolectric con bases sintéticas; las instrumentadas se ejecutan solo sobre `com.turnocontrol.verification` | [V] |
| Permisos | El manifiesto **elimina** `INTERNET` y `ACCESS_NETWORK_STATE` (`tools:node="remove"`) | [V] |
| Google y autenticación | No hay ninguna biblioteca de Credential Manager, Google Identity ni Supabase | [V] |
| ML Kit | `com.google.mlkit:text-recognition:16.0.1`, el modelo latino **incluido en la app**. Ver el desglose de dependencias en §10 | [V] |

### 1.1 Suposiciones que dejan de valer con varias cuentas
1. **Una base por proceso.** `TurnoDatabase` es un singleton de Hilt y todos los repositorios (`@Singleton`) guardan su referencia. Cambiar de base en caliente dejaría referencias obsoletas.
2. **ViewModels de la Activity.** Conservan borradores, instantáneas y la cola de importación de la base anterior.
3. **Recuperación de copias.** `backup-recovery` es global: un diario de restauración interrumpida de la cuenta A podría evaluarse contra la base de B. **Hay que separarlo por base.**
4. **Compuerta de arranque.** Solo se ejecuta una vez y sobre una base. Debe ejecutarse sobre la base que se activa en cada cambio.
5. **DataStore global.** Sirve para preferencias del dispositivo (tema). Cualquier preferencia de cuenta futura necesita un almacén por cuenta.
6. **Perfil `id = 1`.** Sigue siendo válido porque cada archivo tiene un solo perfil (D5).

---

## 2. Arquitectura de autenticación [P]

### 2.1 Modos

| Modo | Base activa | Red | Sincronización |
|---|---|---|---|
| **Invitado** (por defecto) | `turnocontrol.db` sin vincular, o una base de invitado elegida por el usuario | Ninguna petición propia de la app (ver §10 sobre ML Kit) | Desactivada |
| **Cuenta Google** | La base vinculada a esa cuenta | Supabase Auth y PostgREST/RPC; Google Identity a través de Google Play services | Manual o automática (fases posteriores) |
| **Cuenta con correo** | La base vinculada a esa cuenta | Supabase Auth y RPC | Igual |
| **Sesión caducada** | La base de la cuenta, editable sin conexión | Solo renovar la sesión o iniciar sesión de nuevo | En pausa; la cola se conserva |

### 2.2 Componentes (dominio, sin dependencias de red)
```kotlin
sealed interface AuthState {
    data object Guest : AuthState
    data class SignedIn(val accountId: String, val provider: Provider, val maskedEmail: String) : AuthState
    data class SessionExpired(val accountId: String) : AuthState   // Datos accesibles; la sincronización espera.
}
enum class Provider { GOOGLE, EMAIL }

sealed interface AuthResult {
    data class Success(val session: AuthSession) : AuthResult
    data object Cancelled : AuthResult
    data object InvalidCredentials : AuthResult
    data object EmailNotConfirmed : AuthResult
    data object Network : AuthResult
    data object RateLimited : AuthResult
    data object ReauthenticationRequired : AuthResult
    data class Failed(val code: String) : AuthResult              // Código interno, nunca el token ni el cuerpo HTTP.
}

interface AuthGateway {                                           // Adaptador de Supabase Auth; uno falso en los tests.
    suspend fun signInWithGoogleIdToken(idToken: String, rawNonce: String): AuthResult
    suspend fun signUpWithEmail(email: String, password: CharArray): AuthResult
    suspend fun signInWithEmail(email: String, password: CharArray): AuthResult
    suspend fun sendPasswordReset(email: String): AuthResult
    suspend fun completeDeepLink(uri: String): AuthResult         // PKCE: verificación de correo y recuperación de contraseña.
    suspend fun updatePassword(newPassword: CharArray): AuthResult
    suspend fun refresh(accountId: String): AuthResult
    suspend fun signOut(accountId: String)
    suspend fun deleteAccount(accountId: String): AuthResult      // Edge Function; requiere reautenticación reciente.
}
interface GoogleCredentialSource { suspend fun requestIdToken(hashedNonce: String): GoogleCredential } // Credential Manager.
interface SessionVault { fun load(accountId: String): AuthSession?; fun save(s: AuthSession); fun clear(accountId: String) }
```
- `accountId` es el `sub` (UUID) del usuario de Supabase **devuelto por el servidor**, nunca un valor que escriba el usuario.
- Las contraseñas viajan como `CharArray`, se envían una vez al SDK, se sobrescriben con ceros y nunca se guardan.

---

## 3. Inicio de sesión con Google [P]

### 3.1 Flujo
Credential Manager con Google ID token, intercambiado con Supabase.

1. La app genera un `rawNonce` aleatorio (≥ 32 bytes de un generador criptográfico) y calcula `hashedNonce = SHA-256(rawNonce)` en hexadecimal.
2. Credential Manager se llama con:
   - `GetGoogleIdOption` (hoja inferior; primero `filterByAuthorizedAccounts = true` y, si no hay cuentas, `false`), o
   - `GetSignInWithGoogleOption` (botón «Continuar con Google»).

   En ambos casos con `setServerClientId(<ID de cliente WEB>)` y `setNonce(hashedNonce)`. **[V]** Ambas opciones y `setNonce` están documentados.
3. Credential Manager devuelve un `GoogleIdTokenCredential` con el ID token.
4. La app llama a `auth.signInWith(IDToken) { idToken; provider = Google; nonce = rawNonce }` de supabase-kt. **[V]** Supabase admite el inicio con ID token de Google y nonce.
5. **Supabase Auth valida el token en el servidor**: firma con las claves públicas de Google, `iss` (accounts.google.com), `aud` igual al ID de cliente configurado en el proveedor Google, `exp`, y que el hash del `rawNonce` coincida con el claim `nonce`.
   - La app **no** valida el token para conceder acceso ni lo envía a ningún otro sitio.
   - El ID token de Google se descarta tras el intercambio.
6. Supabase devuelve su propia sesión (access token JWT y refresh token), que se guarda según §9.

### 3.2 Decisiones y casos límite
- **Sin `compose-auth`.** Se recomienda llamar directamente a Credential Manager en lugar del módulo `compose-auth` de supabase-kt. Hay menos dependencias, control total del nonce y del manejo de errores, y se puede probar con un `GoogleCredentialSource` falso. **[P]** `compose-auth` es una alternativa válida.
- **Cancelación** (`GetCredentialCancellationException`): da `AuthResult.Cancelled`. Sin mensaje de error y sin cambios.
- **Sin credenciales** (`NoCredentialException`): se ofrece el botón «Continuar con Google» (`GetSignInWithGoogleOption`) o el correo.
- **Fallo de red**: da `Network`, con un reintento manual. La app sigue en el modo en que estaba.
- **Cambio de cuenta Google**: el usuario elige otra cuenta en Credential Manager, y se aplica §7.4.
- **Al cerrar sesión**: `CredentialManager.clearCredentialState()` para que no se vuelva a elegir la cuenta automáticamente. **[NV]** Recomendado en la guía, pero comprobar el comportamiento exacto.
- **Configuración del propietario (más adelante):**
  - ID de cliente **Web** (sirve como `serverClientId` y para el proveedor Google de Supabase, con su secreto);
  - clientes **Android** para `com.turnocontrol` (SHA-1 de release) y `com.turnocontrol.verification` (SHA-1 de depuración);
  - pantalla de consentimiento OAuth con los ámbitos `openid`, `email` y `profile`.

---

## 4. Correo y contraseña [P]

Todo lo hace Supabase Auth. No hay criptografía de contraseñas propia.

| Flujo | Diseño |
|---|---|
| Registro | `signUpWith(Email)` con la confirmación de correo **obligatoria**. Hasta confirmarla no hay sesión ni vinculación. La contraseña tiene un mínimo de 10 caracteres según la configuración del proyecto; se recomienda activar la protección contra contraseñas filtradas si el plan la incluye **[NV]** |
| Verificación | Flujo PKCE. El enlace del correo abre la app (preferible un **App Link https verificado**; alternativa `turnocontrol://auth/callback`) y se llama a `completeDeepLink`. Con PKCE, interceptar el enlace no basta sin el verificador guardado en el dispositivo |
| Inicio de sesión | `signInWith(Email)`. Los errores se traducen a `InvalidCredentials`, `EmailNotConfirmed`, `RateLimited` o `Network` con mensajes genéricos que no revelan si el correo existe |
| Recuperación | `resetPasswordForEmail(email, redirectTo)`, enlace PKCE, sesión de recuperación y luego `updatePassword`. La pantalla dice «si existe una cuenta, recibirás un correo» |
| Cambio de contraseña | Requiere una sesión reciente; si no, `ReauthenticationRequired` y se pide la contraseña actual o un nuevo inicio de sesión. En Supabase debe activarse el «cambio de contraseña seguro» **[NV]** |
| Recuperar la sesión | Al arrancar se lee la sesión del almacén cifrado. Sin conexión, la app funciona y la sincronización espera |
| Sesión caducada | Si el refresh token se rechaza (revocado o caducado), se pasa a `SessionExpired(accountId)`: los datos siguen accesibles, la cola se conserva y aparece el aviso «Vuelve a iniciar sesión» |
| Borrado de cuenta | Ajustes → Cuenta → Eliminar cuenta: reautenticación, explicación, confirmación escrita y Edge Function `delete-account`. Localmente se ofrece conservar los datos como invitado o borrarlos (§7.5). También hace falta un enlace web, que exige Google Play |

---

## 5. Modo invitado [P]
- Es el predeterminado. En el primer arranque tras actualizar, la app sigue igual: no se pide crear cuenta.
- En Ajustes aparece una tarjeta «Cuenta: sin cuenta (datos solo en este dispositivo)» con los botones para iniciar sesión.
- Sin ningún cliente HTTP inicializado. El `SupabaseClient` se crea de forma diferida, solo al iniciar un flujo de cuenta.
- Las pantallas y funciones actuales no cambian.

---

## 6. Evaluación de dependencias (no instaladas)

| Biblioteca | Versión propuesta | minSdk | Kotlin | Permisos | Seguridad | Limitaciones | Fuente |
|---|---|---|---|---|---|---|---|
| `io.github.jan-tennert.supabase:bom` (+ `auth-kt`, `postgrest-kt`) | **3.9.0**, según klibs.io (2026-11-03) **[NV en Maven Central]** | **[NV]** | **[NV]** compatibilidad con Kotlin 2.1.20 del proyecto | `INTERNET` | Cliente de la comunidad (supabase-community), MIT. Fijar la versión y revisar el changelog | `SettingsSessionManager` guarda la sesión en SharedPreferences **sin cifrar** **[V]**: hay que sustituirlo (§9) | klibs.io, mvnrepository, docs de supabase-kt |
| Motor Ktor (`ktor-client-okhttp` o `ktor-client-android`) | El que fije el BOM de supabase-kt **[NV]** | **[NV]** | **[NV]** | `INTERNET` | OkHttp facilita TLS moderno y una posible fijación de certificados | Más tamaño con OkHttp | docs de Ktor |
| Plugin y `kotlinx-serialization-json` | Plugin igual a la versión de Kotlin (2.1.20); runtime compatible **[NV]** | — | Ligado a Kotlin | — | Sin reflexión | — | docs de Kotlin |
| `androidx.credentials:credentials` y `credentials-play-services-auth` | **1.6.0** estable (2026-04-08) **[V]** | **[NV]** | — | ninguno propio | Los proveedores de credenciales están en Google Play services (otro UID) | Requiere Google Play services para Google | notas de versión de AndroidX |
| `com.google.android.libraries.identity.googleid:googleid` | **1.2.1** (2026-01-22) **[V]** | **[NV]** | — | ninguno | Nonce con `setNonce` | — | notas de versión de Credential Manager |
| `androidx.security:security-crypto` | **No usar**: obsoleta desde 2025 **[V]** (informe anterior) | — | — | — | — | — | docs de AndroidX |
| WorkManager | Fase de sincronización automática, **no 4B** | — | — | `ACCESS_NETWORK_STATE` para la restricción de red | — | — | — |

La fase 4B solo añadiría dependencias de autenticación en el paso 8 y con aprobación expresa (D2). Los pasos 1–7 usan interfaces y adaptadores falsos.

---

## 7. Una base local por cuenta [P]

### 7.1 Registro de bases
- **Archivo:** `noBackupFilesDir/accounts/registry.json`, escrito de forma atómica (temporal + `ATOMIC_MOVE`). Va fuera de las copias del sistema, que además ya están desactivadas.
- **Formato:** `{ "active": "<accountId|null>", "accounts": [{ "accountId", "databaseFile", "provider", "maskedEmail", "linkedAt" }] }`.
- **Datos personales:** solo el UUID de Supabase y un correo enmascarado para la interfaz.
- **`turnocontrol.db`:**
  - **nunca se renombra ni se mueve**;
  - si el usuario vincula sus datos actuales a una cuenta, ese archivo pasa a ser la base de esa cuenta (`SyncState.accountId`) y el registro lo apunta;
  - las demás cuentas usan `AccountDatabases.fileName(accountId)`.
- **Fuente de verdad:** si el registro y `SyncState.accountId` no coinciden, manda la base. La app no la abre para escribir y muestra un error con opción de diagnóstico; nunca reasigna nada en silencio.

### 7.2 Cambio de base: reinicio controlado del proceso
Se recomienda frente a un componente Hilt por cuenta por cuatro motivos:
- elimina por construcción **todas** las referencias obsoletas (repositorios, ViewModels, flujos y la instancia de Room);
- la compuerta de arranque y la recuperación de copias se ejecutan sobre la base nueva;
- es fácil de probar;
- coste: un reinicio visible de 1–2 s, que solo ocurre al cambiar de cuenta o vincular.

Secuencia (`AccountSwitcher`):
1. Rechazar el cambio si hay una escritura o sincronización en curso. Un `DatabaseGate` (lectura y escritura) lo adquieren en modo compartido `ScheduleRepository.save`, `ConfigurationRepository.save`, `BackupRepository.restore` y `SyncStore`; el cambio lo adquiere en modo exclusivo con un tiempo límite.
2. Cancelar el trabajo de sincronización pendiente.
3. `db.close()`, que en Room cierra las conexiones y, en modo WAL, hace el checkpoint al cerrar la última **[NV: verificar el checkpoint en el dispositivo]**.
4. Escribir `active` en el registro.
5. Reiniciar el proceso: `Intent` de lanzamiento con `FLAG_ACTIVITY_CLEAR_TASK` y fin del proceso.
6. Al arrancar, `StorageModule` lee el registro y abre el archivo activo. Se ejecutan `CompatibilityChecker` y la recuperación de restauraciones interrumpidas **del directorio de esa base**.

### 7.3 Archivos WAL y SHM
- Cada base tiene sus propios `-wal` y `-shm`, que Room y SQLite gestionan.
- Nunca se copia, mueve ni borra el `.db` sin sus compañeros, y nunca con la base abierta.
- **Copias de seguridad:** se hacen siempre con el exportador JSON existente, nunca copiando archivos.
- **Borrar los datos de una cuenta** del dispositivo usa `Context.deleteDatabase(name)`, que elimina los tres archivos, y solo con la base cerrada.

### 7.4 Sesión, cuenta y base
- **Sin mezcla de bases:** iniciar sesión con B mientras está activa la base de A **nunca** une las dos. Si B ya tiene base, se cambia a ella; si no, se crea una vacía para B. Pasar datos de A a B solo es posible exportando una copia y restaurándola explícitamente.
- **Sesión que no corresponde a la base:** `SyncStore.synchronize` ya comprueba que `transport.accountId` coincida con `SyncState.accountId` **[V]**. Además, el `AuthGateway` devuelve la sesión con su `accountId` del servidor, y el `SyncTransport` solo se construye para la cuenta de la base activa.
- **Lo que ya separa la estructura:**
  - el cursor, el contador y la cola viven dentro de cada base, así que no se reutilizan entre cuentas;
  - los identificadores de operación son UUID;
  - `deviceId` es por base.

### 7.5 Cerrar sesión
- **«Cerrar sesión»** es la opción predeterminada: se borra la sesión de ese `accountId` y la base se queda. **Decisión del propietario (D-A1)** sobre qué base queda activa después:
  - **(a, recomendada)** la misma base, en estado «sin sesión»: los datos siguen visibles y las ediciones se acumulan para cuando la misma cuenta vuelva a entrar;
  - **(b)** una base de invitado aparte, vacía.
- **«Cerrar sesión y quitar los datos de este dispositivo»** solo está disponible con la cola vacía y la última sincronización correcta. Ofrece exportar una copia antes y pide confirmación escrita.

### 7.6 Reinicio y arranque sin conexión
El registro y la sesión cifrada se leen sin red. Si el refresh token no se puede renovar, el estado es `SignedIn` pendiente de renovación o `SessionExpired`, y los datos siguen disponibles.

---

## 8. Primera vinculación [P]

Desarrolla §9 del documento de cuentas.

1. Desde Ajustes, el usuario elige iniciar sesión o crear una cuenta. **La base actual no cambia.**
2. Tras autenticarse:
   - se crea la copia de recuperación `noBackupFilesDir/linking/<accountId>/pre-link.json` (exportador JSON con verificación);
   - se escribe un diario `linking.journal` con el estado `STARTED` y el `accountId`.
3. Se descarga la instantánea remota a memoria o a un archivo temporal **sin escribir en la base**. `LinkMergePlanner` (puro, sobre `SyncCodecs` y `SyncRules`) la clasifica en: solo local, solo remoto, idéntico, conflicto, inválido al combinar y desconocido.
4. Vista previa con los recuentos por tipo, incluidas las bajas (D9). La opción predeterminada es **«Combinar»**. Los conflictos exigen una decisión. «Usar solo la cuenta» y «Usar solo este dispositivo» piden reautenticación y confirmación escrita.
5. Al confirmar, **en una transacción de Room**:
   - se aplican las elecciones remotas;
   - `SyncStore.link(accountId)`;
   - se encolan los registros locales elegidos con el `base_version` de la instantánea;
   - el diario pasa a `APPLIED`.

   Después, una sincronización normal. Los conflictos que surjan entre tanto siguen el flujo normal.
6. **Recuperación tras una interrupción** (se mira el diario al arrancar):
   - `STARTED` sin transacción aplicada: la base está intacta; se descarta el diario y se informa;
   - `APPLIED`: el vínculo es válido y la sincronización continúa con la cola persistente;
   - si el estado no cuadra, se pide decidir entre restaurar `pre-link.json` o conservar, con la misma interfaz que `RecoveryOutcome.NeedsDecision`.
7. **Nada se sube ni se sustituye sin la confirmación del propietario.** La primera sincronización es explícita.

---

## 9. Seguridad de la sesión [P]

| Elemento | Diseño |
|---|---|
| Almacén | `SessionManager` propio para supabase-kt, que sustituye a `SettingsSessionManager`, el cual usa SharedPreferences en claro **[V]**. Un archivo por cuenta, `noBackupFilesDir/sessions/<sha256(accountId)>.bin`, cifrado con **AES-256-GCM**. La clave es no exportable y está en **Android Keystore** (alias por cuenta; StrongBox si existe, si no TEE) y se crea con `KeyGenParameterSpec` |
| Desbloqueo del dispositivo | La clave **no** exige autenticación del usuario (`setUserAuthenticationRequired(false)`), para que la sincronización pueda renovar la sesión con la app abierta. Alternativa (D-A3): exigir desbloqueo reciente, lo que impide sincronizar en segundo plano |
| Renovación y rotación | El SDK renueva la sesión automáticamente. En Supabase: rotación de refresh token con detección de reutilización y JWT de 1 h |
| Revocación | Un rechazo del refresh token da `SessionExpired` y se borra la sesión guardada de esa cuenta |
| Cerrar sesión | `signOut(scope = LOCAL)`, borrar el archivo y eliminar la clave de Keystore de esa cuenta |
| Reinicio | Se lee el archivo cifrado. Si el descifrado falla (clave invalidada, restauración en otro dispositivo), la sesión se borra y se pide iniciar sesión, sin tocar los datos |
| Registros de diagnóstico | Un interceptor elimina las cabeceras `Authorization` y `apikey`. Se prohíbe registrar sesiones, tokens o correos completos, y un test lo comprueba |
| Copias | Las sesiones y el registro están en `noBackupFilesDir`, fuera del exportador JSON y de las copias del sistema |
| Dispositivo perdido o robado | Desde la web o desde otro dispositivo se pueden revocar todas las sesiones («cerrar sesión en todas partes», `signOut(scope = GLOBAL)`). Los datos locales dependen del bloqueo del dispositivo; un bloqueo propio de la app es una mejora futura |
| Contraseñas | Nunca se guardan |
| Claves | En la app solo va la clave publicable de Supabase. La clave privilegiada vive solo en la Edge Function |

### Modelo de amenazas de la sesión
| Amenaza | Mitigación | Riesgo residual |
|---|---|---|
| Otra app lee el almacenamiento | Sandbox de la app y archivo cifrado con una clave de Keystore no exportable | Bajo |
| Volcado del dispositivo con root | La clave sigue en TEE o StrongBox, pero un atacante con root puede usar la clave dentro del proceso | Medio; aceptado |
| Extracción por copia de seguridad | `allowBackup=false` y `noBackupFilesDir` | Bajo |
| Robo del refresh token | Rotación y detección de reutilización en el servidor; revocación global | Bajo-medio |
| Tokens en registros o informes | Interceptor de redacción y test | Bajo |

---

## 10. ML Kit y privacidad de red

### 10.1 Hechos verificados
- **Dependencias resueltas** (`:app:dependencies`, `debugRuntimeClasspath`) **[V]**:
  - `com.google.mlkit:text-recognition:16.0.1`, `text-recognition-bundled-common:17.0.0`, `vision-common:17.3.0`, `mlkit:common:18.11.0`;
  - `play-services-mlkit-text-recognition:19.0.1` y `-common:19.1.0`;
  - `play-services-base:18.5.0`, `basement:18.4.0`;
  - `firebase-components:16.1.0`, `firebase-encoders(-json)`;
  - **`com.google.android.datatransport:transport-api:2.2.1`, `transport-runtime:2.2.6`, `transport-backend-cct:2.3.3`**.
- **Manifiesto fusionado** (un `debug` ya existente, solo lectura) **[V]**: contiene `MlKitInitProvider`, `MlKitComponentDiscoveryService`, `TransportBackendDiscovery` con `CctBackendFactory`, `JobInfoSchedulerService` y `AlarmManagerSchedulerBroadcastReceiver`.
- **Documentación de Google** **[V]**:
  - el procesamiento de imágenes y del texto reconocido ocurre **en el dispositivo** y no se envía a Google;
  - **pero** ML Kit envía **métricas de uso y rendimiento** (dispositivo, app, latencia, configuración de la API) por HTTPS;
  - el desarrollador debe informar de ello a los usuarios.
- **Conclusión:** hoy no sale nada porque falta `INTERNET`. Al añadirlo, **el modo invitado dejaría de tener cero tráfico**: la telemetría de ML Kit, mediante `datatransport`/CCT, podría conectarse aunque la sincronización esté desactivada.

### 10.2 Opciones de control
| Opción | Efecto | Coste o riesgo | Marca |
|---|---|---|---|
| A. Mantener `INTERNET` eliminado hasta el paso 8 de la fase 4B | Cero tráfico garantizado por el sistema durante el desarrollo con proveedores falsos | Ninguno | [P] recomendada ya |
| B. Eliminar del manifiesto los componentes de `datatransport` (`tools:node="remove"` en `TransportBackendDiscovery`, `JobInfoSchedulerService` y `AlarmManagerSchedulerBroadcastReceiver`) y excluir `transport-backend-cct` en Gradle | Sin backend de envío, los eventos se descartan | **[NV]** que ML Kit no falle ni reintente sin fin. **[NV]** si es compatible con las condiciones de ML Kit. Requiere tests de OCR y de tráfico | [P] |
| C. **OCR en un servicio `android:isolatedProcess="true"`** | El proceso aislado usa un UID efímero y el dominio SELinux `isolated_app`, **sin red** aunque la app tenga `INTERNET` **[V]** (documentación de AOSP, Chromium y Firefox). Es el aislamiento más fuerte | **[NV]** que ML Kit funcione ahí: no hay `ContentProvider`, por lo que haría falta `MlKit.initialize()` manual, y no hay acceso a Google Play services. La imagen tendría que pasar por IPC con memoria compartida o un descriptor de archivo. Exige una **prueba de concepto** | [P] |
| D. Permiso de red por componente | **No existe en Android:** `INTERNET` es por UID (aplicación). Un proceso aparte con el mismo UID **no** aísla | — | [V] |
| E. Network Security Config | No bloquea dominios HTTPS; solo controla la confianza y el texto en claro. Útil para desactivar el texto en claro y para fijar certificados de Supabase | — | [V] |
| F. Otro motor de OCR (por ejemplo Tesseract) | Sin telemetría de Google | Precisión peor, APK más grande y rehacer las pruebas de OCR. **No está justificado** salvo que fallen B y C | [P] |

**Recomendación:**
1. Opción A ahora.
2. Antes del paso 8, una prueba de concepto de C.
3. Si C funciona, C + B + E. Si no, B + E, aceptando el riesgo residual documentado y la información al usuario.

### 10.3 Pruebas de privacidad medibles
Se ejecutan en un emulador con la app `.verification`. **Nunca en el A53 ni en la app real.**
1. **Captura de tráfico:** emulador con `-tcpdump` o mitmproxy con su CA solo en el emulador, y `TrafficStats.getUidTxBytes/RxBytes` del UID antes y después.
2. **Modo invitado, 30 min con importación OCR y uso normal:** **0 conexiones** del UID de la app y 0 bytes por UID. Los procesos de Google Play services (otro UID) se registran aparte.
3. **Sesión iniciada:** solo resoluciones DNS y conexiones a `<proyecto>.supabase.co`. Credential Manager funciona en el UID de Google Play services.
4. **StrictMode en las builds de prueba** con `detectUntaggedSockets()`. Las conexiones propias se etiquetan con `TrafficStats.setThreadStatsTag`; cualquier socket sin etiqueta (de terceros) aparece en los registros y hace fallar la prueba.
5. **Contenido:** ninguna petición contiene imágenes, texto reconocido ni campos médicos. Se buscan marcadores sintéticos en el tráfico descifrado del emulador.
6. **Manifiesto fusionado:** un test comprueba la lista exacta de permisos y la ausencia de los componentes eliminados.

---

## 11. Interfaz [P]

### 11.1 Navegación
- **Rutas nuevas bajo Ajustes**, sin pestañas nuevas:
  - `Cuenta`: estado, iniciar sesión, crear cuenta, sincronización, cambios pendientes y gestión;
  - `Cuenta/Correo`: inicio de sesión y registro;
  - `Cuenta/Recuperar`;
  - `Cuenta/Vincular`: asistente de primera vinculación;
  - `Cuenta/Conflictos`: fase posterior.
- **Reglas de navegación:** se mantienen las actuales (`launchSingleTop` y sin restaurar estado).
- **Material 3:** paleta azul, temas claro y oscuro, y tarjetas como las de Ajustes.

### 11.2 ViewModels
| ViewModel | Estado | Notas |
|---|---|---|
| `AccountViewModel` | `AuthState`, sincronización (última, pendientes, conflictos, `REVIEW_REQUIRED`), `busy` y mensaje | Depende de `AuthGateway`, `SessionVault`, `AccountRegistry`, `AccountSwitcher` y `SyncStore`. No conserva datos de la base tras un cambio, porque el proceso se reinicia |
| `EmailAuthViewModel` | Campos y errores de validación | La contraseña solo en memoria, sin `SavedStateHandle` |
| `LinkingViewModel` | Fases (autenticado, copia previa, vista previa, decisiones, aplicando, hecho o recuperación) y recuentos | Persiste en el diario de vinculación, no en el ViewModel |

### 11.3 Textos y errores (español)
| Situación | Mensaje |
|---|---|
| Invitado | «Tus datos se guardan solo en este dispositivo. Crear una cuenta es opcional y sirve para sincronizarlos con otros dispositivos.» |
| Credenciales incorrectas | «Correo o contraseña incorrectos.» |
| Sin red | «No hay conexión. Puedes seguir usando la app; vuelve a intentarlo más tarde.» |
| Sesión caducada | «Tu sesión ha caducado. Tus datos siguen en este dispositivo; inicia sesión de nuevo para sincronizar.» |
| Cuenta existente | «Ya existe una cuenta con ese correo. Inicia sesión o recupera la contraseña.» |
| Vinculación fallida | «No se ha cambiado nada. Puedes reintentarlo; la copia previa está guardada.» |
| Cambio de cuenta bloqueado | «Espera a que termine el guardado o la sincronización antes de cambiar de cuenta.» |

---

## 12. Modelo de amenazas general

| Amenaza | Control |
|---|---|
| Acceso de la cuenta A a los datos de B en el servidor | RLS con `auth.uid()`, RPC `security invoker`, y propietario tomado de la sesión verificada, nunca de lo que envía el cliente |
| Acceso de A a los datos de B en el dispositivo | Archivos separados, registro, `SyncState.accountId` y reinicio del proceso al cambiar |
| Sesión obsoleta escribiendo en otra base | Comprobación de `accountId` en `SyncStore`, transporte construido para la base activa y proceso nuevo |
| ID token reutilizado | Nonce validado por Supabase |
| Interceptación de enlaces de correo | PKCE y App Link verificado |
| Telemetría o fugas de red de terceros | §10 |
| Datos de salud (bajas) | Solo fecha y estado (D9) y RLS. **Los controles técnicos no equivalen a cumplir la normativa:** hacen falta además la base jurídica y el consentimiento, una evaluación de impacto si procede, un contrato de encargo con Supabase, la región de la UE, la política de privacidad y el formulario Data safety. Son obligaciones legales del responsable, fuera del código |

---

## 13. Estrategia de pruebas (futura; datos sintéticos y proveedores falsos)

| Área | Casos |
|---|---|
| Autenticación | Google correcto (nonce con hash enviado, nonce en claro entregado al gateway); cancelación; sin credenciales; correo y contraseña correctos o incorrectos; registro con correo sin confirmar; recuperación; cambio de contraseña con reautenticación; sesión caducada; renovación; cerrar sesión; arranque sin conexión con sesión guardada; descifrado fallido de la sesión |
| Varias cuentas | Aislamiento entre A y B (archivos y transporte); cambio con una escritura en curso (rechazado y sin pérdida); cambio con sincronización en curso; proceso terminado a mitad del cambio (el registro manda y no hay base mezclada); repositorios obsoletos imposibles tras el reinicio (test de instancia); B intentando sincronizar la base de A (`ACCOUNT_MISMATCH`); recuperación de restauraciones aislada por base; cerrar sesión con cambios pendientes; iniciar sesión sin conexión |
| Datos existentes | De invitado a cuenta con la nube vacía o con datos; vista previa; combinación; conflictos; copia previa; vinculación interrumpida en cada estado del diario; recuperación tras reiniciar |
| Privacidad | Tráfico (§10.3); OCR sin red; ningún token en los registros (captura de logcat en la prueba); ningún token en la copia JSON; contenido de sincronización sin campos médicos (ya cubierto en la fase 2 y ampliado) |

---

## 14. Hoja de ruta de la fase 4B

| # | Paso | Archivos | Depende de | Riesgos | Tests y aceptación | Vuelta atrás | Configuración externa | Aprobación |
|---|---|---|---|---|---|---|---|---|
| 1 | Interfaces de autenticación (`AuthState`, `AuthResult`, `AuthGateway`, `SessionVault`, `GoogleCredentialSource`) | `domain/auth/*` | — | Bajo | Compilan; sin dependencias nuevas | Revertir | — | Sí |
| 2 | Proveedor falso y tests | `test/.../FakeAuthGateway`, máquina de estados de `AccountViewModel` | 1 | Bajo | Todos los casos de autenticación de §13 en JVM | Revertir | — | Sí |
| 3 | Bases por cuenta: `AccountRegistry`, `DatabaseGate`, `AccountSwitcher`, `StorageModule` según el registro y `backup-recovery` por base | `data/accounts/*`, `di/StorageModule`, `BackupRepository` | 1 | **Alto** (datos reales) | Aislamiento, interrupción del cambio, recuperación por base, `turnocontrol.db` intacto; migración a un directorio de recuperación por base sin perder un diario existente | Bandera; el registro ausente equivale al comportamiento actual | — | **Sí** |
| 4 | Interfaz de invitado y cuenta (Ajustes → Cuenta, con el proveedor falso) | `ui/account/*`, navegación | 2, 3 | Medio | Navegación, textos, sin red; tests de UI en JVM | Bandera | — | Sí |
| 5 | Almacén de sesión cifrado con Keystore | `data/auth/KeystoreSessionVault` | 1 | Medio | Cifrado de ida y vuelta (Robolectric con proveedor de pruebas; Keystore real solo en emulador), descifrado fallido, borrado, fuera de copias | Revertir | — | Sí |
| 6 | Google con Credential Manager (adaptador sin red real; probado con un falso) | `data/auth/CredentialManagerGoogleSource` | 1, 5 | Medio | Nonce, cancelación, sin credenciales | Revertir | Clientes OAuth (más adelante) | **Sí** (dependencias D2) |
| 7 | Correo y contraseña (adaptador) y enlaces PKCE | `data/auth/*`, `intent-filter` del enlace | 1, 5 | Medio | Flujos con un servidor de autenticación falso | Revertir | Dominio para el App Link (D-A2) | **Sí** |
| 8 | Configuración real de Supabase: dependencias (supabase-kt, Ktor, serialización), permiso `INTERNET` y controles de §10 | Gradle, manifiesto, `SupabaseAuthGateway` | 6, 7, prueba de concepto de §10 | **Alto** (privacidad) | Tráfico, manifiesto, tests sin red en invitado; proyecto `dev` con cuentas sintéticas | Revertir el permiso o una bandera | Proyecto Supabase UE (dev), proveedor Google, SMTP, RLS y pgTAP | **Sí** |
| 9 | Primera vinculación (`LinkMergePlanner`, diario, asistente) | `domain/sync/LinkMergePlanner`, `ui/account/Linking*`, `SyncStore` | 3, 4, 8 | **Alto** | Todos los casos de datos existentes de §13 | Bandera; `pre-link.json` | — | **Sí** |
| 10 | Validación de seguridad en emulador y dispositivo de pruebas (paquete `.verification`) | — | 1–9 | — | Pruebas instrumentadas, tráfico, Keystore real, cambio de cuenta | — | — | **Sí** (sin el A53 real ni la app real salvo autorización expresa) |

**Orden recomendado:** 1, 2, 3, 5, 4, 6, 7, prueba de concepto de §10, 8, 9, 10.

---

## 15. Decisiones del propietario

| # | Decisión | Recomendación |
|---|---|---|
| D-A1 | Al cerrar sesión, ¿qué base queda activa? | La misma, en estado «sin sesión» |
| D-A2 | Enlaces de verificación y recuperación: App Link https (requiere dominio y `assetlinks.json`) o esquema propio | App Link; esquema propio solo en `dev` |
| D-A3 | ¿Exigir desbloqueo del dispositivo para usar la sesión? | No; impediría sincronizar en segundo plano |
| D-A4 | ¿`compose-auth` o Credential Manager directo? | Directo |
| D-A5 | Telemetría de ML Kit: ¿prueba de concepto de OCR en proceso aislado, eliminar componentes, o aceptar e informar? | Prueba de concepto primero; nunca habilitar `INTERNET` sin un control probado |
| D-A6 | ¿Autorizar las dependencias (supabase-kt 3.9.0 **tras verificarla en Maven Central**, Ktor, serialización, Credentials 1.6.0, googleid 1.2.1)? | Sí, en el paso 8 y con versiones fijadas |
| D-A7 | ¿Reinicio del proceso al cambiar de cuenta? | Sí |
| D-A8 | ¿Borrado de cuenta en la fase 4B o en la fase de cumplimiento? | En la fase de cumplimiento, antes de publicar |

---

## 16. Bloqueos pendientes
1. Versiones y requisitos (minSdk, Kotlin, Ktor) de supabase-kt **[NV]**: verificar en Maven Central y en el changelog antes del paso 8.
2. Viabilidad de ML Kit en un proceso aislado y efecto de eliminar `datatransport` **[NV]**: requiere una prueba de concepto en emulador.
3. Dominio para App Links (D-A2).
4. Requisitos legales del tratamiento de las bajas (§12) antes de cualquier sincronización real.

---

## Fuentes (consultadas el 2026-11-22)
- supabase-kt (versiones): https://klibs.io/project/supabase-community/supabase-kt · https://mvnrepository.com/artifact/io.github.jan-tennert.supabase/supabase-kt · https://github.com/supabase-community/supabase-kt/releases
- supabase-kt `SettingsSessionManager`: https://supabase-community.github.io/supabase-kt/auth-kt/io.github.jan.supabase.auth/-settings-session-manager/index.html
- Supabase, Sign in with Google: https://supabase.com/docs/guides/auth/social-login/auth-google · Blog de inicio nativo: https://supabase.com/blog/native-mobile-auth
- Supabase, PKCE: https://supabase.com/docs/guides/auth/sessions/pkce-flow
- AndroidX Credentials (versiones): https://developer.android.com/jetpack/androidx/releases/credentials
- Implementar Sign in with Google: https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation
- Notas de versión de Credential Manager y googleid: https://developers.google.com/identity/android-credential-manager/releases
- ML Kit, divulgación de datos en Android: https://developers.google.com/ml-kit/android-data-disclosure · Condiciones: https://developers.google.com/ml-kit/terms
- Procesos aislados: https://chromium.googlesource.com/chromium/src/+/HEAD/docs/security/android-sandbox.md · https://firefox-source-docs.mozilla.org/mobile/android/geckoview/project/isolated-process/overview.html · https://android.googlesource.com/platform/external/sepolicy/+/a39b131/isolated_app.te
- `security-crypto` obsoleto: https://developer.android.com/reference/androidx/security/crypto/package-summary

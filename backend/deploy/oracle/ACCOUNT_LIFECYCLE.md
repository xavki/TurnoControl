# Ciclo de vida de la cuenta: sesiones, borrado, retención y dispositivos sin conexión (fase 5F.1D)

Estado: implementado y probado en local (PostgreSQL 16, imagen de Supabase y pila completa con GoTrue real). **No desplegado** en el staging de Oracle.

## 1. Sesiones
| Parámetro | Valor | Evidencia |
|---|---|---|
| Duración del token de acceso | 900 s (15 min) | Caducidad real verificada en staging ARM64; el refresh token recupera la sesión |
| Rotación de refresh tokens | Activada | `tests/refresh-token-policy-test.sh` |
| Intervalo de reutilización | **0 s** (`TURNO_REFRESH_TOKEN_REUSE_INTERVAL`) | Probado con 0, 2 y 10 s |
| Inactividad máxima o duración máxima de sesión | Sin configurar (decisión del propietario) | Con 1, 30, 90 y 400 días sin conexión la renovación funciona |

**Por qué 0.** Con un intervalo mayor que cero, el último token de una familia revocada sigue funcionando durante esos segundos después de detectar la reutilización (observado con 10 s). Con 0, la familia se revoca al instante, y aun así todo lo normal se recupera, porque GoTrue responde al reintento con el **padre** del token activo devolviendo ese token activo:

| Situación | Con 0 s |
|---|---|
| Respuesta de renovación perdida; el cliente reintenta con el token anterior | Se recupera (devuelve el token activo) |
| Proceso cortado a mitad de la renovación | Se recupera |
| Reintentos repetidos tras una respuesta incierta | Se recupera |
| Dos renovaciones simultáneas con el mismo token | Las dos reciben el mismo token activo |
| Varios dispositivos de la misma cuenta | Son sesiones independientes; revocar una no afecta a las otras |
| Reutilizar un token antiguo (señal de robo) | Rechazado, y la familia queda revocada |

**Contrato del cliente:**
1. Guardar el par nuevo (acceso y refresh) **antes** de usarlo.
2. Tras una renovación incierta (timeout, cierre de la app, respuesta perdida), reintentar con el **mismo** refresh token anterior.
3. No lanzar renovaciones en paralelo. `AuthCoordinator` ya las serializa.
4. Al cerrar sesión, borrar siempre el par local.

**Caducidad frente a revocación.** El token de acceso es un JWT sin estado: PostgREST lo acepta hasta que caduca (como máximo 15 min), aunque la sesión ya esté cerrada o revocada. GoTrue sí deja de aceptar el refresh token al instante.

**Operaciones sensibles.** Cualquier RPC que no se deba ejecutar con un token de una sesión ya cerrada tiene que comprobar la sesión en el servidor. `delete_my_account` es la primera:
- exige que la sesión del claim `session_id` siga existiendo;
- exige además una **autenticación reciente**: el `amr` más nuevo, que es la hora del inicio de sesión original y GoTrue conserva al renovar, debe tener como mucho 10 minutos.

## 2. Borrado de cuenta (`public.delete_my_account('ELIMINAR MI CUENTA')`)
- **Quién:** solo la propia cuenta. Su identidad sale de los claims verificados; el cliente nunca envía un id. El gateway solo acepta POST con `apikey`.
- **Condiciones:**
  - frase de confirmación exacta;
  - token vigente y de `role=authenticated`;
  - autenticación de hace 10 minutos o menos;
  - sesión existente y no caducada.
  - Si falla cualquiera, se rechaza con 28000 o 22023.
- **Qué borra, en una sola transacción:**
  - la fila de `auth.users`, que en cascada elimina identidades, sesiones (y sus refresh tokens), MFA y todo lo de `turno.*`;
  - explícitamente, lo que GoTrue deja sin clave foránea en cascada: `auth.flow_state`, `auth.refresh_tokens` antiguos y las entradas de `auth.audit_log_entries` de esa cuenta (que guardan el correo).
- **Qué se conserva:** un contador sin identificadores en `turno_ops.account_deletions`.
- **Concurrencia:** se serializa con `sync_push`. En los cuatro órdenes probados (incluida una cuenta sin `user_state`) la cuenta acaba sin ninguna fila y no hay deadlock.
- **Después del borrado:**
  - repetir la petición devuelve `ALREADY_DELETED` (idempotente);
  - con el token aún vigente, `sync_push` y `sync_pull` reciben 28000 `account not found`: la cuenta no resucita y la respuesta no expone nombres de tablas;
  - el refresh token y el inicio de sesión dejan de funcionar.
- **Verificación:** un barrido de todas las columnas de `auth`, `turno` y `turno_ops` no encontró ningún resto del id ni del correo.
- **Privilegios:** GoTrue activa RLS **sin políticas** en `auth.*` (descubierto contra la pila real). El acceso pasa por el rol `turno_auth_ops`:
  - es NOLOGIN y tiene BYPASSRLS;
  - tiene privilegios **solo de columna** sobre las tablas de `auth` que necesita;
  - es dueño de tres funciones de propósito fijo en `turno_ops` que solo `turno_owner` puede ejecutar.
  - `turno_owner` no recibe ningún privilegio sobre `auth` y conserva el RLS forzado de `turno.*`.
- **Copias:** los datos borrados siguen en las copias cifradas hasta que caducan (ver `BACKUP_PLAN.md` §6).

## 3. Retención (`scripts/retention.sh`, solo operador)
- **Purga, a partir de N días (90 por defecto, mínimo 30):**
  - `turno.record_history`;
  - `turno.applied_mutations`, **salvo** la de la versión actual de cada registro;
  - `auth.audit_log_entries`.
- **Nunca toca** `turno.records` (estado actual con lápidas) ni `turno.user_state`.
- **Seguridad de la ejecución:** simulación por defecto; `--apply` exige una copia verificada de menos de 2 horas; una sola transacción, que se aborta si los recuentos cambian durante la ejecución.
- **Registro:** solo recuentos, sin identificadores.
- **No está programada en ningún sitio.**

## 4. Dispositivos sin conexión durante mucho tiempo
Como `turno.records` nunca se purga, **ningún periodo sin conexión obliga a una resincronización completa**. `sync_pull` desde un cursor antiguo devuelve el estado actual de cada clave cambiada desde entonces, lápidas incluidas. Probado con 1, 30, 89, 91 y 400 días, antes y después de purgar:
- se reciben las ediciones, los **borrados** y las recreaciones hechos desde otro dispositivo;
- el outbox obsoleto recibe CONFLICT (comparación de `base_version`) y nunca resucita ni sobrescribe nada;
- reenviar una operación cuya fila de idempotencia se purgó devuelve CONFLICT con la versión del servidor. **Nunca se duplica.** El cliente Android lo adopta en silencio si el contenido es idéntico (`SyncEngine.sameContent`).

**Diferencia de comportamiento que hay que conocer.** Supongamos que se pierde una respuesta, después otro dispositivo edita ese registro, y después pasa la purga. Al reintentar, el usuario ve un conflicto donde antes se habría confirmado en silencio. No hay pérdida de datos.

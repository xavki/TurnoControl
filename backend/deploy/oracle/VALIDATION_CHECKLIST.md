# Lista de validación

Estados: **✔ superado** (ejecutado, con evidencia) · **◐ parcial** · **☐ pendiente** (necesita el servidor real o una decisión).

**Entorno de la fase 5F.1C, etapa 0 (2026-11-24):**
- Docker Desktop en un PC **x86_64**. Se usaron las variantes **amd64** de las mismas imágenes fijadas por digest de índice.
- Pila aislada con el proyecto `turnocontrol-validation-5f1c0` (`tests/compose.validation.yaml`), secretos sintéticos y cuentas sintéticas.
- **No se ha ejecutado nada en ARM64 ni en Oracle.**

## Configuración
| Comprobación | Estado | Evidencia |
|---|---|---|
| `tests/static-checks.sh` (configuración resuelta, secretos, scripts, finales de línea y regresiones) | ✔ | 113 superadas, 0 fallidas |
| db, auth y rest sin puertos publicados; el gateway solo en `127.0.0.1` | ✔ | Configuración resuelta y `docker ps` en ejecución |
| `caddy validate` con la imagen fijada | ✔ | `Valid configuration` |
| Las imágenes funcionan juntas | ✔ amd64 / ☐ arm64 | Pila healthy en 23–25 s |
| Las 4 imágenes publican arm64 | ✔ | Metadatos de Docker Hub |

## Base de datos
| Comprobación | Estado | Evidencia |
|---|---|---|
| 75 pruebas SQL y concurrencia en `postgres:16` | ✔ | 75/0, concurrencia superada |
| 75 pruebas SQL y concurrencia en `supabase/postgres:17.6.1.136` (sin shim) | ✔ | 75/0, concurrencia superada |
| `migrate.sh`: simulación, `--apply --first-install` y nueva simulación sin pendientes | ✔ | 3 aplicados, después 0 pendientes |
| `verify_deployment.sql` y `verify_runtime_settings.sql` | ✔ | 15/15 y 3/3 |
| Migración interrumpida (error o backend terminado) sin dejar nada a medias | ✔ | Sin esquema, rol ni RPC; el reintento limpio funciona |
| `migrate.sh` y `backup.sh` rechazan contenedores ajenos (otro proyecto, imagen PostgreSQL normal o inexistente) | ✔ | Dos señuelos desechables |

## Autenticación (JWT reales de GoTrue)
| Comprobación | Estado |
|---|---|
| Token válido aceptado; `role` y `aud` = `authenticated` | ✔ |
| Firma alterada, otro secreto, token basura, `alg=none` o payload cambiado a B: 401 | ✔ |
| Token caducado, audiencia incorrecta, sin `exp`, sin `sub` o `sub` que no es UUID: denegado | ✔ |
| Sin token, o clave anon como sesión: denegado | ✔ |
| Roles `service_role`, `supabase_admin`, `turno_owner`, `postgres`, `authenticator` o `supabase_auth_admin` falsificados: denegados | ✔ |
| Claims extra con la cuenta de B: se sigue viendo exactamente lo de A | ✔ |
| `iss` ajeno: **aceptado** (PostgREST no valida `iss`; documentado) | ◐ limitación |
| Tras cerrar sesión, GoTrue rechaza la sesión; el token de acceso sigue valiendo en las RPC hasta que caduca | ◐ limitación (documentada, con controles propuestos) |
| Enumeración de cuentas y límites por IP de GoTrue | ☐ (manual, con SMTP real) |

## Escalada de privilegios (pruebas negativas directas con las credenciales de `authenticator`)
| Comprobación | Estado |
|---|---|
| `SET ROLE` a `supabase_admin`, `service_role`, `turno_owner`, `postgres`, `supabase_auth_admin`, `pg_read_all_data`, `pg_write_all_data` o `pg_execute_server_program`: denegado | ✔ |
| `authenticator` o `authenticated` leyendo `turno.*`, `auth.users` o `turno_deploy`: denegado | ✔ |
| `CREATE TABLE`, `CREATE ROLE` o `COPY … TO PROGRAM`: denegado | ✔ |
| Roles sin contraseña (`pgbouncer`, `supabase_storage_admin`, …): no pueden iniciar sesión | ✔ |

## Autorización y sincronización por HTTP (`api-security-test.sh`, 60/60)
| Comprobación | Estado |
|---|---|
| El gateway devuelve 404 para tablas, otras funciones, `/admin`, `/authorize`, peticiones sin `apikey` o con una incorrecta, y GET a las RPC | ✔ |
| A no ve los datos de B; B no alcanza el registro de A, y el conflicto no revela nada | ✔ |
| Idempotencia, reenvío tras «timeout», conflicto, reintento con la versión remota, lápidas y paginación | ✔ |
| `user_id` o `p_user_id` inyectados, inyección SQL en la clave y en el cursor, `operation_id` no UUID, lote de 101, JSON malformado, cuerpo de más de 2 MB (413) | ✔ |
| Concurrencia sobre el mismo día: una aplicada y otra en conflicto; un único registro | ✔ |
| Resiliencia (`resilience-test.sh`, 9/9): desconexión antes y después del commit, dos cuentas a la vez, reinicio de PostgREST con tráfico | ✔ |

## Privacidad
| Comprobación | Estado |
|---|---|
| Baja con texto adicional: REJECTED; baja con fecha y estado: APPLIED | ✔ |
| Registros sin secretos, JWT, correos ni contenido sincronizado (`log-privacy-scan.sh`, también después de las desconexiones de `resilience-test.sh`) | ✔ (tras poner GoTrue en `warn` y filtrar el registro de errores del gateway) |
| Revisión legal (art. 9 del RGPD), EIPD y política de privacidad | ☐ |
| Veredicto de privacidad de la app | **PRIVACY NOT VERIFIED** (sin cambios) |

## Copias y restauración
| Comprobación | Estado |
|---|---|
| Fallo seguro sin destinatario, sin destino externo, con un destinatario que no es age, con un directorio no válido o relativo, sin rclone, con retención inferior a 2 días o con una línea inyectada | ✔ |
| Copia cifrada (sin texto en claro), con suma de comprobación y con la base correcta | ✔ |
| Detección de byte alterado, truncado, vacío, falta de sha o de roles, sha alterado, archivo sin cifrar y nombre inesperado | ✔ |
| Clave equivocada: el simulacro falla antes de crear nada | ✔ |
| Copia interrumpida (fallo de cifrado o SIGTERM): sin restos | ✔ |
| Simulacro: 20/20 comprobaciones y recuentos más huella idénticos al origen | ✔ (tras corregir los privilegios posteriores a la restauración) |
| Simulacro interrumpido: el contenedor se elimina | ✔ |
| Subida externa con rclone | ☐ (rclone no instalado en el PC; destino sin decidir) |

## Operación
| Comprobación | Estado |
|---|---|
| Apagado controlado (PostgreSQL limpio) y arranque con los datos persistidos | ✔ |
| `healthcheck.sh` y control negativo (gateway parado) | ✔ (en Windows con un sustituto de `free`) |
| Tiempo de espera de cabeceras del gateway (cliente lento cortado) | ✔ 10,0 s |
| Estabilidad de `api-security-test.sh` | ✔ 6 ejecuciones seguidas con 60/60 (tras corregir la prueba de firma alterada, que fallaba de forma intermitente) |
| Consumo: reposo ~108 MiB y pico ~158 MiB entre los 4 servicios, sin OOM | ✔ (amd64) |

## Staging privado en ARM64 (5F.1C etapa 1, Oracle VM.Standard.A1.Flex)
Proyecto `turnocontrol-staging` (`compose.staging.yaml`), gateway solo en `127.0.0.1:18080`, cuentas y datos sintéticos.

| Comprobación | Resultado |
|---|---|
| Imágenes `linux/arm64` con los digests fijados; PostgreSQL 17.6 en `aarch64` | ✔ |
| Arranque en secuencia (db, auth, rest, gateway), todo healthy; ningún puerto en `0.0.0.0`; sin respuesta desde la IP privada ni desde Internet | ✔ |
| `migrate.sh`: simulación, aplicación, sin pendientes, checksums registrados iguales a los archivos; un segundo `--first-install` se rechaza | ✔ |
| `verify_deployment.sql` 15/15 y `verify_runtime_settings.sql` 3/3 | ✔ |
| Escalada con las credenciales reales de `authenticator`: 23/23 denegadas; `authenticator` solo es miembro de `anon` y `authenticated` | ✔ |
| Suite SQL en la imagen arm64: 75/75, y concurrencia | ✔ |
| `api-security-test.sh`: 60/60 (×4, también tras reiniciar `auth`, `rest` y la pila completa) | ✔ |
| `resilience-test.sh` 9/9 · `data-integrity-test.sh` 42/42 · `log-privacy-scan.sh` superado | ✔ |
| `session-lifecycle-test.sh` 14/14: caducidad real a los 900 s, recuperación con el refresh token, rotación y revocación de familia (con la ventana de 10 s documentada) | ✔ / ◐ ventana |
| Copias: fallo seguro, rechazo de contenedores ajenos, copia cifrada, detección de alteración y truncado | ✔ |
| Simulacro arm64: 20/20 y recuentos más huella idénticos; con una clave equivocada falla antes de crear nada | ✔ |
| Purga simulada de `applied_mutations`: reenviar una operación devuelve CONFLICT y nunca la duplica | ✔ |
| Parada y arranque controlados de toda la pila: datos idénticos | ✔ |
| Alta y autoconfirmación cerradas al terminar (`422 signup_disabled`) | ✔ |
| Contenedores existentes intactos (ID, inicio, 0 reinicios) | ✔ |

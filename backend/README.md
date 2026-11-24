# Backend de sincronización de TurnoControl (solo pruebas locales)

**No está desplegado en ningún sitio.** Contiene la migración SQL del protocolo de sincronización y sus pruebas de seguridad, que se ejecutan contra un PostgreSQL real en un contenedor desechable. No incluye credenciales, archivos `.env`, claves ni datos reales.

## Contenido
| Archivo | Qué es |
|---|---|
| `supabase/migrations/20261122000000_turno_sync.sql` | Esquema `turno`, RLS, validación de contenido y RPC `public.sync_push` y `public.sync_pull` |
| `supabase/migrations/20261124000000_hosting_privileges.sql` | Solo revoca: anula los privilegios por defecto de la imagen de Supabase, que darían EXECUTE de las RPC a `anon` y `service_role` |
| `supabase/tests/00_local_auth_shim.sql` | **Solo para pruebas:** reproduce los roles de Supabase (`anon`, `authenticated`, `service_role`, `authenticator`) y `auth.uid()`. En Supabase alojado ya existen, y además la pasarela verifica la firma del JWT, algo que esta imitación **no** hace |
| `supabase/tests/05_gotrue_auth_uid.sql` | **Solo para pruebas con la imagen de Supabase:** instala el `auth.uid()` que en una pila real crean las migraciones de GoTrue |
| `supabase/tests/10_sync_security_test.sql` | 75 comprobaciones: RLS, anónimos, sesiones inválidas o caducadas, propietario falsificado, escrituras y borrados directos, idempotencia, conflictos, lápidas, paginación, atomicidad, transacciones interrumpidas, privacidad de las bajas, inyección SQL y borrado de cuenta |
| `scripts/run-local-tests.sh` | Ejecuta todo y añade una prueba de concurrencia con dos sesiones simultáneas |

## Ejecutar las pruebas
Requisitos: Docker en marcha y la imagen `postgres:16` (se puede cambiar con `TURNO_PG_IMAGE`).

```bash
bash backend/scripts/run-local-tests.sh
```

Qué hace el script:
- Crea **un único** contenedor, `turnocontrol-sync-test`. Si ya existe, no lo toca y se detiene.
- Lo arranca **sin red** (`--network none`): no se publica ningún puerto y `psql` se ejecuta dentro del contenedor.
- La contraseña del superusuario es aleatoria en cada ejecución y no se guarda.
- Al terminar, también si falla, elimina **solo** ese contenedor y su volumen anónimo.

Salida esperada:

```
RESULTADO: 75 superadas, 0 fallidas
CONCURRENCIA: superada (una aplicada, una en conflicto, un solo registro)
```

Con la imagen de Supabase (sin el shim, como `supabase_admin` y aplicando `deploy/oracle/db/hardening`):

```bash
TURNO_SUPABASE_IMAGE=1 TURNO_PG_IMAGE=supabase/postgres:17.6.1.136 bash backend/scripts/run-local-tests.sh
```

Si una ejecución se interrumpe sin limpiar, elimina solo ese contenedor: `docker rm -f -v turnocontrol-sync-test`.

## Modelo de seguridad
- **Mínimo privilegio:** `authenticated` no tiene ningún privilegio sobre las tablas; solo puede ejecutar las dos RPC. `anon` no tiene ningún acceso.
- **Funciones con privilegios:** las RPC son `SECURITY DEFINER`, pertenecen al rol `turno_owner` (sin inicio de sesión) y fijan `search_path` vacío. Son las únicas funciones de ese tipo, y una prueba lo comprueba.
- **Propietario de los datos:** sale **solo** de la sesión. `turno.current_account()` exige `role = authenticated`, un `exp` futuro y un `sub` UUID igual a `auth.uid()`.
- **Lotes estrictos:** una operación con campos no previstos (por ejemplo `user_id` o `diagnosis`) hace fallar el lote entero.
- **RLS activada y forzada** en las cuatro tablas, también para `turno_owner`: es una segunda capa por si hubiera un fallo en las funciones.
- **Borrados:** no hay borrado directo. Las lápidas representan borrados reales, y el borrado físico solo ocurre en cascada al eliminar la cuenta.

## Protocolo
- **`sync_push(device_id, mutaciones)`:** una transacción por llamada, con bloqueo por cuenta (el orden de `seq` es el orden de confirmación) y escritura condicionada a `base_version`.
  - Idempotente por `operation_id` dentro de cada cuenta.
  - Resultados: `APPLIED` (con su versión y secuencia), `CONFLICT` (con el registro remoto) o `REJECTED` (con el motivo).
  - Cada versión sustituida se guarda en `record_history`.
- **`sync_pull(cursor, límite)`:** devuelve `{changes, next_cursor, has_more}`, ordenado por `seq` y solo de la cuenta de la sesión.
  - Un cursor negativo se rechaza y el límite queda entre 1 y 1000.
  - La tabla guarda solo la última versión de cada registro: un dispositivo que vuelve tras mucho tiempo recibe el estado actual, no cada versión intermedia.
- **Validación del contenido:** formato canónico por entidad. Una baja solo puede ser `1|fecha|SICK_LEAVE|MANUAL|`; cualquier dato adicional se rechaza.

## Despliegue autoalojado
La preparación para un VPS propio (Docker Compose, sin desplegar) está en [`deploy/oracle/`](deploy/oracle/README.md).

## Antes de un despliegue real
Requiere aprobación expresa y otra fase:
- proyecto Supabase en la UE;
- verificar la firma del JWT en la pasarela (no cubierto aquí);
- tarea de purga de `record_history` y `applied_mutations`;
- Edge Function para borrar la cuenta;
- revisión legal del tratamiento de las bajas;
- repetir estas pruebas contra el proyecto `dev`.

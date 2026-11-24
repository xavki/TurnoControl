# Copias de seguridad y recuperación (propuesta)

Las copias de TurnoControl son **independientes de cualquier otra copia del servidor**. No las comparte, no las sustituye y no les afecta. **Nunca se guardan en Git**: el historial de Git impediría limitar cuánto tiempo se conservan y borrar los datos de una cuenta eliminada.

## 1. Qué se copia
- `pg_dump -Fc` de la base `postgres` del contenedor `turnocontrol-db`, con los esquemas `auth` y `turno` (cuentas, hashes de contraseñas y datos sincronizados).
- `pg_dumpall --roles-only --no-role-passwords`, para recrear `turno_owner` y las pertenencias a roles. No incluye contraseñas.
- El script comprueba la etiqueta del proyecto Compose y la imagen del contenedor, y **se niega a operar con cualquier otra base de datos**.

## 2. Cómo
| Aspecto | Propuesta (por aprobar) |
|---|---|
| Frecuencia | Diaria a las **04:00 UTC**, lejos de otras tareas nocturnas del servidor. La tarea programada **no está instalada**. |
| Cifrado | `age` con la clave pública de TurnoControl. El volcado pasa directamente a `age`, así que **no hay ninguna copia en claro en el disco**. La clave privada vive fuera del servidor. |
| Local | `TURNO_BACKUP_DIR` (la ruta debe contener `turnocontrol`), con permisos 700 y 7 días de retención. Solo se borran archivos `turnocontrol-db-*.{dump.age,roles.sql.age,sha256}` del primer nivel de ese directorio. |
| Fuera del servidor | **Obligatorio.** Adaptadores en `scripts/lib-backup-dest.sh`: `dir` (ruta montada) o `rclone` (bucket compatible con S3, por ejemplo Object Storage en la UE). Si no hay destino, el script falla, salvo que se indique `TURNO_BACKUP_ALLOW_LOCAL_ONLY=1`. **Destino sin decidir.** |
| Destino correcto | El destino tiene que tener el marcador `.turnocontrol-backup-destination` con el contenido de `TURNO_BACKUP_DEST_ID`. Se comprueba **antes** de volcar nada; un destino sin el marcador exacto se rechaza. |
| Subida | `<nombre>.partial`, comprobación del sha256 **remoto** y renombrado. Un corte deja como mucho un `.partial`, que la ejecución siguiente borra. Nunca se sobrescribe. |
| Credencial | La del VPS, **solo de escritura** en el bucket, guardada en la configuración de rclone (permisos 600). Para leer o restaurar se usa otra credencial, que guarda el propietario. |
| Retención externa | Una regla de ciclo de vida del bucket conserva unos 30 días; opcionalmente, retención de objetos (WORM) contra borrados accidentales o maliciosos. Los scripts nunca borran en un bucket (`backup-remote.sh prune` solo funciona con `dir`, en simulación por defecto, y no borra nunca todas las copias). |
| Supervisión | `scripts/backup-remote.sh status`: antigüedad de la copia externa más reciente y verificación completa de su integridad (la descarga y la comprueba). |
| Integridad | sha256 de cada archivo, cabecera `age` y tamaño mayor que cero (`verify-backup.sh`, tras cada copia). |
| Prueba de restauración | **Mensual**, con `restore-drill.sh`: contenedor desechable sin red, la misma imagen, verificación SQL y recuento de filas. |
| Alertas | `healthcheck.sh` avisa si no hay una copia de menos de 26 h. |

**Conservación y supresión:** cuando se borra una cuenta, sus datos desaparecen de la base al momento, pero siguen en las copias hasta que caducan (unos 30 días). La política de privacidad debe decirlo.

## 3. Estimación de espacio [C]
Supuestos pesimistas: unos 2 KB por fila contando índices, historial e idempotencia, 365 días por usuario y año, tres versiones de media. Salen unos 2 MB por usuario y año en la base. Un volcado `-Fc` comprimido ocupa aproximadamente un 25 % de eso.

| Usuarios | Base | Un volcado | 7 locales y 30 externos |
|---|---|---|---|
| 10 (pruebas) | < 50 MB (casi todo es el esquema de la imagen) | < 5 MB | < 200 MB |
| 100 | ~200 MB/año | ~50 MB | ~0,4 GB local y ~1,5 GB externo |
| 1 000 | ~2 GB/año | ~0,5 GB | ~3,5 GB local y ~15 GB externo |

## 4. Prueba de restauración (mensual)
1. Descarga una copia reciente desde el destino externo. No sirve la local: así se prueba la cadena completa.
2. `scripts/verify-backup.sh <archivo>`.
3. `scripts/restore-drill.sh <archivo> <identidad-age> [recuentos-del-origen.txt]`, en el equipo del propietario o en el servidor con la clave cargada temporalmente. La clave nunca entra en el contenedor.
4. Todas las líneas de verificación deben terminar en `|t`, y los recuentos deben cuadrar con los de producción de ese día.
5. Anota la fecha, el resultado y los errores de `pg_restore`. En el primer simulacro (5F.1C etapa 0, con datos sintéticos) hubo **0 errores**.

**Que exista una copia no significa que sea válida.** Una copia solo se considera buena después de un simulacro superado.

### Paso obligatorio después de cualquier restauración
Hay que volver a aplicar `db/hardening/*.sql` y `backend/supabase/migrations/20261124000000_hosting_privileges.sql`. Los dos son idempotentes y solo revocan, y `restore-drill.sh` ya lo hace.

**Motivo (defecto encontrado en el primer simulacro):**
- `pg_restore` recrea las funciones como `supabase_admin`, y los privilegios por defecto de la imagen les vuelven a dar EXECUTE a `anon` y `service_role`.
- `pg_dump` guarda las ACL comparándolas con el valor por defecto del sistema, así que la revocación no viaja en la copia.
- Sin este paso, `verify_deployment.sql` falla en «anon NO puede ejecutar las RPC».

### Qué comprueba el simulacro
- las 15 propiedades de `verify_deployment.sql`;
- de `verify_restore_integrity.sql`: sin huérfanos, `seq` únicos, `last_seq` coherente, índices verificados con `amcheck`, y que cada cuenta reciba por `sync_pull` exactamente sus filas;
- opcionalmente, recuentos y huella md5 idénticos a los del origen (`tests/sql/restore_counts.sql`).

## 5. Recuperación
| Escenario | Procedimiento |
|---|---|
| **Borrado accidental de datos de una cuenta** | Restaurar la copia en el contenedor del simulacro, extraer las filas de esa cuenta y reinsertarlas con una transacción revisada. Los dispositivos vuelven a sincronizar a partir del cursor. |
| **Datos corruptos** | Detener solo `turnocontrol-rest` y `turnocontrol-auth`. Hacer una copia del estado actual. Restaurar la última copia válida en una base nueva con `pg_restore` en un volumen nuevo y cambiar a ella, o restaurar en el mismo volumen tras una copia física. Volver a aplicar `db/hardening`. Ejecutar `verify_deployment.sql`. Arrancar. |
| **Pérdida del VPS (detallado)** | 1. Con la credencial de lectura, `backup-remote.sh fetch latest <dir>`, que descarga y verifica. 2. En la VM nueva: `.env` desde el gestor de contraseñas, `compose up` con el volumen vacío y `migrate.sh --apply --first-install`. 3. `pg_restore --data-only` de `auth`, `turno` y `turno_ops`. 4. Volver a aplicar `db/hardening` y `db/privileges`, y ejecutar `verify_deployment.sql`. La clave privada age solo existe fuera del VPS: sin ella, las copias son irrecuperables. Guarda dos copias en lugares distintos. |
| **Pérdida del VPS** | Nueva VM ARM64 con Docker, la carpeta del repositorio, el `.env` desde el gestor de contraseñas (con los mismos secretos, para que las sesiones y la clave anon sigan valiendo), `docker compose up -d` con el volumen vacío, `migrate.sh --apply --first-install`, `pg_restore --data-only` de los esquemas `auth` y `turno` y la verificación. Los clientes vuelven a enviar lo que tengan pendiente en su outbox. **Por verificar** en el primer simulacro completo. |
| **Actualización fallida** | `ROLLBACK_PLAN.md` §5. |

**Objetivos orientativos [S]:**
- se pierden como máximo 24 h de datos (copia diaria; los dispositivos conservan su outbox local);
- la restauración tarda menos de 4 h.

Para reducir la pérdida máxima haría falta archivado WAL, que queda fuera del alcance.

## 6. Borrado de cuentas y copias
- Un borrado elimina los datos de la base al momento. Las copias cifradas anteriores los conservan hasta que caducan: 7 días en local y unos 30 en el destino externo. **Borrar de forma selectiva dentro de un volcado cifrado no es viable**: habría que descifrarlo, editarlo y volver a cifrarlo, lo que rompe la integridad y la custodia de la clave. La política de privacidad debe indicar ese plazo.
- **Riesgo al restaurar:** restaurar una copia anterior a un borrado **devolvería esa cuenta**. Mitigación propuesta (decisión del propietario, no implementada): guardar durante el plazo de retención de las copias un registro de borrados con el id con hash y una «pimienta» del servidor, para reaplicar los borrados tras cualquier restauración, y purgarlo después. Mientras no exista, cualquier restauración debe revisar manualmente los borrados ocurridos después de la fecha de la copia.

## 7. Claves
- La clave privada age vive **fuera del VPS**, en dos sitios: el gestor de contraseñas del propietario y un soporte sin conexión. En el VPS solo está la clave pública.
- **Rotación:** nueva pareja de claves. La privada antigua se conserva mientras existan copias cifradas con ella (unos 30 días).
- **Prueba de restauración mensual:** `backup-remote.sh fetch` y `restore-drill.sh` en el equipo del propietario, que es donde está la clave.

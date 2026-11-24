# Seguridad del backend autoalojado

## 1. Modelo de exposición
| Componente | Quién puede llegar | Cómo se garantiza |
|---|---|---|
| PostgreSQL | Solo `auth` y `rest`, por la red `turnocontrol_internal` | Sin `ports:` y con una red `internal: true`. **Los puertos que publica Docker se saltan la cadena INPUT del cortafuegos del host**, así que lo que protege es la ausencia de `ports:`, no el cortafuegos. |
| GoTrue y PostgREST | Solo el gateway | Sin `ports:`. PostgREST está únicamente en la red interna. |
| Gateway | `127.0.0.1` del servidor y el proxy aprobado (red `turnocontrol_edge`) | `ports: 127.0.0.1:…`. Nunca `0.0.0.0`. |
| Internet | Solo el proxy inverso aprobado, con TLS | Decisión del propietario (ver `DEPLOYMENT_PLAN.md`). |

`tests/static-checks.sh` verifica estas reglas en la configuración ya resuelta.

## 2. Autenticación y autorización
- **Quién es el propietario de los datos:** solo se deduce del JWT verificado. PostgREST verifica la firma HS256, `exp` y `aud=authenticated`. Después, `turno.current_account()` vuelve a exigir `role=authenticated`, un `exp` futuro y un `sub` UUID igual a `auth.uid()`. El cliente nunca envía un `user_id`, y si lo envía, el lote completo se rechaza.
- **Privilegios de la base de datos:**
  - `authenticated` solo puede ejecutar `public.sync_push` y `public.sync_pull`.
  - `anon` y `service_role` no pueden ejecutar nada, gracias a la migración `20261124000000_hosting_privileges.sql`, que anula los privilegios por defecto de la imagen de Supabase.
  - El esquema `turno` no está expuesto.
- **Gateway:**
  - Deniega por defecto.
  - De GoTrue solo deja pasar `signup`, `token`, `verify`, `recover`, `resend`, `user`, `logout`, `settings`, `health` y `reauthenticate`.
  - De PostgREST solo `POST /rest/v1/rpc/sync_push` y `POST /rest/v1/rpc/sync_pull`.
  - Rutas bloqueadas: `/admin`, `/invite`, OAuth web, tablas y cualquier otra función.
- **GoTrue:** los usuarios anónimos y el teléfono están **desactivados en el propio `compose.yaml`**, no en `.env`. Un usuario anónimo de GoTrue recibe `role=authenticated` y las RPC lo aceptarían.
- **Sesiones:**
  - Los tokens de acceso duran 1 h.
  - Los refresh tokens rotan, y si se reutiliza uno se revoca toda su familia.
  - Cambiar la contraseña exige reautenticarse.
- **Emisor (`iss`):** PostgREST no lo comprueba (verificado: un token con `iss` ajeno pero firmado con el secreto se acepta). No es explotable sin el secreto, porque la firma ya liga el token a este servidor.
- **Limitación conocida:** PostgREST valida los tokens sin estado. Un token de acceso sigue siendo válido en las RPC hasta que caduca (como máximo 1 h), aunque la sesión ya se haya cerrado. Verificado en 5F.1C etapa 0: tras cerrar sesión, GoTrue rechaza la sesión, pero las RPC siguen aceptando el token de acceso. Controles propuestos (decisión del propietario):
  - acortar `TURNO_JWT_EXPIRY`, por ejemplo a 15 min (más renovaciones);
  - para operaciones sensibles (el futuro borrado de cuenta), comprobar en SQL que la sesión del claim `session_id` sigue existiendo en `auth.sessions`;
  - el cierre de sesión en la app borra siempre el token local (ya lo hace `EncryptedSessionVault`).

## 3. Secretos
| Secreto | Dónde vive | Quién lo ve |
|---|---|---|
| `TURNO_POSTGRES_PASSWORD` | `.env` del servidor | `supabase_admin` y `postgres`, que solo se usan dentro del servidor. |
| `TURNO_AUTHENTICATOR_PASSWORD` | `.env` | El contenedor `rest`. |
| `TURNO_AUTH_ADMIN_PASSWORD` | `.env` | El contenedor `auth`. |
| `TURNO_JWT_SECRET` | `.env` | Los contenedores `auth` y `rest`. |
| `TURNO_ANON_KEY` | `.env` y, en el futuro, la app | Es **público por diseño**: corresponde a `anon`, que no tiene ningún acceso. |
| Clave `service_role` | **No existe de forma permanente** | Se genera al momento para una operación de administración, con una caducidad de minutos, y no se guarda. |
| Clave privada `age` de las copias | **Fuera del servidor** (gestor de contraseñas o soporte sin conexión) | Solo el propietario. |
| Credenciales SMTP, de Google y de rclone | `.env` o la configuración de rclone del servidor, con permisos 600 | Los servicios que las usan. |

- **Generación:** `scripts/generate-secrets.sh` crea un `.env` nuevo con permisos 600. Los valores son hexadecimales, para que sean seguros dentro de las URI `postgres://`. Nunca los imprime ni sobrescribe un archivo existente. **Ejecutarlo para producción requiere aprobación.**
- **Almacenamiento:** el `.env` va con permisos 600, pertenece al usuario de despliegue y Git lo ignora. Debe haber una copia en el gestor de contraseñas del propietario. Nunca se comparte por chat, correo ni issues, ni en capturas o registros.
- **Visibilidad:** Docker muestra las variables de entorno de un contenedor a quien pueda ejecutar `docker inspect` en el servidor. Es el modelo estándar de Compose, y por eso el acceso a Docker equivale a acceso de administrador.
- **Rotación:**
  - **Contraseñas de roles:** los scripts de inicio solo se ejecutan con la base vacía, así que cambiar `.env` **no** basta. Hay que ejecutar `ALTER ROLE authenticator PASSWORD '…'` (o el de `supabase_auth_admin`) con `psql` dentro del contenedor, actualizar `.env` y luego `docker compose up -d` para recrear solo el servicio afectado.
  - **`TURNO_JWT_SECRET`:** hay que generar un secreto nuevo y una `TURNO_ANON_KEY` nueva, y recrear `auth`, `rest` y el gateway. **Todas las sesiones se cierran**, y la app necesita la nueva clave anon.
  - **Clave de copias `age`:** nueva pareja de claves. La clave privada antigua se conserva mientras existan copias cifradas con ella.

## 4. Operaciones de administración
- La administración se hace solo por SSH al servidor y con `docker exec … psql -U supabase_admin` (socket local), o contra GoTrue desde dentro de la red interna.
- **Borrar una cuenta**, por ejemplo una cuenta sintética de prueba: se genera un JWT temporal con `role=service_role`, firmado con `TURNO_JWT_SECRET` y con una caducidad de 5 min, y se llama a `DELETE /admin/users/<uuid>` de GoTrue **desde la red interna, no a través del gateway**, que bloquea `/admin`.
  - Las claves foráneas `on delete cascade` eliminan los datos de sincronización.
  - Las copias cifradas caducan según la retención (ver `BACKUP_PLAN.md`).

## 5. Registros
- **PostgreSQL:** `log_statement=none`, `log_min_error_statement=panic` y `log_min_duration_statement=-1`. Así no se registra ningún texto SQL, y por tanto ningún contenido sincronizado.
- **PostgREST:** `PGRST_LOG_LEVEL=error`, sin líneas de petición.
- **Gateway:** sin registro de accesos. El registro de errores (por ejemplo, ante una desconexión del cliente) guardaba toda la petición: la cabecera `apikey` y la URI, que en `/auth/v1/verify` contiene el token del enlace del correo. Caddy solo redacta `Authorization`. Un filtro en el `Caddyfile` deja ahora solo el método, el protocolo, el host y la IP del cliente (corregido en 5F.1C etapa 0).
- **GoTrue:** nivel `warn`. A nivel `info` escribe cada evento de auditoría y de petición **con el correo del usuario** (observado en 5F.1C etapa 0). Ese rastro de auditoría ya se guarda en `auth.audit_log_entries`, así que los registros de stdout solo contienen avisos y errores. `auth.audit_log_entries` contiene correos e IP: necesita una purga periódica, como `record_history`.
- **Comprobación automática:** `tests/log-privacy-scan.sh` busca secretos, JWT, correos de prueba y contenido sincronizado en los registros de los cuatro contenedores.
- **Rotación de registros:** 10 MB × 3 archivos por contenedor.
- **Lo que nunca debe aparecer en un registro, informe o captura:** contraseñas, `TURNO_JWT_SECRET`, tokens de acceso o de refresco, claves privadas, el contenido de `.env`, cuerpos de petición o contenido sincronizado.
- **Para revisar registros** usa `docker logs --tail 200 turnocontrol-auth` y similares. No copies en ningún sitio líneas con cabeceras `Authorization`.

## 6. Privacidad y protección de datos
- **Qué se sincroniza de una baja:** solo `1|fecha|SICK_LEAVE|MANUAL|`. La validación SQL rechaza cualquier dato adicional (diagnóstico, motivo, síntomas, médico, documentos o notas) y lo cubren pruebas.
- **Aunque no haya detalles médicos, una fecha marcada como baja puede ser un dato relativo a la salud** (art. 9 del RGPD). No basta con omitir el diagnóstico para afirmar que se cumple la normativa. **Antes de subir datos reales** hacen falta:
  1. base jurídica: probablemente consentimiento explícito para la categoría especial;
  2. evaluación de impacto (EIPD) si procede, y registro de actividades de tratamiento;
  3. política de privacidad publicada, con plazos de conservación y derechos de acceso, rectificación, supresión y portabilidad;
  4. contrato de encargo con el proveedor de la infraestructura y con el de copias externas, y comprobación de que todo está en la UE;
  5. purga periódica de `record_history` y `applied_mutations` (propuesta: 90 días) y caducidad de las copias;
  6. revisión de un profesional del derecho.
- El veredicto de privacidad de la app sigue siendo **PRIVACY NOT VERIFIED** (ML Kit). Este backend no lo cambia, y el permiso `INTERNET` de producción sigue desactivado.

## 7. Riesgos residuales
- Un secreto JWT filtrado permite crear tokens de cualquier usuario. Para mitigarlo: el secreto solo existe en el servidor, las sesiones son cortas, se rota si hay sospecha y el endurecimiento impide escalar a superusuario.
- Un acceso de administrador al servidor equivale a acceso a todos los datos. Para mitigarlo: SSH solo con clave, sin contraseña, y el menor número posible de personas con acceso.
- Las imágenes de terceros pueden tener vulnerabilidades. Para mitigarlo: versiones fijadas con digest y actualizaciones controladas (`MONITORING.md`).

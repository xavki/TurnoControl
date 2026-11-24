# Plan de despliegue (propuesta; nada de esto está aprobado ni ejecutado)

Cada etapa requiere la autorización expresa del propietario. Ninguna etapa toca otros proyectos del servidor.

## 1. Requisitos previos
- [ ] El propietario aprueba la fase 5F.1C.
- [ ] Se ha elegido el subdominio o nombre público, y su puerto HTTPS.
- [ ] Se ha elegido la estrategia de proxy (sección 4).
- [ ] Se ha elegido un proveedor SMTP. Hasta entonces el alta sigue cerrada.
- [ ] Se ha elegido un destino de copias externas y se ha generado la pareja de claves `age`. La clave privada queda fuera del servidor.
- [ ] Se ha aprobado descargar las 4 imágenes fijadas.
- [ ] `scripts/preflight.sh` en el servidor da `PREFLIGHT: superado`. Comprueba arm64, el puerto libre, que no haya nombres en conflicto, y el disco y la RAM disponibles.

## 2. Etapas
| # | Etapa | Resultado | Vuelta atrás |
|---|---|---|---|
| 0 | ✔ **Hecha en 5F.1C, etapa 0** (x86_64; ver `VALIDATION_CHECKLIST.md`). **Prueba local de la imagen de Supabase.** En el PC de desarrollo: `TURNO_SUPABASE_IMAGE=1 TURNO_PG_IMAGE=supabase/postgres:17.6.1.136 bash backend/scripts/run-local-tests.sh`. Requiere aprobar la descarga de esa imagen. | 75 pruebas SQL y la de concurrencia sobre la imagen real, sin GoTrue | Se borra el contenedor desechable. |
| 1 | ✔ **Hecha en 5F.1C etapa 1, en ARM64** (proyecto `turnocontrol-staging`, `compose.staging.yaml`). **Staging privado en el servidor.** Copiar la carpeta, `generate-secrets.sh` y `preflight.sh`. Después `docker compose up -d` y esperar a que los 4 servicios estén healthy. Acceso solo por túnel SSH. | Pila en marcha y sin exposición | `ROLLBACK_PLAN.md` §1 |
| 2 | **Migración.** `scripts/migrate.sh` (simulación), luego `scripts/migrate.sh --apply --first-install` y por último `tests/sql/verify_deployment.sql` y `verify_runtime_settings.sql`. | Esquema, RLS y permisos verificados | §2 |
| 3 | **Pruebas de seguridad HTTP** con cuentas sintéticas (`tests/api-security-test.sh`). Después se borran las cuentas y se vuelve a cerrar el alta. | Firma, audiencia y caducidad de los JWT, aislamiento A/B y privacidad, comprobados de verdad | §2 |
| 4 | **Copias.** `backup.sh`, `verify-backup.sh` y primer `restore-drill.sh`. La tarea programada (04:00 UTC, propuesta) solo se instala tras aprobarla. | Restauración probada | §4 |
| 5 | **HTTPS público** a través del proxy aprobado (sección 4). | Endpoint TLS | §3 |
| 6 | **Cliente Android** en una variante de depuración con red. La variante de producción **sigue sin `INTERNET`** mientras el veredicto sea PRIVACY NOT VERIFIED y no haya revisión legal. | Integración real | Desactivar la variante. |

## 3. Migración de la base de datos
**Orden de inicialización:**
1. **Primer arranque de `turnocontrol-db`, con el volumen vacío.** La imagen crea los roles de Supabase (`anon`, `authenticated`, `service_role`, `authenticator`, `supabase_auth_admin`…), el esquema `auth` inicial, las extensiones y los privilegios por defecto. Después, `db/init/99-turno-roles.sql` da contraseñas propias a `authenticator` y `supabase_auth_admin`.
2. **Arranque de `turnocontrol-auth`.** GoTrue aplica sus migraciones al esquema `auth`, incluida `auth.uid()` leyendo `request.jwt.claims`.
3. **`scripts/migrate.sh --apply --first-install`, manual y opt-in.** Lo ejecuta `supabase_admin` (superusuario) por el socket local del contenedor. Pasos:
   1. comprueba que el contenedor es el de este proyecto y que `auth.uid()` ya es la versión de GoTrue;
   2. crea `turno_deploy.applied_files`, el registro de archivos aplicados;
   3. aplica `db/hardening/10_api_role_memberships.sql`, que revoca `supabase_admin` y `service_role` de `authenticator`;
   4. aplica `backend/supabase/migrations/*.sql` por orden de nombre:
      - `20261122000000_turno_sync.sql`: crea el rol `turno_owner` y el esquema `turno`, las tablas con RLS activada y forzada, la validación y las RPC `SECURITY DEFINER`, y concede la ejecución a `authenticated`;
      - `20261124000000_hosting_privileges.sql`: revoca de `anon` y `service_role` lo que les daban los privilegios por defecto de la imagen.
   5. Cada archivo se ejecuta en **una sola transacción** y queda registrado con su sha256.
4. **Verificación:** `docker exec -i turnocontrol-db psql -U supabase_admin -d postgres -At < tests/sql/verify_deployment.sql`. Todas las líneas deben terminar en `|t`.

**Reglas:**
- Las migraciones **no se montan** en `docker-entrypoint-initdb.d`. Nada se aplica solo al arrancar, y ningún arranque borra ni sustituye la base.
- Sin `--apply`, el script solo enumera lo pendiente.
- Fuera de la primera instalación, el script exige una copia de menos de 2 horas que supere la verificación.
- Las migraciones son **solo de añadir**: un archivo ya aplicado cuyo contenido cambie detiene el script. Los cambios van siempre en un archivo nuevo.
- No hay `DROP SCHEMA` ni recreaciones como forma de actualizar.

**Limitaciones de la vuelta atrás:**
- No hay migraciones «down». Si una migración falla, su transacción no deja nada a medias.
- Para deshacer una migración ya confirmada se usa:
  - **una migración nueva que la corrija**, que es lo preferible;
  - o, como último recurso, restaurar la copia previa, lo que **pierde** los cambios posteriores a esa copia (`BACKUP_PLAN.md`).
- Una migración que falla o se interrumpe no deja nada a medias: verificado en 5F.1C etapa 0, tanto con un error al final como terminando el backend a mitad.
- `create role turno_owner` no se puede repetir: la migración base solo es válida una vez por clúster. El registro `turno_deploy` lo garantiza.

**Recuperación de la base:** `BACKUP_PLAN.md` §5.

**Verificado en la etapa 0:** `create schema … authorization turno_owner`, `alter … owner to turno_owner` y `grant references on auth.users` funcionan con `supabase_admin` en PG 17.6 (amd64). Repetido con éxito en ARM64 en la etapa 1.

## 4. HTTPS y proxy inverso (Caddy)
El servidor ya tiene un proxy inverso de otro proyecto (`<proxy-existente>`) que gestiona los puertos web públicos y su certificado. **Este plan no lo modifica.**

| | Opción A: ampliar el proxy existente | Opción B: proxy propio de TurnoControl |
|---|---|---|
| Cambios | Un bloque nuevo en su configuración y unir su contenedor a la red `turnocontrol_edge` | Un contenedor proxy nuevo |
| Puertos | Ninguno nuevo | Necesita su propio puerto público, porque los existentes ya están ocupados. Eso supone **abrir un puerto** en el cortafuegos y en las reglas de red del proveedor. |
| Certificado | El proxy existente lo obtiene para el subdominio nuevo | ACME necesita el puerto 80 o 443, que ya están ocupados, o un reto DNS con API del proveedor de DNS |
| Riesgo para otros servicios | Bajo si se valida antes y se recarga sin cortes, aunque crea una dependencia entre proyectos | Nulo en tiempo de ejecución, pero alto en complejidad (puertos y certificados) |
| Complejidad | Baja | Alta |

**Recomendación: opción A, una vez aprobada.** Procedimiento seguro, para la fase 5F.1C o posterior:
1. Hacer una copia del archivo de configuración actual del proxy en el servidor (fuera de Git).
2. Preparar un archivo **candidato** con el bloque nuevo, sin tocar el original:
   ```
   {$TURNO_PUBLIC_HOST} {
       encode gzip
       request_body {
           max_size 2MB
       }
       reverse_proxy turnocontrol-gateway:8000
   }
   ```
   `TURNO_PUBLIC_HOST` es el nombre que apruebe el propietario. No se inventa ningún valor.
3. Validarlo **con la misma imagen y versión** que el proxy existente, en un contenedor desechable y sin red, por ejemplo `docker run --rm --network none -v <candidato>:/etc/caddy/Caddyfile:ro <imagen-del-proxy> caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile`.
4. Unir el proxy a la red: `docker network connect turnocontrol_edge <contenedor-del-proxy>`. Es un cambio en caliente, sin reiniciar, que da conectividad sin publicar nada nuevo.
5. Sustituir la configuración y **recargar sin cortes** (`caddy reload`, nunca reiniciar). Si falla, Caddy mantiene la configuración anterior.
6. Comprobar el subdominio nuevo **y** los servicios existentes.
7. Hacerlo persistente en el compose del otro proyecto (red externa `turnocontrol_edge`). Es un cambio en otro proyecto y requiere la aprobación de su responsable.
- **Vuelta atrás:** restaurar la configuración copiada, `caddy reload` y `docker network disconnect turnocontrol_edge <contenedor-del-proxy>`.
- **Puerto HTTPS no estándar:** si el HTTPS público no está en el 443, la verificación de App Links de Android no funciona (`assetlinks.json` tiene que servirse en el 443). Los enlaces de correo tendrían que usar el esquema propio de la app.
- **Cabeceras:** el proxy de borde **sustituye** la cabecera `X-Forwarded-For` del cliente. Así se comporta Caddy por defecto cuando no confía en proxies anteriores. El gateway confía en ella solo si llega desde redes privadas. Hay que comprobarlo en la etapa 5, porque de ello dependen los límites por IP de GoTrue.

## 5. Autenticación por correo
Ya preparado en `compose.yaml`:
- confirmación obligatoria (`TURNO_MAILER_AUTOCONFIRM=false`);
- alta cerrada por defecto;
- contraseña de al menos 10 caracteres, que GoTrue guarda con bcrypt (nunca en claro);
- rotación de refresh tokens;
- reautenticación para cambiar la contraseña;
- cambio de correo con doble confirmación;
- límites: 10 correos por hora, máximo uno cada 60 s, y límites por IP para la verificación, los OTP y la renovación de tokens;
- redirecciones solo a la lista exacta `TURNO_REDIRECT_ALLOW_LIST`, vacía hasta que se apruebe.

**Enumeración de cuentas:** con la confirmación activada, GoTrue responde igual al alta de un correo que ya existe, y la recuperación siempre responde lo mismo. La app debe mostrar «si existe una cuenta, recibirás un correo». **Hay que comprobarlo en staging.**

**Flujo previsto (`docs/ARQUITECTURA_0.3.0_AUTENTICACION.md`):**
- PKCE, con el enlace que abre la app. Lo preferible es un App Link https verificado; la alternativa es el esquema propio que documenta la arquitectura.
- Recuperación de contraseña mediante enlace PKCE, y después `updatePassword`.

**Decisiones del propietario:**
- proveedor SMTP;
- remitente y dominio del remitente (SPF/DKIM);
- textos de los correos;
- redirecciones exactas.

No se ha configurado ningún SMTP ni se ha enviado ningún correo.

**Borrado de cuenta:** GoTrue no tiene un endpoint para que el propio usuario se borre. Opciones:
1. una RPC `SECURITY DEFINER` propia que borre la fila de `auth.users` de la cuenta de la sesión (los datos caen en cascada);
2. un pequeño endpoint del servidor que use la API de administración;
3. un procedimiento manual mientras haya pocos usuarios.

Es una **decisión pendiente**, que necesitará sus propias pruebas.

## 6. Google (preparado y desactivado)
- `GOTRUE_EXTERNAL_GOOGLE_ENABLED` está fijado a `"false"` en `compose.yaml`. Activarlo supone un cambio revisado de ese archivo.
- **Requisitos:**
  - un proyecto en Google Cloud del propietario;
  - un cliente OAuth web y otro Android, este con el SHA-1 de la firma de la app;
  - la pantalla de consentimiento;
  - la lista de IDs de cliente en `TURNO_GOOGLE_CLIENT_IDS`.
- **Flujo nativo:** Credential Manager devuelve un ID token y GoTrue lo intercambia (`grant_type=id_token`) comprobando el nonce. **No se debe activar `SKIP_NONCE_CHECK`**, porque la app ya envía el nonce.
- El flujo web (`/authorize` y `/callback`) sigue bloqueado en el gateway y no lo necesita.
- **No está probado:** que funcione exige credenciales reales de Google.

## 7. Compatibilidad con Android
| Necesidad | Cómo la cubre la API | Estado |
|---|---|---|
| Modo invitado | Sin red ni cuenta. La API no interviene. | Ya existe en la app |
| Correo y contraseña | `/auth/v1/signup`, `/token?grant_type=password`, `/verify` y `/recover` | Preparado; por probar |
| Google | `/auth/v1/token?grant_type=id_token` | Desactivado |
| Renovación de sesión | `/auth/v1/token?grant_type=refresh_token`, con rotación | Preparado |
| Cambio de cuenta | Cada cuenta tiene su sesión y su base local (`AccountStorageCoordinator`). La API es la misma. | Cliente listo |
| Sincronización sin conexión, conflictos e idempotencia | `sync_push` (con `operation_id` y `base_version`) y `sync_pull` (cursor por `seq`) | SQL probado en local |
| Primer dispositivo y vinculación del invitado | El cliente sube su outbox con `base_version` nulo. Los conflictos se resuelven en el cliente. | Cliente listo |
| Varios dispositivos | `device_id` por llamada y orden por `seq` | SQL probado en local |
| Borrado de cuenta | Sección 5 | Pendiente de decidir |

En esta fase no se ha cambiado nada en Android: sin permiso `INTERNET`, sin bibliotecas de red y sin compilar ninguna APK.

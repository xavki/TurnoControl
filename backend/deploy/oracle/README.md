# Backend autoalojado de TurnoControl: preparación (fase 5F.1B)

**No está desplegado.** Esta carpeta prepara una instalación mínima compatible con Supabase en un VPS ARM64 propio, con Docker Compose. Ningún archivo contiene credenciales ni datos de un servidor concreto. Desplegarla requiere la aprobación expresa del propietario (fase 5F.1C).

## Contenido
| Archivo | Qué es |
|---|---|
| `compose.yaml` | Los cuatro servicios, con redes, volúmenes, límites, health checks y registros. |
| `.env.example` | Plantilla de configuración. Solo contiene marcadores. |
| `backup.env.example` | Plantilla de configuración de las copias. No incluye ninguna contraseña de la base de datos. |
| `.gitignore` | Impide que se suban `.env`, `backup.env`, copias y claves. |
| `gateway/Caddyfile` | Gateway interno. Deniega por defecto y solo deja pasar la autenticación y las dos RPC. |
| `db/init/99-turno-roles.sql` | Contraseñas propias de `authenticator` y `supabase_auth_admin`. Solo se ejecuta en el primer arranque. |
| `db/hardening/10_api_role_memberships.sql` | Impide que la conexión de la API asuma `service_role` o `supabase_admin`. |
| `scripts/` | `preflight`, `generate-secrets`, `migrate`, `backup`, `verify-backup`, `restore-drill` y `healthcheck`. |
| `tests/static-checks.sh` | Validación local sin contenedores ni red. |
| `tests/api-security-test.sh` | Pruebas HTTP de seguridad contra un staging (60 comprobaciones). |
| `tests/resilience-test.sh` | Desconexiones, concurrencia entre cuentas y reinicio de PostgREST con tráfico. |
| `tests/log-privacy-scan.sh` | Busca secretos, JWT, correos y contenido sincronizado en los registros. |
| `tests/compose.validation.yaml` | Override que renombra todos los contenedores, redes y volúmenes para una validación local. |
| `compose.staging.yaml` | Override del staging privado: nombres `turnocontrol-staging-*`, gateway solo en `127.0.0.1`. |
| `tests/data-integrity-test.sh` | Turnos normal, partido, nocturno y con horas extra, vacaciones, libre y baja; días inválidos, conflicto, lápida, aislamiento y paginación. |
| `tests/session-lifecycle-test.sh` | Rotación y reutilización de refresh tokens y caducidad real del token de acceso. |
| `tests/sql/*.sql` | Consultas de verificación de solo lectura. |
| `SECURITY.md` | Secretos, exposición, registros, privacidad y aspectos legales. |
| `DEPLOYMENT_PLAN.md` | Requisitos previos, etapas, migración, Caddy, correo, Google y Android. |
| `BACKUP_PLAN.md` | Copias, restauración y recuperación ante desastres. |
| `MONITORING.md` | Supervisión ligera y mantenimiento. |
| `ROLLBACK_PLAN.md` | Cómo volver atrás en cada etapa. |
| `VALIDATION_CHECKLIST.md` | Qué hay que comprobar y en qué estado está cada punto. |

## Arquitectura
```
Android (futuro) ──HTTPS──▶ proxy inverso aprobado (TLS)
                                 │  red turnocontrol_edge (o túnel SSH a 127.0.0.1)
                                 ▼
                        turnocontrol-gateway (Caddy, :8000; publicado solo en 127.0.0.1)
                          │ /auth/v1/* (lista cerrada)        │ POST /rest/v1/rpc/sync_push|sync_pull
                          ▼                                   ▼
                 turnocontrol-auth (GoTrue)           turnocontrol-rest (PostgREST)
                          │        └─ red turnocontrol_egress (SMTP; Google si se activa)
                          └──────────────┬────────────────────┘
                                         ▼  red turnocontrol_internal (internal: true)
                                 turnocontrol-db (supabase/postgres)
                                 volúmenes turnocontrol_pgdata y turnocontrol_db_config
```
- **Proyecto Compose propio** (`turnocontrol`), con contenedores, redes y volúmenes con prefijo `turnocontrol`. No usa ninguna red, volumen ni base de datos de otros proyectos del servidor.
- **PostgreSQL** no publica ningún puerto. Solo es accesible desde la red interna.
- **El gateway** publica un único puerto, en `127.0.0.1`.
- **Componentes que no se incluyen:** Studio, postgres-meta, Realtime, Storage, imgproxy, Edge Runtime, Supavisor, Logflare y Vector. Ninguno es necesario para la autenticación ni para las dos RPC. La administración se hace por SSH con `psql` dentro del contenedor.

## Versiones (verificadas el 2026-11-24 en Docker Hub)
| Servicio | Imagen | arm64 | Tamaño arm64 comprimido |
|---|---|---|---|
| Base de datos | `supabase/postgres:17.6.1.136@sha256:f371b5f3…` | sí | 364 MB |
| Autenticación | `supabase/gotrue:v2.198.0@sha256:ba8c0c82…` | sí | 29 MB |
| REST | `postgrest/postgrest:v14.18@sha256:c7cd7e26…` | sí | 163 MB |
| Gateway | `caddy:2.11.9-alpine@sha256:003a9780…` | sí | 24 MB |

- Las tres primeras son **las mismas versiones que fija el `docker-compose.yml` oficial de Supabase** (rama `master`, consultado el 2026-11-24). Los digests completos están en `compose.yaml`.
- Que la imagen exista para arm64 no garantiza que todo funcione junto. La compatibilidad real entre estas versiones y la migración de TurnoControl **no está verificada** hasta que se arranquen (ver `VALIDATION_CHECKLIST.md`).

### Diferencias respecto a la pila oficial
| Oficial | Aquí | Motivo |
|---|---|---|
| Gateway Envoy (o Kong) con traducción de claves `sb_publishable_`/`sb_secret_` | Caddy con una lista cerrada de rutas | Configuración de unas 60 líneas, fácil de revisar, y misma tecnología que el proxy de borde. Solo admite la clave anon heredada (JWT). |
| Una sola contraseña para todos los roles | Contraseñas distintas para `supabase_admin`, `authenticator` y `supabase_auth_admin` | Mínimo privilegio. |
| `authenticator` puede asumir `service_role` (en `supabase/postgres:17.6.1.136`; no `supabase_admin`, aunque el init script lo declara) | Solo puede asumir `anon` y `authenticated` (`db/hardening`, verificado con pruebas negativas directas) | Un secreto JWT filtrado no debe dar acceso que se salte el RLS a través de la API. |
| PostgREST con rol anónimo | Sin `PGRST_DB_ANON_ROLE`, y `jwt-aud` obligatorio | Ninguna RPC se puede llamar sin sesión. |
| Studio, Realtime, Storage, etc. | No se incluyen | No se necesitan. |

## Recursos [C]
| Servicio | Límite de memoria | CPU (límite) | Uso esperado en reposo [S] |
|---|---|---|---|
| db | 2 GB (`shared_buffers` 512 MB) | 2,0 | 300 MB – 1 GB |
| auth | 256 MB | 0,5 | ~30–60 MB |
| rest | 256 MB | 0,5 | ~30–80 MB |
| gateway | 128 MB | 0,5 | ~15–30 MB |
| **Total** | **≈ 2,6 GB como techo** | **3,5 como techo** (los límites no son reservas) | **≈ 0,4–1,2 GB** |

- **Disco:** unos 580 MB comprimidos, que ocupan unos 1,5–2 GB descomprimidos [S].
- **Datos [C]:** con supuestos pesimistas, unos 2 MB por usuario y año, contando historial e idempotencia.
- **Copias:** ver `BACKUP_PLAN.md`.

## Validación local
```bash
bash backend/deploy/oracle/tests/static-checks.sh   # no arranca nada ni descarga nada
```

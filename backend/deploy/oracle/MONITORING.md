# Supervisión y mantenimiento (propuesta)

Sin agentes ni infraestructura adicional. Todo se hace con `scripts/healthcheck.sh`, que es de solo lectura y escribe una línea `OK`/`ALERT` por comprobación. Termina con código 1 si hay alguna alerta, y está pensado para ejecutarse cada 15 min desde cron (**no instalado**). Cómo notificar las alertas (correo, webhook…) es decisión del propietario.

## Qué comprueba
| Señal | Cómo | Umbral |
|---|---|---|
| Estado de los contenedores | `docker inspect`: estado, salud, reinicios y si los terminó el OOM killer | Todos running y healthy, como mucho 3 reinicios y ningún OOM |
| PostgreSQL | `pg_isready` dentro del contenedor | Responde |
| Gateway | `GET /healthz` en el puerto de loopback | 200 |
| Autenticación | `GET /auth/v1/health` | 200 |
| API REST | `POST /rest/v1/rpc/sync_pull` **sin sesión** | 401/403: está activa **y** sigue denegando el acceso anónimo |
| Disco | `df /` | Por debajo del 80 % |
| Memoria | `free`, memoria disponible | Más de 2 GB |
| Volumen de datos | `docker system df -v` | Solo informativo |
| Copias | La copia local más reciente | Menos de 26 h |
| Copia externa | `scripts/backup-remote.sh status`: la más reciente se descarga y se verifica | Menos de 26 h e íntegra |
| Retención | `turno_ops.retention_runs` (solo recuentos), cuando se programe | Una ejecución en el periodo previsto |
| Certificado | `openssl s_client` contra `TURNO_PUBLIC_TLS_HOST`, cuando haya HTTPS | Más de 14 días |

## Revisar registros sin exponer datos
- Usa `docker logs --tail 200 --since 1h turnocontrol-auth` y equivalentes para `rest`, `gateway` y `db`.
- No copies en informes ni chats líneas con `Authorization`, `apikey`, tokens, correos completos ni cuerpos de petición.
- Nunca subas el nivel de registro de PostgreSQL ni de PostgREST en producción para depurar: esos registros incluirían SQL o peticiones con contenido sincronizado.
- No ejecutes `docker inspect` sin `--format`, porque muestra todas las variables de entorno, incluidas las contraseñas.

## Detectar problemas
- **Copias fallidas:** el script de copias termina con un código distinto de cero y lo registra. Además, `healthcheck.sh` avisa si no hay una copia reciente.
- **Disco lleno:**
  - el umbral del 80 % avisa con margen;
  - los registros están limitados a 30 MB por contenedor;
  - las copias locales se purgan por retención.
  - Si el disco crece de forma anómala, revisa primero `docker system df` y el directorio de copias, sin borrar nada de otros proyectos.
- **Reinicios inesperados o OOM:** la alerta de reinicios y la de OOM. Hay que revisar los límites de `compose.yaml` antes de subirlos.

## Actualizaciones controladas
1. Lee las notas de la versión de la imagen nueva (GoTrue, PostgREST, `supabase/postgres` o Caddy).
2. Obtén el digest arm64 nuevo desde el registro, sin descargar la imagen.
3. Cambia la etiqueta y el digest en `compose.yaml`, en una rama y con revisión.
4. Prueba en staging: `static-checks.sh`, la etapa 0 si cambia la imagen de la base, `api-security-test.sh` y `verify_deployment.sql`.
5. En producción:
   1. copia verificada;
   2. `docker compose pull <servicio>` (con aprobación);
   3. `docker compose up -d <servicio>`, **de uno en uno**;
   4. `healthcheck.sh`.
6. Si falla, sigue `ROLLBACK_PLAN.md` §5.
- Una versión mayor de PostgreSQL es un proyecto aparte, con volcado y restauración.

**Frecuencia propuesta:**
- revisión mensual de versiones;
- parches de seguridad de GoTrue o PostgREST en menos de 7 días, siempre que pasen por staging.

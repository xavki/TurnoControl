#!/usr/bin/env bash
# Lightweight monitoring for the TurnoControl stack. Read-only. Prints one OK/ALERT line per check and exits 1 if any
# check alerts, so a scheduler (cron, not installed) can notify. Never prints environment values or request contents.
set -euo pipefail
. "$(dirname "$0")/lib.sh"
require_cmd docker df free curl
alerts=0
ok() { echo "OK     $1"; }
alert() { echo "ALERT  $1"; alerts=$((alerts + 1)); }

for c in "$TURNO_CONTAINER_PREFIX"-db "$TURNO_CONTAINER_PREFIX"-auth "$TURNO_CONTAINER_PREFIX"-rest "$TURNO_CONTAINER_PREFIX"-gateway; do
  state=$(docker inspect -f '{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}|{{.RestartCount}}|{{.State.OOMKilled}}' "$c" 2>/dev/null || echo "missing|none|0|false")
  IFS='|' read -r status health restarts oom <<< "$state"
  if [ "$status" = running ] && [ "$health" = healthy ]; then ok "$c en marcha y healthy"; else alert "$c estado=$status salud=$health"; fi
  [ "$restarts" -le 3 ] && ok "$c reinicios=$restarts" || alert "$c se ha reiniciado $restarts veces"
  [ "$oom" = false ] && ok "$c sin OOM" || alert "$c terminado por falta de memoria"
done

docker exec "$TURNO_DB_CONTAINER" pg_isready -U postgres -q && ok "PostgreSQL acepta conexiones" || alert "PostgreSQL no responde"

load_env_file "$TURNO_ENV_FILE"
port=${TURNO_GATEWAY_PORT:-18080}
code() { curl -s -o /dev/null -m 5 -w '%{http_code}' "$@" || echo 000; }
[ "$(code "http://127.0.0.1:$port/healthz")" = 200 ] && ok "gateway responde" || alert "gateway no responde"
[ "$(code -H "apikey: $TURNO_ANON_KEY" "http://127.0.0.1:$port/auth/v1/health")" = 200 ] && ok "Auth responde" || alert "Auth no responde"
# A request without a session must be refused (401/403), proving PostgREST is up AND still denies anonymous access.
case "$(code -X POST -H "apikey: $TURNO_ANON_KEY" -H 'Content-Type: application/json' -d '{"p_since":0,"p_limit":1}' "http://127.0.0.1:$port/rest/v1/rpc/sync_pull")" in
  401|403) ok "REST responde y deniega sin sesión";;
  *) alert "REST no deniega sin sesión o no responde";;
esac

# Use% is always the second-to-last column (the filesystem name may contain spaces).
disk=$(df -P / | awk 'NR==2{v=$(NF-1); gsub("%","",v); print v}')
[ "$disk" -lt 80 ] && ok "disco al ${disk}%" || alert "disco al ${disk}%"
mem=$(free -m | awk '/^Mem:/{print $7}')
[ "$mem" -gt 2048 ] && ok "RAM disponible ${mem} MB" || alert "RAM disponible solo ${mem} MB"
volume=$(docker system df -v --format '{{range .Volumes}}{{if eq .Name "turnocontrol_pgdata"}}{{.Size}}{{end}}{{end}}' 2>/dev/null || true)
[ -n "$volume" ] && ok "volumen de datos: $volume"

if [ -f "$TURNO_BACKUP_ENV_FILE" ]; then
  load_env_file "$TURNO_BACKUP_ENV_FILE"
  max=${TURNO_BACKUP_MAX_AGE_HOURS:-26}
  recent=$(find "${TURNO_BACKUP_DIR:?}" -maxdepth 1 -type f -name 'turnocontrol-db-*.dump.age' -mmin -$((max * 60)) 2>/dev/null | head -1)
  [ -n "$recent" ] && ok "copia de menos de ${max} h" || alert "no hay copia de menos de ${max} h"
fi

if [ -n "${TURNO_PUBLIC_TLS_HOST:-}" ]; then   # host:port of the public endpoint, set only once HTTPS exists
  end=$(echo | openssl s_client -connect "$TURNO_PUBLIC_TLS_HOST" -servername "${TURNO_PUBLIC_TLS_HOST%%:*}" 2>/dev/null | openssl x509 -noout -enddate 2>/dev/null | cut -d= -f2)
  if [ -n "$end" ] && [ $(( ($(date -d "$end" +%s) - $(date +%s)) / 86400 )) -gt 14 ]; then ok "certificado válido más de 14 días"; else alert "certificado caduca pronto o no se pudo leer"; fi
fi
[ "$alerts" -eq 0 ] && echo "ESTADO: correcto" || { echo "ESTADO: $alerts alertas"; exit 1; }

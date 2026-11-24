#!/usr/bin/env bash
# Read-only checks before the first `compose up` (phase 5F.1C). Changes nothing; prints only PASS/FAIL lines.
set -euo pipefail
. "$(dirname "$0")/lib.sh"
require_cmd docker ss awk df free
fails=0
check() { if eval "$2" >/dev/null 2>&1; then echo "PASS  $1"; else echo "FAIL  $1"; fails=$((fails + 1)); fi; }

load_env_file "$TURNO_ENV_FILE"
require_vars TURNO_POSTGRES_PASSWORD TURNO_AUTHENTICATOR_PASSWORD TURNO_AUTH_ADMIN_PASSWORD TURNO_JWT_SECRET TURNO_ANON_KEY \
  TURNO_API_EXTERNAL_URL TURNO_SITE_URL TURNO_GATEWAY_PORT
port=$TURNO_GATEWAY_PORT

check "arquitectura arm64/aarch64"            '[ "$(uname -m)" = aarch64 ]'
check "Docker Compose v2+ disponible"         'docker compose version'
check "compose.yaml válido con este .env"     'compose config --quiet'
check "puerto 127.0.0.1:$port libre"          '! ss -tlnH | awk "{print \$4}" | grep -Eq "(^|:)$port\$"'
for n in turnocontrol_internal turnocontrol_egress turnocontrol_edge; do
  check "red $n libre o ya de turnocontrol" '! docker network inspect '"$n"' >/dev/null 2>&1 || [ "$(docker network inspect -f "{{index .Labels \"com.docker.compose.project\"}}" '"$n"')" = turnocontrol ]'
done
for c in "$TURNO_CONTAINER_PREFIX"-db "$TURNO_CONTAINER_PREFIX"-auth "$TURNO_CONTAINER_PREFIX"-rest "$TURNO_CONTAINER_PREFIX"-gateway; do
  check "contenedor $c libre o ya de turnocontrol" '! docker inspect '"$c"' >/dev/null 2>&1 || [ "$(docker inspect -f "{{index .Config.Labels \"com.docker.compose.project\"}}" '"$c"')" = turnocontrol ]'
done
check "al menos 20 GB libres en /"            '[ "$(df -Pk / | awk "NR==2{print \$4}")" -gt 20971520 ]'
check "al menos 4 GB de RAM disponible"       '[ "$(free -m | awk "/^Mem:/{print \$7}")" -gt 4096 ]'
check ".env sin claves service_role"          '! grep -Eq "^TURNO_[A-Z_]*SERVICE[A-Z_]*=" "$TURNO_ENV_FILE"'
[ "$fails" -eq 0 ] && echo "PREFLIGHT: superado" || { echo "PREFLIGHT: $fails comprobaciones fallidas"; exit 1; }

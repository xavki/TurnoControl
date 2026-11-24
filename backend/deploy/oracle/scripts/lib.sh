#!/usr/bin/env bash
# Shared helpers for the TurnoControl deployment scripts. Sourced, never executed.
# Rules every script follows: never print secret values, never touch containers/volumes outside the `turnocontrol`
# Compose project, fail closed when configuration is missing.
set -euo pipefail

DEPLOY_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
BACKEND_DIR=$(cd "$DEPLOY_DIR/../.." && pwd)
# Production defaults. A validation run overrides all of them together (tests/compose.validation.yaml); the safety
# checks below still require the container to carry the matching Compose project label and the expected image.
TURNO_PROJECT=${TURNO_PROJECT:-turnocontrol}
TURNO_CONTAINER_PREFIX=${TURNO_CONTAINER_PREFIX:-turnocontrol}
TURNO_DB_CONTAINER=$TURNO_CONTAINER_PREFIX-db
TURNO_ENV_FILE=${TURNO_ENV_FILE:-$DEPLOY_DIR/.env}
TURNO_BACKUP_ENV_FILE=${TURNO_BACKUP_ENV_FILE:-$DEPLOY_DIR/backup.env}
TURNO_COMPOSE_OVERLAY=${TURNO_COMPOSE_OVERLAY:-}
TURNO_DB_IMAGE_PREFIX=supabase/postgres:

log() { printf '[%s] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }
require_cmd() { for c in "$@"; do command -v "$c" >/dev/null 2>&1 || die "falta el comando '$c'"; done; }

# Loads KEY=VALUE lines without executing the file (no `source`, so a crafted file cannot run commands).
load_env_file() {
  local file=$1 line key value
  [ -f "$file" ] || die "no existe $file"
  local mode; mode=$(stat -c '%a' "$file" 2>/dev/null || echo unknown)
  # NTFS (Git Bash on a developer PC) cannot represent 600; the check only means something on the Linux server.
  case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) mode=windows;; esac
  case "$mode" in 600|400|unknown|windows) ;; *) die "$file debe tener permisos 600 (tiene $mode)";; esac
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in ''|'#'*) continue;; esac
    key=${line%%=*}; value=${line#*=}
    [[ "$key" =~ ^TURNO_[A-Z0-9_]+$ ]] || die "clave no válida en $file"
    printf -v "$key" '%s' "$value"; export "${key?}"
  done < "$file"
}

require_vars() {
  local v
  for v in "$@"; do
    [ -n "${!v:-}" ] || die "falta $v"
    case "${!v}" in *'<'*|*'>'*) die "$v aún contiene un marcador de la plantilla";; esac
  done
}

compose() {
  if [ -n "$TURNO_COMPOSE_OVERLAY" ]; then
    docker compose -p "$TURNO_PROJECT" -f "$DEPLOY_DIR/compose.yaml" -f "$TURNO_COMPOSE_OVERLAY" --env-file "$TURNO_ENV_FILE" "$@"
  else
    docker compose -p "$TURNO_PROJECT" -f "$DEPLOY_DIR/compose.yaml" --env-file "$TURNO_ENV_FILE" "$@"
  fi
}

# Refuses to continue unless the target is THIS project's database container (never another project's).
assert_turno_db() {
  local info project image
  info=$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}|{{.Config.Image}}|{{.State.Running}}' "$TURNO_DB_CONTAINER" 2>/dev/null) \
    || die "no existe el contenedor $TURNO_DB_CONTAINER"
  project=${info%%|*}; image=${info#*|}; image=${image%%|*}
  [ "$project" = "$TURNO_PROJECT" ] || die "$TURNO_DB_CONTAINER no pertenece al proyecto $TURNO_PROJECT"
  case "$image" in "$TURNO_DB_IMAGE_PREFIX"*) ;; *) die "$TURNO_DB_CONTAINER no usa la imagen esperada";; esac
  [ "${info##*|}" = true ] || die "$TURNO_DB_CONTAINER no está en marcha"
}

# psql as supabase_admin over the container's local socket (trust for that role inside the container only).
db_psql() { docker exec -i "$TURNO_DB_CONTAINER" psql -U supabase_admin -d postgres -v ON_ERROR_STOP=1 -X -q "$@"; }

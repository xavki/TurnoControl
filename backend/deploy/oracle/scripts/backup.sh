#!/usr/bin/env bash
# Daily encrypted logical backup of the TurnoControl database ONLY. Independent of any other backup on the server.
#  - Source: container turnocontrol-db (project label and image are checked; any other database is refused).
#  - pg_dump -Fc streams straight into `age`: no plaintext copy ever touches the disk.
#  - Also dumps the cluster roles WITHOUT passwords (turno_owner must exist before a restore).
#  - Off-server copy with rclone is REQUIRED unless TURNO_BACKUP_ALLOW_LOCAL_ONLY=1. Remote retention = bucket lifecycle.
#  - Local retention deletes only files matching turnocontrol-db-*.dump.age / *.roles.sql.age in TURNO_BACKUP_DIR.
# Proposed schedule (not installed): 04:00 UTC daily. See BACKUP_PLAN.md.
set -euo pipefail
. "$(dirname "$0")/lib.sh"
require_cmd docker age sha256sum find
load_env_file "$TURNO_BACKUP_ENV_FILE"
require_vars TURNO_BACKUP_DIR TURNO_BACKUP_AGE_RECIPIENT TURNO_BACKUP_LOCAL_RETENTION_DAYS
case "$TURNO_BACKUP_DIR" in /*turnocontrol*) ;; *) die "TURNO_BACKUP_DIR debe ser absoluta y contener 'turnocontrol'";; esac
case "$TURNO_BACKUP_AGE_RECIPIENT" in age1*) ;; *) die "TURNO_BACKUP_AGE_RECIPIENT debe ser una clave pública age1...";; esac
[[ "$TURNO_BACKUP_LOCAL_RETENTION_DAYS" =~ ^[0-9]+$ ]] && [ "$TURNO_BACKUP_LOCAL_RETENTION_DAYS" -ge 2 ] || die "retención local no válida (mínimo 2 días)"
if [ -z "${TURNO_BACKUP_RCLONE_DEST:-}" ] && [ "${TURNO_BACKUP_ALLOW_LOCAL_ONLY:-0}" != 1 ]; then
  die "falta TURNO_BACKUP_RCLONE_DEST (o TURNO_BACKUP_ALLOW_LOCAL_ONLY=1 de forma explícita)"
fi
[ -n "${TURNO_BACKUP_RCLONE_DEST:-}" ] && require_cmd rclone
assert_turno_db

umask 077
mkdir -p "$TURNO_BACKUP_DIR"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
base="$TURNO_BACKUP_DIR/turnocontrol-db-$stamp"
cleanup() { rm -f -- "$base.dump.age.partial" "$base.roles.sql.age.partial"; }
trap cleanup EXIT

log "volcado lógico de la base de TurnoControl"
docker exec "$TURNO_DB_CONTAINER" pg_dump -U supabase_admin -d postgres -Fc --no-password \
  | age -r "$TURNO_BACKUP_AGE_RECIPIENT" -o "$base.dump.age.partial"
docker exec "$TURNO_DB_CONTAINER" pg_dumpall -U supabase_admin --roles-only --no-role-passwords \
  | age -r "$TURNO_BACKUP_AGE_RECIPIENT" -o "$base.roles.sql.age.partial"
# pipefail makes either failed pipeline abort before the rename below.
mv -- "$base.dump.age.partial" "$base.dump.age"
mv -- "$base.roles.sql.age.partial" "$base.roles.sql.age"
(cd "$TURNO_BACKUP_DIR" && sha256sum "$(basename "$base").dump.age" "$(basename "$base").roles.sql.age" > "$(basename "$base").sha256")
"$DEPLOY_DIR/scripts/verify-backup.sh" "$base.dump.age" >/dev/null || die "la copia recién creada no supera la verificación"

if [ -n "${TURNO_BACKUP_RCLONE_DEST:-}" ]; then
  for ext in dump.age roles.sql.age sha256; do rclone copyto --immutable "$base.$ext" "$TURNO_BACKUP_RCLONE_DEST/$(basename "$base").$ext"; done
  log "copia externa subida"
else
  log "AVISO: solo copia local (TURNO_BACKUP_ALLOW_LOCAL_ONLY=1)"
fi

# Local retention: explicit name patterns, one directory level, regular files only.
find "$TURNO_BACKUP_DIR" -maxdepth 1 -type f \( -name 'turnocontrol-db-*.dump.age' -o -name 'turnocontrol-db-*.roles.sql.age' -o -name 'turnocontrol-db-*.sha256' \) \
  -mtime +"$TURNO_BACKUP_LOCAL_RETENTION_DAYS" -print -delete | sed 's/^/eliminada copia local antigua: /' >&2
log "copia terminada: $(basename "$base")"

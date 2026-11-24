#!/usr/bin/env bash
# Off-server backup destination adapters (phase 5F.1D). Sourced by backup.sh and backup-remote.sh, never executed.
#
#   TURNO_BACKUP_DEST_TYPE = none | dir | rclone
#     none    no off-server copy; only allowed with TURNO_BACKUP_ALLOW_LOCAL_ONLY=1
#     dir     a mounted directory (NAS, external disk, a second VM's mount) — also the fake storage used by the tests
#     rclone  any rclone remote, e.g. an S3-compatible bucket such as Oracle Object Storage: <remote>:<bucket>/<prefix>
#   TURNO_BACKUP_DEST      the directory (dir) or the rclone path (rclone)
#   TURNO_BACKUP_DEST_ID   expected content of the marker file `.turnocontrol-backup-destination` that the owner puts at
#                          the root of the destination ONCE when provisioning it. Any other destination is refused, so a
#                          typo or a reused credential can never write TurnoControl backups somewhere else.
# Upload protocol: copy to `<name>.partial`, verify the REMOTE sha256 against the local file, then rename to `<name>`.
# A crash leaves at most a `.partial` (never a truncated final file); the next run removes stale `turnocontrol-db-*.partial`.
# Production credential (documented in BACKUP_PLAN.md): write-only on the bucket; retention by bucket lifecycle rules.
# Backward compatible: TURNO_BACKUP_RCLONE_DEST without TURNO_BACKUP_DEST_TYPE means type rclone.

set -euo pipefail
MARKER=.turnocontrol-backup-destination
dest_type() {
  if [ -n "${TURNO_BACKUP_DEST_TYPE:-}" ]; then echo "$TURNO_BACKUP_DEST_TYPE"
  elif [ -n "${TURNO_BACKUP_RCLONE_DEST:-}" ]; then echo rclone
  else echo none; fi
}
dest_path() { if [ -n "${TURNO_BACKUP_DEST:-}" ]; then echo "$TURNO_BACKUP_DEST"; else echo "${TURNO_BACKUP_RCLONE_DEST:-}"; fi; }

# Fails (die) unless the destination is reachable AND carries the expected marker. Prints nothing secret.
dest_check() {
  local t p id; t=$(dest_type); p=$(dest_path); id=${TURNO_BACKUP_DEST_ID:-}
  case "$t" in
    none) [ "${TURNO_BACKUP_ALLOW_LOCAL_ONLY:-0}" = 1 ] || die "sin destino externo (TURNO_BACKUP_DEST_TYPE) ni TURNO_BACKUP_ALLOW_LOCAL_ONLY=1"; return 0;;
    dir|rclone) ;;
    *) die "TURNO_BACKUP_DEST_TYPE no válido: $t";;
  esac
  [ -n "$p" ] || die "falta TURNO_BACKUP_DEST"
  [ -n "$id" ] || die "falta TURNO_BACKUP_DEST_ID (contenido esperado del marcador del destino)"
  case "$id" in *[!A-Za-z0-9._-]*) die "TURNO_BACKUP_DEST_ID solo admite letras, números, punto, guion y guion bajo";; esac
  local found
  if [ "$t" = dir ]; then
    case "$p" in /*) ;; *) [ "${p:1:1}" = ":" ] || die "TURNO_BACKUP_DEST debe ser una ruta absoluta";; esac
    [ -d "$p" ] || die "el destino no existe o no es un directorio"
    [ -f "$p/$MARKER" ] || die "el destino no tiene el marcador $MARKER: no es un destino de copias de TurnoControl"
    found=$(tr -d '\r\n' < "$p/$MARKER")
  else
    require_cmd rclone
    found=$(rclone cat "$p/$MARKER" 2>/dev/null | tr -d '\r\n') || die "no se pudo leer el marcador del destino rclone"
  fi
  [ "$found" = "$id" ] || die "el marcador del destino no coincide con TURNO_BACKUP_DEST_ID: destino equivocado, no se sube nada"
}

# Removes only stale partial uploads of TurnoControl backups (older than 60 min). Never touches complete backups.
dest_clean_partials() {
  local t p; t=$(dest_type); p=$(dest_path)
  case "$t" in
    dir) find "$p" -maxdepth 1 -type f -name 'turnocontrol-db-*.partial' -mmin +60 -print -delete | sed 's/^/subida parcial antigua eliminada: /' >&2 || true;;
    rclone) rclone delete "$p" --include 'turnocontrol-db-*.partial' --min-age 1h --max-depth 1 >/dev/null 2>&1 || true;;
  esac
}

# Uploads one local file under its basename with the partial + remote-hash + rename protocol.
dest_put() {
  local f=$1 t p name sum rsum; t=$(dest_type); p=$(dest_path); name=$(basename "$f")
  case "$name" in turnocontrol-db-*) ;; *) die "nombre inesperado para subir: $name";; esac
  sum=$(sha256sum "$f" | cut -d' ' -f1)
  if [ "$t" = dir ]; then
    [ ! -e "$p/$name" ] || die "ya existe $name en el destino; no se sobrescribe"
    [ "${TURNO_BACKUP_FAULT:-}" = upload ] && { head -c 100 "$f" > "$p/$name.partial"; die "fallo simulado durante la subida (TURNO_BACKUP_FAULT)"; }
    cp -- "$f" "$p/$name.partial"
    rsum=$(sha256sum "$p/$name.partial" | cut -d' ' -f1)
    [ "$rsum" = "$sum" ] || die "la suma remota de $name no coincide; se deja el .partial para investigar"
    mv -- "$p/$name.partial" "$p/$name"
  else
    if rclone lsf "$p/$name" 2>/dev/null | grep -q .; then die "ya existe $name en el destino; no se sobrescribe"; fi
    rclone copyto "$f" "$p/$name.partial"
    rsum=$(rclone cat "$p/$name.partial" | sha256sum | cut -d' ' -f1)
    [ "$rsum" = "$sum" ] || die "la suma remota de $name no coincide; se deja el .partial para investigar"
    rclone moveto "$p/$name.partial" "$p/$name"
  fi
}

# Lists complete backup sets in the destination (base names, oldest first).
dest_list_sets() {
  local t p; t=$(dest_type); p=$(dest_path)
  if [ "$t" = dir ]; then find "$p" -maxdepth 1 -type f -name 'turnocontrol-db-*.dump.age' -printf '%f\n' | sed 's/\.dump\.age$//' | sort
  else rclone lsf "$p" --include 'turnocontrol-db-*.dump.age' --max-depth 1 | sed 's/\.dump\.age$//' | sort; fi
}

# Downloads one set (dump, roles, sha256) into a local directory.
dest_get_set() {
  local base=$1 out=$2 t p ext; t=$(dest_type); p=$(dest_path)
  case "$base" in turnocontrol-db-[0-9]*Z) ;; *) die "nombre de copia no válido";; esac
  for ext in dump.age roles.sql.age sha256; do
    if [ "$t" = dir ]; then cp -- "$p/$base.$ext" "$out/$base.$ext"; else rclone copyto "$p/$base.$ext" "$out/$base.$ext"; fi
  done
}

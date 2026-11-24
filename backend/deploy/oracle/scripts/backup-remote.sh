#!/usr/bin/env bash
# Off-server backup operations (phase 5F.1D). Uses the destination configured in backup.env (see lib-backup-dest.sh).
#   scripts/backup-remote.sh status [--max-age-hours N]  # newest set age + full integrity check of that set (download)
#   scripts/backup-remote.sh fetch <set|latest> <dir>     # download a set (dump, roles, sha256) and verify it, e.g. to
#                                                         # restore on a NEW server after losing the VPS
#   scripts/backup-remote.sh prune --days N [--apply]     # dir adapter ONLY; dry run by default. For buckets, retention
#                                                         # is enforced by lifecycle rules and the VPS credential is
#                                                         # write-only, so this command refuses rclone destinations.
# Never prints secrets, never touches files that are not turnocontrol-db-* sets.
set -euo pipefail
. "$(dirname "$0")/lib.sh"
. "$(dirname "$0")/lib-backup-dest.sh"
require_cmd sha256sum find
load_env_file "$TURNO_BACKUP_ENV_FILE"
cmd=${1:?uso: backup-remote.sh status|fetch|prune ...}; shift
[ "$(dest_type)" != none ] || die "no hay destino externo configurado"
# prune is refused for buckets BEFORE touching the destination (their retention belongs to lifecycle rules).
[ "$cmd" != prune ] || [ "$(dest_type)" = dir ] || die "prune solo para el adaptador dir; en un bucket la retención la aplican sus reglas de ciclo de vida"
dest_check

case "$cmd" in
  status)
    max=${TURNO_BACKUP_MAX_AGE_HOURS:-26}; [ "${1:-}" = --max-age-hours ] && max=${2:?}
    newest=$(dest_list_sets | tail -1)
    [ -n "$newest" ] || { echo "ALERT  no hay ninguna copia completa en el destino"; exit 1; }
    stamp=${newest#turnocontrol-db-}
    age_h=$(( ($(date -u +%s) - $(date -u -d "$(echo "$stamp" | sed -E 's/^(....)(..)(..)T(..)(..)(..)Z$/\1-\2-\3 \4:\5:\6/')" +%s)) / 3600 ))
    tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
    dest_get_set "$newest" "$tmp"
    if "$DEPLOY_DIR/scripts/verify-backup.sh" "$tmp/$newest.dump.age" >/dev/null 2>&1; then integ=OK; else integ=FALLO; fi
    echo "copias completas en el destino: $(dest_list_sets | wc -l) · más reciente: $newest (hace ${age_h} h) · integridad: $integ"
    [ "$age_h" -le "$max" ] && [ "$integ" = OK ] || { echo "ALERT  copia externa antigua o dañada"; exit 1; }
    echo "OK     copia externa reciente e íntegra";;
  fetch)
    which=${1:?falta la copia (nombre o latest)}; out=${2:?falta el directorio de salida}
    [ "$which" = latest ] && which=$(dest_list_sets | tail -1)
    [ -n "$which" ] || die "no hay copias en el destino"
    mkdir -p "$out"; umask 077
    dest_get_set "$which" "$out"
    "$DEPLOY_DIR/scripts/verify-backup.sh" "$out/$which.dump.age" || die "la copia descargada no supera la verificación"
    log "descargada y verificada: $which";;
  prune)
    [ "$(dest_type)" = dir ] || die "prune solo para el adaptador dir; en un bucket la retención la aplican sus reglas de ciclo de vida"
    [ "${1:-}" = --days ] || die "uso: prune --days N [--apply]"
    days=${2:?}; [[ "$days" =~ ^[0-9]+$ ]] && [ "$days" -ge 7 ] || die "--days debe ser un número ≥ 7"
    apply=0; [ "${3:-}" = --apply ] && apply=1
    p=$(dest_path); total=$(dest_list_sets | wc -l)
    mapfile -t old < <(find "$p" -maxdepth 1 -type f -name 'turnocontrol-db-*.dump.age' -mtime +"$days" -printf '%f\n' | sed 's/\.dump\.age$//' | sort)
    [ "${#old[@]}" -lt "$total" ] || die "la retención borraría TODAS las copias; se cancela"
    for base in "${old[@]}"; do
      if [ "$apply" = 1 ]; then rm -f -- "$p/$base.dump.age" "$p/$base.roles.sql.age" "$p/$base.sha256"; echo "eliminada: $base"
      else echo "se eliminaría: $base"; fi
    done
    echo "${#old[@]} de $total copias $([ "$apply" = 1 ] && echo eliminadas || echo 'elegibles (simulación)')";;
  *) die "orden desconocida: $cmd";;
esac

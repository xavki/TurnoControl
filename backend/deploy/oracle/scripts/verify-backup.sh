#!/usr/bin/env bash
# Structural check of one backup set, without the private key: checksums match, files are age-encrypted, not empty.
# It does NOT prove the dump restores; only scripts/restore-drill.sh does (BACKUP_PLAN.md).
#   usage: scripts/verify-backup.sh <.../turnocontrol-db-YYYYMMDDTHHMMSSZ.dump.age>
set -euo pipefail
. "$(dirname "$0")/lib.sh"
require_cmd sha256sum head
dump=${1:?uso: verify-backup.sh <archivo .dump.age>}
case "$(basename "$dump")" in turnocontrol-db-*.dump.age) ;; *) die "nombre de archivo inesperado";; esac
dir=$(dirname "$dump"); base=$(basename "$dump" .dump.age)
for f in "$dir/$base.dump.age" "$dir/$base.roles.sql.age" "$dir/$base.sha256"; do [ -s "$f" ] || die "falta o está vacío: $(basename "$f")"; done
(cd "$dir" && sha256sum --quiet -c "$base.sha256") || die "suma de comprobación incorrecta"
for f in "$dir/$base.dump.age" "$dir/$base.roles.sql.age"; do
  [ "$(head -c 21 "$f")" = "age-encryption.org/v1" ] || die "$(basename "$f") no tiene cabecera age"
done
echo "VERIFICADA (estructura): $base"

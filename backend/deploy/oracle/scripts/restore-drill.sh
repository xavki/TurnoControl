#!/usr/bin/env bash
# Monthly restore drill: proves a backup actually restores. Uses ONE disposable container with NO network
# (turnocontrol-restore-drill), the same pinned Postgres image as compose.yaml, and removes only that container at the end.
# Never touches turnocontrol-db or any other database. The age private key is read from a file given at run time and
# is never copied into the container (decryption happens on the host and streams through stdin).
#   usage: scripts/restore-drill.sh <.../turnocontrol-db-*.dump.age> <archivo-identidad-age> [recuentos-esperados.txt]
# The optional third file holds the `recuentos:` line printed for the source database (scripts/backup.sh logs nothing
# about content; produce it with the same query on the source the day of the backup) and is compared verbatim.
# Requires the image to be present locally (pulling it is a separate, approved step).
set -euo pipefail
. "$(dirname "$0")/lib.sh"
require_cmd docker age grep openssl
dump=${1:?uso: restore-drill.sh <archivo .dump.age> <identidad age>}
identity=${2:?falta el archivo de identidad age}
"$DEPLOY_DIR/scripts/verify-backup.sh" "$dump" >/dev/null || die "la copia no supera la verificación estructural"
roles="${dump%.dump.age}.roles.sql.age"
# Fail before creating anything if the key cannot decrypt the set (age authenticates every chunk).
age -d -i "$identity" "$dump" > /dev/null 2>&1 || die "la identidad age no descifra la copia (clave equivocada o copia dañada)"
age -d -i "$identity" "$roles" > /dev/null 2>&1 || die "la identidad age no descifra la copia de roles"
NAME=turnocontrol-restore-drill
IMAGE=$(grep -Eo 'supabase/postgres:[^[:space:]]+' "$DEPLOY_DIR/compose.yaml" | head -1)
[ -n "$IMAGE" ] || die "no se encontró la imagen en compose.yaml"
docker image inspect "$IMAGE" >/dev/null 2>&1 || die "la imagen $IMAGE no está en local; descargarla requiere aprobación"
docker ps -a --format '{{.Names}}' | grep -qx "$NAME" && die "$NAME ya existe; no se toca"
cleanup() { docker rm -f -v "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT

docker run -d --name "$NAME" --network none --label turnocontrol.purpose=restore-drill \
  -e POSTGRES_PASSWORD="$(openssl rand -hex 24)" "$IMAGE" >/dev/null
for _ in $(seq 1 120); do docker exec "$NAME" pg_isready -U postgres -h localhost -q 2>/dev/null && break; sleep 1; done
sleep 10   # the image runs its own init migrations after the first ready signal
dpsql() { docker exec -i "$NAME" psql -U supabase_admin -d postgres -X -q "$@"; }

log "restaurando roles (los que ya existen en la imagen dan un aviso esperado)"
age -d -i "$identity" "$roles" | dpsql -v ON_ERROR_STOP=0 >/dev/null 2>&1 || true
# A fresh image grants service_role/supabase_admin to authenticator again; re-apply the hardening (also in a real recovery).
for h in "$DEPLOY_DIR"/db/hardening/*.sql; do dpsql -v ON_ERROR_STOP=1 < "$h"; done
log "restaurando la base"
errors=$(age -d -i "$identity" "$dump" | docker exec -i "$NAME" pg_restore -U supabase_admin -d postgres --clean --if-exists 2>&1 \
  | grep -c '^pg_restore: error' || true)
log "errores de pg_restore: $errors (los objetos internos de la imagen pueden generar algunos; revisa la verificación)"

# MANDATORY after any restore (found in 5F.1C stage 0): pg_restore recreates the functions as supabase_admin, and the
# image's default privileges grant EXECUTE on them to anon/service_role again (pg_dump stores ACLs relative to the
# built-in default, so the earlier revocation is not carried by the dump). Re-apply the idempotent privilege files.
log "reaplicando endurecimiento y privilegios tras restaurar"
for h in "$DEPLOY_DIR"/db/hardening/*.sql "$BACKEND_DIR"/supabase/migrations/20261124000000_hosting_privileges.sql; do
  dpsql -v ON_ERROR_STOP=1 < "$h" 2>/dev/null
done

result=$(dpsql -v ON_ERROR_STOP=1 -At < "$DEPLOY_DIR/tests/sql/verify_deployment.sql" && dpsql -v ON_ERROR_STOP=1 -At < "$DEPLOY_DIR/tests/sql/verify_restore_integrity.sql")   || { echo "${result:-}"; echo "SIMULACRO: FALLIDO (la verificación no pudo ejecutarse sobre lo restaurado)"; exit 1; }
echo "$result"
counts=$(dpsql -At < "$DEPLOY_DIR/tests/sql/restore_counts.sql")
echo "$counts"
failed=0
echo "$result" | grep -q '|f$' && failed=1
if [ -n "${3:-}" ]; then
  if [ "$(tr -d '\r' < "$3")" = "$counts" ]; then echo "recuentos y huella: idénticos al origen"; else echo "recuentos y huella: DISTINTOS del origen"; failed=1; fi
fi
[ "$failed" = 0 ] || { echo "SIMULACRO: FALLIDO"; exit 1; }
echo "SIMULACRO: superado"

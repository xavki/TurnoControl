#!/usr/bin/env bash
# Runs the synchronization backend tests in ONE disposable PostgreSQL container.
#  - Container name: turnocontrol-sync-test (refuses to run if it already exists; never touches other containers).
#  - --network none: no port is published and the container has no network at all; psql runs inside it.
#  - The superuser password is random per run and never written to disk.
#  - The container (and its anonymous data volume) is removed at the end, also on failure. Synthetic data only.
set -euo pipefail
NAME=turnocontrol-sync-test
IMAGE=${TURNO_PG_IMAGE:-postgres:16}
HERE=$(cd "$(dirname "$0")/.." && pwd)

if docker ps -a --format '{{.Names}}' | grep -qx "$NAME"; then
  echo "El contenedor $NAME ya existe; no se toca. Elimínalo a mano si es de una ejecución anterior." >&2; exit 2
fi
cleanup() { docker rm -f -v "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT

docker run -d --name "$NAME" --network none -e POSTGRES_PASSWORD="$(openssl rand -hex 24 2>/dev/null || date +%s%N)" "$IMAGE" >/dev/null
for _ in $(seq 1 60); do docker exec "$NAME" pg_isready -U postgres -q && break; sleep 1; done
sleep 2
psql() { docker exec -i "$NAME" psql -U postgres -d postgres -v ON_ERROR_STOP=1 -q "$@"; }

psql < "$HERE/supabase/tests/00_local_auth_shim.sql"
psql < "$HERE/supabase/migrations/20261122000000_turno_sync.sql"
psql < "$HERE/supabase/tests/10_sync_security_test.sql"

# Concurrency: two sessions push the same record from the same base at the same time. The per-account lock must make
# exactly one succeed and the other report a conflict (never two versions from one base).
psql -c "insert into auth.users values ('00000000-0000-4000-8000-0000000000cc', 'concurrencia@example.invalid');"
push() {
  cat <<SQL | psql -At
set role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-4000-8000-0000000000cc","role":"authenticated","exp":9999999999}',false) is null;
begin;
select (public.sync_push('22222222-2222-4222-8222-22222222222$1', jsonb_build_array(jsonb_build_object('operation_id','e0000000-0000-4000-8000-00000000000$1','entity','SCHEDULED_DAY','key','2026-12-31','op','UPSERT','payload','1|2026-12-31|FREE|MANUAL|','base_version',null,'data_format',1))))->0->>'status';
select pg_sleep($2) is null;
commit;
SQL
}
push 1 2 > /tmp/turno_c1 & first=$!
sleep 0.5
push 2 0 > /tmp/turno_c2 & second=$!
wait $first; wait $second
results=$(cat /tmp/turno_c1 /tmp/turno_c2 | grep -E 'APPLIED|CONFLICT' | sort | tr '\n' ' ')
rm -f /tmp/turno_c1 /tmp/turno_c2
versions=$(psql -At -c "select count(*) from turno.records where user_id = '00000000-0000-4000-8000-0000000000cc'")
if [ "$results" = "APPLIED CONFLICT " ] && [ "$versions" = "1" ]; then
  echo "CONCURRENCIA: superada (una aplicada, una en conflicto, un solo registro)"
else
  echo "CONCURRENCIA: FALLIDA ($results, registros=$versions)" >&2; exit 1
fi

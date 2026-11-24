#!/usr/bin/env bash
# Interruption and concurrency tests against a running STAGING / validation stack (synthetic accounts only).
#  - client disconnects before the request body is complete  -> nothing is applied
#  - client disconnects right after sending a complete push   -> applied at most once; resending the same operation_id
#                                                               returns the same outcome, never a second version
#  - two accounts writing the same key at the same time        -> both applied, each in its own account
#  - PostgREST container restarted while requests are flowing  -> requests fail cleanly or succeed; no partial state
# Restarting a container needs TURNO_TEST_REST_CONTAINER (the validation stack's REST container); it is never inferred.
#   usage: TURNO_TEST_BASE=http://127.0.0.1:18080 TURNO_TEST_ANON_KEY=... TURNO_TEST_REST_CONTAINER=... tests/resilience-test.sh
set -euo pipefail
PY=python3; "$PY" -c "" 2>/dev/null || PY=python
BASE=${TURNO_TEST_BASE:?}; KEY=${TURNO_TEST_ANON_KEY:?}; REST=${TURNO_TEST_REST_CONTAINER:?}
case "$BASE" in http://127.0.0.1:*|http://localhost:*) ;; *) echo "solo contra loopback o túnel SSH" >&2; exit 2;; esac
case "$REST" in *validation*|*staging*) ;; *) echo "TURNO_TEST_REST_CONTAINER debe ser un contenedor de validación o staging" >&2; exit 2;; esac
BODY=$(mktemp); trap 'rm -f "$BODY"' EXIT
pass=0; fail=0
check() { if [ "$2" = "$3" ]; then echo "PASS  $1"; pass=$((pass + 1)); else echo "FAIL  $1 (esperado '$3', obtenido '$2')"; fail=$((fail + 1)); fi; }
http() { curl -s -o "$BODY" -w '%{http_code}' -m 15 "$@" || echo 000; }
field() { "$PY" -c 'import json,sys; d=json.load(open(sys.argv[1])); exec("print(" + sys.argv[2] + ")")' "$BODY" "$1"; }
uuid() { "$PY" -c 'import uuid; print(uuid.uuid4())'; }
now=$(date +%s); dev=22222222-2222-4222-8222-222222222222
token() { local email="turno-test-res$1-$now@example.invalid" pw; pw=$(openssl rand -hex 16)
  http -X POST "$BASE/auth/v1/signup" -H "apikey: $KEY" -H 'Content-Type: application/json' -d "{\"email\":\"$email\",\"password\":\"$pw\"}" >/dev/null
  http -X POST "$BASE/auth/v1/token?grant_type=password" -H "apikey: $KEY" -H 'Content-Type: application/json' -d "{\"email\":\"$email\",\"password\":\"$pw\"}" >/dev/null
  field 'd.get("access_token", "")'; }
mut() { printf '{"p_device_id":"%s","p_mutations":[{"operation_id":"%s","entity":"SCHEDULED_DAY","key":"%s","op":"UPSERT","payload":"1|%s|%s|MANUAL|","base_version":null,"data_format":1}]}' "$dev" "$1" "$2" "$2" "$3"; }
push() { http -X POST "$BASE/rest/v1/rpc/sync_push" -H "apikey: $KEY" -H 'Content-Type: application/json' -H "Authorization: Bearer $1" -d "$2"; }
versions() { http -X POST "$BASE/rest/v1/rpc/sync_pull" -H "apikey: $KEY" -H 'Content-Type: application/json' -H "Authorization: Bearer $1" -d '{"p_since":0,"p_limit":1000}' >/dev/null
  field "[c['version'] for c in d['changes'] if c['key'] == '$2']"; }
# Raw socket: send the request (optionally truncated) and close without reading the response.
raw() { RAW_TOKEN=$1 RAW_BODY=$2 RAW_CUT=$3 "$PY" - "$BASE" "$KEY" <<'PY'
import os, socket, sys, time, urllib.parse
u = urllib.parse.urlparse(sys.argv[1]); body = os.environ["RAW_BODY"].encode()
head = (f"POST /rest/v1/rpc/sync_push HTTP/1.1\r\nHost: {u.hostname}\r\napikey: {sys.argv[2]}\r\n"
        f"Authorization: Bearer {os.environ['RAW_TOKEN']}\r\nContent-Type: application/json\r\n"
        f"Content-Length: {len(body)}\r\nConnection: close\r\n\r\n").encode()
s = socket.create_connection((u.hostname, u.port))
cut = os.environ["RAW_CUT"] == "1"
s.sendall(head + (body[: len(body) // 2] if cut else body))
time.sleep(0.05 if not cut else 0.5)
s.close()
PY
}

A=$(token a); B=$(token b)
[ -n "$A" ] && [ -n "$B" ] || { echo "no se pudieron crear las cuentas sintéticas" >&2; exit 1; }

# 1. Disconnect before the body is complete: nothing may be applied.
op=$(uuid); raw "$A" "$(mut "$op" 2026-11-01 FREE)" 1; sleep 1
check "desconexión antes de completar el cuerpo: nada aplicado" "$(versions "$A" 2026-11-01)" "[]"

# 2. Disconnect right after sending: at most one version; the retry with the same operation_id converges.
op=$(uuid); raw "$A" "$(mut "$op" 2026-11-02 FREE)" 0; sleep 1
after_drop=$(versions "$A" 2026-11-02)
push "$A" "$(mut "$op" 2026-11-02 FREE)" >/dev/null; retry=$(field 'd[0]["status"]')
check "desconexión tras enviar: como mucho una versión ($after_drop)" "$(case "$after_drop" in "[]"|"[1]") echo ok;; *) echo "$after_drop";; esac)" ok
check "reintento con el mismo operation_id: APPLIED" "$retry" APPLIED
check "tras el reintento: exactamente una versión" "$(versions "$A" 2026-11-02)" "[1]"

# 3. Two accounts, same key, simultaneously.
opa=$(uuid); opb=$(uuid)
( curl -s -o /dev/null -m 15 -X POST "$BASE/rest/v1/rpc/sync_push" -H "apikey: $KEY" -H 'Content-Type: application/json' -H "Authorization: Bearer $A" -d "$(mut "$opa" 2026-11-03 FREE)" ) &
( curl -s -o /dev/null -m 15 -X POST "$BASE/rest/v1/rpc/sync_push" -H "apikey: $KEY" -H 'Content-Type: application/json' -H "Authorization: Bearer $B" -d "$(mut "$opb" 2026-11-03 VACATION)" ) &
wait
check "A y B escriben la misma clave a la vez: A tiene su versión" "$(versions "$A" 2026-11-03)" "[1]"
check "A y B escriben la misma clave a la vez: B tiene la suya" "$(versions "$B" 2026-11-03)" "[1]"

# 4. Restart PostgREST while a burst of distinct operations is in flight; then resend all of them.
ops=(); for i in $(seq 1 20); do ops+=("$(uuid)"); done
( for i in $(seq 1 20); do curl -s -o /dev/null -m 10 -X POST "$BASE/rest/v1/rpc/sync_push" -H "apikey: $KEY" -H 'Content-Type: application/json' -H "Authorization: Bearer $A" -d "$(mut "${ops[$((i-1))]}" "2026-10-$(printf %02d "$i")" FREE)" || true; done ) &
burst=$!
sleep 0.3; docker restart -t 5 "$REST" >/dev/null; wait $burst || true
for _ in $(seq 1 30); do [ "$(docker inspect -f '{{.State.Health.Status}}' "$REST")" = healthy ] && break; sleep 1; done
check "PostgREST vuelve a healthy tras el reinicio" "$(docker inspect -f '{{.State.Health.Status}}' "$REST")" healthy
for i in $(seq 1 20); do push "$A" "$(mut "${ops[$((i-1))]}" "2026-10-$(printf %02d "$i")" FREE)" >/dev/null; done
http -X POST "$BASE/rest/v1/rpc/sync_pull" -H "apikey: $KEY" -H 'Content-Type: application/json' -H "Authorization: Bearer $A" -d '{"p_since":0,"p_limit":1000}' >/dev/null
check "tras reiniciar y reenviar: 20 días, cada uno en versión 1" "$(field "sorted(set(c['version'] for c in d['changes'] if c['key'].startswith('2026-10-'))), sum(1 for c in d['changes'] if c['key'].startswith('2026-10-'))")" "[1] 20"
check "seq estrictamente creciente y sin repetidos" "$(field "(lambda s: s == sorted(set(s)))([c['seq'] for c in d['changes']])")" True

echo "RESULTADO RESILIENCIA: $pass superadas, $fail fallidas"
[ "$fail" -eq 0 ]

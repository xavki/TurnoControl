#!/usr/bin/env bash
# Session lifecycle against a running STAGING / validation stack (synthetic account): refresh-token rotation, reuse
# detection, and — when TURNO_TEST_EXPECT_EXPIRY_SECONDS is set to the stack's (short, staging-only) JWT expiry — real
# expiry of a GoTrue-issued access token followed by recovery through the refresh token. Never prints tokens.
#   usage: TURNO_TEST_BASE=http://127.0.0.1:18080 TURNO_TEST_ANON_KEY=... [TURNO_TEST_EXPECT_EXPIRY_SECONDS=60] tests/session-lifecycle-test.sh
set -euo pipefail
PY=python3; "$PY" -c "" 2>/dev/null || PY=python
BASE=${TURNO_TEST_BASE:?}; KEY=${TURNO_TEST_ANON_KEY:?}; EXPIRY=${TURNO_TEST_EXPECT_EXPIRY_SECONDS:-}
case "$BASE" in http://127.0.0.1:*|http://localhost:*) ;; *) echo "solo contra loopback o túnel SSH" >&2; exit 2;; esac
BODY=$(mktemp); trap 'rm -f "$BODY"' EXIT
pass=0; fail=0
check() { if [ "$2" = "$3" ]; then echo "PASS  $1"; pass=$((pass + 1)); else echo "FAIL  $1 (esperado '$3', obtenido '$2')"; fail=$((fail + 1)); fi; }
http() { curl -s -o "$BODY" -w '%{http_code}' -m 15 "$@" || echo 000; }
field() { "$PY" -c 'import json,sys; d=json.load(open(sys.argv[1])); exec("print(" + sys.argv[2] + ")")' "$BODY" "$1"; }
rpc() { http -X POST "$BASE/rest/v1/rpc/sync_pull" -H "apikey: $KEY" -H 'Content-Type: application/json' -H "Authorization: Bearer $1" -d '{"p_since":0,"p_limit":1}'; }
refresh() { http -X POST "$BASE/auth/v1/token?grant_type=refresh_token" -H "apikey: $KEY" -H 'Content-Type: application/json' -d "{\"refresh_token\":\"$1\"}"; }
now=$(date +%s); email="turno-test-ses-$now@example.invalid"; pw=$(openssl rand -hex 16)
http -X POST "$BASE/auth/v1/signup" -H "apikey: $KEY" -H 'Content-Type: application/json' -d "{\"email\":\"$email\",\"password\":\"$pw\"}" >/dev/null
check "inicio de sesión" "$(http -X POST "$BASE/auth/v1/token?grant_type=password" -H "apikey: $KEY" -H 'Content-Type: application/json' -d "{\"email\":\"$email\",\"password\":\"$pw\"}")" 200
AT=$(field 'd["access_token"]'); RT=$(field 'd["refresh_token"]'); ttl=$(field 'd["expires_in"]')
echo "INFO  expires_in del token de acceso: ${ttl}s"
[ -z "$EXPIRY" ] || check "expires_in coincide con la caducidad configurada" "$ttl" "$EXPIRY"
check "token de acceso válido en la RPC" "$(rpc "$AT")" 200

# Rotation: refreshing returns a NEW refresh token; the new access token works.
check "renovación con el refresh token" "$(refresh "$RT")" 200
AT2=$(field 'd["access_token"]'); RT2=$(field 'd["refresh_token"]')
check "la renovación entrega otro refresh token (rotación)" "$( [ "$RT2" != "$RT" ] && echo distinto)" distinto
check "el nuevo token de acceso funciona" "$(rpc "$AT2")" 200
check "segunda renovación con el refresh token nuevo" "$(refresh "$RT2")" 200
RT3=$(field 'd["refresh_token"]')
# Reuse of an already-rotated token outside the reuse interval (10 s) must be refused and revoke the family.
sleep 12
code=$(refresh "$RT"); check "reutilizar un refresh token ya rotado (pasado el intervalo): rechazado" "$(case "$code" in 400|401|403) echo rechazado;; *) echo "$code";; esac)" rechazado
# GoTrue revokes the whole family here, but RevokeTokenFamily sets updated_at = now(), and a revoked token is accepted
# again within the reuse interval counted from that timestamp. So the latest token keeps working for ~10 s after the
# revocation (observed in 5F.1C stage 1; GoTrue v2.198.0 behaviour, see SECURITY.md). Check after the window.
sleep 12
code=$(refresh "$RT3"); check "tras la reutilización y pasado el intervalo, toda la familia queda revocada" "$(case "$code" in 400|401|403) echo revocada;; *) echo "$code";; esac)" revocada

if [ -n "$EXPIRY" ]; then
  check "nuevo inicio de sesión" "$(http -X POST "$BASE/auth/v1/token?grant_type=password" -H "apikey: $KEY" -H 'Content-Type: application/json' -d "{\"email\":\"$email\",\"password\":\"$pw\"}")" 200
  AT=$(field 'd["access_token"]'); RT=$(field 'd["refresh_token"]')
  check "token recién emitido: aceptado" "$(rpc "$AT")" 200
  wait_s=$((EXPIRY + 35))   # PostgREST allows 30 s of clock skew
  echo "INFO  esperando ${wait_s}s a que caduque el token de acceso real"
  sleep "$wait_s"
  check "token de acceso caducado de verdad: 401" "$(rpc "$AT")" 401
  check "recuperación: el refresh token sigue sirviendo tras caducar el acceso" "$(refresh "$RT")" 200
  check "el token renovado funciona" "$(rpc "$(field 'd["access_token"]')")" 200
fi
echo "RESULTADO SESIÓN: $pass superadas, $fail fallidas"
[ "$fail" -eq 0 ]

#!/usr/bin/env bash
# Deterministic refresh-token policy tests against a VALIDATION / STAGING stack (synthetic accounts only). Run once per
# configured GOTRUE_SECURITY_REFRESH_TOKEN_REUSE_INTERVAL and pass that value in TURNO_TEST_REUSE_INTERVAL, so the
# assertions about the post-revocation window match the configuration under test.
#
# Covers: v1 token format, rotation, lost refresh response (retry with the SAME old token), process killed mid-refresh,
# repeated uncertain retries, concurrent refreshes, reuse of an old token (family revocation and the post-revocation
# window), several devices on one account, local and global sign-out, and — when TURNO_TEST_DB_CONTAINER is set —
# days offline simulated by backdating the session and token timestamps in the (disposable) database.
#
# Client contract these tests encode: persist the new token pair before using it; after an uncertain refresh (timeout,
# crash, lost response) retry with the SAME old refresh token, which GoTrue answers with the currently active token.
#   usage: TURNO_TEST_BASE=http://127.0.0.1:18080 TURNO_TEST_ANON_KEY=... TURNO_TEST_REUSE_INTERVAL=0 \
#          [TURNO_TEST_DB_CONTAINER=<validation db container>] tests/refresh-token-policy-test.sh
set -euo pipefail
PY=python3; "$PY" -c "" 2>/dev/null || PY=python
BASE=${TURNO_TEST_BASE:?}; KEY=${TURNO_TEST_ANON_KEY:?}; INTERVAL=${TURNO_TEST_REUSE_INTERVAL:?}; DB=${TURNO_TEST_DB_CONTAINER:-}
case "$BASE" in http://127.0.0.1:*|http://localhost:*) ;; *) echo "solo contra loopback o túnel SSH" >&2; exit 2;; esac
case "$DB" in ""|*validation*) ;; *) echo "TURNO_TEST_DB_CONTAINER solo puede ser un contenedor de validación (se modifican fechas)" >&2; exit 2;; esac
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
BODY="$WORK/body"
pass=0; fail=0
check() { if [ "$2" = "$3" ]; then echo "PASS  $1"; pass=$((pass + 1)); else echo "FAIL  $1 (esperado '$3', obtenido '$2')"; fail=$((fail + 1)); fi; }
http() { curl -s -o "$BODY" -w '%{http_code}' -m 15 "$@" || echo 000; }
field() { "$PY" -c 'import json,sys; d=json.load(open(sys.argv[1])); print(d.get(sys.argv[2], ""))' "$BODY" "$1"; }
refresh() { http -X POST "$BASE/auth/v1/token?grant_type=refresh_token" -H "apikey: $KEY" -H 'Content-Type: application/json' -d "{\"refresh_token\":\"$1\"}"; }
ok_or_denied() { case "$1" in 200) echo aceptado;; 400|401|403) echo rechazado;; *) echo "$1";; esac; }
now=$(date +%s); n=0
login() { # $1 = email; prints "access refresh" of a NEW session (device)
  http -X POST "$BASE/auth/v1/token?grant_type=password" -H "apikey: $KEY" -H 'Content-Type: application/json' -d "{\"email\":\"$1\",\"password\":\"$PW\"}" >/dev/null
  echo "$(field access_token) $(field refresh_token)"; }
account() { n=$((n + 1)); EMAIL="turno-test-rt$n-$now@example.invalid"; PW=$(openssl rand -hex 16)
  http -X POST "$BASE/auth/v1/signup" -H "apikey: $KEY" -H 'Content-Type: application/json' -d "{\"email\":\"$EMAIL\",\"password\":\"$PW\"}" >/dev/null; }
rpc() { http -X POST "$BASE/rest/v1/rpc/sync_pull" -H "apikey: $KEY" -H 'Content-Type: application/json' -H "Authorization: Bearer $1" -d '{"p_since":0,"p_limit":1}'; }
db() { docker exec -i "$DB" psql -U supabase_admin -d postgres -XAt -c "$1" < /dev/null; }

# ---------- 1. Format and rotation
account; read -r AT RT <<< "$(login "$EMAIL")"
check "formato de refresh token v1 (12 caracteres): si GoTrue cambia de algoritmo, revisar esta política" "${#RT}" 12
check "rotación: renovar entrega otro refresh token" "$(refresh "$RT"; [ "$(field refresh_token)" != "$RT" ] && echo distinto)" "200distinto"
RT2=$(field refresh_token)

# ---------- 2. Lost response: the client never saw RT2 and retries with RT (the same old token)
code=$(refresh "$RT"); got=$(field refresh_token)
check "respuesta perdida: reintentar con el token anterior es aceptado" "$(ok_or_denied "$code")" aceptado
check "respuesta perdida: devuelve el token activo, sin crear otro" "$( [ "$got" = "$RT2" ] && echo mismo)" mismo
code=$(refresh "$RT"); check "segundo reintento incierto con el mismo token anterior: aceptado" "$(ok_or_denied "$code")" aceptado
check "el token activo sigue siendo el mismo tras los reintentos" "$( [ "$(field refresh_token)" = "$RT2" ] && echo mismo)" mismo

# ---------- 3. Process killed mid-refresh (request sent, connection dropped before reading the response)
RAW_TOKEN=$RT2 "$PY" - "$BASE" "$KEY" <<'PY'
import os, socket, sys, time, urllib.parse
u = urllib.parse.urlparse(sys.argv[1]); body = ('{"refresh_token":"%s"}' % os.environ["RAW_TOKEN"]).encode()
req = (f"POST /auth/v1/token?grant_type=refresh_token HTTP/1.1\r\nHost: {u.hostname}\r\napikey: {sys.argv[2]}\r\n"
       f"Content-Type: application/json\r\nContent-Length: {len(body)}\r\nConnection: close\r\n\r\n").encode() + body
s = socket.create_connection((u.hostname, u.port)); s.sendall(req); time.sleep(0.3); s.close()
PY
sleep 1
code=$(refresh "$RT2"); RT3=$(field refresh_token); AT3=$(field access_token)
check "proceso cortado durante la renovación: reintentar con el mismo token recupera la sesión" "$(ok_or_denied "$code")" aceptado
check "tras el corte, el token de acceso recuperado funciona" "$(rpc "$AT3")" 200

# ---------- 4. Concurrent refreshes with the same token (e.g. two app components racing)
( refresh "$RT3" > "$WORK/c1"; cp "$BODY" "$WORK/b1" ) &
( curl -s -o "$WORK/b2" -w '%{http_code}' -m 15 -X POST "$BASE/auth/v1/token?grant_type=refresh_token" -H "apikey: $KEY" -H 'Content-Type: application/json' -d "{\"refresh_token\":\"$RT3\"}" > "$WORK/c2" ) &
wait
check "dos renovaciones simultáneas con el mismo token: ambas aceptadas" "$(cat "$WORK/c1")$(cat "$WORK/c2")" "200200"
same=$("$PY" -c 'import json,sys; a=json.load(open(sys.argv[1]))["refresh_token"]; b=json.load(open(sys.argv[2]))["refresh_token"]; print("mismo" if a == b else "distinto")' "$WORK/b1" "$WORK/b2")
check "las dos respuestas simultáneas entregan el mismo token activo" "$same" mismo
RT4=$("$PY" -c 'import json,sys; print(json.load(open(sys.argv[1]))["refresh_token"])' "$WORK/b1")
check "la sesión sigue utilizable tras la carrera" "$(ok_or_denied "$(refresh "$RT4")")" aceptado
RT5=$(field refresh_token)

# ---------- 5. Reuse of an old (grandparent) token: theft signal -> family revoked
sleep $((INTERVAL + 2))
check "reutilizar un token antiguo (no el padre del activo), pasado el intervalo: rechazado" "$(ok_or_denied "$(refresh "$RT")")" rechazado
code=$(refresh "$RT5")
if [ "$INTERVAL" -eq 0 ]; then
  check "intervalo 0: el último token de la familia queda revocado al instante" "$(ok_or_denied "$code")" rechazado
else
  check "intervalo $INTERVAL: el último token aún se acepta dentro de la ventana (comportamiento de GoTrue)" "$(ok_or_denied "$code")" aceptado
  sleep $((INTERVAL + 2)); RT6=$(field refresh_token)
  check "intervalo $INTERVAL: pasada la ventana, el token emitido en ella también queda revocado tras otra reutilización" "$(refresh "$RT" >/dev/null; sleep $((INTERVAL + 2)); ok_or_denied "$(refresh "$RT6")")" rechazado
fi

# ---------- 6. Several devices on one account are independent sessions
account; read -r ATa RTa <<< "$(login "$EMAIL")"; read -r ATb RTb <<< "$(login "$EMAIL")"
refresh "$RTa" >/dev/null; RTa2=$(field refresh_token)
check "dispositivo A renueva sin afectar al dispositivo B" "$(ok_or_denied "$(refresh "$RTb")")" aceptado
RTb2=$(field refresh_token)
sleep $((INTERVAL + 2)); refresh "$RTa" >/dev/null   # theft signal on device A only
check "revocar la familia de A no afecta a la sesión de B" "$(ok_or_denied "$(refresh "$RTb2")")" aceptado
RTb3=$(field refresh_token); ATb3=$(field access_token)

# ---------- 7. Sign-out: local (this device) vs global (all devices)
read -r ATc RTc <<< "$(login "$EMAIL")"
http -X POST "$BASE/auth/v1/logout?scope=local" -H "apikey: $KEY" -H "Authorization: Bearer $ATc" >/dev/null
check "cierre de sesión local: el refresh de ese dispositivo queda inválido" "$(ok_or_denied "$(refresh "$RTc")")" rechazado
check "cierre de sesión local: el otro dispositivo sigue" "$(ok_or_denied "$(refresh "$RTb3")")" aceptado
RTb4=$(field refresh_token); ATb4=$(field access_token)
http -X POST "$BASE/auth/v1/logout?scope=global" -H "apikey: $KEY" -H "Authorization: Bearer $ATb4" >/dev/null
check "cierre de sesión global: todos los refresh quedan inválidos" "$(ok_or_denied "$(refresh "$RTb4")")" rechazado
echo "INFO  token de acceso tras el cierre global, en la RPC: HTTP $(rpc "$ATb4") (sin estado: vale hasta que caduque, ≤ 15 min)"

# ---------- 8. Days offline (backdated timestamps; disposable validation DB only)
if [ -n "$DB" ]; then
  account; read -r ATo RTo <<< "$(login "$EMAIL")"
  uid=$(db "select id from auth.users where email = '$EMAIL'")
  for days in 1 30 90 400; do
    db "update auth.sessions set created_at = now() - interval '$days days', updated_at = now() - interval '$days days', refreshed_at = now() - interval '$days days' where user_id = '$uid'; update auth.refresh_tokens set created_at = now() - interval '$days days', updated_at = now() - interval '$days days' where user_id = '$uid'" >/dev/null
    code=$(refresh "$RTo"); check "$days días sin conexión: la renovación recupera la sesión (sin límite de inactividad configurado)" "$(ok_or_denied "$code")" aceptado
    RTo=$(field refresh_token)
    check "$days días sin conexión: el nuevo token de acceso funciona" "$(rpc "$(field access_token)")" 200
  done
else
  echo "SKIP  pruebas de días sin conexión (necesitan TURNO_TEST_DB_CONTAINER de validación)"
fi

echo "RESULTADO REFRESH (intervalo $INTERVAL): $pass superadas, $fail fallidas"
[ "$fail" -eq 0 ]

#!/usr/bin/env bash
# E-mail authentication flows against a VALIDATION stack whose GoTrue sends to the FAKE SMTP server (Mailpit, from
# tests/compose.validation.yaml). Synthetic @example.invalid addresses only; nothing leaves the host. Needs GoTrue with
# TURNO_MAILER_AUTOCONFIRM=false, TURNO_DISABLE_SIGNUP=false, SMTP host turnocontrol-mailpit:1025 and a high
# TURNO_RATE_LIMIT_EMAIL_SENT (the global hourly cap is tested separately with TURNO_TEST_ONLY_GLOBAL_LIMIT=1).
#   usage: TURNO_TEST_BASE=http://127.0.0.1:18080 TURNO_TEST_ANON_KEY=... TURNO_TEST_MAILPIT=http://127.0.0.1:18025 \
#          TURNO_TEST_DB_CONTAINER=<validation db> tests/email-auth-test.sh
set -euo pipefail
PY=python3; "$PY" -c "" 2>/dev/null || PY=python
BASE=${TURNO_TEST_BASE:?}; KEY=${TURNO_TEST_ANON_KEY:?}; MP=${TURNO_TEST_MAILPIT:?}; DB=${TURNO_TEST_DB_CONTAINER:?}
case "$BASE$MP" in http://127.0.0.1:*http://127.0.0.1:*) ;; *) echo "solo contra loopback" >&2; exit 2;; esac
case "$DB" in *validation*) ;; *) echo "TURNO_TEST_DB_CONTAINER solo puede ser de validación" >&2; exit 2;; esac
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT; BODY="$WORK/body"
pass=0; fail=0
check() { if [ "$2" = "$3" ]; then echo "PASS  $1"; pass=$((pass + 1)); else echo "FAIL  $1 (esperado '$3', obtenido '$2')"; fail=$((fail + 1)); fi; }
http() { curl -s -o "$BODY" -w '%{http_code}' -m 15 "$@" || echo 000; }
field() { "$PY" -c 'import json,sys; d=json.load(open(sys.argv[1])); exec("print(" + sys.argv[2] + ")")' "$BODY" "$1"; }
db() { docker exec -i "$DB" psql -U supabase_admin -d postgres -XAt -c "$1" < /dev/null; }
now=$(date +%s); n=0
# Random suffix: a counter would not survive the $(addr) subshell and every address would come out identical.
addr() { echo "turno-test-mail-$(openssl rand -hex 4)-$now@example.invalid"; }
json_auth() { http -X POST "$BASE/auth/v1/$1" -H "apikey: $KEY" -H 'Content-Type: application/json' -d "$2"; }
login() { json_auth "token?grant_type=password" "{\"email\":\"$1\",\"password\":\"$2\"}"; }
# Messages addressed EXACTLY to $1 (Mailpit's search is fuzzy, so list and filter by the recipient address).
mail_ids() { curl -s -m 10 "$MP/api/v1/messages?limit=500" | "$PY" -c 'import json,sys
want=sys.argv[1].lower()
for m in json.load(sys.stdin).get("messages", []):
    if any((t.get("Address") or "").lower() == want for t in (m.get("To") or [])): print(m["ID"])' "$1"; }
mail_count() { mail_ids "$1" | wc -l | tr -d ' '; }
# Waits (up to 15 s) until at least $2 messages have reached $1, so a link is never read before its e-mail arrives.
wait_mail() { local i; for i in $(seq 1 30); do [ "$(mail_count "$1")" -ge "$2" ] && return 0; sleep 0.5; done; return 0; }
mail_link() { # $1 address, $2 link type: link of that type in the NEWEST message to that address. Retries while the
  # message body is not readable yet (it appears in the list a moment before), and never falls back to an older message.
  local id link i
  for i in $(seq 1 30); do
    id=$(mail_ids "$1" | head -1)
    if [ -n "$id" ]; then
      link=$(curl -s -m 10 "$MP/api/v1/message/$id" | "$PY" -c 'import html,json,re,sys
try: d=json.load(sys.stdin)
except ValueError: d={}
t=html.unescape((d.get("Text") or "") + " " + (d.get("HTML") or ""))
links=[l for l in re.findall(r"http://127\.0\.0\.1:18080/auth/v1/verify\?[^\s\"<>]+", t) if ("type=" + sys.argv[1]) in l]
print(links[0] if links else "")' "$2")
      [ -n "$link" ] && { echo "$link"; return; }
    fi
    sleep 0.5
  done
  echo ""; }
visit() { [ -n "$1" ] || { echo "000 sin-enlace"; return; }; curl -s -o /dev/null -m 15 -w '%{http_code} %{redirect_url}' "$1" || echo "000"; }   # browser click (no apikey)
# Errors first: "error_code=" would otherwise match the PKCE "code=" pattern.
verdict() { case "$1" in *error_code=*|*error=*) echo error;; *access_token=*|*[?\&]code=*) echo ok;; *) echo "otro: ${1%% *}";; esac; }
pkce() { "$PY" -c 'import base64,hashlib,secrets; v=secrets.token_urlsafe(48); c=base64.urlsafe_b64encode(hashlib.sha256(v.encode()).digest()).rstrip(b"=").decode(); print(v, c)'; }

if [ "${TURNO_TEST_ONLY_GLOBAL_LIMIT:-0}" = 1 ]; then
  # GoTrue configured with TURNO_RATE_LIMIT_EMAIL_SENT=3: the 4th e-mail-sending request within the hour is refused.
  codes=""; for i in 1 2 3 4; do codes="$codes$(json_auth signup "{\"email\":\"$(addr)\",\"password\":\"$(openssl rand -hex 16)\"}") "; done
  check "límite global de correos por hora (3): el cuarto envío se rechaza con 429" "$codes" "200 200 200 429 "
  echo "RESULTADO LÍMITE GLOBAL: $pass superadas, $fail fallidas"; [ "$fail" -eq 0 ]; exit
fi

check "GoTrue: confirmación de correo exigida (mailer_autoconfirm=false)" "$(http "$BASE/auth/v1/settings" -H "apikey: $KEY"; field 'd["mailer_autoconfirm"]')" "200False"

# ---------- 1. Sign-up with PKCE (the app's flow): e-mail, unconfirmed login refused, link -> code -> session
E1=$(addr); P1=$(openssl rand -hex 16); read -r VER CHAL <<< "$(pkce)"
check "alta con PKCE: 200" "$(json_auth signup "{\"email\":\"$E1\",\"password\":\"$P1\",\"code_challenge\":\"$CHAL\",\"code_challenge_method\":\"s256\"}")" 200
check "alta: sin sesión antes de confirmar" "$(field '"access_token" in d')" False
wait_mail "$E1" 1; check "alta: llega exactamente 1 correo de confirmación" "$(mail_count "$E1")" 1
L1=$(mail_link "$E1" signup)
check "el enlace apunta a la URL pública configurada (loopback en validación)" "$(case "$L1" in http://127.0.0.1:18080/auth/v1/verify\?*) echo ok;; *) echo "no: ${L1:0:40}";; esac)" ok
check "el correo no contiene la contraseña" "$(for id in $(mail_ids "$E1"); do curl -s "$MP/api/v1/message/$id"; done | grep -c "$P1" || true)" 0
check "inicio de sesión antes de confirmar: rechazado (email_not_confirmed)" "$(login "$E1" "$P1"; field 'd.get("error_code")')" "400email_not_confirmed"
res=$(visit "$L1"); code=$(echo "$res" | grep -oE '[?&]code=[^&#]+' | cut -d= -f2 || true)
check "clic en el enlace (navegador, sin apikey): redirige con un código PKCE" "$( [ -n "$code" ] && echo con-codigo)" con-codigo
check "intercambio del código con el verificador: sesión" "$(json_auth "token?grant_type=pkce" "{\"auth_code\":\"$code\",\"code_verifier\":\"$VER\"}"; field '"access_token" in d')" "200True"
check "el mismo código no se puede canjear dos veces" "$(json_auth "token?grant_type=pkce" "{\"auth_code\":\"$code\",\"code_verifier\":\"$VER\"}")" 404
check "tras confirmar, inicio de sesión normal: 200" "$(login "$E1" "$P1")" 200
check "reutilizar el enlace de confirmación: error (un solo uso)" "$(verdict "$(visit "$L1")")" error

# ---------- 2. Invalid and expired links, resend
E2=$(addr); P2=$(openssl rand -hex 16); json_auth signup "{\"email\":\"$E2\",\"password\":\"$P2\"}" >/dev/null
wait_mail "$E2" 1; L2=$(mail_link "$E2" signup)
check "enlace manipulado (token alterado): error" "$(verdict "$(visit "$(echo "$L2" | sed -E 's/(token=)[^&]{4}/\1abcd/')")")" error
check "  la cuenta sigue sin confirmar" "$(login "$E2" "$P2"; field 'd.get("error_code")')" "400email_not_confirmed"
db "update auth.users set confirmation_sent_at = now() - interval '2 hours' where email = '$E2'; update auth.one_time_tokens set created_at = now() - interval '2 hours', updated_at = now() - interval '2 hours' where user_id = (select id from auth.users where email = '$E2')" >/dev/null
check "enlace caducado (enviado hace 2 h, validez 1 h): error otp_expired" "$(visit "$L2" | grep -oE 'error_code=[a-z_]+' | head -1)" "error_code=otp_expired"
check "  la cuenta sigue sin confirmar" "$(login "$E2" "$P2"; field 'd.get("error_code")')" "400email_not_confirmed"
check "reenviar la confirmación: 200" "$(json_auth resend "{\"type\":\"signup\",\"email\":\"$E2\"}")" 200
wait_mail "$E2" 2; check "  llega un segundo correo" "$(mail_count "$E2")" 2
check "reenviar otra vez enseguida: 429 (máximo uno por minuto)" "$(json_auth resend "{\"type\":\"signup\",\"email\":\"$E2\"}"; field 'd.get("error_code")')" "429over_email_send_rate_limit"
check "el enlace nuevo confirma (implícito: sesión en el fragmento)" "$(verdict "$(visit "$(mail_link "$E2" signup)")")" ok
check "  el enlace antiguo (caducado) sigue sin servir" "$(verdict "$(visit "$L2")")" error

# ---------- 3. Account enumeration resistance
check "alta con un correo ya confirmado: misma respuesta 200 que un alta nueva" "$(json_auth signup "{\"email\":\"$E1\",\"password\":\"$(openssl rand -hex 16)\"}")$(field '"id" in d')" "200True"
check "  y no se envía ningún correo a la cuenta existente" "$(mail_count "$E1")" 1
r_exist=$(json_auth recover "{\"email\":\"$E1\"}")$(cat "$BODY"); NOBODY=$(addr)
r_none=$(json_auth recover "{\"email\":\"$NOBODY\"}")$(cat "$BODY")
check "recuperación: cuenta existente e inexistente responden igual" "$( [ "$r_exist" = "$r_none" ] && echo igual || echo "distinto")" igual
check "  a la inexistente no le llega nada" "$(mail_count "$NOBODY")" 0
w1=$(login "$E1" wrong-password-123)$(field 'd.get("error_code")'); w2=$(login "$NOBODY" wrong-password-123)$(field 'd.get("error_code")')
check "contraseña errónea y cuenta inexistente: mismo error" "$w1|$w2" "400invalid_credentials|400invalid_credentials"

# ---------- 4. Password reset (recovery link -> session -> new password)
wait_mail "$E1" 2; check "recuperación: llega el correo" "$(mail_count "$E1")" 2
check "segunda petición de recuperación enseguida: 429" "$(json_auth recover "{\"email\":\"$E1\"}")" 429
res=$(visit "$(mail_link "$E1" recovery)"); RAT=$(echo "$res" | grep -oE 'access_token=[^&]+' | cut -d= -f2 || true)
check "el enlace de recuperación abre una sesión de recuperación" "$( [ -n "$RAT" ] && echo sesión)" sesión
NEWP=$(openssl rand -hex 16)
check "cambiar la contraseña con esa sesión: 200" "$(http -X PUT "$BASE/auth/v1/user" -H "apikey: $KEY" -H "Authorization: Bearer $RAT" -H 'Content-Type: application/json' -d "{\"password\":\"$NEWP\"}")" 200
check "la contraseña antigua ya no sirve" "$(login "$E1" "$P1")" 400
check "la nueva sí" "$(login "$E1" "$NEWP")" 200
AT1=$(field 'd["access_token"]')
check "contraseña demasiado corta (< 10): rechazada" "$(http -X PUT "$BASE/auth/v1/user" -H "apikey: $KEY" -H "Authorization: Bearer $AT1" -H 'Content-Type: application/json' -d '{"password":"corta1"}')" 422

# ---------- 5. Secure e-mail change: BOTH the old and the new address must confirm
E1N=$(addr)
check "solicitar cambio de correo: 200" "$(http -X PUT "$BASE/auth/v1/user" -H "apikey: $KEY" -H "Authorization: Bearer $AT1" -H 'Content-Type: application/json' -d "{\"email\":\"$E1N\"}")" 200
wait_mail "$E1N" 1; check "  llega un correo a la dirección nueva" "$(mail_count "$E1N")" 1
wait_mail "$E1" 3; check "  y otro a la antigua (3 en total para ella)" "$(mail_count "$E1")" 3
visit "$(mail_link "$E1N" email_change)" >/dev/null
check "confirmar solo desde la nueva: el correo NO cambia todavía" "$(db "select email from auth.users where email in ('$E1', '$E1N')")" "$E1"
visit "$(mail_link "$E1" email_change)" >/dev/null
check "confirmar también desde la antigua: el correo cambia" "$(db "select email from auth.users where email in ('$E1', '$E1N')")" "$E1N"
check "inicio de sesión con el correo nuevo" "$(login "$E1N" "$NEWP")" 200

# ---------- 6. Invalid addresses
check "alta con una dirección no válida: 400" "$(json_auth signup "{\"email\":\"no-es-un-correo\",\"password\":\"$(openssl rand -hex 16)\"}")" 400
check "alta sin contraseña: 4xx" "$(c=$(json_auth signup "{\"email\":\"$(addr)\"}"); case "$c" in 4*) echo 4xx;; *) echo "$c";; esac)" 4xx

echo "RESULTADO CORREO: $pass superadas, $fail fallidas"
[ "$fail" -eq 0 ]

#!/usr/bin/env bash
# Account deletion over HTTP against a VALIDATION / STAGING stack with real GoTrue tokens (synthetic accounts only).
# When TURNO_TEST_DB_CONTAINER is given (validation only), it also scans EVERY text/uuid/json column of the auth schema
# and the turno schemas for the deleted account's id and e-mail, so no GoTrue table without a cascading key is missed.
#   usage: TURNO_TEST_BASE=http://127.0.0.1:18080 TURNO_TEST_ANON_KEY=... TURNO_TEST_JWT_SECRET=... \
#          [TURNO_TEST_DB_CONTAINER=<validation db container>] tests/account-deletion-test.sh
set -euo pipefail
PY=python3; "$PY" -c "" 2>/dev/null || PY=python
BASE=${TURNO_TEST_BASE:?}; KEY=${TURNO_TEST_ANON_KEY:?}; SECRET=${TURNO_TEST_JWT_SECRET:?}; DB=${TURNO_TEST_DB_CONTAINER:-}
case "$BASE" in http://127.0.0.1:*|http://localhost:*) ;; *) echo "solo contra loopback o túnel SSH" >&2; exit 2;; esac
case "$DB" in ""|*validation*) ;; *) echo "TURNO_TEST_DB_CONTAINER solo puede ser un contenedor de validación" >&2; exit 2;; esac
BODY=$(mktemp); trap 'rm -f "$BODY"' EXIT
pass=0; fail=0
check() { if [ "$2" = "$3" ]; then echo "PASS  $1"; pass=$((pass + 1)); else echo "FAIL  $1 (esperado '$3', obtenido '$2')"; fail=$((fail + 1)); fi; }
denied() { case "$1" in 401|403) echo denegado;; *) echo "$1";; esac; }
http() { curl -s -o "$BODY" -w '%{http_code}' -m 15 "$@" || echo 000; }
field() { "$PY" -c 'import json,sys; d=json.load(open(sys.argv[1])); exec("print(" + sys.argv[2] + ")")' "$BODY" "$1"; }
claim() { "$PY" -c 'import base64,json,sys; p=sys.argv[1].split(".")[1]; p+="="*(-len(p)%4); print(json.loads(base64.urlsafe_b64decode(p)).get(sys.argv[2], ""))' "$1" "$2"; }
# Forge a token with the server secret for negative cases, copying a real token's claims and overriding some of them.
forge() { TOKEN=$1 OVERRIDE=$2 JWT_SECRET=$SECRET "$PY" -c 'import base64,hashlib,hmac,json,os
b=lambda d: base64.urlsafe_b64encode(d).rstrip(b"=").decode()
p=os.environ["TOKEN"].split(".")[1]; c=json.loads(base64.urlsafe_b64decode(p+"="*(-len(p)%4))); c.update(json.loads(os.environ["OVERRIDE"]))
m=b(b"{\"alg\":\"HS256\",\"typ\":\"JWT\"}")+"."+b(json.dumps(c).encode())
print(m+"."+b(hmac.new(os.environ["JWT_SECRET"].encode(),m.encode(),hashlib.sha256).digest()))'; }
uuid() { "$PY" -c 'import uuid; print(uuid.uuid4())'; }
now=$(date +%s); dev=77777777-7777-4777-8777-777777777777
account() { EMAIL="turno-test-del$1-$now@example.invalid"; PW=$(openssl rand -hex 16)
  http -X POST "$BASE/auth/v1/signup" -H "apikey: $KEY" -H 'Content-Type: application/json' -d "{\"email\":\"$EMAIL\",\"password\":\"$PW\"}" >/dev/null
  http -X POST "$BASE/auth/v1/token?grant_type=password" -H "apikey: $KEY" -H 'Content-Type: application/json' -d "{\"email\":\"$EMAIL\",\"password\":\"$PW\"}" >/dev/null
  AT=$(field 'd["access_token"]'); RT=$(field 'd["refresh_token"]'); }
push() { http -X POST "$BASE/rest/v1/rpc/sync_push" -H "apikey: $KEY" -H 'Content-Type: application/json' -H "Authorization: Bearer $1" \
  -d "{\"p_device_id\":\"$dev\",\"p_mutations\":[{\"operation_id\":\"$(uuid)\",\"entity\":\"SCHEDULED_DAY\",\"key\":\"$2\",\"op\":\"UPSERT\",\"payload\":\"1|$2|FREE|MANUAL|\",\"base_version\":null,\"data_format\":1}]}"; }
pull() { http -X POST "$BASE/rest/v1/rpc/sync_pull" -H "apikey: $KEY" -H 'Content-Type: application/json' -H "Authorization: Bearer $1" -d '{"p_since":0,"p_limit":100}'; }
del() { http -X POST "$BASE/rest/v1/rpc/delete_my_account" -H "apikey: $KEY" -H 'Content-Type: application/json' ${1:+-H "Authorization: Bearer $1"} -d "{\"p_confirmation\":\"${2:-ELIMINAR MI CUENTA}\"}"; }
db() { docker exec -i "$DB" psql -U supabase_admin -d postgres -XAt -c "$1" < /dev/null; }

account b; AT_B=$AT; RT_B=$RT; EMAIL_B=$EMAIL; push "$AT_B" 2027-09-01 >/dev/null
account a; AT_A=$AT; RT_A=$RT; EMAIL_A=$EMAIL; SUB_A=$(claim "$AT_A" sub); SID_A=$(claim "$AT_A" session_id)
check "A tiene datos" "$(push "$AT_A" 2027-09-01)" 200
push "$AT_A" 2027-09-02 >/dev/null

# ---------- Gateway surface
check "gateway: GET a delete_my_account = 404" "$(http "$BASE/rest/v1/rpc/delete_my_account" -H "apikey: $KEY")" 404
check "gateway: sin apikey = 404" "$(http -X POST "$BASE/rest/v1/rpc/delete_my_account" -H 'Content-Type: application/json' -d '{}')" 404

# ---------- Rejections (A must survive every one of them)
check "sin token: denegado" "$(denied "$(del "")")" denegado
check "clave anon como sesión: denegado" "$(denied "$(del "$KEY")")" denegado
check "frase de confirmación incorrecta: 400" "$(del "$AT_A" "borrar")" 400
check "autenticación de hace 1 h (amr antiguo, firma válida): denegado" "$(denied "$(del "$(forge "$AT_A" "{\"amr\":[{\"method\":\"password\",\"timestamp\":$((now - 3600))}]}")")")" denegado
check "token sin amr: denegado" "$(denied "$(del "$(forge "$AT_A" '{"amr":null}')")")" denegado
check "session_id inventado: denegado" "$(denied "$(del "$(forge "$AT_A" "{\"session_id\":\"$(uuid)\"}")")")" denegado
check "sub de B con la sesión de A: denegado" "$(denied "$(del "$(forge "$AT_A" "{\"sub\":\"$(claim "$AT_B" sub)\"}")")")" denegado
check "token caducado: 401" "$(del "$(forge "$AT_A" "{\"exp\":$((now - 120))}")")" 401
check "firma alterada: 401" "$(del "$(TOKEN=$AT_A "$PY" -c 'import os; h,p,s=os.environ["TOKEN"].split("."); i=len(s)//2; print(h+"."+p+"."+s[:i]+("A" if s[i]!="A" else "B")+s[i+1:])')")" 401
pull "$AT_A" >/dev/null; check "A sigue intacta tras los rechazos" "$(field 'len(d["changes"])')" 2

# Signed-out session: the access token is still unexpired, but the session is gone -> cannot delete.
http -X POST "$BASE/auth/v1/token?grant_type=password" -H "apikey: $KEY" -H 'Content-Type: application/json' -d "{\"email\":\"$EMAIL_A\",\"password\":\"$PW\"}" >/dev/null
AT_A2=$(field 'd["access_token"]')
http -X POST "$BASE/auth/v1/logout?scope=local" -H "apikey: $KEY" -H "Authorization: Bearer $AT_A2" >/dev/null
check "sesión cerrada (token de acceso aún vigente): no puede borrar" "$(denied "$(del "$AT_A2")")" denegado

# ---------- Valid deletion
check "borrado válido: 200" "$(del "$AT_A")" 200
check "borrado válido: DELETED con 2 registros" "$(field 'd["status"], d["records"]')" "DELETED 2"
check "repetir con el mismo token: 200" "$(del "$AT_A")" 200
check "repetir con el mismo token: ALREADY_DELETED (idempotente)" "$(field 'd["status"]')" ALREADY_DELETED
check "push con el token aún vigente: denegado (sin resurrección)" "$(denied "$(push "$AT_A" 2027-09-03)")" denegado
check "el error no expone nombres de tablas ni restricciones" "$(grep -ciE 'user_state|_fkey|turno\.|foreign key' "$BODY" || true)" 0
check "pull con el token aún vigente: denegado" "$(denied "$(pull "$AT_A")")" denegado
code=$(http -X POST "$BASE/auth/v1/token?grant_type=refresh_token" -H "apikey: $KEY" -H 'Content-Type: application/json' -d "{\"refresh_token\":\"$RT_A\"}")
check "el refresh token de A ya no sirve" "$(case "$code" in 400|401|403) echo rechazado;; *) echo "$code";; esac)" rechazado
code=$(http -X POST "$BASE/auth/v1/token?grant_type=password" -H "apikey: $KEY" -H 'Content-Type: application/json' -d "{\"email\":\"$EMAIL_A\",\"password\":\"$PW\"}")
check "A ya no puede iniciar sesión" "$(case "$code" in 400|401|403) echo rechazado;; *) echo "$code";; esac)" rechazado
pull "$AT_B" >/dev/null; check "B no pierde nada" "$(field 'len(d["changes"])')" 1
check "B sigue pudiendo renovar" "$(http -X POST "$BASE/auth/v1/token?grant_type=refresh_token" -H "apikey: $KEY" -H 'Content-Type: application/json' -d "{\"refresh_token\":\"$RT_B\"}")" 200

if [ -n "$DB" ]; then
  # Every text-like, uuid and json column of auth, turno and turno_ops: no trace of A's id or e-mail.
  left=$(db "do \$\$ declare c record; n bigint; total bigint := 0; begin
    for c in select table_schema s, table_name t, column_name col from information_schema.columns
             where table_schema in ('auth','turno','turno_ops') and data_type in ('uuid','text','character varying','json','jsonb')
               and table_name in (select table_name from information_schema.tables where table_schema in ('auth','turno','turno_ops') and table_type = 'BASE TABLE') loop
      execute format('select count(*) from %I.%I where %I::text like %L or %I::text like %L', c.s, c.t, c.col, '%$SUB_A%', c.col, '%$EMAIL_A%') into n;
      if n > 0 then raise notice 'resto en %.%.%: %', c.s, c.t, c.col, n; total := total + n; end if;
    end loop;
    perform set_config('turno.left', total::text, false); end \$\$; select current_setting('turno.left')" 2>&1 | tail -1)
  check "barrido de auth/turno/turno_ops: ningún resto del id ni del correo de A" "$left" 0
  check "B conserva sus entradas de auditoría" "$( [ "$(db "select count(*) from auth.audit_log_entries where payload->>'actor_id' = '$(claim "$AT_B" sub)'")" -gt 0 ] && echo sí)" sí
fi

echo "RESULTADO BORRADO HTTP: $pass superadas, $fail fallidas"
[ "$fail" -eq 0 ]

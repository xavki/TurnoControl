#!/usr/bin/env bash
# HTTP security tests against a RUNNING TurnoControl STAGING stack, through the gateway on the server's loopback (or an
# SSH tunnel to it). NOT executed in phase 5F.1B. Synthetic accounts only; never point it at a stack with real users.
#
# Staging prerequisites: TURNO_DISABLE_SIGNUP=false and TURNO_MAILER_AUTOCONFIRM=true, so two synthetic accounts can be
# created without e-mail. Put both back to their safe defaults afterwards.
#   usage: TURNO_TEST_BASE=http://127.0.0.1:18080 TURNO_TEST_ANON_KEY=... TURNO_TEST_JWT_SECRET=... tests/api-security-test.sh
# The JWT secret is only used to forge negative-test tokens (expired, wrong audience, wrong role); it is read from the
# environment and never printed. Exit code 0 only if every check passes.
set -euo pipefail
for c in curl openssl python3; do command -v "$c" >/dev/null || { echo "falta $c" >&2; exit 2; }; done
BASE=${TURNO_TEST_BASE:?}; KEY=${TURNO_TEST_ANON_KEY:?}; SECRET=${TURNO_TEST_JWT_SECRET:?}
case "$BASE" in http://127.0.0.1:*|http://localhost:*) ;; *) echo "solo contra el gateway en loopback o túnel SSH" >&2; exit 2;; esac
BODY=$(mktemp); trap 'rm -f "$BODY"' EXIT
pass=0; fail=0
check() { if [ "$2" = "$3" ]; then echo "PASS  $1"; pass=$((pass + 1)); else echo "FAIL  $1 (esperado '$3', obtenido '$2')"; fail=$((fail + 1)); fi; }
denied() { case "$1" in 401|403) echo denegado;; *) echo "$1";; esac; }
jwt() { # $1 = payload JSON, $2 = signing secret. Signed in python, reading the secret from the environment, so it never
  # appears on a command line.
  JWT_PAYLOAD=$1 JWT_SECRET=$2 python3 -c 'import base64,hashlib,hmac,os
b=lambda d: base64.urlsafe_b64encode(d).rstrip(b"=").decode()
m=b(b"{\"alg\":\"HS256\",\"typ\":\"JWT\"}")+"."+b(os.environ["JWT_PAYLOAD"].encode())
print(m+"."+b(hmac.new(os.environ["JWT_SECRET"].encode(),m.encode(),hashlib.sha256).digest()))'; }
http() { curl -s -o "$BODY" -w '%{http_code}' -m 10 "$@" || echo 000; }
field() { python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); exec("print(" + sys.argv[2] + ")")' "$BODY" "$1"; }
rpc() { local token=$1 fn=$2 data=$3
  if [ -n "$token" ]; then http -X POST "$BASE/rest/v1/rpc/$fn" -H "apikey: $KEY" -H 'Content-Type: application/json' -H "Authorization: Bearer $token" -d "$data"
  else http -X POST "$BASE/rest/v1/rpc/$fn" -H "apikey: $KEY" -H 'Content-Type: application/json' -d "$data"; fi; }
uuid() { python3 -c 'import uuid; print(uuid.uuid4())'; }
claim() { python3 -c 'import base64,json,sys; p=sys.argv[1].split(".")[1]; p+="="*(-len(p)%4); print(json.loads(base64.urlsafe_b64decode(p))[sys.argv[2]])' "$1" "$2"; }
now=$(date +%s); dev=11111111-1111-4111-8111-111111111111
signup() { # echoes the access token of a fresh synthetic account
  local email="turno-test-$1-$now@example.invalid" pw; pw=$(openssl rand -hex 16)
  http -X POST "$BASE/auth/v1/signup" -H "apikey: $KEY" -H 'Content-Type: application/json' -d "{\"email\":\"$email\",\"password\":\"$pw\"}" >/dev/null
  http -X POST "$BASE/auth/v1/token?grant_type=password" -H "apikey: $KEY" -H 'Content-Type: application/json' -d "{\"email\":\"$email\",\"password\":\"$pw\"}" >/dev/null
  field 'd.get("access_token", "")'; }
mut() { # $1 op id, $2 key, $3 payload JSON string, $4 base_version
  printf '{"p_device_id":"%s","p_mutations":[{"operation_id":"%s","entity":"SCHEDULED_DAY","key":"%s","op":"UPSERT","payload":%s,"base_version":%s,"data_format":1}]}' "$dev" "$1" "$2" "$3" "$4"; }
pull='{"p_since":0,"p_limit":100}'

# ---------- Gateway surface (deny by default)
check "gateway: /healthz"                        "$(http "$BASE/healthz")" 200
check "gateway: raíz REST = 404"                 "$(http "$BASE/rest/v1/" -H "apikey: $KEY")" 404
check "gateway: tabla directa = 404"             "$(http "$BASE/rest/v1/records" -H "apikey: $KEY")" 404
check "gateway: otra función = 404"              "$(http -X POST "$BASE/rest/v1/rpc/current_account" -H "apikey: $KEY")" 404
check "gateway: GoTrue /admin = 404"             "$(http "$BASE/auth/v1/admin/users" -H "apikey: $KEY")" 404
check "gateway: GoTrue /authorize = 404"         "$(http "$BASE/auth/v1/authorize?provider=google" -H "apikey: $KEY")" 404
check "gateway: GET a la RPC = 404"              "$(http "$BASE/rest/v1/rpc/sync_pull" -H "apikey: $KEY")" 404
check "gateway: sin apikey = 404"                "$(http -X POST "$BASE/rest/v1/rpc/sync_pull" -H 'Content-Type: application/json' -d '{}')" 404
check "gateway: apikey incorrecta = 404"         "$(http -X POST "$BASE/rest/v1/rpc/sync_pull" -H 'apikey: x' -H 'Content-Type: application/json' -d '{}')" 404

# ---------- Authentication
A=$(signup a); B=$(signup b)
if [ -z "$A" ] || [ -z "$B" ]; then echo "no se pudieron crear las cuentas sintéticas (¿staging con alta y autoconfirmación?)" >&2; exit 1; fi
SUB_A=$(claim "$A" sub)
check "JWT de GoTrue: role=authenticated"        "$(claim "$A" role)" authenticated
check "JWT de GoTrue: aud=authenticated"         "$(claim "$A" aud)" authenticated
check "sin JWT: denegado"                        "$(denied "$(rpc "" sync_pull "$pull")")" denegado
check "JWT válido: aceptado"                     "$(rpc "$A" sync_pull "$pull")" 200
# Alter an INNER signature character: the last base64url character of a 32-byte HS256 signature carries only 4 significant
# bits, so changing it can decode to the very same signature (an intermittent false failure seen in 5F.1C stage 0).
tampered=$(TOKEN="$A" python3 -c 'import os
h,p,s=os.environ["TOKEN"].split("."); i=len(s)//2; c="A" if s[i]!="A" else "B"
print(h+"."+p+"."+s[:i]+c+s[i+1:])')
check "firma alterada (carácter interior): 401"   "$(rpc "$tampered" sync_pull "$pull")" 401
check "JWT basura: 401"                          "$(rpc "abc.def.ghi" sync_pull "$pull")" 401
check "firmado con otro secreto: 401"            "$(rpc "$(jwt "{\"sub\":\"$SUB_A\",\"role\":\"authenticated\",\"aud\":\"authenticated\",\"exp\":$((now + 600))}" wrong-secret)" sync_pull "$pull")" 401
check "JWT caducado: 401"                        "$(rpc "$(jwt "{\"sub\":\"$SUB_A\",\"role\":\"authenticated\",\"aud\":\"authenticated\",\"exp\":$((now - 120))}" "$SECRET")" sync_pull "$pull")" 401
check "audiencia incorrecta: 401"                "$(rpc "$(jwt "{\"sub\":\"$SUB_A\",\"role\":\"authenticated\",\"aud\":\"otra\",\"exp\":$((now + 600))}" "$SECRET")" sync_pull "$pull")" 401
check "clave anon como sesión: denegado"         "$(denied "$(rpc "$KEY" sync_pull "$pull")")" denegado
check "role=service_role: denegado"              "$(denied "$(rpc "$(jwt "{\"sub\":\"$SUB_A\",\"role\":\"service_role\",\"aud\":\"authenticated\",\"exp\":$((now + 600))}" "$SECRET")" sync_pull "$pull")")" denegado
check "role=supabase_admin: denegado"            "$(denied "$(rpc "$(jwt "{\"sub\":\"$SUB_A\",\"role\":\"supabase_admin\",\"aud\":\"authenticated\",\"exp\":$((now + 600))}" "$SECRET")" sync_pull "$pull")")" denegado

# ---------- Authorization and synchronization
op1=$(uuid)
rpc "$A" sync_push "$(mut "$op1" 2026-12-01 '"1|2026-12-01|FREE|MANUAL|"' null)" >/dev/null
check "A sube un día: APPLIED"                   "$(field 'd[0]["status"]')" APPLIED
rpc "$A" sync_push "$(mut "$op1" 2026-12-01 '"1|2026-12-01|FREE|MANUAL|"' null)" >/dev/null
check "misma operación repetida: idempotente"    "$(field 'd[0]["status"]')" APPLIED
rpc "$A" sync_pull "$pull" >/dev/null
check "A ve un único cambio"                     "$(field 'len(d["changes"])')" 1
rpc "$B" sync_pull "$pull" >/dev/null
check "B no ve los datos de A"                   "$(field 'len(d["changes"])')" 0
# Valid content on purpose: content validation runs before the version check, so an invalid payload would be REJECTED
# and never exercise isolation. B sees no record with that key in ITS account, so base_version=1 must conflict.
rpc "$B" sync_push "$(mut "$(uuid)" 2026-12-01 '"1|2026-12-01|VACATION|MANUAL|"' 1)" >/dev/null
check "B no alcanza el registro de A (CONFLICT)" "$(field 'd[0]["status"]')" CONFLICT
check "B: el conflicto no revela el registro de A" "$(field 'd[0].get("remote")')" None
rpc "$A" sync_pull "$pull" >/dev/null
check "el registro de A sigue intacto"           "$(field 'd["changes"][0]["payload"]')" "1|2026-12-01|FREE|MANUAL|"
check "user_id inyectado: lote rechazado"        "$(rpc "$A" sync_push "{\"p_device_id\":\"$dev\",\"p_mutations\":[{\"operation_id\":\"$(uuid)\",\"entity\":\"SCHEDULED_DAY\",\"key\":\"2026-12-02\",\"op\":\"UPSERT\",\"payload\":\"1|2026-12-02|FREE|MANUAL|\",\"base_version\":null,\"data_format\":1,\"user_id\":\"$SUB_A\"}]}")" 400
rpc "$A" sync_push "$(mut "$(uuid)" 2026-12-03 '"1|2026-12-03|SICK_LEAVE|MANUAL|gripe"' null)" >/dev/null
check "baja con texto médico: REJECTED"          "$(field 'd[0]["status"]')" REJECTED
rpc "$A" sync_push "$(mut "$(uuid)" 2026-12-04 '"1|2026-12-04|SICK_LEAVE|MANUAL|"' null)" >/dev/null
check "baja solo fecha y estado: APPLIED"        "$(field 'd[0]["status"]')" APPLIED
check "cursor negativo: 400"                     "$(rpc "$A" sync_pull '{"p_since":-1,"p_limit":10}')" 400
check "lote vacío: 400"                          "$(rpc "$A" sync_push "{\"p_device_id\":\"$dev\",\"p_mutations\":[]}")" 400
check "JSON malformado: 400"                     "$(rpc "$A" sync_push '{"p_device_id":')" 400
rpc "$A" sync_pull '{"p_since":0,"p_limit":1}' >/dev/null
check "paginación: has_more con límite 1"        "$(field 'd["has_more"]')" True

# ---------- Token manipulation and escalation (5F.1C stage 0)
SUB_B=$(claim "$B" sub)
# Same signature, payload swapped to B's subject: must fail signature verification.
swapped="$(printf '%s' "$A" | cut -d. -f1).$(SWAP_SUB="$SUB_B" python3 -c 'import base64,json,os,sys
p=sys.argv[1].split(".")[1]; d=json.loads(base64.urlsafe_b64decode(p+"="*(-len(p)%4))); d["sub"]=os.environ["SWAP_SUB"]
print(base64.urlsafe_b64encode(json.dumps(d,separators=(",",":")).encode()).rstrip(b"=").decode())' "$A").$(printf '%s' "$A" | cut -d. -f3)"
check "payload cambiado a B con la firma de A: 401" "$(rpc "$swapped" sync_pull "$pull")" 401
# Correctly signed token for A carrying misleading extra claims: ownership must still come from `sub`.
rpc "$A" sync_pull "$pull" >/dev/null; a_view=$(field 'sorted((c["key"], c["version"]) for c in d["changes"])')
rpc "$(jwt "{\"sub\":\"$SUB_A\",\"role\":\"authenticated\",\"aud\":\"authenticated\",\"exp\":$((now + 600)),\"user_id\":\"$SUB_B\",\"account\":\"$SUB_B\"}" "$SECRET")" sync_pull "$pull" >/dev/null
check "claims extra con la cuenta de B: ve exactamente lo de A" "$(field 'sorted((c["key"], c["version"]) for c in d["changes"])')" "$a_view"
check "token sin sub: denegado"                  "$(denied "$(rpc "$(jwt "{\"role\":\"authenticated\",\"aud\":\"authenticated\",\"exp\":$((now + 600))}" "$SECRET")" sync_pull "$pull")")" denegado
check "sub que no es UUID: denegado"             "$(denied "$(rpc "$(jwt "{\"sub\":\"x'; drop table turno.records; --\",\"role\":\"authenticated\",\"aud\":\"authenticated\",\"exp\":$((now + 600))}" "$SECRET")" sync_pull "$pull")")" denegado
check "token sin exp: denegado"                  "$(denied "$(rpc "$(jwt "{\"sub\":\"$SUB_A\",\"role\":\"authenticated\",\"aud\":\"authenticated\"}" "$SECRET")" sync_pull "$pull")")" denegado
check "alg=none sin firma: 401"                  "$(rpc "$(printf '%s' '{"alg":"none","typ":"JWT"}' | python3 -c 'import base64,sys;print(base64.urlsafe_b64encode(sys.stdin.read().encode()).rstrip(b"=").decode())').$(printf '%s' "$A" | cut -d. -f2)." sync_pull "$pull")" 401
for r in turno_owner postgres supabase_admin authenticator supabase_auth_admin; do
  check "role=$r falsificado: denegado"          "$(denied "$(rpc "$(jwt "{\"sub\":\"$SUB_A\",\"role\":\"$r\",\"aud\":\"authenticated\",\"exp\":$((now + 600))}" "$SECRET")" sync_pull "$pull")")" denegado
done
# Issuer: PostgREST does not check `iss` (documented limitation; the signature already binds the token to this server).
echo "INFO  iss ajeno con firma válida: HTTP $(rpc "$(jwt "{\"sub\":\"$SUB_A\",\"role\":\"authenticated\",\"aud\":\"authenticated\",\"iss\":\"https://evil.example.invalid\",\"exp\":$((now + 600))}" "$SECRET")" sync_pull "$pull") (PostgREST no valida iss)"

# ---------- Malformed, oversized and injection input
big=$(python3 -c 'print("{\"p_device_id\":\"11111111-1111-4111-8111-111111111111\",\"p_mutations\":[],\"pad\":\"" + "x"*2200000 + "\"}")')
check "petición de más de 2 MB: 413"            "$(printf '%s' "$big" | curl -s -o "$BODY" -w '%{http_code}' -m 20 -X POST "$BASE/rest/v1/rpc/sync_push" -H "apikey: $KEY" -H 'Content-Type: application/json' -H "Authorization: Bearer $A" --data-binary @- || echo 000)" 413
check "cursor con inyección SQL: 400"           "$(rpc "$A" sync_pull '{"p_since":"0; drop table turno.records","p_limit":1}')" 400
rpc "$A" sync_push "$(mut "$(uuid)" "2026-12-05'; drop table turno.records; --" '"1|2026-12-05|FREE|MANUAL|"' null)" >/dev/null
check "clave con inyección SQL: REJECTED"        "$(field 'd[0]["status"]')" REJECTED
check "operation_id que no es UUID: 400"         "$(rpc "$A" sync_push "{\"p_device_id\":\"$dev\",\"p_mutations\":[{\"operation_id\":\"no-uuid\",\"entity\":\"SCHEDULED_DAY\",\"key\":\"2026-12-06\",\"op\":\"UPSERT\",\"payload\":\"1|2026-12-06|FREE|MANUAL|\",\"base_version\":null,\"data_format\":1}]}")" 400
check "lote de 101 operaciones: 400"             "$(rpc "$A" sync_push "$(python3 -c 'import json,uuid;print(json.dumps({"p_device_id":"11111111-1111-4111-8111-111111111111","p_mutations":[{"operation_id":str(uuid.uuid4()),"entity":"SCHEDULED_DAY","key":"2027-01-01","op":"UPSERT","payload":"1|2027-01-01|FREE|MANUAL|","base_version":None,"data_format":1}]*101}))')")" 400
check "parámetro p_user_id inventado: rechazado" "$( [ "$(rpc "$A" sync_pull "{\"p_since\":0,\"p_limit\":10,\"p_user_id\":\"$SUB_B\"}")" != 200 ] && echo rechazado)" rechazado

# ---------- Concurrency and replay over HTTP
k=2026-12-10; opc1=$(uuid); opc2=$(uuid)
rpc "$A" sync_push "$(mut "$opc1" "$k" '"1|2026-12-10|FREE|MANUAL|"' null)" >/dev/null &
c1=$!
( curl -s -o "$BODY.2" -m 10 -X POST "$BASE/rest/v1/rpc/sync_push" -H "apikey: $KEY" -H 'Content-Type: application/json' -H "Authorization: Bearer $A" -d "$(mut "$opc2" "$k" '"1|2026-12-10|VACATION|MANUAL|"' null)" ) &
c2=$!
wait $c1; wait $c2
statuses=$(python3 -c 'import json,sys; print(" ".join(sorted(json.load(open(f))[0]["status"] for f in sys.argv[1:])))' "$BODY" "$BODY.2")
rm -f "$BODY.2"
check "dos envíos simultáneos del mismo día: uno aplicado y otro en conflicto" "$statuses" "APPLIED CONFLICT"
rpc "$A" sync_pull "$pull" >/dev/null
check "un único registro para ese día"           "$(field "sum(1 for c in d['changes'] if c['key'] == '$k')")" 1
rpc "$A" sync_push "$(mut "$opc1" "$k" '"1|2026-12-10|FREE|MANUAL|"' null)" >/dev/null; r1=$(field 'd[0]["status"]')
rpc "$A" sync_push "$(mut "$opc2" "$k" '"1|2026-12-10|VACATION|MANUAL|"' null)" >/dev/null; r2=$(field 'd[0]["status"]')
check "reenvío de ambas operaciones (tras «timeout»): misma respuesta que la primera vez" "$(echo "$r1 $r2" | tr ' ' '\n' | sort | tr '\n' ' ')" "APPLIED CONFLICT "
rpc "$A" sync_pull "$pull" >/dev/null
ver=$(field "[c['version'] for c in d['changes'] if c['key'] == '$k'][0]")
rpc "$A" sync_push "$(mut "$(uuid)" "$k" '"1|2026-12-10|FREE|MANUAL|"' "$ver")" >/dev/null
check "reintento tras conflicto con la versión remota: APPLIED" "$(field 'd[0]["status"]')" APPLIED
rpc "$A" sync_push "$(mut "$(uuid)" "$k" null "$((ver + 1))" | sed 's/"op":"UPSERT"/"op":"DELETE"/')" >/dev/null
check "borrado (lápida): APPLIED"                "$(field 'd[0]["status"]')" APPLIED
rpc "$A" sync_pull "$pull" >/dev/null
check "la lápida se sincroniza como deleted sin contenido" "$(field "[(c['deleted'], c['payload']) for c in d['changes'] if c['key'] == '$k'][0]")" "(True, None)"
rpc "$B" sync_pull "$pull" >/dev/null
check "B sigue sin ver nada de A tras todo lo anterior" "$(field 'all(c["key"] not in ("2026-12-01","2026-12-04","2026-12-10") for c in d["changes"])')" True

# ---------- Session lifecycle
http -X POST "$BASE/auth/v1/logout" -H "apikey: $KEY" -H "Authorization: Bearer $B" >/dev/null
check "tras logout, GoTrue rechaza la sesión de B" "$(denied "$(http "$BASE/auth/v1/user" -H "apikey: $KEY" -H "Authorization: Bearer $B")")" denegado
# Known limitation (documented): PostgREST validates JWTs statelessly, so B's ACCESS token keeps working on the RPCs
# until it expires (TURNO_JWT_EXPIRY, 1 h). This line records the observed behaviour instead of asserting it.
echo "INFO  RPC con el token de B tras logout: HTTP $(rpc "$B" sync_pull "$pull") (200 esperado hasta que caduque)"

echo "RESULTADO API: $pass superadas, $fail fallidas"
echo "Limpieza: borra las cuentas turno-test-*-$now@example.invalid con la API de administración (SECURITY.md)."
[ "$fail" -eq 0 ]

#!/usr/bin/env bash
# Data-integrity round trip against a running STAGING / validation stack, with synthetic accounts and synthetic days:
# normal, split, overnight and overtime shifts, vacation, free day and sick leave (date + SICK_LEAVE only). Checks that
# every accepted payload comes back byte-identical, that invalid days are rejected, that conflicts and tombstones behave,
# and that two accounts writing the SAME keys with different content stay completely isolated.
#   usage: TURNO_TEST_BASE=http://127.0.0.1:18080 TURNO_TEST_ANON_KEY=... tests/data-integrity-test.sh
set -euo pipefail
PY=python3; "$PY" -c "" 2>/dev/null || PY=python
BASE=${TURNO_TEST_BASE:?}; KEY=${TURNO_TEST_ANON_KEY:?}
case "$BASE" in http://127.0.0.1:*|http://localhost:*) ;; *) echo "solo contra loopback o túnel SSH" >&2; exit 2;; esac
BODY=$(mktemp); trap 'rm -f "$BODY"' EXIT
pass=0; fail=0
check() { if [ "$2" = "$3" ]; then echo "PASS  $1"; pass=$((pass + 1)); else echo "FAIL  $1 (esperado '$3', obtenido '$2')"; fail=$((fail + 1)); fi; }
http() { curl -s -o "$BODY" -w '%{http_code}' -m 15 "$@" || echo 000; }
field() { "$PY" -c 'import json,sys; d=json.load(open(sys.argv[1])); exec("print(" + sys.argv[2] + ")")' "$BODY" "$1"; }
uuid() { "$PY" -c 'import uuid; print(uuid.uuid4())'; }
now=$(date +%s); dev=33333333-3333-4333-8333-333333333333
token() { local email="turno-test-int$1-$now@example.invalid" pw; pw=$(openssl rand -hex 16)
  http -X POST "$BASE/auth/v1/signup" -H "apikey: $KEY" -H 'Content-Type: application/json' -d "{\"email\":\"$email\",\"password\":\"$pw\"}" >/dev/null
  http -X POST "$BASE/auth/v1/token?grant_type=password" -H "apikey: $KEY" -H 'Content-Type: application/json' -d "{\"email\":\"$email\",\"password\":\"$pw\"}" >/dev/null
  field 'd.get("access_token", "")'; }
# One mutation; $4 = base_version (null or a number), $5 = op (UPSERT/DELETE).
push() { local tok=$1 key=$2 payload=$3 base=${4:-null} op=${5:-UPSERT} pj
  if [ "$op" = DELETE ]; then pj=null; else pj="\"$payload\""; fi
  http -X POST "$BASE/rest/v1/rpc/sync_push" -H "apikey: $KEY" -H 'Content-Type: application/json' -H "Authorization: Bearer $tok" \
    -d "{\"p_device_id\":\"$dev\",\"p_mutations\":[{\"operation_id\":\"$(uuid)\",\"entity\":\"SCHEDULED_DAY\",\"key\":\"$key\",\"op\":\"$op\",\"payload\":$pj,\"base_version\":$base,\"data_format\":1}]}" >/dev/null
  field 'd[0]["status"]'; }
pull() { http -X POST "$BASE/rest/v1/rpc/sync_pull" -H "apikey: $KEY" -H 'Content-Type: application/json' -H "Authorization: Bearer $1" -d '{"p_since":0,"p_limit":1000}' >/dev/null; }
remote() { pull "$1"; field "next(((c['version'], c['deleted'], c['payload']) for c in d['changes'] if c['key'] == '$2'), None)"; }

A=$(token a); B=$(token b)
[ -n "$A" ] && [ -n "$B" ] || { echo "no se pudieron crear las cuentas sintéticas" >&2; exit 1; }

# ---------- Valid synthetic days (A)
declare -A DAYS=(
  [2027-03-01]='1|2027-03-01|WORK|MANUAL|08:00+0-15:00+0:N'
  [2027-03-02]='1|2027-03-02|WORK|MANUAL|08:00+0-12:00+0:N,16:00+0-20:00+0:N'
  [2027-03-03]='1|2027-03-03|WORK|MANUAL|22:00+0-06:00+1:N'
  [2027-03-04]='1|2027-03-04|WORK|MANUAL|08:00+0-16:00+0:N,16:00+0-19:30+0:E'
  [2027-03-05]='1|2027-03-05|VACATION|MANUAL|'
  [2027-03-06]='1|2027-03-06|FREE|MANUAL|'
  [2027-03-07]='1|2027-03-07|SICK_LEAVE|MANUAL|'
  [2027-03-08]='1|2027-03-08|WORK|OCR_REVIEWED|07:00+0-15:00+0:N'
)
declare -A LABEL=([2027-03-01]="turno normal" [2027-03-02]="turno partido" [2027-03-03]="turno nocturno (cruza medianoche)" [2027-03-04]="horas extra"
  [2027-03-05]="vacaciones" [2027-03-06]="día libre" [2027-03-07]="baja (solo fecha y estado)" [2027-03-08]="turno revisado por OCR")
for k in $(printf '%s\n' "${!DAYS[@]}" | sort); do
  check "A: ${LABEL[$k]} aceptado" "$(push "$A" "$k" "${DAYS[$k]}")" APPLIED
done
pull "$A"
for k in $(printf '%s\n' "${!DAYS[@]}" | sort); do
  check "A: ${LABEL[$k]} vuelve idéntico byte a byte" "$(field "next(c['payload'] for c in d['changes'] if c['key'] == '$k')")" "${DAYS[$k]}"
done

# ---------- Invalid days must be rejected (and never stored)
declare -A BAD=(
  [2027-04-01]='1|2027-04-01|SICK_LEAVE|MANUAL|gripe'
  [2027-04-02]='1|2027-04-02|SICK_LEAVE|OCR_REVIEWED|'
  [2027-04-03]='1|2027-04-03|WORK|MANUAL|08:00+0-14:00+0:N,13:00+0-18:00+0:N'
  [2027-04-04]='1|2027-04-04|WORK|MANUAL|00:00+0-23:00+0:N,23:00+0-02:00+1:E'
  [2027-04-05]='1|2027-04-06|FREE|MANUAL|'
  [2027-04-06]='1|2027-04-06|VACATION|MANUAL|08:00+0-15:00+0:N'
  [2027-04-07]='1|2027-04-07|WORK|MANUAL|'
  [2027-04-08]='1|2027-04-08|HOLIDAY|MANUAL|'
)
declare -A BLABEL=([2027-04-01]="baja con texto médico" [2027-04-02]="baja con origen OCR" [2027-04-03]="intervalos solapados" [2027-04-04]="más de 24 h"
  [2027-04-05]="fecha del contenido distinta de la clave" [2027-04-06]="vacaciones con intervalos" [2027-04-07]="trabajo sin intervalos" [2027-04-08]="tipo de día desconocido")
for k in $(printf '%s\n' "${!BAD[@]}" | sort); do
  check "A: ${BLABEL[$k]} rechazado" "$(push "$A" "$k" "${BAD[$k]}")" REJECTED
done
pull "$A"
check "A: ningún día inválido se almacenó" "$(field "sum(1 for c in d['changes'] if c['key'].startswith('2027-04-'))")" 0

# ---------- Conflict, resolution and tombstone (A)
check "A: crear sin base sobre un día existente = CONFLICT" "$(push "$A" 2027-03-05 '1|2027-03-05|FREE|MANUAL|')" CONFLICT
check "A: el conflicto no cambió nada" "$(remote "$A" 2027-03-05)" "(1, False, '1|2027-03-05|VACATION|MANUAL|')"
check "A: base antigua (0) = CONFLICT" "$(push "$A" 2027-03-05 '1|2027-03-05|FREE|MANUAL|' 0)" CONFLICT
check "A: resolver con la versión remota (1) = APPLIED" "$(push "$A" 2027-03-05 '1|2027-03-05|FREE|MANUAL|' 1)" APPLIED
check "A: ahora versión 2 con el nuevo contenido" "$(remote "$A" 2027-03-05)" "(2, False, '1|2027-03-05|FREE|MANUAL|')"
check "A: borrar con base 2 = APPLIED" "$(push "$A" 2027-03-05 '' 2 DELETE)" APPLIED
check "A: lápida en versión 3, sin contenido" "$(remote "$A" 2027-03-05)" "(3, True, None)"
check "A: recrear tras la lápida con base 3 = APPLIED" "$(push "$A" 2027-03-05 '1|2027-03-05|VACATION|MANUAL|' 3)" APPLIED
check "A: versión 4, viva otra vez" "$(remote "$A" 2027-03-05)" "(4, False, '1|2027-03-05|VACATION|MANUAL|')"

# ---------- Isolation: B writes the SAME keys with different content
for k in 2027-03-01 2027-03-03 2027-03-07; do
  check "B: misma clave $k que A, contenido propio = APPLIED" "$(push "$B" "$k" "1|$k|FREE|MANUAL|")" APPLIED
done
pull "$B"
check "B: ve exactamente sus 3 días, ninguno de A" "$(field "sorted((c['key'], c['payload']) for c in d['changes'])")" "[('2027-03-01', '1|2027-03-01|FREE|MANUAL|'), ('2027-03-03', '1|2027-03-03|FREE|MANUAL|'), ('2027-03-07', '1|2027-03-07|FREE|MANUAL|')]"
check "B: no ve la baja de A" "$(field "any('SICK_LEAVE' in (c['payload'] or '') for c in d['changes'])")" False
pull "$A"
check "A: sus días no cambiaron por las escrituras de B" "$(field "[next(c['payload'] for c in d['changes'] if c['key'] == k) for k in ('2027-03-01','2027-03-03','2027-03-07')]")" "['1|2027-03-01|WORK|MANUAL|08:00+0-15:00+0:N', '1|2027-03-03|WORK|MANUAL|22:00+0-06:00+1:N', '1|2027-03-07|SICK_LEAVE|MANUAL|']"
check "A: seq estrictamente creciente" "$(field "(lambda s: s == sorted(set(s)))([c['seq'] for c in d['changes']])")" True
# Cursor pagination over A's changes, page size 3: pages concatenate to the full list with no gaps or repeats.
pull "$A"; full=$(field "[c['seq'] for c in d['changes']]")
cursor=0; pages=""; for _ in $(seq 1 20); do
  http -X POST "$BASE/rest/v1/rpc/sync_pull" -H "apikey: $KEY" -H 'Content-Type: application/json' -H "Authorization: Bearer $A" -d "{\"p_since\":$cursor,\"p_limit\":3}" >/dev/null
  pages="$pages$(field "','.join(str(c['seq']) for c in d['changes'])"),"; more=$(field 'd["has_more"]'); cursor=$(field 'd["next_cursor"]')
  [ "$more" = True ] || break
done
check "A: la paginación con límite 3 reconstruye la lista completa" "[$(echo "$pages" | tr -s ',' | sed 's/^,//; s/,$//; s/,/, /g')]" "$full"

echo "RESULTADO INTEGRIDAD: $pass superadas, $fail fallidas"
[ "$fail" -eq 0 ]

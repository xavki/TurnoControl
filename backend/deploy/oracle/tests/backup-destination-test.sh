#!/usr/bin/env bash
# Off-server backup tests with FAKE storage (dir adapter in a temporary directory). Needs a running VALIDATION stack and
# the usual TURNO_PROJECT / TURNO_CONTAINER_PREFIX / TURNO_ENV_FILE variables pointing at it. Synthetic data only.
# Creates its own age key pair and backup.env in a temporary work directory, never touches real destinations.
#   usage: TURNO_TEST_WORKDIR=<empty dir whose path contains "turnocontrol"> tests/backup-destination-test.sh
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
W=${TURNO_TEST_WORKDIR:?}; case "$W" in *turnocontrol*) ;; *) echo "TURNO_TEST_WORKDIR debe contener 'turnocontrol'" >&2; exit 2;; esac
case "${TURNO_CONTAINER_PREFIX:-}" in *validation*) ;; *) echo "solo contra una pila de validación" >&2; exit 2;; esac
mkdir -p "$W"; LOCAL="$W/local-turnocontrol"; FAKE="$W/fake-remote"; mkdir -p "$LOCAL" "$FAKE"
age-keygen -o "$W/identity.txt" 2>/dev/null; RCPT=$(age-keygen -y "$W/identity.txt")
export TURNO_BACKUP_ENV_FILE="$W/backup.env"
pass=0; fail=0
check() { if [ "$2" = "$3" ]; then echo "PASS  $1"; pass=$((pass + 1)); else echo "FAIL  $1 (esperado '$3', obtenido '$2')"; fail=$((fail + 1)); fi; }
cfg() { # $1 dest type, $2 dest path, $3 dest id, extra lines...
  { echo "TURNO_BACKUP_DIR=$LOCAL"; echo "TURNO_BACKUP_AGE_RECIPIENT=$RCPT"; echo "TURNO_BACKUP_LOCAL_RETENTION_DAYS=7"
    echo "TURNO_BACKUP_DEST_TYPE=$1"; echo "TURNO_BACKUP_DEST=$2"; echo "TURNO_BACKUP_DEST_ID=$3"; shift 3; for l in "$@"; do echo "$l"; done; } > "$TURNO_BACKUP_ENV_FILE"; }
run() { bash "$HERE/scripts/backup.sh" > "$W/out.txt" 2>&1; echo $?; }
err() { grep -oE 'ERROR: .*' "$W/out.txt" | head -1 | cut -c1-90; }
nlocal() { find "$LOCAL" -maxdepth 1 -type f -name 'turnocontrol-db-*' | wc -l | tr -d ' '; }
nremote() { find "$FAKE" -maxdepth 1 -type f -name 'turnocontrol-db-*' | wc -l | tr -d ' '; }

# ---------- Destination validation happens BEFORE anything is dumped
cfg dir "$FAKE" turnocontrol-test-dest
check "destino sin marcador: rechazado" "$(run)" 1
check "  motivo: no es un destino de TurnoControl" "$(err | grep -c 'marcador')" 1
check "  no se creó ninguna copia local" "$(nlocal)" 0
echo "otro-proyecto" > "$FAKE/.turnocontrol-backup-destination"
check "marcador de otro destino: rechazado" "$(run)" 1
check "  motivo: destino equivocado" "$(err | grep -c 'destino equivocado')" 1
echo "turnocontrol-test-dest" > "$FAKE/.turnocontrol-backup-destination"
cfg dir "$W/no-existe" turnocontrol-test-dest
check "destino inexistente: rechazado" "$(run)" 1
cfg dir "$FAKE" ""
check "sin TURNO_BACKUP_DEST_ID: rechazado" "$(run)" 1
cfg dir "$FAKE" 'id con espacios'
check "TURNO_BACKUP_DEST_ID con caracteres no válidos: rechazado" "$(run)" 1
cfg nube "$FAKE" turnocontrol-test-dest
check "tipo de destino desconocido: rechazado" "$(run)" 1
cfg none "" ""
check "sin destino y sin ALLOW_LOCAL_ONLY: rechazado" "$(run)" 1
check "tras todos los rechazos, ni copias locales ni remotas" "$(nlocal)$(nremote)" 00

# ---------- Interrupted upload (simulated crash mid-copy)
cfg dir "$FAKE" turnocontrol-test-dest TURNO_BACKUP_FAULT=upload
check "subida interrumpida: el script falla" "$(run)" 1
check "  en el destino solo queda un .partial, ninguna copia «completa»" "$(find "$FAKE" -name 'turnocontrol-db-*' ! -name '*.partial' | wc -l | tr -d ' ')$(find "$FAKE" -name '*.partial' | wc -l | tr -d ' ')" 01
touch -d '2 hours ago' "$FAKE"/*.partial

# ---------- Normal run: partial cleaned, set uploaded and verified
cfg dir "$FAKE" turnocontrol-test-dest
check "copia normal tras la interrupción: correcta" "$(run)" 0
check "  el .partial antiguo se eliminó" "$(find "$FAKE" -name '*.partial' | wc -l | tr -d ' ')" 0
check "  el conjunto completo está en el destino (3 archivos más el de la interrupción)" "$(find "$FAKE" -maxdepth 1 -name 'turnocontrol-db-*' ! -name '*.partial' | wc -l | tr -d ' ')" 3
base=$(ls "$FAKE" | grep '\.dump\.age$' | sed 's/\.dump\.age$//' | tail -1)
check "  sumas remotas idénticas a las locales" "$(cd "$FAKE" && sha256sum --quiet -c "$base.sha256" && echo ok)" ok
check "  sin texto en claro en el destino" "$(grep -c -a -E 'turno-test-|SICK_LEAVE|turno_owner' "$FAKE/$base.dump.age")" 0
check "backup-remote status: reciente e íntegra" "$(bash "$HERE/scripts/backup-remote.sh" status 2>&1 | grep -c '^OK')" 1

# ---------- Corruption in the destination is detected
cp "$FAKE/$base.dump.age" "$W/keep.age"
printf '\x00' | dd of="$FAKE/$base.dump.age" bs=1 seek=3000 conv=notrunc status=none
check "copia remota alterada: status la detecta" "$(bash "$HERE/scripts/backup-remote.sh" status > "$W/st.txt" 2>&1; echo $?) $(grep -c 'integridad: FALLO' "$W/st.txt")" "1 1"
check "copia remota alterada: fetch se niega a darla por buena" "$(bash "$HERE/scripts/backup-remote.sh" fetch latest "$W/fetched-bad" > /dev/null 2>&1; echo $?)" 1
cp "$W/keep.age" "$FAKE/$base.dump.age"
head -c 5000 "$W/keep.age" > "$FAKE/$base.dump.age"
check "copia remota truncada: fetch la rechaza" "$(bash "$HERE/scripts/backup-remote.sh" fetch latest "$W/fetched-trunc" > /dev/null 2>&1; echo $?)" 1
cp "$W/keep.age" "$FAKE/$base.dump.age"

# ---------- Lost VPS: fetch from the destination and restore into an isolated database
check "fetch de la última copia: descargada y verificada" "$(bash "$HERE/scripts/backup-remote.sh" fetch latest "$W/fetched" > /dev/null 2>&1; echo $?)" 0
docker exec -i "$TURNO_CONTAINER_PREFIX-db" psql -U supabase_admin -d postgres -XAt < "$HERE/tests/sql/restore_counts.sql" > "$W/expected.txt"
check "restaurar la copia descargada (contenedor aislado sin red): simulacro superado" "$(bash "$HERE/scripts/restore-drill.sh" "$W/fetched/$base.dump.age" "$W/identity.txt" "$W/expected.txt" 2>&1 | grep -c 'SIMULACRO: superado')" 1
age-keygen -o "$W/wrong.txt" 2>/dev/null
check "restaurar con una clave equivocada: rechazado antes de crear nada" "$(bash "$HERE/scripts/restore-drill.sh" "$W/fetched/$base.dump.age" "$W/wrong.txt" > "$W/rd.txt" 2>&1; echo $?) $(grep -c 'no descifra' "$W/rd.txt")" "1 1"
check "restaurar sin archivo de identidad: rechazado" "$(bash "$HERE/scripts/restore-drill.sh" "$W/fetched/$base.dump.age" "$W/no-existe.txt" > /dev/null 2>&1; echo $?)" 1
cfg dir "$FAKE" turnocontrol-test-dest
sed -i 's/^TURNO_BACKUP_AGE_RECIPIENT=.*/TURNO_BACKUP_AGE_RECIPIENT=/' "$TURNO_BACKUP_ENV_FILE"
check "copia sin clave pública age: rechazada" "$(run)" 1

# ---------- Retention in the dir adapter (dry run by default, only turnocontrol-db-* sets, never all of them)
cfg dir "$FAKE" turnocontrol-test-dest
echo "ajeno" > "$FAKE/notas-del-propietario.txt"
for d in 40 20; do o="turnocontrol-db-2026010${d:0:1}T000000Z"; for e in dump.age roles.sql.age sha256; do echo x > "$FAKE/$o.$e"; touch -d "$d days ago" "$FAKE/$o.$e"; done; done
check "prune en simulación: lista 1 copia elegible y no borra nada" "$(bash "$HERE/scripts/backup-remote.sh" prune --days 30 2>&1 | grep -c 'se eliminaría') $(ls "$FAKE" | grep -c 'turnocontrol-db-20260104')" "1 3"
check "prune aplicado: borra solo esa copia" "$(bash "$HERE/scripts/backup-remote.sh" prune --days 30 --apply > /dev/null 2>&1; ls "$FAKE" | grep -c 'turnocontrol-db-20260104') $(ls "$FAKE" | grep -c 'turnocontrol-db-20260102')" "0 3"
check "prune no toca archivos ajenos ni el marcador" "$(ls -a "$FAKE" | grep -cE '^(notas-del-propietario.txt|\.turnocontrol-backup-destination)$')" 2
touch -d '30 days ago' "$FAKE"/turnocontrol-db-*   # now EVERY remaining set is older than 7 days
check "prune que borraría todas las copias: cancelado" "$(bash "$HERE/scripts/backup-remote.sh" prune --days 7 --apply > "$W/pr.txt" 2>&1; echo $?) $(grep -c 'TODAS' "$W/pr.txt")" "1 1"
check "  tras cancelar, siguen todas las copias" "$(ls "$FAKE" | grep -c '\.dump\.age$')" 2
check "prune con menos de 7 días: rechazado" "$(bash "$HERE/scripts/backup-remote.sh" prune --days 3 > /dev/null 2>&1; echo $?)" 1
cfg rclone "remoto:bucket/turnocontrol" turnocontrol-test-dest
check "prune contra un destino rclone: rechazado (retención = reglas del bucket)" "$(bash "$HERE/scripts/backup-remote.sh" prune --days 30 > "$W/pr2.txt" 2>&1; echo $?) $(grep -c 'ciclo de vida' "$W/pr2.txt")" "1 1"

echo "RESULTADO COPIAS EXTERNAS: $pass superadas, $fail fallidas"
[ "$fail" -eq 0 ]

#!/usr/bin/env bash
# Local, offline validation of the deployment files. Starts no container, pulls no image, contacts no server.
# Uses a throwaway .env with SYNTHETIC values (never real ones) in a temporary directory and never prints it.
#   usage: bash backend/deploy/oracle/tests/static-checks.sh
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
REPO=$(cd "$HERE/../../.." && pwd)
BACKEND="$REPO/backend"
for c in docker bash grep git; do command -v "$c" >/dev/null || { echo "falta $c" >&2; exit 2; }; done
# python3 on Linux; plain `python` on Windows (the Store alias "python3" only prints an install hint).
PY=python3; "$PY" -c "" 2>/dev/null || PY=python; "$PY" -c "" 2>/dev/null || { echo "falta python" >&2; exit 2; }
pass=0; fail=0
ok() { echo "PASS  $1"; pass=$((pass + 1)); }
ko() { echo "FAIL  $1"; fail=$((fail + 1)); }
check() { if eval "$2" >/dev/null 2>&1; then ok "$1"; else ko "$1"; fi; }

TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
# Synthetic placeholders only: obviously fake, not secrets.
sed -E 's/<generado-por-generate-secrets>/synthetic0000000000000000000000000000000000000000000000000000000/; s#https://<pendiente-de-aprobacion>#https://api.example.invalid#' \
  "$HERE/.env.example" > "$TMP/synthetic.env"

compose() { docker compose -p turnocontrol -f "$HERE/compose.yaml" --env-file "$TMP/synthetic.env" "$@"; }
check "compose.yaml válido (docker compose config)" 'compose config --quiet'
check "faltan variables obligatorias => config falla" '! docker compose -p turnocontrol -f "$HERE/compose.yaml" --env-file /dev/null config --quiet'
compose config --format json > "$TMP/resolved.json"

"$PY" - "$TMP/resolved.json" > "$TMP/structure.txt" <<'PY'
import json, sys
c = json.load(open(sys.argv[1]))
s, nets = c["services"], c["networks"]
def out(name, cond): print(("PASS  " if cond else "FAIL  ") + name)
out("proyecto Compose = turnocontrol", c.get("name") == "turnocontrol")
out("exactamente 4 servicios", sorted(s) == ["turnocontrol-auth", "turnocontrol-db", "turnocontrol-gateway", "turnocontrol-rest"])
for n in ("turnocontrol-db", "turnocontrol-auth", "turnocontrol-rest"):
    out(f"{n}: sin puertos publicados", not s[n].get("ports"))
gw = s["turnocontrol-gateway"].get("ports", [])
out("gateway: un único puerto, solo en 127.0.0.1", len(gw) == 1 and gw[0].get("host_ip") == "127.0.0.1")
internal = {k for k, v in nets.items() if v.get("internal")}
for n in ("turnocontrol-db", "turnocontrol-rest"):
    out(f"{n}: solo redes internal", set(s[n]["networks"]) <= internal and len(s[n]["networks"]) > 0)
out("ninguna red ajena (todas turnocontrol_*)", all(v.get("name", k).startswith("turnocontrol_") and not v.get("external") for k, v in nets.items()))
out("ningún volumen ajeno (todos turnocontrol_*)", all(v.get("name", k).startswith("turnocontrol_") and not v.get("external") for k, v in c["volumes"].items()))
for n, svc in s.items():
    img = svc["image"]
    out(f"{n}: imagen con versión y digest, sin latest", "@sha256:" in img and ":latest" not in img and len(img.split("@")[1]) == 71)
    out(f"{n}: límite de memoria", bool(svc.get("mem_limit")))
    out(f"{n}: healthcheck", bool(svc.get("healthcheck", {}).get("test")))
    out(f"{n}: restart unless-stopped", svc.get("restart") == "unless-stopped")
    out(f"{n}: no-new-privileges", "no-new-privileges:true" in svc.get("security_opt", []))
    out(f"{n}: logs rotados", svc.get("logging", {}).get("options", {}).get("max-size") is not None)
    out(f"{n}: sin modo privilegiado ni red host", not svc.get("privileged") and svc.get("network_mode") not in ("host",))
    for v in svc.get("volumes", []):
        if v.get("type") == "bind":
            out(f"{n}: montaje de archivo de solo lectura ({v['target']})", v.get("read_only") is True)
a = s["turnocontrol-auth"]["environment"]
out("auth: usuarios anónimos desactivados", a.get("GOTRUE_EXTERNAL_ANONYMOUS_USERS_ENABLED") == "false")
out("auth: teléfono desactivado", a.get("GOTRUE_EXTERNAL_PHONE_ENABLED") == "false")
out("auth: Google desactivado", a.get("GOTRUE_EXTERNAL_GOOGLE_ENABLED") == "false")
out("auth: alta cerrada por defecto", a.get("GOTRUE_DISABLE_SIGNUP") == "true")
out("auth: confirmación de correo exigida por defecto", a.get("GOTRUE_MAILER_AUTOCONFIRM") == "false")
out("auth: rotación de refresh tokens", a.get("GOTRUE_SECURITY_REFRESH_TOKEN_ROTATION_ENABLED") == "true")
out("auth: límite por IP con cabecera del gateway", a.get("GOTRUE_RATE_LIMIT_HEADER") == "X-Turno-Client-Ip")
# Regressions found at runtime in 5F.1C stage 0.
out("auth: GOTRUE_JWT_DEFAULT_GROUP_NAME presente (sin ella el token no lleva role)", a.get("GOTRUE_JWT_DEFAULT_GROUP_NAME") == "authenticated")
out("auth: registros a nivel warn (a nivel info salen los correos)", a.get("GOTRUE_LOG_LEVEL") == "warn")
r = s["turnocontrol-rest"]["environment"]
out("rest: solo el esquema public", r.get("PGRST_DB_SCHEMAS") == "public")
out("rest: sin rol anónimo", "PGRST_DB_ANON_ROLE" not in r)
out("rest: audiencia JWT exigida", r.get("PGRST_JWT_AUD") == "authenticated")
out("rest: OpenAPI desactivado", r.get("PGRST_OPENAPI_MODE") == "disabled")
out("rest: GUC request.jwt.claims (no heredadas)", r.get("PGRST_DB_USE_LEGACY_GUCS") == "false")
cmd = " ".join(s["turnocontrol-db"]["command"])
out("db: sin registro de SQL", "log_statement=none" in cmd and "log_min_error_statement=panic" in cmd)
mounts = [v.get("target", "") for v in s["turnocontrol-db"].get("volumes", [])]
out("db: las migraciones NO se montan en initdb (opt-in)", not any("turno_sync" in m or "migrations/2026" in m for m in mounts))
everything = json.dumps(c).lower()
out("ninguna variable service_role en la configuración", "service_role_key" not in everything)
PY
while IFS= read -r line; do case "$line" in PASS*) ok "${line#PASS  }";; *) ko "${line#FAIL  }";; esac; done < "$TMP/structure.txt"

# Secrets in compose.yaml: every credential must be an ${...} reference.
check "compose.yaml sin credenciales literales" '! grep -Eqi "(password|secret|_key|pass)[^:]*:[[:space:]]*[\"'"'"']?[A-Za-z0-9+/]{16,}" "$HERE/compose.yaml"'
check ".env.example solo con marcadores" '! grep -Eq "^TURNO_[A-Z_]*(PASSWORD|SECRET|KEY)=[^<]" "$HERE/.env.example"'
# Content scans skip this file, which contains the patterns themselves.
check "ningún JWT real en los archivos" '! grep -rEq --exclude=static-checks.sh "eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\." "$HERE"'
check "ninguna clave privada" '! grep -rEq --exclude=static-checks.sh "BEGIN [A-Z ]*PRIVATE KEY|AGE-SECRET-KEY-1" "$HERE"'
# Image tags such as supabase/postgres:17.6.1.136 look like addresses; they are skipped.
check "ninguna IPv4 salvo 127.0.0.1 y 0.0.0.0" '! grep -rE --exclude=static-checks.sh "([0-9]{1,3}\.){3}[0-9]{1,3}" "$HERE" | grep -v "supabase/postgres:" | grep -Eo "([0-9]{1,3}\.){3}[0-9]{1,3}" | grep -Ev "^(127\.0\.0\.1|0\.0\.0\.0)$"'
check ".env real ignorado por Git" 'git -C "$REPO" check-ignore -q backend/deploy/oracle/.env && git -C "$REPO" check-ignore -q backend/deploy/oracle/backup.env'
check ".env.example NO ignorado" '! git -C "$REPO" check-ignore -q backend/deploy/oracle/.env.example'

# Scripts: syntax, strict mode, no dangerous commands.
for f in "$HERE"/scripts/*.sh "$HERE"/tests/*.sh "$REPO"/backend/scripts/run-local-tests.sh; do
  n=${f#"$REPO/"}
  check "bash -n $n" 'bash -n "$f"'
  check "modo estricto en $n" 'grep -q "set -euo pipefail" "$f"'
done
check "sin comandos destructivos amplios" '! grep -nE "rm -rf /|docker (system|volume|image|container|network) prune|docker compose down -v|docker volume rm|drop (schema|database)|DROP (SCHEMA|DATABASE)|mkfs|dd if=" "$HERE"/scripts/*.sh "$HERE"/tests/api-security-test.sh "$HERE"/tests/resilience-test.sh "$HERE"/tests/log-privacy-scan.sh'
check "los borrados de copias usan patrones explícitos" 'grep -q "turnocontrol-db-\*.dump.age" "$HERE/scripts/backup.sh"'
check "gateway con tiempos de espera acotados (slowloris)" 'grep -q "read_header" "$HERE/gateway/Caddyfile" && grep -q "read_body" "$HERE/gateway/Caddyfile"'
check "gateway: el registro de errores no guarda cabeceras ni URI" 'grep -q "request>headers delete" "$HERE/gateway/Caddyfile" && grep -q "request>uri delete" "$HERE/gateway/Caddyfile"'
check "la prueba de firma alterada no toca el último carácter" '! grep -q "\${A%?}x" "$HERE/tests/api-security-test.sh"'
check "el simulacro reaplica la base de privilegios tras restaurar" 'grep -q "db/privileges" "$HERE/scripts/restore-drill.sh"'
check "migrate.sh aplica la base de privilegios tras cada ejecución" 'grep -q "db/privileges" "$HERE/scripts/migrate.sh"'
check "la base de privilegios solo revoca, salvo EXECUTE a authenticated de la lista permitida" '! grep -v "^\s*--" "$HERE/db/privileges/90_rpc_privileges.sql" | grep -Eiq "grant [a-z ,%]+ to (anon|service_role|public|authenticator)" && grep -q "delete_my_account" "$HERE/db/privileges/90_rpc_privileges.sql"'
check "gateway: delete_my_account solo por POST y con apikey (mismo bloque que las RPC de sincronización)" 'grep -A3 "@rpc {" "$HERE/gateway/Caddyfile" | grep -q "method POST" && grep -A3 "@rpc {" "$HERE/gateway/Caddyfile" | grep -q "/rest/v1/rpc/delete_my_account"'
check "retention.sh: simulación por defecto y copia previa obligatoria para --apply" 'grep -q "^apply=0" "$HERE/scripts/retention.sh" && grep -q "no hay una copia de menos de 2 horas" "$HERE/scripts/retention.sh"'
check "la purga de retención nunca borra de turno.records ni de user_state" '! grep -Eiq "delete from turno\.(records|user_state)" "$BACKEND/supabase/migrations/20261126000000_retention.sql"'
check "intervalo de reutilización de refresh tokens por defecto 0" 'grep -q "REFRESH_TOKEN_REUSE_INTERVAL:-0}" "$HERE/compose.yaml"'
check "la migración de borrado no da BYPASSRLS ni privilegios sobre auth a turno_owner" '! grep -Eiq "(grant [a-z ,()_]+ on auth\.[a-z_]+ to turno_owner|alter role turno_owner)" "$BACKEND/supabase/migrations/20261125000000_account_deletion.sql"'
check "el simulacro comprueba el descifrado antes de crear nada" 'grep -q "no descifra la copia" "$HERE/scripts/restore-drill.sh"'
check "override de validación: ningún nombre de producción" '! grep -nE "^\s+(container_name|name): turnocontrol(-db|-auth|-rest|-gateway|_internal|_egress|_edge|_pgdata|_db_config|_gateway_data|_gateway_config)\s*$" "$HERE/tests/compose.validation.yaml" && [ "$(grep -cE "^\s+(container_name|name): turnocontrol-validation-5f1c0" "$HERE/tests/compose.validation.yaml")" = 11 ]'
check "override de staging: ningún nombre de producción" '[ "$(grep -cE "^\s+(container_name|name): turnocontrol-staging" "$HERE/compose.staging.yaml")" = 11 ]'
check "la prueba de sesión espera al intervalo de reutilización antes de comprobar la familia" 'grep -q "pasado el intervalo, toda la familia queda revocada" "$HERE/tests/session-lifecycle-test.sh"'
check "copias externas: marcador de destino obligatorio y comprobado antes del volcado" 'grep -q "dest_check" "$HERE/scripts/backup.sh" && grep -q "destino equivocado" "$HERE/scripts/lib-backup-dest.sh" && [ "$(grep -nv "^\s*#" "$HERE/scripts/backup.sh" | grep "dest_check" | head -1 | cut -d: -f1)" -lt "$(grep -nv "^\s*#" "$HERE/scripts/backup.sh" | grep "pg_dump" | head -1 | cut -d: -f1)" ]'
check "copias externas: subida con .partial y suma remota antes de renombrar" 'grep -q "\.partial" "$HERE/scripts/lib-backup-dest.sh" && grep -q "la suma remota" "$HERE/scripts/lib-backup-dest.sh"'
check "copias externas: prune rechazado para buckets" 'grep -q "ciclo de vida" "$HERE/scripts/backup-remote.sh"'
check "las copias nunca usan git" '! grep -nE "\bgit (add|commit|push)" "$HERE/scripts/backup.sh"'

# SQL consistency (static).
MIG="$REPO/backend/supabase/migrations"
check "migraciones en orden de marca temporal" '[ "$(ls "$MIG" | sort | tr "\n" " ")" = "$(ls "$MIG" | tr "\n" " ")" ]'
check "la migración de alojamiento solo revoca o re-concede a authenticated" '! grep -Eiv "^\s*(--|$|revoke |grant execute on function public\.sync_push\(uuid, jsonb\), public\.sync_pull\(bigint, int\) to authenticated;)" "$MIG/20261124000000_hosting_privileges.sql"'
check "el endurecimiento no concede ni crea nada" '! grep -Eiq "^\s*(grant|create|alter|drop|insert|update|delete)\b" "$HERE/db/hardening/10_api_role_memberships.sql"'
check "el endurecimiento comprueba su propio efecto" 'grep -q "raise exception" "$HERE/db/hardening/10_api_role_memberships.sql"'
for f in compose.yaml gateway/Caddyfile scripts/backup.sh db/init/99-turno-roles.sql .env.example; do
  check "fin de línea LF forzado por Git: $f" '[ "$(git -C "$REPO" check-attr eol -- "backend/deploy/oracle/$f" | sed "s/.*: //")" = lf ]'
done
check "fin de línea LF forzado: run-local-tests.sh y migraciones" '[ "$(git -C "$REPO" check-attr eol -- backend/scripts/run-local-tests.sh backend/supabase/migrations/20261124000000_hosting_privileges.sql | sed "s/.*: //" | sort -u)" = lf ]'
check "ningún archivo de despliegue con CRLF en disco" '! grep -rlI $'"'"'\r'"'"' "$HERE"'
check "las pruebas insertan en auth.users con columnas explícitas" '! grep -rE "insert into auth\.users values" "$REPO/backend/supabase/tests" "$REPO/backend/scripts"'

echo "RESULTADO ESTÁTICO: $pass superadas, $fail fallidas"
[ "$fail" -eq 0 ]

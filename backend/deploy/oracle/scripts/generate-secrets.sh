#!/usr/bin/env bash
# Creates a NEW .env from .env.example with freshly generated secrets. Run it ON THE SERVER, once, in phase 5F.1C or
# later (generating production secrets needs the owner's approval). It never prints a secret and never overwrites.
# Everything is generated and signed inside one python3 process: no secret ever appears on a command line
# (/proc/<pid>/cmdline) or in the shell environment.
#   usage: scripts/generate-secrets.sh <ruta-del-nuevo-.env>
set -euo pipefail
. "$(dirname "$0")/lib.sh"
require_cmd python3
out=${1:?uso: generate-secrets.sh <ruta-del-nuevo-.env>}
[ -e "$out" ] && die "$out ya existe; no se sobrescribe (para rotar, ver SECURITY.md)"
umask 077

python3 - "$DEPLOY_DIR/.env.example" "$out" <<'PY'
import base64, hashlib, hmac, json, os, secrets, sys, time

def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()

# Hex values: safe inside postgres:// URIs without escaping. 32 bytes = 256 bits each.
values = {k: secrets.token_hex(32) for k in
          ("TURNO_POSTGRES_PASSWORD", "TURNO_AUTHENTICATOR_PASSWORD", "TURNO_AUTH_ADMIN_PASSWORD", "TURNO_JWT_SECRET")}
now = int(time.time())
header = b64url(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
payload = b64url(json.dumps({"role": "anon", "iss": "turnocontrol", "iat": now, "exp": now + 10 * 365 * 24 * 3600},
                            separators=(",", ":")).encode())
signature = hmac.new(values["TURNO_JWT_SECRET"].encode(), f"{header}.{payload}".encode(), hashlib.sha256).digest()
values["TURNO_ANON_KEY"] = f"{header}.{payload}.{b64url(signature)}"

lines = []
for line in open(sys.argv[1], encoding="utf-8"):
    key = line.split("=", 1)[0]
    lines.append(f"{key}={values[key]}\n" if key in values else line)
fd = os.open(sys.argv[2], os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, "w", encoding="utf-8") as f:
    f.writelines(lines)
PY
log "creado $out (600). Faltan los valores no secretos: URLs, SMTP y lista de redirecciones."
log "Guarda una copia del archivo en el gestor de contraseñas del propietario; no lo envíes por chat ni correo."

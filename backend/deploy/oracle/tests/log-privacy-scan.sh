#!/usr/bin/env bash
# Scans the logs of the four TurnoControl containers for things that must never be logged: the configured secrets,
# any JWT, synthetic test e-mail addresses and synchronized day content. Prints only counts, never the matches.
# Run it right after tests/api-security-test.sh (it uses the same synthetic accounts and payloads).
#   usage: TURNO_CONTAINER_PREFIX=turnocontrol TURNO_ENV_FILE=.env tests/log-privacy-scan.sh
set -euo pipefail
PREFIX=${TURNO_CONTAINER_PREFIX:-turnocontrol}
ENV_FILE=${TURNO_ENV_FILE:?ruta del .env usado por la pila}
fail=0
logs() { for s in db auth rest gateway; do docker logs "$PREFIX-$s" 2>&1; done; }
ALL=$(mktemp); trap 'rm -f "$ALL"' EXIT
logs > "$ALL"
count() { grep -cE "$1" "$ALL" || true; }
countf() { grep -cF -- "$1" "$ALL" || true; }
report() { if [ "$2" -eq 0 ]; then echo "PASS  $1"; else echo "FAIL  $1 ($2 líneas)"; fail=$((fail + 1)); fi; }
for k in TURNO_JWT_SECRET TURNO_POSTGRES_PASSWORD TURNO_AUTHENTICATOR_PASSWORD TURNO_AUTH_ADMIN_PASSWORD TURNO_ANON_KEY TURNO_SMTP_PASS TURNO_GOOGLE_SECRET; do
  v=$(grep "^$k=" "$ENV_FILE" | cut -d= -f2- || true)
  [ -n "$v" ] && report "$k no aparece en los registros" "$(countf "$v")"
done
report "ningún JWT en los registros"                     "$(count 'eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}')"
report "ningún correo de las cuentas de prueba"          "$(count 'turno-test-[a-z]+-[0-9]+@example\.invalid')"
report "ningún contenido de día sincronizado"            "$(count '1\|20[0-9]{2}-[0-9]{2}-[0-9]{2}\|(WORK|FREE|VACATION|SICK_LEAVE)')"
report "ninguna cabecera Authorization"                  "$(grep -ciE 'authorization: *bearer' "$ALL" || true)"
echo "líneas de registro analizadas: $(wc -l < "$ALL")"
[ "$fail" -eq 0 ] && echo "PRIVACIDAD DE REGISTROS: superada" || { echo "PRIVACIDAD DE REGISTROS: $fail fallos"; exit 1; }

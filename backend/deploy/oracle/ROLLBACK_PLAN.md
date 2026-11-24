# Vuelta atrás

Principio: cada paso afecta **solo** a objetos con prefijo `turnocontrol` (proyecto Compose, contenedores, redes y volúmenes). Ningún paso usa `docker system prune`, `docker volume prune` ni `docker compose down -v` sin indicar el nombre exacto, ni toca otros proyectos.

## 1. Staging recién creado (sin datos reales)
```bash
docker compose -p turnocontrol -f compose.yaml --env-file .env down         # para y elimina SOLO los contenedores de turnocontrol
docker volume rm turnocontrol_pgdata turnocontrol_db_config turnocontrol_gateway_data turnocontrol_gateway_config   # solo si se quiere empezar de cero
docker network ls --filter name=turnocontrol_                                # down ya elimina las redes del proyecto
```
`docker volume rm` destruye los datos: solo se usa en staging sin datos reales y tras comprobar el nombre exacto.

## 2. Migración
- **Falla durante la ejecución:** la transacción del archivo se revierte sola y no queda nada a medias. Se corrige con un archivo nuevo.
- **Se aplicó, pero hay que deshacerla:**
  - **Staging:** volver al §1 y empezar de cero.
  - **Producción:** escribir una migración correctiva nueva. Como último recurso, restaurar la copia verificada que `migrate.sh` exige antes de aplicar (`BACKUP_PLAN.md` §5), sabiendo que se pierde lo posterior a esa copia.
- **Endurecimiento** (`db/hardening`): se puede revertir con `grant service_role to authenticator;`. Solo debe hacerse si una necesidad aprobada lo exige.

## 3. Exposición HTTPS (proxy existente, opción A)
1. Restaurar la copia previa de la configuración del proxy.
2. Recargarlo sin cortes (`caddy reload`).
3. `docker network disconnect turnocontrol_edge <contenedor-del-proxy>`.
4. Comprobar que los demás servicios siguen respondiendo.

## 4. Copias
Desactivar la tarea programada (cuando exista). Los archivos ya creados se conservan hasta que caduquen. No se borran a mano salvo para responder a una petición de supresión, y siempre según la política de privacidad.

## 5. Actualización de una imagen
1. Antes de empezar: una copia verificada y apuntar el digest actual (está en `compose.yaml`, en Git).
2. Si falla: volver a la versión anterior de `compose.yaml` y ejecutar `docker compose up -d <servicio>`.
3. **PostgreSQL:** las versiones menores de la misma imagen mayor se pueden revertir. Un cambio de versión **mayor** de PostgreSQL **no** se puede revertir in situ: exige restaurar la copia en la versión anterior. Por eso necesita su propio plan y aprobación.
4. **GoTrue:** sus migraciones del esquema `auth` solo avanzan. Volver a una versión anterior tras una migración nueva puede no funcionar. Hay que probar cada versión nueva en staging y revertir restaurando la copia si hace falta.

## 6. Retirar TurnoControl del servidor
§1 completo, más la desconexión del proxy (§3). Los volúmenes de datos solo se eliminan después de exportar o borrar los datos de los usuarios según la política de privacidad, y con la aprobación del propietario.

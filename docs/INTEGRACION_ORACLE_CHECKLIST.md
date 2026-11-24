# TurnoControl 0.3.0: lista para la integración final con el servidor

Fecha: 2026-11-24. Estado: **preparación; no se ha empezado la integración**. La sincronización real sigue desactivada, la app de producción no tiene permiso de Internet y no se ha desplegado ni modificado nada en Oracle Cloud durante esta fase.

Estados:
- **HECHO EN LOCAL**: implementado y probado en local (backend con Docker, o app con JVM/Robolectric).
- **NO DESPLEGADO**: existe, pero no está en el staging.
- **SOLO DISEÑO**
- **BLOQUEADO (decisión)**: falta una decisión del propietario.
- **BLOQUEADO (credenciales)**: faltan cuentas, dominios o claves externas.
- **PENDIENTE**: sin empezar.

Las fuentes son los informes privados de las fases 5F.1A–5F.1D (no versionados) y `backend/`. Aquí no se copia ningún nombre de host, IP, puerto ni identificador de cuenta.

| # | Elemento | Estado | Qué falta | Bloqueante |
|---|---|---|---|---|
| 1 | Protección al restaurar cuentas borradas | SOLO DISEÑO | Registro de borrados con hash y pimienta durante la retención de las copias, o revisión manual asumida | Decisión |
| 2 | Verificación de las migraciones del backend | HECHO EN LOCAL (amd64); NO DESPLEGADO | Dos migraciones y la base de privilegios sin aplicar en el staging; repetir las suites en ARM64 | Decisión (aprobar el manifiesto de actualización) |
| 3 | Despliegue en el staging ARM64 | Parcial: staging privado con las migraciones anteriores | Actualización controlada (copia, `migrate.sh --apply`, recrear GoTrue y gateway) y regresión en ARM64 | Decisión |
| 4 | Configuración de la autenticación | HECHO EN LOCAL (GoTrue, sesiones, reutilización de refresh tokens con intervalo 0) | Duración máxima e inactividad de sesión (hoy sin límite) | Decisión |
| 5 | Endpoint HTTPS seguro | SOLO DISEÑO (opción A, validada sintácticamente con la versión del proxy existente) | Elegir el puerto y obtener la aprobación del responsable del proxy compartido | Decisión |
| 6 | Dominio y App Links | PENDIENTE (hay plantilla de `assetlinks.json`) | Dominio propio y huellas SHA-256 de las firmas de release y verificación | Credenciales (dominio) |
| 7 | SMTP | HECHO EN LOCAL con SMTP falso (41/41); producción BLOQUEADA | Proveedor, subdominio de envío, SPF/DKIM/PTR y STARTTLS en el puerto 587 | Decisión + credenciales |
| 8 | Google OAuth | PENDIENTE | Clientes web y Android (firma de release y `.verification`); en Android, `CredentialManagerGoogleSource` | Credenciales |
| 9 | Mitigación de privacidad de ML Kit | Estrategia B medida en emulador | La telemetría de Google Play services y el UUID persistente sin resolver; **PRIVACY NOT VERIFIED** | Decisión (postura legal) |
| 10 | Revisión del permiso INTERNET de producción | PENDIENTE (eliminado con `tools:node="remove"`) | Decisión D1, prueba de tráfico que solo permita el host propio, actualizar README y Data safety | Decisión |
| 11 | Copias externas cifradas | HECHO EN LOCAL (adaptadores `dir` y `rclone`, 34/34); NO DESPLEGADO | Bucket real, clave solo de escritura, ciclo de vida/WORM, custodia de la clave age en dos lugares | Decisión + credenciales |
| 12 | Borrado de cuenta | HECHO EN LOCAL en el servidor (33/33 SQL, 27/27 HTTP) | Pantalla y flujo en Android (no existe); en el dispositivo, borrar por la ruta de la base de la cuenta | Depende de 8 |
| 13 | Retención (90 días) | HECHO EN LOCAL | Programar la purga y su ventana horaria | Decisión |
| 14 | Sincronización entre varios dispositivos | Motor y almacén locales probados con un servidor falso (`SyncEngineTest` t01–t33, `SyncStoreTest`) | Cliente HTTP real (`SyncTransport`), sesión real, WorkManager y dependencias (D2); prueba de extremo a extremo de «sesión caducada nunca autoriza» | Depende de 4–8 y 10 |
| 15 | Resolución de conflictos sin conexión | Motor probado; **pantalla de conflictos añadida en 0.3.0** (`ConflictsUiTest`), no accesible desde producción | Conectarla al flujo de cuenta real. **Además, resolver `REVIEW_REQUIRED`:** tras restaurar una copia en una base vinculada no existe ninguna forma de volver a sincronizar | Decisión de diseño (cómo revisar los datos restaurados frente al servidor) |
| 16 | Validación de seguridad | Local: RLS y aislamiento (SQL), sesiones cifradas (`SessionVaultTest`), aislamiento de cuentas en el dispositivo | Revisión externa, captcha o límites para el alta pública, pruebas en el staging real | Decisión |
| 17 | Revisión legal y de privacidad | PENDIENTE | Art. 9 del RGPD (bajas), EIPD, política de privacidad, plazo de las copias tras un borrado, DPA de los proveedores, Data safety | Decisión (propietario y asesoría) |
| 18 | Prueba de extremo a extremo con dos dispositivos | PENDIENTE | Dos dispositivos de prueba (nunca el Samsung A53 personal), cuentas sintéticas y el staging actualizado | Depende de todo lo anterior |

## Orden recomendado
1. Decisiones del propietario: 1, 4, 5, 9, 10, 13, 15 (diseño de `REVIEW_REQUIRED`), 16 y 17.
2. Actualización controlada del staging (2–3) con un manifiesto aprobado.
3. Credenciales externas: dominio, SMTP, OAuth y bucket (6, 7, 8, 11).
4. Cliente real en Android detrás del permiso aprobado (10, 14), primero en la variante de verificación.
5. Borrado de cuenta en Android (12) y prueba con dos dispositivos (18).

Nada de esta lista se ha iniciado. Requiere autorización explícita.

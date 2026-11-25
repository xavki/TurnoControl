# Pendientes para futuras versiones

Ninguno de estos puntos se ha clasificado como P0 ni P1. Origen: lectura de código, auditorías y validación física.

## Antes de nuevas versiones
1. Mantener la custodia de la clave de firma de release, con copias cifradas verificadas: sin ella no se pueden publicar actualizaciones con la misma firma. Esa firma no actualiza instalaciones 0.0.1 firmadas con la clave de depuración.
2. Probar en un dispositivo la migración de datos 0.0.1 → 0.1.0, con una copia propia previa de los datos.
3. Probar el APK Release firmado en un dispositivo físico y en otras versiones de Android.

## Accesibilidad (P2)
- Selector de fila en fotografía: la casilla «Delimitar banda manual» y el control de ampliación no tienen nombre accesible; la superficie de selección no tiene acción de clic y la banda manual solo se define arrastrando. Propuesta: etiquetas asociadas a los controles y dos controles accesibles de inicio y fin de banda.
- Evaluación real con TalkBack del flujo de importación.

## Funcionales (P2)
- Historial de vigencias de tarifas: hoy la estimación bruta aplica las tarifas actuales a todos los periodos (y lo indica como «Estimación con las tarifas actuales»); un historial requeriría migración Room y se pospone.
- Horas reales, nóminas reales y extras pagadas frente a compensadas: sin modelo; decisiones N1–N5 en `docs/DECISIONES_NOMINA_0.3.0.md`.
- Cambio de horario de verano: los totales son nominales.
- La marca «hoy» puede quedar desfasada si la app sigue abierta pasada la medianoche.
- El inicio de semana está fijado en lunes, sin atender a la configuración regional.
- Los borradores de edición y de revisión OCR se pierden si el sistema mata el proceso (sin `SavedStateHandle`).
- Tras guardar una importación, la pantalla de revisión conserva los borradores hasta una nueva importación.

## Detectados en la verificación de 0.3.0 en el Samsung A53 (2026-11-25, P2)
- Al cambiar de mes en Calendario se conserva el número de día seleccionado (por ejemplo, el 25 de octubre queda resaltado aunque hoy sea el 25 de noviembre). «Hoy» sigue marcado con borde.
- Texto en singular: los diálogos de vacaciones y baja médica dicen «Se marcarán 1 día como…».
- La vista Semana solo muestra los puntos de tipo de día, sin horas, y debajo sigue el «Resumen del mes».
- El tema oscuro solo depende del interruptor de Ajustes (desactivado por defecto); la app no sigue el tema oscuro del sistema.
- OCR con la foto inclinada: con un cuadrante sintético, 0° y 4° dan los 7 días correctos; con 8° y 12° no se reconstruyen las filas (se ofrecen bandas horizontales mezcladas). No se guarda nada erróneo: la revisión lo impide y queda la vía manual.
- Texto al 200 % **y** zoom de pantalla al máximo a la vez: en el editor de turnos, «Guardar registro» se solapa visualmente con «Cancelar registro» y el contenido queda en una franja pequeña (se puede desplazar y los dos botones responden); el botón «Hoy» se parte en dos líneas. Con texto al 200 % y la densidad habitual todo cabe.
- TalkBack: cada día del calendario se anuncia con la fecha en formato ISO («2026-10-12 · Trabajo») en lugar de «lunes, 12 de octubre».

## Cuentas y sincronización (antes de activar la sincronización real)
- `REVIEW_REQUIRED` tras restaurar una copia en una base vinculada: no existe ninguna operación para revisarla y reanudar la sincronización. Hace falta decidir el diseño (probablemente un plan de fusión como el de la primera vinculación) antes de sincronizar de verdad.
- El reinicio controlado de pantallas (`AccountSwitchRestart`) solo se lanza tras cerrar sesión. El inicio de sesión y el cambio de cuenta (`AuthCoordinator.commitNewAuthentication`) necesitarán el mismo evento cuando exista un proveedor. El evento está probado a nivel de ViewModel; el reinicio de la Activity, no.
- Las pantallas de primera vinculación y de conflictos existen, pero no están conectadas a la navegación de producción.
- Lista completa para el servidor: `docs/INTEGRACION_ORACLE_CHECKLIST.md`.

## Pruebas y entorno
- Regenerar las capturas con `PortfolioScreenshotsTest`.
- Probar la importación con fotos reales de cuadrantes (en 0.3.0 solo se usaron cuadrantes sintéticos), otras versiones de Android y recuperación tras fallo de escritura en dispositivo.
- Escuchar el recorrido con TalkBack real: en 0.3.0 solo se revisó el árbol de accesibilidad y los tests automáticos.
- El SIGSEGV de `libhwui` observado por Codex no se reprodujo; queda sin explicar.
- `captureDialog` en `PortfolioScreenshotsTest` y las pruebas de intents dependen del foco de ventana: en emuladores headless conviene ocultar los diálogos de error del sistema.
- Mantenimiento de lint: 21 `UseTomlInstead` y 1 `KaptUsageInsteadOfKsp`.

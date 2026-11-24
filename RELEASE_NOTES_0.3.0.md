# TurnoControl 0.3.0: notas de versión (BORRADOR, sin publicar)

Estado: desarrollo local en la rama `feature/0.2.0-redesign`. No hay tag, APK ni release. Los valores `versionCode 2` y `versionName 0.2.0` no han cambiado. Este documento describe el código local, no una versión distribuida.

## Novedades para el usuario (sin cuenta ni Internet)
- **Calendario:** botón «Importar horario desde una imagen», con el mismo flujo de revisión y confirmación que Ajustes → Importar horario.
- **Resumen:**
  - tarjeta «Horas extra del mes»;
  - tarjeta «Horas extra acumuladas del año AAAA» (del 1 de enero al 31 de diciembre);
  - historial «Horas extra por mes» del año seleccionado.
- **Estimación de nómina:**
  - aclara que se calcula con horas planificadas y no con fichajes;
  - avisa de que todas las extras declaradas se estiman como pagadas y de que no se distinguen las compensadas con descanso.
- **Editor de turnos:** si la salida no es posterior a la entrada, el mensaje explica que hay que activar «Día siguiente».
- **Accesibilidad:**
  - TalkBack anuncia los mensajes de error y de confirmación (regiones vivas);
  - las cifras de las tarjetas de estadísticas pasan a la línea siguiente en lugar de cortarse con fuentes grandes.
- **Rendimiento:** con un historial muy largo, la cuadrícula del calendario deja de recorrer todos los días guardados en cada repintado.

## Infraestructura preparada, desactivada en producción
- **Cuentas:** almacenamiento separado por cuenta, sesión cifrada, coordinador de autenticación y cierre de sesión seguro. Tras un cambio de almacenamiento confirmado, la aplicación reconstruye todas las pantallas. El inicio de sesión sigue desactivado: no hay ningún proveedor real.
- **Sincronización:**
  - el motor, la cola persistente, las bajas (tombstones), la cuarentena y la vista previa de la primera vinculación ya existían;
  - nueva pantalla de **conflictos de sincronización**, que no es accesible desde producción;
  - la sincronización real está desactivada y no hay conexión con ningún servidor.

## Sin cambios
- Room sigue en la versión 5, `DataFormat` en 2 y las copias de seguridad en formato 1. Las copias de 0.2.0 siguen siendo restaurables.
- El manifiesto de producción sigue sin permiso de Internet.

## Limitaciones conocidas
Véanse `PENDIENTES_FUTUROS.md`, `docs/DECISIONES_NOMINA_0.3.0.md` y `docs/INTEGRACION_ORACLE_CHECKLIST.md`. En especial:
- las pruebas instrumentadas, TalkBack real y el texto al 200 % en un dispositivo no se han ejecutado en esta fase;
- tras restaurar una copia en una base vinculada a una cuenta, todavía no existe forma de reanudar la sincronización (`REVIEW_REQUIRED`).

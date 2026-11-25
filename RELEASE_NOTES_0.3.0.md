# TurnoControl 0.3.0: notas de versión

Fecha: 2026-11-25. Versión publicada con el tag `v0.3.0`. `versionCode 3`, `versionName 0.3.0`.

Esta versión funciona **sin cuenta y sin Internet**. Las cuentas y la sincronización con el servidor llegarán en la **0.4.0**.

## Novedades para el usuario
- **Calendario:** botón «Importar horario desde una imagen», con el mismo flujo de revisión y confirmación que Ajustes → Importar horario.
- **Resumen:**
  - tarjeta «Horas extra del mes»;
  - tarjeta «Horas extra acumuladas del año AAAA» (del 1 de enero al 31 de diciembre);
  - historial «Horas extra por mes» del año seleccionado.
- **Estimación de nómina:**
  - aclara que se calcula con horas planificadas y no con fichajes;
  - avisa de que todas las extras declaradas se estiman como pagadas y de que no se distinguen las compensadas con descanso.
- **Editor de turnos:** si la salida no es posterior a la entrada, el mensaje explica que hay que activar «Día siguiente».
- **Cuenta (Ajustes → Cuenta):** la tarjeta de Ajustes y la pantalla de cuenta son visibles e indican «Disponible en la versión 0.4.0». Los botones «Iniciar sesión» y «Crear cuenta» están desactivados; TalkBack los anuncia como disponibles en la 0.4.0.
- **Accesibilidad:**
  - TalkBack anuncia los mensajes de error y de confirmación (regiones vivas);
  - las cifras de las tarjetas de estadísticas pasan a la línea siguiente en lugar de cortarse con fuentes grandes.
- **Rendimiento:** con un historial muy largo, la cuadrícula del calendario deja de recorrer todos los días guardados en cada repintado.

## Infraestructura preparada, desactivada en esta versión
- **Cuentas:** almacenamiento separado por cuenta, sesión cifrada, coordinador de autenticación y cierre de sesión seguro. No hay ningún proveedor de inicio de sesión.
- **Sincronización:** motor, cola persistente, bajas (tombstones), cuarentena, vista previa de la primera vinculación y pantalla de conflictos. Nada de esto es accesible ni se ejecuta; no hay conexión con ningún servidor.
- Los datos sin cuenta siguen en `turnocontrol.db`, el archivo de siempre. Cuando llegue la 0.4.0, cada cuenta tendrá su propia base aparte.

## Compatibilidad y actualización
- **Firma:** la APK release se firma con el mismo certificado que las instalaciones existentes (SHA-256 `9e94c004…0ff415`). Por eso se puede instalar encima con `adb install -r` sin perder datos. La APK **ya no es depurable**.
- **Base de datos:** Room sigue en la versión 5, igual que en 0.2.0. Las instalaciones 0.0.1 (versión 4) migran de 4 a 5 conservando días, intervalos, perfil y contrato. Se ha probado con una copia de datos reales en un emulador aislado (ver «Pruebas»).
- `DataFormat` sigue en 2 y las copias de seguridad en formato 1. Las copias de 0.2.0 se siguen pudiendo restaurar.
- **No se admite volver a una versión anterior.** Las versiones 0.1.0 y anteriores no detectan datos de una versión más nueva.
- El manifiesto no pide permiso de Internet ni de almacenamiento.

## Pruebas de esta versión (2026-11-25)
- JVM (`testDebugUnitTest`): 384/384.
- Lint debug y release: 0 errores.
- Instrumentadas (`connectedVerificationAndroidTest`): 48/48, en un emulador API 35.
- APK release:
  - `apksigner verify`: firma v2 válida, certificado idéntico al de la app instalada;
  - no depurable;
  - sin permisos de red;
  - `zipalign` correcto;
  - SHA-256 de la APK entregada: `b9f3fae7306b08b8de1e62903f689298dbec0f929a59e26fd0f476d7e41ca1f0`.
- Actualización simulada, en un emulador desechable de solo lectura:
  - se instaló la APK 0.0.1 real del teléfono con una copia de sus datos;
  - se actualizó a la 0.3.0 con `install -r` (compilación previa al aviso de la tarjeta de Ajustes; solo cambia ese texto);
  - resultado: mismas filas (huellas idénticas), la base pasa a Room 5 con integridad correcta, tablas de sincronización vacías, sin carpetas de cuenta;
  - la copia de seguridad de la app se exportó («7 días, 8 intervalos») y se validó en la vista previa de restauración, cancelando sin cambios.
- Actualización real en un Samsung A53 (Android 16), sobre la 0.0.1 instalada con datos de prueba:
  - certificado de la app instalada y de la APK nueva idénticos; `adb install -r` sin desinstalar (se conserva la fecha de primera instalación);
  - tras abrirla, los mismos 7 días, 8 intervalos y 27 h 30 min que en la copia técnica tomada antes de actualizar; sin errores en logcat.
- Revisión de `docs/LISTA_PUBLICACION.md` §3 en el A53, automatizada por adb (`uiautomator`, `input`, `screencap`):
  - Calendario: mes, semana, detalle, alta, edición (con el aviso de «Día siguiente»), borrado, vacaciones, baja médica y persistencia tras forzar el cierre;
  - Resumen: extras del mes y del año, historial mensual y avisos de la estimación;
  - OCR con cuadrantes sintéticos elegidos desde el selector de fotos del sistema: vertical, horizontal y girado 4° con los 7 días correctos; edición en la revisión; guardado con confirmación final; reimportación sin duplicados y con el conflicto señalado; entrada desde Calendario y desde Ajustes. Con 8° o más de giro no se reconstruyen las filas (ver limitaciones);
  - copia de seguridad: exportar y restaurar por el selector de archivos del sistema, datos correctos tras forzar el cierre, y rechazo de un archivo que no es una copia;
  - navegación, tema oscuro (interruptor de Ajustes), pantalla Cuenta;
  - accesibilidad: nombres de días, botones y errores comprobados en el árbol de accesibilidad; texto al 200 % con la densidad habitual y con el zoom de pantalla al máximo.
- Tests de accesibilidad en el A53 (`LargeFontCalendarTest`, `SystemBarsThemeTest`, `CalendarTest`, `NavigationTest`, `BackupRestoreUiTest`): 9/9. JVM (`AccessibilityChecksTest`, `AccountUiTest`, `AppNavigationTest`): 19/19.

## Limitaciones conocidas
Véanse `PENDIENTES_FUTUROS.md`, `docs/DECISIONES_NOMINA_0.3.0.md` y `docs/INTEGRACION_ORACLE_CHECKLIST.md`. En especial:
- TalkBack no se ha escuchado en un dispositivo: solo se revisó el árbol de accesibilidad y los tests automáticos;
- el OCR se ha probado en el A53 con cuadrantes sintéticos; con la foto inclinada 8° o más no reconstruye las filas y hay que usar la vía manual;
- con texto al 200 % y el zoom de pantalla al máximo, los botones del editor de turnos se solapan visualmente (siguen funcionando);
- la privacidad de ML Kit y del canal de Google Play services no está verificada del todo;
- el certificado de firma es el de depuración local. Su archivo de claves es el único medio para publicar actualizaciones compatibles y debe guardarse con copias cifradas fuera del equipo.

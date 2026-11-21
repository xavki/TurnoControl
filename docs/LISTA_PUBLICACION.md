# Lista de comprobación para publicar una versión

Se completa en orden. Si algún punto falla, se detiene la publicación. Ningún paso borra datos de una instalación existente.

## 1. Versión
- [ ] `app/build.gradle.kts`: sube `versionCode` (Android no instala encima una versión con un código menor) y `versionName` coincide con las notas.
- [ ] `RELEASE_NOTES_<versión>.md` describe novedades, límites, compatibilidad y el estado real de las pruebas, con fecha.
- [ ] Si cambia el esquema de Room: hay una migración nueva y su test parte de una base de la versión anterior. No se usa `fallbackToDestructiveMigration`.
- [ ] Si cambian los valores guardados (tipos de día, tipos de tarifa): se sube `DataFormat.CURRENT` y se actualizan `CompatibilityChecker`, `BackupCodec` y `docs/COPIAS_DE_SEGURIDAD.md`.

## 2. Pruebas automáticas
- [ ] `gradlew testDebugUnitTest`: 0 fallos.
- [ ] `gradlew lintDebug`: 0 errores.
- [ ] `gradlew assembleDebug assembleVerification assembleVerificationAndroidTest`: compila.
- [ ] `gradlew connectedVerificationAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` en un dispositivo o emulador. Solo afecta al paquete aislado `com.turnocontrol.verification`; sin la opción, Gradle lo desinstala al terminar.
- [ ] En las notas, separar lo ejecutado en esta versión de lo heredado de sesiones anteriores.

## 3. Verificación en un dispositivo real (app `verification`, datos sintéticos)
- [ ] Calendario, semana, detalle, alta, edición y borrado, vacaciones y baja médica.
- [ ] Resumen: extras del mes y acumuladas del año, bruto estimado.
- [ ] OCR: foto vertical, horizontal y girada; revisar sin editar; confirmación final; reimportación sin duplicados.
- [ ] Copia de seguridad: exportar, restaurar en la app de verificación y comprobar los datos tras reiniciarla.
- [ ] Navegación Ajustes → Resumen → Calendario y tema oscuro.
- [ ] Las capturas solo contienen datos sintéticos y se guardan en `test-output/`, que Git ignora.

## 4. Datos del usuario antes de actualizar su instalación
- [ ] El usuario exporta una copia desde **Ajustes → Copia de seguridad** y la guarda fuera del teléfono. Opcionalmente, una copia técnica con `adb exec-out run-as` (solo en compilaciones `debuggable`), que también queda fuera del repositorio.
- [ ] Se comprueba que la copia se puede leer: la app la valida al seleccionarla para restaurar; se cancela en la vista previa.
- [ ] **Nunca** se desinstala la app ni se borran sus datos para «actualizar».

## 5. Firma y compatibilidad
- [ ] El certificado de la APK nueva coincide con el de la instalada: `apksigner verify --print-certs` sobre las dos.
  - Hoy ambas se firman con la clave de depuración local. Huella SHA-256: `9e94c004…0ff415`.
- [ ] **La clave de depuración (`~/.android/debug.keystore`) tiene una copia guardada fuera del repositorio.** Si se pierde, la app instalada no se puede actualizar sin desinstalarla, y desinstalar borra los datos.
- [ ] No se crea ni se cambia una clave de firma sin la aprobación expresa del usuario. Opciones documentadas:
  1. **Seguir con la clave de depuración (estado actual).** Es compatible con lo instalado, pero la APK es `debuggable` y cualquiera con adb puede leer sus datos.
  2. **Configurar una firma «release» con esa misma clave.** Es compatible y permite una APK no depurable. Requiere guardar la clave y sus contraseñas de forma segura, fuera de Git.
  3. **Clave release nueva.** Es incompatible: obliga a desinstalar. Solo es posible exportando antes una copia de seguridad y restaurándola después.

## 6. Actualización y vuelta atrás
- [ ] La actualización se hace con `adb install -r <apk>` (conserva Room y DataStore), nunca con desinstalación previa.
- [ ] Tras actualizar: la app abre sin la pantalla «No se pueden abrir tus datos» y muestra los mismos días.
- [ ] **No se admite volver a una versión anterior.**
  - Desde 0.2.0, los datos se marcan con su formato (`PRAGMA application_id`). Una versión con esa protección rechaza, sin modificarlos, los datos o copias de una versión más nueva, y no se cierra si encuentra valores desconocidos.
  - **0.1.0 y anteriores no tienen esta protección:** instaladas encima (`adb install -d`), fallarían al leer `VACATION`/`SICK_LEAVE` y, al guardar Ajustes, borrarían la tarifa `EXTRA_HOUR`. No hay forma técnica de impedirlo desde la versión nueva.

## 7. Artefacto
- [ ] `aapt dump badging <apk>`: paquete `com.turnocontrol`, `versionCode` y `versionName` esperados.
- [ ] El manifiesto final no tiene permisos de Internet ni de almacenamiento, y el único componente exportado propio es `MainActivity`.
- [ ] Se guarda la suma SHA-256 de la APK entregada.

## 8. Repositorio
- [ ] `git status` limpio; en el área de preparación no hay APK, bases de datos, `*.preferences_pb`, capturas personales, `test-output/`, `INFORME_*.md` ni volcados (`*.stackdump`, `hs_err_pid*`).
- [ ] `git diff --cached --check` sin errores; sin secretos, correos personales ni números de serie de dispositivos.
- [ ] Commit local revisado. Etiqueta, `push` y release solo con aprobación expresa.

# Revisión del cierre de Fase 0

Fecha del cliente: 13 de octubre de 2026. La conexión del Samsung A53 se recuperó y las verificaciones Android pendientes se ejecutaron satisfactoriamente. No se ha iniciado Fase 1, modificado reglas salariales, hecho push, merge ni publicado nada.

## Clasificación de las 39 advertencias originales

Todas tenían severidad `Warning` para lint; ninguna era `Error` o `Fatal`. La prioridad de revisión se determinó por su impacto real, no solo por el nombre de la comprobación.

| Comprobación | Número original | Gravedad para esta entrega | Decisión |
| --- | ---: | --- | --- |
| DataExtractionRules | 1 | Media: configuración de privacidad y compatibilidad anterior a API 31 | Corregida mediante XML explícito de exclusión para Android 8–11 |
| AndroidGradlePluginVersion | 2 | Baja: mantenimiento, avisos duplicados sobre AGP | Mantener combinación AGP/Gradle/JDK comprobada |
| GradleDependency | 17 | Baja: disponibilidad de versiones más recientes, algunos duplicados de Room | No actualizar bibliotecas sin una necesidad funcional o un hallazgo concreto de seguridad |
| UseTomlInstead | 18 | Baja: organización del catálogo de versiones | Mantener declaraciones actuales; trasladarlas no resuelve un problema funcional |
| KaptUsageInsteadOfKsp | 1 | Baja: rendimiento del build y mantenimiento | No migrar procesadores como parte de esta revisión |

Resultado actual: **38 advertencias, 0 errores**. No se añadieron suppressions, baselines, `tools:ignore`, desactivaciones de checks ni cambios de severidad. No se afirma haber realizado una auditoría de vulnerabilidades de todas las dependencias; los avisos de versión por sí solos no acreditan una vulnerabilidad.

La corrección conserva `allowBackup=false` y `dataExtractionRules`, y sustituye `fullBackupContent=false` por `@xml/backup_rules`. El nuevo XML excluye explícitamente todos los dominios de backup soportados, incluidos almacenamiento de dispositivo y archivos externos. Se ha comprobado con `aapt dump xmltree` que el APK contiene los tres atributos. No se ha ensayado una restauración real ni transferencia entre dispositivos. Referencia: [cambios oficiales de backup en Android 12](https://developer.android.com/about/versions/12/behavior-changes-12#backup-restore).

## Verificaciones y resultados de esta revisión

| Comprobación | Resultado actual |
| --- | --- |
| `assembleDebug` | Correcto |
| `assembleVerification` y `assembleVerificationAndroidTest` | Correctos; suite instrumentada actual compilada |
| `testDebugUnitTest` | 6 tests, 0 fallos y 0 errores |
| Migración 1→2 local con Robolectric | Correcta; preserva perfil e importe y añade moneda EUR |
| Reapertura Room y rollback local | Correctos |
| `lintDebug` | Correcto; 38 advertencias de mantenimiento, 0 errores |
| `connectedVerificationAndroidTest` | 4 tests ejecutados en Samsung A53 / Android 16, 0 fallos, 0 omitidos |
| Reinicio real de aplicación/proceso | Script ejecutado: fase seed y fase verify, ambas `OK (1 test)`; salida final PASS |
| Commit de cierre | Primer commit local autorizado tras completar todas las verificaciones; hash entregado en la respuesta de cierre y consultable con `git rev-parse HEAD` |

La prueba instrumentada de migración se amplió para insertar perfil, contrato y cinco tarifas ficticias, comparar todos los campos existentes antes/después y comprobar moneda EUR en las cinco filas. `MigrationTestHelper` valida también la estructura final frente al esquema exportado 2. Esta prueba se ejecutó y pasó en el dispositivo en la revisión final.

La nueva prueba `RestartPersistenceTest` utiliza el repositorio real y cantidades ficticias distintas de las de la referencia. En ejecución normal usa su propia base, sin interferir en el test de Compose. El script de dos fases utiliza la base real de la variante aislada `com.turnocontrol.verification`: guarda configuración, cierra el proceso con `force-stop`, relanza la aplicación y comprueba en un proceso distinto todos los campos del perfil, contrato y cinco tarifas. Las recreaciones de Activity anteriores no demostraban por sí solas un reinicio de proceso. Ahora las dos fases se ejecutaron realmente y pasaron, incluida la aserción de PID diferente; el arranque intermedio devolvió `Status: ok` y `LaunchState: COLD`.

ADB inicialmente detectó el móvil bloqueado; posteriormente dejó de detectar dispositivos. Se repitió la consulta, se reinició el servidor ADB y Windows tampoco mostró un controlador Samsung/Android presente en la consulta realizada. Tras nuevas conexiones y autorización USB por el usuario, ADB confirmó el modelo SM-A536B en estado `device`. El bloqueo quedó resuelto antes de ejecutar las pruebas finales; los intentos anteriores sin dispositivo no se contaron como pruebas superadas.

## Comandos reproducibles

Desde la raíz con JDK 17 y SDK 35 configurados:

```powershell
.\gradlew.bat --no-daemon assembleDebug lintDebug testDebugUnitTest assembleVerification assembleVerificationAndroidTest
.\gradlew.bat --no-daemon connectedVerificationAndroidTest
powershell -NoProfile -File .\scripts\verify-restart.ps1
.\gradlew.bat --no-daemon assembleDebug lintDebug testDebugUnitTest --rerun-tasks
```

El SDK local de esta ejecución está en `.tools/android-sdk`; el script acepta `-AdbPath` para otro SDK. La prueba de interfaz requiere un móvil/emulador autorizado y desbloqueado. El script instala exclusivamente los APK de verificación; no lee ni sustituye la configuración del APK privado `com.turnocontrol`.

## Revisión de privacidad y contenido Git

La carpeta no era un repositorio Git; se inicializó uno local en `main` para el único commit autorizado. La creación del commit se reservó hasta completar satisfactoriamente las verificaciones, incluida la prueba de reinicio real.

Se enumeraron 32 archivos candidatos mediante `git ls-files --cached --others --exclude-standard`, incluyendo este informe. Se revisaron fuentes, fixtures, configuración y documentación. No se encontraron documentos PDF/DOCX, imágenes de horarios/nóminas, bases de datos o claves de firma entre esos candidatos. Los fixtures usan nombres explícitamente ficticios.

El escaneo de texto no encontró firmas de claves privadas, tokens de proveedores habituales, credenciales asignadas, IBAN español, DNI, identificador del móvil, rutas personales de Windows ni nombres reales del caso de referencia. Este resultado es una revisión del contenido candidato, no una garantía universal de ausencia de cualquier dato sensible.

El único binario candidato es el Gradle Wrapper. Su SHA-256 `2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046` coincide con el [checksum oficial de Gradle 8.11.1](https://services.gradle.org/distributions/gradle-8.11.1-wrapper.jar.sha256).

Se confirmó con `git check-ignore` la exclusión de `local.properties`, SDK `.tools`, builds y caché `.gradle`. Se ampliaron las exclusiones para archivos de entorno, claves/certificados y bases de datos. Las exclusiones no sustituyeron al escaneo. Los informes generados, que pueden contener rutas y metadatos del dispositivo, permanecen fuera de Git.

El APK compilado declara únicamente el permiso interno de receptor dinámico no exportado; no declara permisos de Internet, almacenamiento, cámara ni notificaciones.

## Archivos cambiados respecto a la primera entrega

- `.gitignore`: exclusiones de entorno, certificados y bases de datos.
- `app/src/main/AndroidManifest.xml`: referencia al XML de backup anterior a API 31.
- `app/src/main/res/xml/backup_rules.xml`: nuevo; exclusiones explícitas.
- `app/src/androidTest/java/com/turnocontrol/data/PersistenceTest.kt`: conservación completa de datos durante migración; tipos explícitos en argumentos SQL.
- `app/src/androidTest/java/com/turnocontrol/data/RestartPersistenceTest.kt`: nuevo; persistencia mediante repositorio y dos fases para reinicio real.
- `scripts/verify-restart.ps1`: nuevo; ejecución reproducible del reinicio aislado.
- `README.md` y `docs/FASE_0.md`: referencias a esta revisión y comandos.
- `docs/CIERRE_FASE_0.md`: este informe.

Al ser un repositorio recién inicializado, el primer commit incluye también todos los archivos base de Fase 0; la lista anterior distingue los cambios de esta revisión.

## Límites y siguiente revisión

Se mantiene la limitación a un dispositivo y Android 16: no se ha comprobado una matriz de versiones, restauración de backup o distribución de producción. Las 38 advertencias de mantenimiento permanecen visibles. La variante de verificación usa únicamente datos ficticios y no modifica la configuración del APK privado del usuario. Fase 1 continúa fuera del alcance y requiere autorización explícita.

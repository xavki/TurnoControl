# Preparación de portfolio — v0.1.0

Base pública saneada: `d76842e727cdca4f853531b5ee7995a151a94867`. Preparación autorizada el 15/10/2026 para una primera versión de portfolio Android. El clon saneado se adopta como directorio de trabajo independiente, con tres commits históricos y la identidad pública aprobada TurnoControl. Este documento acompaña el único commit local de preparación de v0.1.0; no implica tag, release, repositorio remoto ni publicación. La licencia elegida es MIT y LICENSE contiene Copyright (c) 2026 TurnoControl.

## Alcance

- README orientado al producto, tecnologías, compilación multiplataforma, pruebas, privacidad y limitaciones.
- versionName 0.1.0; versionCode permanece en 1. No cambia el esquema Room ni el comportamiento funcional.
- Permiso ejecutable de gradlew registrado como 100755 para Linux/macOS. En Windows este permiso se conserva mediante el índice Git.
- Exclusión de APK/AAB, temporales, bundles de recuperación, fotos y carpeta private. Solo se permite la excepción PNG de docs/screenshots para capturas públicas sintéticas revisadas, fuera de captures y build. Cualquier futuro recurso fotográfico legítimo necesitará una excepción revisada.
- Script de reinicio documentado, descubrimiento de ADB mediante entorno/PATH y comprobación de un único dispositivo autorizado antes de instalar. Conserva el destino exclusivo com.turnocontrol.verification.
- Prueba PortfolioScreenshotsTest: calendario, detalle, editor y extras reales de Compose, usando únicamente planificación sintética en la variante aislada. No captura barras del sistema, notificaciones ni fotografías de usuario.

## Generación de capturas

Con un único dispositivo autorizado, desbloqueado y en orientación vertical:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 assembleVerification assembleVerificationAndroidTest
adb install -r app/build/outputs/apk/verification/app-verification.apk
adb install -r app/build/outputs/apk/androidTest/verification/app-verification-androidTest.apk
adb shell am instrument -w -e class com.turnocontrol.PortfolioScreenshotsTest com.turnocontrol.verification.test/androidx.test.runner.AndroidJUnitRunner
```

La prueba genera cuatro PNG en cache/portfolio de la aplicación aislada. Usa fechas del mes actual, horas inventadas y ningún nombre. Restaura la planificación anterior y el tema de la variante de pruebas al terminar; no afecta al paquete privado. Exportar los PNG mediante `adb exec-out run-as com.turnocontrol.verification cat cache/portfolio/<nombre>.png` a docs/screenshots usando una transferencia binaria, sin redirección textual de Windows PowerShell. Nombres: calendar.png, day-detail.png, shift-editor.png y extra-hours.png. Revisar visualmente los cuatro antes de versionarlos. Los ficheros de caché pueden eliminarse después de exportar.

La suite connectedVerificationAndroidTest puede desinstalar su variante al finalizar. Para recuperar las capturas, ejecutar la prueba dedicada después de instalar ambos APK, como arriba. No instalar ni ejecutar estas fixtures sobre com.turnocontrol.

## Verificación

Verificaciones realizadas el 15/10/2026 mediante el Wrapper del proyecto:

| Comprobación | Resultado |
| --- | --- |
| assembleDebug y assembleVerificationAndroidTest | BUILD SUCCESSFUL; versión 0.1.0, versionCode 1 |
| testDebugUnitTest | 69 tests, 0 fallos ni errores |
| lintDebug / lintVerification | 0 errores; 43 / 47 advertencias de mantenimiento, sin supresiones nuevas |
| Copia limpia de fuentes: assembleDebug testDebugUnitTest | BUILD SUCCESSFUL; 53 tareas ejecutadas; 69 tests, 0 fallos ni errores |
| connectedVerificationAndroidTest final, Samsung A53 / Android 16 | 19 tests, 0 fallos ni errores, 0 omitidos; incluye regresión y el nuevo generador |
| Generación dedicada de capturas | OK (1 test); cuatro PNG exportados y revisados visualmente, con datos ficticios |
| Script de reinicio | seed y verify: OK (1 test) cada fase, PID distinto y conservación de perfil, contrato, cinco tarifas, partidos, nocturnos y extras |
| Ajuste temporal de pantalla | Restaurado y comprobado en su valor original 0 |

La copia limpia se creó sin .git, local.properties, SDK interno ni builds previos. Se ejecutó su propio gradlew.bat con JDK 17 y ANDROID_HOME apuntando al SDK externo a esa copia. Utilizó la caché de dependencias del equipo: no fue una descarga desde cero ni una ejecución Linux/macOS. La comprobación limpia cubrió la aplicación y tests locales; los tests instrumentados se compilan y ejecutan desde el proyecto principal.

Incidencias de preparación: el primer generador no distinguía las dos raíces de Compose al abrir el editor; además, la captura de su raíz podía recoger el fondo de la Activity. La revisión visual detectó el problema y se sustituyó únicamente la captura de diálogo del test por PixelCopy de su ventana real. Una repetición Android tuvo fallos de interfaz con el móvil bloqueado, confirmado mediante ADB; se pidió desbloquear y repetir, sin modificar la aplicación ni ocultar fallos. El script asumía la variable OS de Windows, ausente en este entorno: ahora consulta la plataforma .NET. Las advertencias de XML del SDK, procesadores y empaquetado de bibliotecas nativas de debug no impidieron compilar.

La última suite completa y la generación dedicada pasaron después de esas correcciones. Las copias PNG de caché se eliminaron tras exportar. No se modificó ni instaló el paquete privado del usuario. Los cuatro esquemas y el código de producción permanecen idénticos a la base, salvo versionName en la configuración de compilación; versionCode sigue en 1.

## Cierre local y alcance de los archivos

Se incluyen los doce archivos de preparación autorizados: LICENSE, README.md, .gitignore, app/build.gradle.kts, gradlew (solo modo), scripts/verify-restart.ps1, app/src/androidTest/java/com/turnocontrol/PortfolioScreenshotsTest.kt, este documento y los cuatro PNG de docs/screenshots. Se añaden únicamente aclaraciones documentales sobre las etapas históricas y sus hashes de base. No cambian funcionalidades, esquemas Room, contratos, tarifas ni reglas salariales.

La licencia MIT y la identidad pública ya están aprobadas. La corrección OCR fue validada manualmente por el usuario el 15/10/2026 para el formato probado, sin observaciones; sus limitaciones se mantienen. Los informes históricos conservan sus fechas, condiciones y resultados, aunque describan revisiones o autorizaciones entonces pendientes.

El historial adoptado conserva los árboles, mensajes, fechas y orden de los tres commits de desarrollo; la identidad se saneó en una copia independiente. El repositorio original y los respaldos se conservan fuera del proyecto público. Los mecanismos de recuperación e informes internos no forman parte de los archivos destinados a publicación.

La comprobación del clon saneado con los archivos de preparación también finalizó el 15/10/2026: assembleDebug, testDebugUnitTest, lintDebug, lintVerification y connectedVerificationAndroidTest pasaron; 69 tests locales y 19 Android, sin fallos ni omisiones. Los resultados de etapas anteriores de la tabla precedente mantienen sus condiciones originales. Las aclaraciones documentales posteriores no requieren cambios de código.

Una copia limpia para validar debe contener exclusivamente archivos versionados y candidatos revisados, incluyendo el Wrapper y los esquemas. Excluir local.properties, herramientas locales, cachés, informes, APK y .git. Configurar ANDROID_HOME externamente y ejecutar el Wrapper, sin recurrir al ejecutable Gradle global. Usar cachés existentes no demuestra una descarga íntegra desde cero ni ejecución en Linux/macOS.

No se incluyen fotografías reales, datos laborales privados, secretos, bases de datos, APK, respaldos ni informes internos. La publicación en GitHub, la creación de un repositorio remoto y cualquier tag o release requieren autorización posterior. Antes de distribuir un APK sigue siendo necesaria la revisión de los componentes de terceros realmente empaquetados.

## Revisión de licencias de terceros — 15/10/2026

MIT se aplica al código propio de TurnoControl. El usuario delegó la elección del titular y nombre público; se eligió el alias TurnoControl, sin atribuirlo a una persona o sociedad inventada ni utilizar la identidad corporativa. LICENSE contiene el texto estándar MIT y Copyright (c) 2026 TurnoControl. Las atribuciones de terceros no cambian.

Se han revisado los encabezados versionados, META-INF del Wrapper y los POM disponibles en la caché local de dependencias. Esta revisión no es un inventario completo de todas las dependencias transitivas de un futuro APK.

| Componente revisado | Declaración observada | Tratamiento |
| --- | --- | --- |
| gradlew y gradlew.bat | Apache-2.0 y copyright de los autores originales | Mantener los encabezados sin sustituirlos por MIT |
| gradle-wrapper.jar | Incluye META-INF/LICENSE con Apache 2.0; no se encontró NOTICE en el JAR | Conservar el JAR íntegro y su licencia incluida |
| Hilt 2.56.2, Room 2.7.1, DataStore 1.1.4, coroutines-test 1.10.2 | Apache 2.0 en sus POM | No relicenciar; preservar licencias y avisos al redistribuir sus componentes |
| ML Kit Text Recognition 16.0.1 y componentes relacionados examinados | ML Kit Terms of Service en sus POM | No presentar el SDK ni sus modelos como MIT |
| JUnit 4.13.2, solo tests | Eclipse Public License 1.0 en su POM | Conservar su licencia; no forma parte de las fuentes propias de la app |
| Robolectric 4.14.1, solo tests | MIT en su POM | Su copyright y permiso son propios del componente, no del titular de TurnoControl |

Referencias primarias: [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0), [términos ML Kit](https://developers.google.com/ml-kit/terms), [MIT](https://opensource.org/license/mit). Apache exige mantener avisos y proporcionar su licencia al redistribuir el componente, y conservar los NOTICE aplicables cuando existan. El Wrapper ya contiene el texto de su licencia; no se elimina ni modifica. El repositorio no incluye los AAR/JAR descargados de dependencias, salvo ese Wrapper. Las capturas son salidas sintéticas de la interfaz propia y el icono versionado es XML; no se incorporan fotos o recursos gráficos de terceros.

Los términos de ML Kit distinguen procesamiento local de entradas y posibles comunicaciones de métricas del SDK. TurnoControl elimina INTERNET y ACCESS_NETWORK_STATE del manifiesto; no se deduce de ello una certificación general de cumplimiento para una futura distribución. Antes de distribuir APK, revisar el inventario transitivo y conservar los textos y avisos que correspondan a los componentes realmente empaquetados. No se añaden pantallas de licencias ni cambios funcionales en esta preparación.

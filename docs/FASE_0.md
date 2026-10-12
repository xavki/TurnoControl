# Revisión de Fase 0

Este documento registra la primera ejecución. La revisión posterior solicitada por el usuario y su estado actual están en [CIERRE_FASE_0.md](CIERRE_FASE_0.md).

Alcance: base Android, cinco destinos navegables, perfil/contrato/tarifas locales, Room, DataStore, Hilt y pruebas iniciales. La especificación DOCX se leyó como referencia y no se copió al proyecto. No se implementaron OCR, importaciones, fichajes operativos, avisos, cálculo salarial ni PDF.

## Inspección del entorno

La carpeta inicial estaba vacía y no tenía un repositorio Git, ni en ella ni en sus carpetas padre. No se han creado commits, remotos ni acciones de push, merge, release o despliegue.

Se encontraron JDK 17.0.15 y Gradle 8.11.1 en la caché local; Gradle y ADB no estaban en PATH. Android Studio estaba instalado, pero no había SDK utilizable en las ubicaciones comprobadas ni variables ANDROID_HOME/JAVA_HOME configuradas. Se descargaron las herramientas oficiales y se instalaron SDK 35, Build Tools 35.0.0 y Platform Tools bajo `.tools/android-sdk`, con autorización de instalación/licencias. El SHA-256 del ZIP de herramientas coincidió con el publicado por Android Developers.

El sandbox impidió cargar los servicios nativos de Gradle y resolver ciertas descargas. Las comprobaciones efectivas se ejecutaron con autorización fuera del sandbox. Bash se intentó usar, pero no devolvía salida útil en este entorno; se utilizó PowerShell. No se encontró el RTK.md referenciado en las ubicaciones del proyecto y ancestros comprobadas.

El Gradle Wrapper se generó desde la distribución local. Las versiones están fijadas (AGP 8.9.0, Kotlin/Compose compiler 2.1.20, Room 2.7.1, Hilt 2.56.2), sin versiones dinámicas. La combinación AGP/Gradle/JDK sigue la [tabla oficial de compatibilidad de AGP 8.9](https://developer.android.com/build/releases/agp-8-9-0-release-notes). El plugin de Compose usa la misma versión que Kotlin conforme a la [guía del compilador](https://kotlinlang.org/docs/compose-compiler-migration-guide.html).

## Cambios entregados

Todos los archivos de fuente y configuración son nuevos. El inventario principal está en el README: configuración Gradle y wrapper; manifiesto y reglas de backup; icono; Application/Activity; paquetes ui, domain, data y di; esquemas JSON 1/2; pruebas locales e instrumentadas; documentación y exclusiones de Git.

Un módulo `app` contiene MVVM, repositorio e inyección. El modelo de esta fase se limita a perfil, contrato y tarifas. Los destinos Inicio, Calendario, Fichajes, Nómina y Ajustes son accesibles mediante navegación inferior con conservación del estado de navegación. El tema oscuro es una preferencia DataStore. Los estados vacíos no muestran horas, ingresos ni eventos inventados.

La configuración exige nombre visible, fecha ISO válida, zona IANA válida y horas positivas convertibles a minutos enteros. Las cinco tarifas son opcionales, aceptan coma decimal y hasta cuatro decimales. No existen tarifas precargadas ni reglas automáticas de pluses. La edición actual sustituye la configuración anterior, sin historial contractual operativo.

Room usa transacciones, claves foráneas e índices. Los esquemas se exportan al repositorio de fuentes. La migración 1→2 añade moneda EUR y preserva el importe decimal. No se habilita recreación destructiva de la base.

## Comandos reproducibles

Con JDK 17, SDK 35 configurado y desde la raíz:

```powershell
.\gradlew.bat --no-daemon assembleDebug lintDebug testDebugUnitTest
.\gradlew.bat --no-daemon connectedVerificationAndroidTest
.\gradlew.bat --no-daemon installDebug
```

Se utilizó también el ejecutable de Gradle 8.11.1 ya presente en la caché, equivalente al wrapper generado. Las pruebas instrumentadas usan una variante `verification` con ID diferente para aislar los datos ficticios. `installDebug` actualiza el APK privado con el ID original.

Informes generados y excluidos de Git:

- `app/build/reports/tests/testDebugUnitTest/index.html`
- `app/build/reports/lint-results-debug.html`
- `app/build/reports/androidTests/connected/verification/index.html`
- `app/build/outputs/apk/debug/app-debug.apk`

## Incidencias corregidas durante la verificación

- SDK ausente: instalación local y configuración en `local.properties`, ignorado.
- ADB inicialmente sin dispositivo, posteriormente bloqueado/no autorizado: el usuario conectó, desbloqueó y autorizó el móvil.
- Runtime Robolectric sin resolución DNS en su endpoint inicial: se configuró Maven Central; posteriormente pudo ejecutar los tests.
- Test de Room que trataba RoomDatabase como Closeable: cierre explícito en `finally`.
- Test del esquema que exigía índices incluso en tablas sin índices: lectura opcional del campo JSON.
- Import incorrecto de una aserción Compose: corregido.
- Expectativa de normalización visual del decimal durante recreación: corregida; el ViewModel conserva el texto introducido, mientras Room guarda la cantidad decimal canónica.
- Daemon anterior incapaz de iniciar ADB: proceso nuevo con `--no-daemon`.

## Resultado final

Revisión cerrada el 13 de octubre de 2026, según la fecha del cliente. El comando final `--no-daemon assembleDebug lintDebug testDebugUnitTest connectedVerificationAndroidTest installDebug` terminó con `BUILD SUCCESSFUL`.

| Comprobación | Resultado |
| --- | --- |
| APK debug | Compilado e instalado en el móvil |
| Validación JUnit | 3 tests, 0 fallos |
| Room con Robolectric | 3 tests, 0 fallos: reapertura, rollback y migración |
| Android instrumentado | 3 tests, 0 fallos: recorrido Compose, persistencia y migración |
| Lint debug | Ejecutado, 0 errores y 39 advertencias |
| Arranque del APK privado | `am start -W` devolvió `Status: ok`, arranque COLD y proceso presente |

Lint conserva 2 avisos sobre AGP, 17 sobre dependencias, 18 sobre uso del catálogo, 1 sobre kapt y 1 sobre reglas de backup anteriores a API 31. Este último permanece en el informe aun habiendo configurado explícitamente `allowBackup=false`, `fullBackupContent=false` y las reglas para API 31+. La configuración de backup no se ha probado mediante una restauración real. También aparecen avisos de herramientas sobre formato XML del SDK, opciones kapt sin procesadores en tests y bibliotecas nativas empaquetadas sin eliminar símbolos; no bloquearon la construcción.

APK verificado: `app/build/outputs/apk/debug/app-debug.apk`.

SHA-256: `08DD69AA11102E841287D2882EE1EBAFB562CC0231B0D9CDBF2022BA0EFD0781`.

El APK privado queda abierto para revisión. Los tests usan datos ficticios en la variante aislada; no incorporan documentos reales. El éxito corresponde únicamente a las funciones y al dispositivo comprobados, no a las funciones de fases futuras.

## Límites de verificación y producto

Las pruebas del móvil cubren un Samsung A53 con Android 16. No equivalen a una matriz de dispositivos, APIs, tamaños de pantalla, TalkBack, idiomas, proceso muerto, poco espacio o restricciones de fabricantes. No se ha verificado una versión de publicación ni firma de producción. El APK privado es de depuración.

No hay datos reales en las fuentes ni fixtures. Los informes de herramientas contienen metadatos locales y permanecen fuera de Git. La aplicación usa almacenamiento privado; no implementa cifrado adicional, exportación, bloqueo biométrico o borrado completo desde Ajustes. No requiere permisos de Internet, cámara, lectura general de almacenamiento o notificaciones en su manifiesto fuente.

Las dependencias fijadas no son las más recientes. Lint avisa sobre versiones, dependencias declaradas directamente y uso de kapt. La actualización de toda la cadena no forma parte de esta fase. Tampoco se ha creado CI remoto: no existe repositorio/remoto configurado ni se ha autorizado publicar código.

## Preguntas abiertas para una fase futura

1. ¿Cuál será el identificador definitivo de paquete para Google Play y quién custodiará la firma? `com.turnocontrol` es provisional.
2. ¿Qué convenio y contrato determinan franjas nocturnas, plus especial, acumulación, pausas, festivos y clasificación de horas?
3. ¿Se necesitarán salario mensual, pagas separadas y cambios de vigencia dentro del mes? Esta base solo configura tarifas por hora y la fecha inicial.
4. ¿Qué políticas de retención, exportación/borrado y protección adicional se desean antes de incorporar documentos sensibles?
5. ¿Qué dispositivos/API y pruebas de accesibilidad deben formar la matriz de aceptación posterior?

Estas respuestas no se han sustituido por reglas supuestas. Fase 1 queda pendiente de revisión y autorización explícita.

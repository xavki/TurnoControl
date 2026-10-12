# TurnoControl

Base Android nativa de Fase 0. Incluye navegación y configuración local; las pantallas Calendario, Fichajes y Nómina muestran estados vacíos y explican sus límites. No incluye OCR, documentos PDF, notificaciones, fichajes ni cálculo de nómina.

## Compilar y verificar

Requisitos: JDK 17, Android SDK con `platforms;android-35`, `build-tools;35.0.0` y `platform-tools`. El Gradle Wrapper usa Gradle 8.11.1. Las dependencias iniciales requieren conexión; la aplicación no necesita servidor.

Configura `ANDROID_HOME` o crea `local.properties` (ignorado por Git):

```properties
sdk.dir=C:/ruta/al/Android/Sdk
```

Desde la raíz, en Windows:

```powershell
.\gradlew.bat --no-daemon assembleDebug lintDebug testDebugUnitTest
.\gradlew.bat --no-daemon connectedVerificationAndroidTest
.\gradlew.bat --no-daemon installDebug
```

En Bash, utiliza `./gradlew` con los mismos argumentos. `--no-daemon` evita reutilizar un daemon iniciado con un entorno o permisos diferentes. Las pruebas conectadas requieren un dispositivo o emulador con Android 8 o superior, depuración USB autorizada y pantalla desbloqueada.

El SDK utilizado durante esta ejecución está en `.tools/android-sdk`, excluido del control de versiones. Para preparar otro entorno, usa el SDK Manager de Android Studio o las herramientas oficiales y acepta sus licencias de forma explícita.

APK privado: `app/build/outputs/apk/debug/app-debug.apk`. Está firmado con la clave de depuración local, no con una clave de distribución. La compilación de prueba `verification` usa `com.turnocontrol.verification` para aislar sus datos del APK privado `com.turnocontrol`.

## Estructura

Un solo módulo `app`, sin módulos de dominio o datos separados:

```text
gradle/libs.versions.toml                 Plugins y dependencias principales fijados
app/src/main/AndroidManifest.xml         Aplicación sin permisos de red o almacenamiento general
app/src/main/java/com/turnocontrol/
  TurnoApplication.kt                    Inicio de Hilt
  MainActivity.kt                        Compose, Material 3 y Navigation
  ui/ConfigurationViewModel.kt           Estado, validación y operaciones asíncronas
  domain/Configuration.kt                Borrador de configuración y validación decimal
  data/Database.kt                       Entidades, DAO y migración
  data/ConfigurationRepository.kt        Room y preferencias DataStore
  di/StorageModule.kt                    Provisión singleton de Room
app/schemas/                            Esquemas Room 1 y 2 exportados
app/src/test/                           Validación y Room con Robolectric
app/src/androidTest/                    Compose, persistencia y migración en Android
```

MVVM: Compose observa `StateFlow` con el ciclo de vida; el ViewModel utiliza el repositorio, que guarda perfil, contrato y tarifas en una única transacción. Hilt proporciona la base y el repositorio. La validación es una función de dominio independiente; no hay motor salarial ni casos de uso vacíos.

## Configuración local

- Perfil: nombre visible, alias opcional y zona horaria IANA.
- Contrato: jornada parcial/completa, horas positivas, unidad semana/mes/año y fecha inicial ISO.
- Cinco tarifas opcionales en EUR/h: base, prorrata, complementaria, plus nocturno y plus nocturno especial. Inicialmente están vacías; los ejemplos del documento no se precargan.
- Tema claro/oscuro persistido con DataStore.

Las horas se guardan como minutos enteros, las cantidades monetarias como cadenas decimales. Se acepta coma o punto decimal y no se usa `Double` para dinero. La configuración actual sustituye a la anterior; todavía no existe historial de vigencias ni selección de varios perfiles. La estructura incluye claves de perfil y campos de vigencia para ampliar estas funciones cuando se apruebe su fase.

## Persistencia y evolución

Room contiene `Profile`, `Contract` y `PayRule`, las entidades necesarias para esta fase. Contrato y tarifas referencian el perfil mediante claves foráneas e índices. Las entidades de turnos, importaciones, fichajes, periodos salariales y comparación de nóminas se añadirán con sus funciones, sin persistir estructuras ficticias ahora.

El esquema inicial 1 conserva la configuración; el esquema 2 añade una moneda explícita `EUR` a `PayRule`. `MIGRATION_1_2` mantiene los datos existentes. Ambos JSON están incluidos; no hay `fallbackToDestructiveMigration`. Las siguientes modificaciones del esquema deben incrementar versión y aportar una migración y su prueba.

## Privacidad

Datos en almacenamiento privado de Android, sin cuentas ni backend. Copia automática y transferencia de datos desactivadas mediante manifiesto y reglas de extracción. No se incorporan documentos, imágenes, alias reales, identificadores del dispositivo ni nóminas a las fuentes o fixtures. No se afirma cifrado propio ni protección contra un dispositivo comprometido.

`.gitignore` excluye SDK local, builds, informes, cachés, `local.properties`, documentos PDF/DOCX y claves de firma. Los informes generados por las herramientas pueden contener rutas locales e identificadores del equipo o dispositivo: permanecen en `app/build/`, nunca deben incorporarse al control de versiones.

## Pruebas y revisión

Los tests locales comprueban validación, reapertura de Room, eliminación de tarifas opcionales, atomicidad ante una clave foránea inválida y migración del esquema exportado. Los instrumentados comprueban navegación, guardado mediante el formulario, recreación de Activity, preferencia de tema, persistencia en disco y migración con `MigrationTestHelper`.

Resultados, incidencias del entorno y preguntas pendientes: [docs/FASE_0.md](docs/FASE_0.md).

La revisión posterior de cierre, incluyendo advertencias de lint y resultados de las verificaciones locales y Android, está en [docs/CIERRE_FASE_0.md](docs/CIERRE_FASE_0.md). Para comprobar un reinicio de proceso con datos ficticios en la variante aislada:

```powershell
.\gradlew.bat --no-daemon assembleVerification assembleVerificationAndroidTest
powershell -NoProfile -File .\scripts\verify-restart.ps1
```

Si el SDK está en otra ubicación, pasa `-AdbPath C:/ruta/al/Sdk/platform-tools/adb.exe`. El script verifica las dos fases instrumentadas, detiene y relanza únicamente `com.turnocontrol.verification`, y exige un PID diferente con perfil, contrato y cinco tarifas conservados. No debe ejecutarse sobre el paquete privado del usuario.

La aplicación se detiene en Fase 0. Continuar requiere revisión y autorización; no se han preparado acciones de publicación o distribución en Google Play.

# TurnoControl

Aplicación Android nativa centrada en un calendario mensual/semanal de turnos programados. Detalle por día, editor normal/partido con rueda de horas y minutos, clasificación extra por intervalo y guardado local sin exigir contrato ni tarifas. Cuatro pestañas: Calendario, Horas extras, Nómina y Ajustes. La fotografía es una entrada alternativa desde Ajustes → Importar horario: OCR local → trabajador → revisión → conflictos → confirmación explícita. No incluye documentos PDF, notificaciones, fichajes reales ni cálculo de nómina. El rediseño está pendiente de revisión visual y funcional y no tiene commit.

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
  ui/ImportScreen.kt                     Selectores Android, vista previa y texto
  ui/ImportViewModel.kt                  Estado temporal y cancelación de importación
  data/ImageOcr.kt                       Decodificación acotada y ML Kit local
  domain/OcrDocument.kt                  Bloques, líneas, elementos y geometría
  di/OcrModule.kt                        Inyección del servicio OCR
  domain/ScheduleInterpretation.kt       Resultados provisionales y parser horario estricto
  domain/WorkerRowsInterpreter.kt        Reconstrucción geométrica independiente de Android
  domain/WeeklyReportInterpreter.kt      Siete fechas, filas multilínea y perspectiva
  domain/ReportCellParser.kt             Anotaciones admitidas, LIB y VAC provisional
  ui/ScheduleReviewViewModel.kt          Interpretación y correcciones temporales
  ui/ScheduleReviewScreen.kt             Revisión del trabajador y sus días
  ui/OcrRowSelector.kt                   Selección explícita sobre foto o lista de filas
  di/ScheduleModule.kt                   Estrategia de interpretación mediante Hilt
  domain/PlannedSchedule.kt              Validación de planificación y solapamientos
  data/ScheduleStorage.kt                Tablas de días/intervalos, DAO y migraciones hasta 4
  data/ScheduleRepository.kt             Guardado atómico y control de cambios concurrentes
  ui/CalendarViewModel.kt                Calendario, editor y confirmación de propuestas
  ui/CalendarScreen.kt                   Mes/Semana y confirmación de conflictos OCR
  ui/DayDetailScreen.kt                  Detalle, edición por intervalo y estados del día
  ui/TimeWheel.kt                        Ruedas Compose de horas/minutos y accesibilidad
  ui/ExtrasScreen.kt                     Extras declaradas por fecha y subtotal
app/schemas/                            Esquemas Room 1, 2, 3 y 4 exportados
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

Room contiene `Profile`, `Contract`, `PayRule`, `ScheduledDay` y `ScheduledInterval`. El calendario necesita únicamente un perfil mínimo local; no crea contratos ni tarifas. Los turnos confirmados se guardan separados del OCR provisional y de futuros fichajes reales. No se persisten imágenes ni el texto OCR completo.

El esquema inicial 1 conserva la configuración; el esquema 2 añade una moneda explícita `EUR` a `PayRule`. `MIGRATION_2_3` crea únicamente las dos tablas de planificación y sus índices. `MIGRATION_3_4` añade `isExtra` por intervalo con valor inicial false, sin inferir extras en turnos antiguos. Se conservan las migraciones y los cuatro esquemas; no hay `fallbackToDestructiveMigration`. Las rutas 1→4, 2→4 y 3→4 tienen pruebas de conservación de configuración y turnos existentes.

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

El cierre previo y la validación manual del OCR comunicada por el usuario están en [docs/FASE_1_1.md](docs/FASE_1_1.md). La base de interpretación reutilizada se describe en [docs/FASE_1_2.md](docs/FASE_1_2.md) y el primer calendario persistente en [docs/CALENDARIO.md](docs/CALENDARIO.md). El estado actual, Room 4, rueda, extras y pasos de prueba están en [docs/REDISENO_INTERFAZ.md](docs/REDISENO_INTERFAZ.md). No se ha creado ningún commit nuevo; la revisión del usuario sigue pendiente.

La corrección posterior de importación de tablas semanales, selección de fila, anotaciones y vacaciones no guardables se documenta en [docs/CORRECCION_OCR_TABLAS.md](docs/CORRECCION_OCR_TABLAS.md). No cambia Room 4 ni clasifica extras automáticamente.

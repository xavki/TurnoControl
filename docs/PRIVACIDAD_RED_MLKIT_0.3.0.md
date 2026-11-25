# TurnoControl 0.3.0: privacidad de red de ML Kit (fase 4B.5)

Estado: **investigación y salvaguardas de prueba. El aislamiento NO ESTÁ VALIDADO TODAVÍA EN ANDROID.** Fecha: 2026-11-22.

Base: rama `feature/0.2.0-redesign`, HEAD `c7bd9ee`.
- No se ha modificado código de producción, el manifiesto ni las dependencias de la app.
- No se ha habilitado Internet ni se ha generado ningún APK.

Marcas:
- **[V]**: verificado en este repositorio o en documentación primaria;
- **[NV]**: sin verificar;
- **[P]**: propuesta.

## 1. Dependencias (verificadas con `:app:dependencies` y `:app:dependencyInsight`, `debugRuntimeClasspath`)

| Artefacto | Versión | Llega por | Función |
|---|---|---|---|
| `com.google.mlkit:text-recognition` | 16.0.1 | declarado en la app | API de OCR latino con modelo incluido **[V]** |
| `com.google.mlkit:text-recognition-bundled-common` | 17.0.0 | `text-recognition` | Modelo incluido en la app (sin descarga) **[V]** |
| `com.google.android.gms:play-services-mlkit-text-recognition` (+ `-common` 19.1.0) | 19.0.1 | **`text-recognition:16.0.1` lo arrastra** | Ruta de Google Play services **[V]**. Si se usa en ejecución con el modelo incluido: **[NV]** |
| `com.google.mlkit:common`, `vision-common` | 18.11.0, 17.3.0 | ML Kit | Registro de componentes e inicialización **[V]** |
| `com.google.android.gms:play-services-base`, `-basement`, `-tasks` | 18.5.0, 18.4.0, 18.2.0 | ML Kit | Base de Play services **[V]** |
| `com.google.firebase:firebase-components`, `-encoders`, `-encoders-json` | 16.1.0, 16.1.0, 17.1.0 | ML Kit | Registro y codificación de eventos **[V]** |
| `com.google.android.datatransport:transport-api`, `transport-runtime`, **`transport-backend-cct`** | 2.2.1, 2.2.6, 2.3.3 | **`play-services-mlkit-text-recognition-common` y `mlkit:common`** | Cola y **envío de eventos (CCT/Firelog) a Google** **[V]** |

## 2. Manifiesto fusionado (`processDebugMainManifest`, generado por las tareas de test, sin empaquetar)

| Componente | Atributos | Papel |
|---|---|---|
| `MlKitInitProvider` (proveedor) | no exportado | Inicializa ML Kit al arrancar el proceso, de forma automática |
| `MlKitComponentDiscoveryService` | no exportado, `directBootAware` | Registro de componentes |
| `TransportBackendDiscovery` (servicio) y meta-data `backend:…CctBackendFactory` | no exportado | Descubre el backend de envío CCT |
| `JobInfoSchedulerService` | no exportado, `BIND_JOB_SERVICE` | **Programa los envíos con JobScheduler** |
| `AlarmManagerSchedulerBroadcastReceiver` | no exportado | Programación alternativa con AlarmManager |
| `uses-permission` | solo `com.turnocontrol.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | `INTERNET` y `ACCESS_NETWORK_STATE` **eliminados** **[V]** |

Nota aparte, anterior a esta fase: en `debug` aparece `androidx.activity.ComponentActivity` exportada. Procede de `debugImplementation("androidx.compose.ui:ui-test-manifest")`, que es solo para pruebas, y **no** está en la versión de publicación **[NV: revisar al preparar la release]**.

## 3. Riesgo de telemetría
- **Procesamiento:** Google documenta que las imágenes y el texto reconocido se procesan **en el dispositivo** y no se envían **[V]**.
- **Métricas:** Google documenta que ML Kit **sí envía métricas** de rendimiento y uso (dispositivo, app, latencia, configuración de la API) por HTTPS, y que el desarrollador debe informar a los usuarios **[V]**.
- **Sin forma oficial de desactivarlas:** no hay ningún interruptor documentado **[V: no hay documentación oficial; la comunidad describe solo apaños por reflexión, frágiles y no soportados]**.
- **Hoy:** sin `INTERNET`, el sistema operativo impide cualquier conexión del UID de la app **[V por diseño de Android: el permiso es por UID]**.
- **Al habilitar `INTERNET`:** se espera que `transport-runtime` envíe esos eventos con JobScheduler o AlarmManager, también en modo invitado y sin sincronización. **[NV: frecuencia y destino exactos, a medir]**
- **Distinción de tráfico:**
  - procesamiento de OCR: local;
  - descarga de modelos: no aplica, el modelo está incluido **[V por el artefacto; el comportamiento real está NV]**;
  - diagnóstico y telemetría: datatransport/CCT;
  - otro tráfico de fondo: desconocido hasta medirlo.

## 4. Estrategias evaluadas

### A. OCR en un proceso aislado (`android:isolatedProcess="true"`)
- **Garantía del sistema [V]:** un proceso aislado recibe un UID efímero y el dominio SELinux `isolated_app`, que le niega la red y casi todos los servicios del sistema, **aunque la app tenga `INTERNET`**.
- **Viabilidad con ML Kit [NV]:**
  - En un proceso aislado no se ejecutan los `ContentProvider`: `MlKitInitProvider` no lo inicializaría y habría que llamar a `MlKit.initialize(context)` a mano.
  - El proceso aislado no puede vincularse a Google Play services; la ruta `play-services-mlkit-*` podría fallar.
  - El acceso a archivos está limitado; el modelo incluido se carga de los assets o recursos del APK, lo que en principio es posible.
  - `JobInfoSchedulerService` no podría programarse desde ahí.
  - Es posible que ML Kit **falle** en lugar de degradarse sin problemas.
- **Comunicación entre procesos:** el servicio aislado se vincula por Binder.
  - La imagen no debe pasar como `Bitmap` en un `Parcel` (límite de transacción de ~1 MB). Se propone `SharedMemory` (API 27+) o un descriptor de archivo de solo lectura, ya que el `minSdk` 26 obliga a tener alternativa.
  - El resultado vuelve como texto y geometría serializada (el `OcrDocument` existente), sin imagen.
- **Coste:** proceso nuevo (latencia de arranque, memoria aparte que encaja con el presupuesto `memoryAwarePlan` actual), errores de proceso muerto y reintentos.
- **Encaje:** `ImageOcr` ya separa la decodificación (`memoryAwarePlan`) del reconocimiento, así que solo cambiaría la llamada `TextRecognition.getClient(...).process(...)`.

### B. Controlar los componentes de telemetría
- **Eliminar del manifiesto** (`tools:node="remove"`) `TransportBackendDiscovery`, `JobInfoSchedulerService`, `AlarmManagerSchedulerBroadcastReceiver` y la meta-data del backend CCT: sin backend ni programador, en teoría los eventos no se envían.
  - **[NV]** si ML Kit falla al no encontrar el backend o el servicio.
  - **[NV]** si es compatible con las condiciones de ML Kit (cuestión legal).
- **Excluir `transport-backend-cct` en Gradle:** el mismo efecto a nivel de clases, con riesgo de `ClassNotFoundException` en ejecución **[NV]**.
- **Inicialización:** no hay un control documentado. Los apaños por reflexión se **rechazan** como garantía.

### C. Otro motor de OCR en el dispositivo
Por ejemplo Tesseract o modelos ONNX/TFLite propios: sin telemetría de Google, pero con precisión desconocida en las tablas reales del propietario, más tamaño y rehacer las pruebas de OCR. **No está justificado ahora** y requeriría tu aprobación.

## 5. Recomendación [P]
1. **Mantener `INTERNET` eliminado** hasta que se cumplan los requisitos de §10.
2. Primero, una **prueba de concepto de A** en un emulador, con el paquete `.verification`, imágenes sintéticas y tu autorización: es la única opción con garantía del sistema operativo.
3. Si A funciona: **A + B**. B como defensa en profundidad, solo si las pruebas de §8 demuestran que el OCR no empeora.
4. Si A no funciona: **B**, con pruebas de tráfico exhaustivas, información al usuario según las condiciones de ML Kit y el riesgo residual documentado.
5. **En ningún caso** se afirma «cero telemetría» sin las mediciones de §8.

## 6. Implementación en esta fase
- **No se ha implementado la prueba de concepto de A ni de B.** Ambas cambian el manifiesto o el comportamiento de ejecución del OCR, y no se pueden validar sin un dispositivo o emulador. Añadirlas sin validar podría romper el OCR sin que lo detectara ninguna prueba JVM.
- **Sí se ha implementado** `NetworkPrivacyTripwireTest` (solo pruebas), que lee el manifiesto fusionado a través de Robolectric:
  1. comprueba que se carga el manifiesto fusionado: incluye un permiso que solo existe tras la fusión;
  2. **falla si se pide `INTERNET` o `ACCESS_NETWORK_STATE`**;
  3. fija el inventario de componentes de datatransport y ML Kit: cualquier actualización de dependencias que lo cambie obliga a revisar la privacidad;
  4. comprueba que hoy ningún servicio usa un proceso aislado.

## 7. Evidencia actual
- Las dos pruebas de `NetworkPrivacyTripwireTest` pasan en la JVM.
- **Estas pruebas son salvaguardas, no evidencia de tráfico cero.** Una inspección estática no demuestra el comportamiento en ejecución.

## 8. Pruebas que necesitan Android (futuras, con autorización y en emulador)
Paquete `.verification` e imágenes **sintéticas**. Nunca en el A53 ni en la app real.
- **Montaje:**
  - emulador con `-tcpdump` y captura DNS;
  - mitmproxy con su CA instalada solo en el emulador;
  - `TrafficStats.getUidTxBytes/getUidRxBytes` y `NetworkStatsManager` por UID antes y después de cada caso;
  - StrictMode `detectUntaggedSockets()` en la build de pruebas, con los sockets propios etiquetados.
- **Casos y criterio de aceptación:**

  | Caso | Aceptación |
  |---|---|
  | Arranque como invitado | 0 conexiones del UID de la app |
  | Uso del calendario como invitado (30 min) | 0 conexiones |
  | OCR vertical, horizontal, rotación automática y manual, reintentos | 0 conexiones; ninguna imagen ni texto en el tráfico |
  | Paso a segundo plano y vuelta, y esperar a que se ejecuten los trabajos de JobScheduler (`adb shell cmd jobscheduler run` **solo en el emulador**) | 0 conexiones |
  | Cuenta sin sesión | 0 conexiones |
  | Sesión iniciada sin sincronización | Solo renovación de la sesión hacia el dominio de Supabase |
  | Sesión iniciada con sincronización | Solo el dominio de Supabase; sin destinos de Google en el UID de la app (Google Play services va aparte) |

- **Comparativa:** cada caso con la configuración actual, con A, con B y con A + B, para medir el efecto real.

## 9. Bloqueos pendientes
1. No hay un entorno Android autorizado para la prueba de concepto ni para las mediciones.
2. Sin confirmar si ML Kit funciona en un proceso aislado y si tolera la eliminación de datatransport.
3. Si eliminar la telemetría es compatible con las condiciones de ML Kit (consulta legal o del propietario).

## 10. Requisitos antes de habilitar Internet
1. Prueba de concepto de A (o la decisión documentada de usar solo B) **validada en un emulador**.
2. Todas las mediciones de §8 superadas y archivadas como evidencia.
3. `NetworkPrivacyTripwireTest` actualizado a propósito: permisos aprobados y nuevo inventario de componentes.
4. Network Security Config sin texto en claro.
5. Información a los usuarios sobre las métricas de ML Kit si queda algún envío, y formulario Data safety actualizado.
6. Tu aprobación expresa.

## Fuentes (consultadas el 2026-11-22)
- ML Kit, divulgación de datos en Android: https://developers.google.com/ml-kit/android-data-disclosure
- Condiciones de ML Kit: https://developers.google.com/ml-kit/terms
- Debate de la comunidad sobre desactivar el registro (apaño por reflexión, no oficial): https://kiwix.lema.org/stackoverflow.com_en_all_2023-11/questions/71465067/disable-firebase-logging-for-google-ml-kit-library-in-android
- Procesos aislados y SELinux `isolated_app`: https://chromium.googlesource.com/chromium/src/+/HEAD/docs/security/android-sandbox.md · https://firefox-source-docs.mozilla.org/mobile/android/geckoview/project/isolated-process/overview.html · https://android.googlesource.com/platform/external/sepolicy/+/a39b131/isolated_app.te

---

## 11. Actualización de la fase 4B.5.1 (2026-11-22)

### 11.1 Cambios de código (commit «refactor: prepare isolated OCR execution boundary»)
- **Límite mínimo [V]:** `TextRecognitionEngine.recognize(bitmap): OcrDocument`.
  - Solo lo cruza el bitmap ya decodificado, con su tamaño comprobado y en posición vertical, y el resultado (texto y geometría).
  - La decodificación, EXIF, la rotación manual y automática, el presupuesto de memoria (`memoryAwarePlan`), la interpretación, la revisión y el guardado **no** cambian y se quedan fuera del límite.
- **Producción [V]:** `LocalImageOcr` usa por defecto `MlKitTextRecognitionEngine`, que contiene **exactamente** el código anterior, en el proceso de la app. Conserva el bloqueo de reconocimiento y la espera al trabajo nativo antes de liberar el reconocedor.
  - **Sin cambios** en el manifiesto, las dependencias, los permisos ni el comportamiento.
  - `OcrEngineBoundaryTest` comprueba que producción sigue usando ese motor, qué cruza el límite, que las llamadas van en serie y que los errores se propagan.

### 11.2 Arquitectura de aislamiento propuesta [P] — NO ESTÁ VALIDADA EN ANDROID
- **Servicio:** `IsolatedOcrService` en su propio proceso (`android:process=":ocr"`), con `android:isolatedProcess="true"` y `android:exported="false"`, enlazado con `bindService`. Solo existiría en una variante de pruebas hasta validarlo.
- **Comunicación (Binder):** una única operación `recognize(imagen, ancho, alto)` que devuelve el `OcrDocument` serializado.
  - La imagen viaja como **`SharedMemory`** (API 27+) o, en API 26, como `ParcelFileDescriptor` de un archivo temporal privado de solo lectura que se borra al terminar.
  - **Nunca** se pasa un `Bitmap` en un `Parcel`: el límite es de ~1 MB y la imagen puede ocupar hasta ~48 MB con 12 MPx.
- **Memoria [P]:** el proceso aislado necesita su propia copia de la imagen; la del proceso principal puede liberarse en cuanto se ha copiado. Hay que calcularlo dentro del presupuesto actual (la imagen y una copia) y rechazar con el mensaje de memoria existente si no cabe.
- **Errores:**
  - si el servicio muere (`DeadObjectException`), se informa como un fallo de OCR con reintento manual y la revisión no se toca;
  - tiempo límite: por ejemplo 60 s, configurable; al vencer, se desvincula y se informa;
  - cancelación: desde la interfaz se desvincula el servicio y el proceso termina;
  - limpieza: siempre en `finally`, liberando la memoria compartida y borrando el archivo temporal.
- **Inicialización de ML Kit [NV]:** en un proceso aislado no se ejecutan los `ContentProvider`, así que `MlKitInitProvider` no lo inicializa; habría que llamar a `MlKit.initialize(context)` a mano. También falta comprobar:
  - si el modelo incluido carga desde los recursos del APK en ese dominio SELinux;
  - si la dependencia `play-services-mlkit-*` exige Google Play services. Un proceso aislado no puede vincularse a servicios, así que es posible que **falle**.
- **Datos personales:** solo cruzan la imagen y el texto reconocido, que ya son del usuario y se quedan en el dispositivo. No cruzan identificadores, la cuenta ni el calendario.

### 11.3 Componentes de telemetría: qué se podría quitar
| Componente | ¿Necesario para el OCR? | Estado |
|---|---|---|
| `MlKitInitProvider`, `MlKitComponentDiscoveryService`, registros `firebase-components` | **Probablemente sí**: inicialización y registro del reconocedor **[NV]** | No tocar |
| `TransportBackendDiscovery` y meta-data `CctBackendFactory` | Probablemente no: solo envío de telemetría **[NV]** | Candidato a eliminar, solo tras validarlo en Android |
| `JobInfoSchedulerService`, `AlarmManagerSchedulerBroadcastReceiver` | Probablemente no: programación de envíos **[NV]** | Candidatos, solo tras validarlo |
| `transport-backend-cct` (Gradle) | Probablemente no **[NV]** | Excluirlo puede provocar `ClassNotFoundException`; solo con prueba |

**No se ha eliminado nada.** No se usan apaños por reflexión. Cualquier eliminación queda fuera de producción hasta pasar las pruebas de §8 y §11.4.

### 11.4 Pruebas de Android añadidas al plan (emulador autorizado, imágenes sintéticas)
Además de las de §8:
- OCR con cancelación y con tiempo límite vencido;
- poca memoria (imagen de 12 MPx con la memoria reducida en el emulador);
- terminar el proceso `:ocr` durante el reconocimiento;
- reiniciar la app con un reconocimiento en curso;
- cuenta sin sesión y con sesión, con la sincronización activada y desactivada.

Cada caso se mide por UID (el de la app y el UID efímero del proceso aislado), y en todos se exige:
- **0 conexiones** fuera del dominio de sincronización aprobado;
- ninguna imagen ni texto en el tráfico;
- precisión del OCR igual a la de la ruta actual con las mismas imágenes sintéticas.

### 11.5 Riesgos
- **Compatibilidad:** ML Kit podría no funcionar en un proceso aislado.
- **Memoria:** hay una copia adicional durante la transferencia.
- **Latencia:** arranque del proceso, que se espera de cientos de ms **[NV]**.
- **Ruta de Play services:** comportamiento imprevisible.
- **Condiciones de ML Kit:** si eliminar la telemetría es compatible.

### 11.6 Criterios para habilitar Internet (sin cambios respecto a §10)
Siguen vigentes los de §10. Esta fase solo aporta el límite de código que permitirá probar la alternativa aislada sin tocar el resto del flujo de OCR.

---

## 12. Estado tras la fase 5D (2026-11-22)
- **Prueba de concepto implementada, solo en la build `verification`**, en `app/src/verification/java/com/turnocontrol/ocr/IsolatedOcr.kt`:
  - `IsolatedOcrService` (`android:isolatedProcess="true"`, `:ocr`, no exportado) ejecuta solo la llamada a ML Kit, con `MlKit.initialize` explícito porque los proveedores no se ejecutan en procesos aislados;
  - la imagen viaja como `SharedMemory` de solo lectura y el resultado como texto de `OcrDocumentCodec`.
- **Producción sin cambios:** los manifiestos `debug` y `release` no contienen el servicio, y el OCR sigue en el proceso de la app.
- **NO ESTÁ VALIDADA EN ANDROID.** `IsolatedOcrServiceTest` está compilada pero no se ha ejecutado: requiere instalar la app de pruebas con tu aprobación.
- **Riesgos abiertos [NV]:**
  - la inicialización de ML Kit sin su proveedor;
  - la carga del modelo incluido;
  - la ruta `play-services-mlkit`, porque un proceso aislado no puede vincularse a Google Play services;
  - que un tiempo límite no interrumpe una transacción Binder ya en curso; solo desvincula el servicio.
- **Salvaguardas:** `NetworkPrivacyTripwireTest` vigila además las entradas de red del manifiesto, que no haya procesos extra en producción y que el servicio aislado siga solo en `verification`, sin permisos.
- **Método de medición del tráfico:** ver `docs/QA_DISPOSITIVO_0.3.0.md` §7.

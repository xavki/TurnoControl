# Copias de seguridad locales (formato 1)

TurnoControl puede guardar todos sus datos en un archivo elegido por el usuario y restaurarlos más tarde: **Ajustes → Copia de seguridad**.

- Todo ocurre sin conexión.
- Se usa el selector de documentos de Android (Storage Access Framework), así que no se pide ningún permiso de almacenamiento.
- **El archivo contiene los horarios y la configuración personal.** Guárdalo en un lugar privado y no lo compartas.

## Contenido

| Sección | Origen | Notas |
|---|---|---|
| `profiles` | Room `Profile` | Como máximo uno, con `id = 1` |
| `contracts` | Room `Contract` | Como máximo uno, con `id = 1` |
| `payRules` | Room `PayRule` | Identificadores y tipos únicos; solo tipos conocidos |
| `scheduledDays` | Room `ScheduledDay` | Fechas únicas; tipos `WORK`, `FREE`, `VACATION`, `SICK_LEAVE` |
| `scheduledIntervals` | Room `ScheduledInterval` | Referencian `scheduledDays.id` mediante `dayId` |
| `settings.darkTheme` | DataStore `preferences` | Única preferencia existente |

Se conservan los identificadores originales y, con ellos, las relaciones entre registros. No se incluyen contraseñas, claves, identificadores del dispositivo, fotografías ni resultados de OCR.

## Estructura

```json
{
  "format": "turnocontrol-backup",
  "formatVersion": 1,
  "app": { "versionName": "0.2.0", "versionCode": 2 },
  "schemaVersion": 4,
  "dataFormat": 2,
  "createdAt": "2026-11-21T20:00:00Z",
  "integrity": { "algorithm": "SHA-256", "data": "<hex>" },
  "data": { "profiles": [], "contracts": [], "payRules": [], "scheduledDays": [], "scheduledIntervals": [], "settings": { "darkTheme": false } }
}
```

- `schemaVersion` es la versión de Room.
- `dataFormat` es el formato de los valores guardados: 1 corresponde a 0.1.0 y 2 a 0.2.0.
- `integrity.data` es el SHA-256 de una serialización canónica de `data`: claves ordenadas, sin espacios, solo enteros, cadenas, booleanos y `null`. Detecta archivos dañados o incompletos. **No es una firma**: no protege contra modificaciones deliberadas.
- Los importes se guardan como cadenas decimales.

## Exportación

1. Se leen todas las tablas dentro de una única transacción de Room, para obtener una instantánea coherente.
2. La preferencia de DataStore se lee aparte, porque no comparte transacción con Room.
3. Se escribe el archivo, se vuelve a leer y se valida. El éxito se comunica solo si el contenido leído coincide con los datos.

## Validación antes de restaurar

Se valida el archivo completo antes de escribir nada. Cualquier fallo rechaza la copia entera, nunca se restaura en parte. Se comprueban:

- **Tamaño, texto y estructura:** como máximo 10 MiB, UTF-8 válido y JSON completo.
- **Versiones:** `format`, `formatVersion`, `schemaVersion` y `dataFormat`. Las versiones futuras se rechazan con un mensaje que pide actualizar la aplicación.
- **Integridad:** la suma SHA-256.
- **Campos y tipos:** que existan todos los obligatorios y con el tipo estricto. Por ejemplo, un número entre comillas no se acepta.
- **Valores conocidos:** los tipos de día y de tarifa desconocidos se rechazan; nunca se convierten en otro valor.
- **Relaciones:** que contratos, tarifas y días tengan perfil, y que cada intervalo tenga su día.
- **Duplicados:** de identificadores, de fechas y de posiciones de intervalo.
- **Fechas, zonas horarias, importes y horas:** minutos de 0 a 1439 y desfases de 0 o 1 día.
- **Turnos:** la misma validación que el calendario (`PlannedScheduleValidator`), con nocturnos y solapes entre días.

## Restauración y recuperación

Room y DataStore no pueden confirmarse en una sola transacción, así que **el proceso completo no es atómico**. Se garantiza lo siguiente:

1. Se muestra una vista previa con el contenido de la copia y el de los datos actuales. Se pide marcar «Entiendo que se sustituirán mis datos actuales» y pulsar «Restaurar». Nunca se combinan datos.
2. Se escriben en el almacenamiento privado de la app (`files/backup-recovery/`), cada uno de forma atómica (archivo temporal y renombrado): una **copia automática de los datos actuales** (`pre-restore.json`), la copia de destino y un diario.
3. Se sustituyen todas las tablas de Room en **una única transacción**: o se aplican todas o ninguna. Si falla, los datos originales quedan intactos y se avisa.
4. Se escribe la preferencia de DataStore.
5. Se vuelven a leer Room y DataStore y se comparan con la copia. Solo entonces se borra el diario y se informa del éxito.

Si el proceso se interrumpe, por ejemplo porque el sistema cierra la app o el teléfono se apaga, en el siguiente arranque y antes de mostrar el calendario:

| Estado encontrado | Acción |
|---|---|
| Room ya coincide con la copia | Se completa el paso 4 (escribir DataStore) y se avisa |
| Room coincide con la copia automática previa | La restauración no llegó a modificar nada: se descarta el diario y se avisa |
| No coincide con ninguna | No se toca nada. El usuario elige entre volver a los datos previos o conservar los actuales |

Restaurar no se permite mientras el calendario tiene una escritura en curso o un guardado de resultado incierto. Tras restaurar se recargan la configuración y el calendario, y se descartan los borradores del OCR.

## Limitaciones

- El archivo no está cifrado. Su protección depende de dónde lo guarde el usuario.
- La copia automática `pre-restore.json` conserva solo el último estado previo a una restauración.
- Una copia creada con una versión más nueva de TurnoControl no se puede restaurar en una más antigua.

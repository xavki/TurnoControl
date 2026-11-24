# TurnoControl 0.3.0: decisiones de nómina pendientes

Fecha: 2026-11-24. Rama `feature/0.2.0-redesign`. Estado: **pendiente de decisión del propietario**. No se ha implementado nada de lo que aquí se propone.

## 1. Por qué no se ha implementado

- El diseño aprobado de 0.2.0 (§8b, aprobado el 21/11/2026) aplazó expresamente el historial de tarifas. La estimación aplica las tarifas actuales a todos los periodos y lo indica con «Estimación con las tarifas actuales».
- `docs/ARQUITECTURA_0.3.0.md` describe `WorkEntry`, `WorkBreak`, `EntryCorrection`, `PayslipRecord` y la reconstrucción de `PayRule` con vigencias, pero está marcado como «propuesta, sin implementar». Ninguna decisión registrada lo aprueba (la tabla D1–D9 de `ARQUITECTURA_0.3.0_CUENTAS_Y_SINCRONIZACION.md` no lo cubre).
- Cualquiera de estas piezas exige una migración de Room (5 → 6), `DataFormat.CURRENT = 3`, el formato 2 de copia de seguridad y nuevos agregados de sincronización. No se hacen cambios de esquema sin aprobación.

Room sigue en la versión 5.

## 2. Qué distingue hoy la aplicación

| Concepto | Estado | Dónde |
|---|---|---|
| Horas planificadas | Existe | `ScheduledDay` + `ScheduledInterval`; `ScheduleSummary` (mes natural y año natural) |
| Horas trabajadas reales | **No existe** | Requiere `WorkEntry`/`WorkBreak` (propuesta) |
| Horas extra declaradas | Existe | Marca explícita `isExtra` por intervalo; nunca se deducen |
| Horas extra pagadas | **No existe como dato** | La estimación valora todas las extras declaradas con la tarifa «Hora extra», es decir, las trata implícitamente como pagadas. La tarjeta de estimación ya lo dice |
| Horas extra compensadas con descanso | **No existe** | Sin modelo ni campo |
| Ganancias estimadas | Existe | `EarningsEstimator`: bruto nominal, tarifa base × horas normales + tarifa de hora extra × extras; «No disponible» si falta la tarifa necesaria; `HALF_UP` a céntimos |
| Valores reales de nómina | **No existe** | Requiere `PayslipRecord` (propuesta) |

La aplicación no calcula impuestos, salario neto, primas por hora extra ni compensaciones por vacaciones o baja, y no debe hacerlo.

## 3. Decisiones necesarias

| # | Pregunta | Opciones | Recomendación |
|---|---|---|---|
| N1 | ¿Se aprueba el historial de tarifas (vigencias de `PayRule`)? | (a) No, mantener «tarifas actuales»; (b) Sí, con la reconstrucción de `PayRule` descrita en `ARQUITECTURA_0.3.0.md` §3 | (b) solo si se van a comparar meses pasados con nóminas reales (N3); si no, (a) |
| N2 | ¿Se aprueba el registro de horas trabajadas reales (fichaje manual)? | (a) No; (b) Fichaje manual; (c) Fichaje manual y en vivo | (b) primero; (c) después, sin servicios en segundo plano |
| N3 | ¿Se aprueba introducir nóminas reales (`PayslipRecord`: bruto y neto escritos por el usuario)? | (a) No; (b) Sí, solo para comparar con la estimación, sin interpretar la diferencia | (b) si el propietario lo quiere; depende de N1 para que la comparación tenga sentido |
| N4 | ¿Cómo se distinguen las extras pagadas de las compensadas? | (a) Seguir tratándolas todas como pagadas, como hoy; (b) un estado por intervalo (pagada / compensada / sin decidir) declarado por el usuario; (c) un saldo de compensación aparte | (b): una columna con valor por defecto «sin decidir», sin deducir nada. Afecta a la sincronización (`scheduled_day`) y a la copia de seguridad |
| N5 | ¿Una sola migración 5 → 6 con todo lo aprobado? | Sí / por fases | Una sola, como recomienda el plan, con copia de seguridad previa |

## 4. Si se aprueba, condiciones mínimas

- Migración aditiva, no destructiva, con fixtures sintéticas v1–v5, `PRAGMA integrity_check` y `PRAGMA foreign_key_check`, y conservación de los ids de `PayRule`.
- Copias de seguridad de formato 1 (0.2.0, esquema 4 y 5) restaurables en el esquema 6, con las tablas nuevas vacías.
- Los textos distinguen siempre: planificado, real, extra declarada, extra pagada, extra compensada, estimación y nómina real.
- Ninguna cifra en euros sin la tarifa o el importe que la justifica.

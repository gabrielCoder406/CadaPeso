// Esquema unificado de transacciones.
//
// Mercado Pago y Prometeo (Banco Galicia) responden con formatos muy distintos. Cada
// proveedor tiene su "normalizador" que convierte su respuesta a ESTE formato, y el resto del
// servidor (base de datos, API para el teléfono) solo conoce este formato. Los nombres de los
// campos son los que usa la app (id_transaccion, monto, tipo_movimiento, origen, fecha, categoria…).

export const ORIGENES = Object.freeze(['MERCADO_PAGO', 'GALICIA']);
export const TIPOS = Object.freeze(['INGRESO', 'GASTO']);
/** Mismos nombres que el enum MedioPago de la app. */
export const MEDIOS = Object.freeze(['EFECTIVO', 'DEBITO', 'CREDITO', 'TRANSFERENCIA', 'BILLETERA']);
/** ANULADA = un pago que ya habíamos informado y después se devolvió o canceló. */
export const ESTADOS = Object.freeze(['CONFIRMADA', 'ANULADA']);

const ZONA_ARGENTINA = 'America/Argentina/Buenos_Aires';
const formatoFecha = new Intl.DateTimeFormat('en-CA', {
  timeZone: ZONA_ARGENTINA, year: 'numeric', month: '2-digit', day: '2-digit',
});

/** "2026-10-10T23:30:00.000-04:00" → "2026-10-11" (el día en Argentina). */
export function fechaArgentina(valor) {
  const fecha = valor instanceof Date ? valor : new Date(valor);
  if (Number.isNaN(fecha.getTime())) throw new Error(`Fecha inválida: ${valor}`);
  return formatoFecha.format(fecha);
}

/** Texto prolijo: sin espacios repetidos y con un largo máximo. */
export function limpiarTexto(texto, maximo = 80) {
  return String(texto ?? '').replace(/\s+/g, ' ').trim().slice(0, maximo).trim();
}

/**
 * Crea una transacción con el formato interno único y valida cada campo. Si un proveedor
 * devuelve algo raro, falla acá (con un mensaje claro) y no en el teléfono.
 */
export function transaccion({
  id_transaccion, monto, tipo_movimiento, origen, fecha, descripcion,
  categoria = null, moneda = 'ARS', medio_pago, cuenta = null, estado = 'CONFIRMADA',
  contraparte = null, transferencia_propia = false,
}) {
  if (!id_transaccion || typeof id_transaccion !== 'string') throw new Error('Falta id_transaccion');
  if (!ORIGENES.includes(origen)) throw new Error(`Origen desconocido: ${origen}`);
  if (!id_transaccion.startsWith(`${origen}:`)) throw new Error('id_transaccion tiene que empezar con el origen');
  if (!TIPOS.includes(tipo_movimiento)) throw new Error(`Tipo de movimiento desconocido: ${tipo_movimiento}`);
  if (!MEDIOS.includes(medio_pago)) throw new Error(`Medio de pago desconocido: ${medio_pago}`);
  if (!ESTADOS.includes(estado)) throw new Error(`Estado desconocido: ${estado}`);
  const importe = Math.round(Number(monto) * 100) / 100;
  if (!Number.isFinite(importe) || importe < 0) throw new Error(`Monto inválido: ${monto}`);
  if (!/^\d{4}-\d{2}-\d{2}$/.test(fecha)) throw new Error(`Fecha inválida: ${fecha}`);
  if (!/^[A-Z]{3}$/.test(moneda)) throw new Error(`Moneda inválida: ${moneda}`);
  return {
    id_transaccion,
    monto: importe,
    tipo_movimiento,
    origen,
    fecha,
    categoria: categoria ? limpiarTexto(categoria, 40) : null,
    descripcion: limpiarTexto(descripcion) || (tipo_movimiento === 'INGRESO' ? 'Ingreso' : 'Gasto'),
    moneda,
    medio_pago,
    cuenta: cuenta ? limpiarTexto(cuenta, 60) : null,
    estado,
    contraparte: contraparte ? limpiarTexto(contraparte, 60) : null,
    transferencia_propia: Boolean(transferencia_propia),
  };
}

/**
 * Convierte un monto que puede venir como número o como texto con formato argentino
 * ("1.234,56") o internacional ("1234.56"). Vacío o inválido → 0.
 */
export function leerMonto(valor) {
  if (typeof valor === 'number') return Number.isFinite(valor) ? Math.abs(valor) : 0;
  let t = String(valor ?? '').replace(/[^\d.,-]/g, '');
  if (t === '' || t === '-') return 0;
  const punto = t.lastIndexOf('.');
  const coma = t.lastIndexOf(',');
  if (punto >= 0 && coma >= 0) {
    // El separador que aparece último es el decimal.
    t = coma > punto ? t.replace(/\./g, '').replace(',', '.') : t.replace(/,/g, '');
  } else if (coma >= 0) {
    const partes = t.split(',');
    t = partes.length > 2 || partes.at(-1).length === 3 ? t.replace(/,/g, '') : t.replace(',', '.');
  } else if (punto >= 0) {
    const partes = t.split('.');
    if (partes.length > 2 || partes.at(-1).length === 3) t = t.replace(/\./g, '');
  }
  const n = Math.abs(Number(t));
  return Number.isFinite(n) ? n : 0;
}

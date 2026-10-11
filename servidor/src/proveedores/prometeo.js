// Banco Galicia a través de Prometeo (agregador de Open Banking con cobertura en Argentina).
//
// Galicia no ofrece una API pública para cuentas personales. Prometeo sí está conectado con
// el banco: se inicia sesión con el usuario y la clave del home banking, y su API REST
// devuelve cuentas, saldos y movimientos.
//
// Importante: este servidor NO guarda las claves del banco. El teléfono las manda (cifradas por
// HTTPS) en cada sincronización, se usan solo en memoria para abrir la sesión en Prometeo, y
// la sesión se cierra al terminar.
//
// API (https://banking.prometeoapi.net, sandbox: https://banking.sandbox.prometeoapi.com):
//   cabecera X-API-Key en todo; X-Session-Key con la sesión abierta.
//   POST /login/ (form)  provider, username, password (+ campos extra del banco)
//        → status: logged_in | select_client | interaction_required | wrong_credentials | error
//   GET  /client/, GET /client/{id}/   elegir cliente cuando el usuario tiene varios
//   GET  /account/                     cuentas con saldo
//   GET  /movement/?account&currency&date_start&date_end   (fechas dd/mm/aaaa)
//   GET  /credit-card/, GET /credit-card/{numero}/movements
//   GET  /provider/, GET /provider/{codigo}/    bancos disponibles y campos de login
//   GET  /logout/

import { sha256Hex } from '../cifrado.js';
import { leerMonto, limpiarTexto, transaccion } from '../esquema.js';
import { ErrorProveedor, pedirJson } from './http.js';

export const PROVEEDOR_GALICIA = 'GALICIA';

export class Prometeo {
  /**
   * @param {object} config la sección prometeo de leerConfig()
   * @param {typeof fetch} [fetchFn]
   */
  constructor(config, fetchFn = globalThis.fetch) {
    this.config = config;
    this.fetch = fetchFn;
    this.codigoCache = config.proveedor || null;
  }

  /**
   * Código de Banco Galicia (personas) en Prometeo. Si no se configuró PROMETEO_PROVEEDOR,
   * se busca en la lista de bancos de Argentina.
   */
  async codigoProveedor() {
    if (this.codigoCache) return this.codigoCache;
    const datos = await this.#pedir('GET', '/provider/');
    const candidatos = (datos.providers ?? []).filter(
      (p) => String(p.country).toUpperCase() === 'AR' && /galicia/i.test(`${p.name} ${p.code}`),
    );
    // Preferimos la banca de personas (no "Office"/empresas).
    const elegido = candidatos.find((p) => !/office|empresa|corp/i.test(`${p.name} ${p.code}`)) ?? candidatos[0];
    if (!elegido) {
      throw new ErrorProveedor('Prometeo no tiene a Banco Galicia en la lista de bancos de tu cuenta', { codigo: 'sin_proveedor' });
    }
    this.codigoCache = elegido.code;
    return elegido.code;
  }

  /**
   * Campos extra que pide el banco para iniciar sesión (por ejemplo, tipo y número de
   * documento). La app los muestra en el formulario. No incluye usuario ni clave.
   */
  async campos() {
    const codigo = await this.codigoProveedor();
    const datos = await this.#pedir('GET', `/provider/${encodeURIComponent(codigo)}/`);
    const campos = datos.provider?.auth_fields ?? [];
    return campos
      .filter((c) => !['username', 'password'].includes(c.name) && !c.interactive)
      .map((c) => ({
        nombre: c.name,
        etiqueta: c.label_es || c.label_en || c.name,
        tipo: c.type === 'choice' ? 'opciones' : c.type === 'password' ? 'clave' : 'texto',
        opcional: Boolean(c.optional),
        opciones: (c.choices ?? []).map((o) => ({ valor: String(o.value ?? o.name ?? ''), etiqueta: String(o.label_es ?? o.label ?? o.name ?? o.value ?? '') })),
      }));
  }

  /**
   * Inicia sesión. Si el banco pide un dato más (token, pregunta de seguridad) devuelve
   * { estado: 'interaccion', … } para que el usuario responda desde la app; la respuesta vuelve
   * en [interaccion] junto con la sesión que estaba abierta.
   */
  async iniciarSesion({ usuario, clave, campos = {}, interaccion = null }) {
    const codigo = await this.codigoProveedor();
    const formulario = new URLSearchParams({ provider: codigo, username: usuario, password: clave });
    for (const [k, v] of Object.entries(campos)) if (v != null && v !== '') formulario.set(k, String(v));
    const cabeceras = { 'Content-Type': 'application/x-www-form-urlencoded' };
    if (interaccion?.sesion) {
      cabeceras['X-Session-Key'] = interaccion.sesion;
      formulario.set(interaccion.campo, interaccion.respuesta);
    }
    const datos = await this.#pedir('POST', '/login/', { cabeceras, cuerpo: formulario.toString(), aceptar: [403] });

    switch (datos.status) {
      case 'logged_in':
        return { estado: 'ok', sesion: datos.key || interaccion?.sesion };
      case 'select_client': {
        const sesion = datos.key || interaccion?.sesion;
        const clientes = await this.#pedir('GET', '/client/', { sesion });
        const ids = Object.keys(clientes.clients ?? {});
        const elegido = campos.cliente && ids.includes(String(campos.cliente)) ? String(campos.cliente) : ids[0];
        if (elegido != null) await this.#pedir('GET', `/client/${encodeURIComponent(elegido)}/`, { sesion });
        return { estado: 'ok', sesion };
      }
      case 'interaction_required':
        return { estado: 'interaccion', sesion: datos.key, pregunta: datos.context || 'El banco pide un dato más', campo: datos.field };
      case 'wrong_credentials':
        throw new ErrorProveedor('Usuario o clave del home banking incorrectos', { estado: 403, codigo: 'credenciales' });
      default:
        throw new ErrorProveedor(`Prometeo no pudo iniciar sesión en el banco: ${datos.message || datos.status || 'error'}`);
    }
  }

  async cerrarSesion(sesion) {
    try {
      await this.#pedir('GET', '/logout/', { sesion });
    } catch {
      // La sesión vence sola; no vale la pena fallar por esto.
    }
  }

  /**
   * Descarga cuentas, saldos y movimientos entre [desde] y [hasta] (Date), ya convertidos al
   * esquema unificado. Abre y cierra la sesión.
   */
  async descargar({ usuario, clave, campos = {}, interaccion = null, desde, hasta, avisar = () => {} }) {
    const inicio = await this.iniciarSesion({ usuario, clave, campos, interaccion });
    if (inicio.estado !== 'ok') return inicio;
    const sesion = inicio.sesion;
    try {
      const transacciones = [];
      const saldos = [];
      const cuentas = (await this.#pedir('GET', '/account/', { sesion })).accounts ?? [];
      for (const cuenta of cuentas) {
        const moneda = monedaValida(cuenta.currency);
        const nombre = nombreCuenta(cuenta.name, cuenta.number);
        if (cuenta.balance != null && cuenta.balance !== '') saldos.push({ cuenta: nombre, moneda, monto: leerMontoConSigno(cuenta.balance) });
        const datos = await this.#pedir('GET', `/movement/?${new URLSearchParams({
          account: cuenta.number, currency: moneda, date_start: fechaPrometeo(desde), date_end: fechaPrometeo(hasta),
        })}`, { sesion });
        for (const mov of datos.movements ?? []) {
          const t = normalizarMovimiento(mov, { numero: cuenta.number, nombre, moneda });
          if (t) transacciones.push(t);
        }
      }

      // Tarjetas de crédito: los consumos son gastos con medio CREDITO.
      let tarjetas = [];
      try {
        tarjetas = (await this.#pedir('GET', '/credit-card/', { sesion })).credit_cards ?? [];
      } catch (e) {
        avisar(`Prometeo no devolvió tarjetas: ${e.message}`);
      }
      for (const tarjeta of tarjetas) {
        for (const moneda of ['ARS', 'USD']) {
          try {
            const datos = await this.#pedir('GET', `/credit-card/${encodeURIComponent(tarjeta.number)}/movements?${new URLSearchParams({
              currency: moneda, date_start: fechaPrometeo(desde), date_end: fechaPrometeo(hasta),
            })}`, { sesion });
            for (const mov of datos.movements ?? []) {
              const t = normalizarMovimientoTarjeta(mov, { numero: tarjeta.number, nombre: nombreCuenta(tarjeta.name, tarjeta.number), moneda });
              if (t) transacciones.push(t);
            }
          } catch (e) {
            avisar(`Movimientos de la tarjeta ${ultimos4(tarjeta.number)} en ${moneda}: ${e.message}`);
          }
        }
      }
      return { estado: 'ok', transacciones, saldos, cuentas: cuentas.length, tarjetas: tarjetas.length };
    } finally {
      await this.cerrarSesion(sesion);
    }
  }

  async #pedir(metodo, ruta, { sesion, cabeceras = {}, cuerpo, aceptar = [] } = {}) {
    const { estado, datos } = await pedirJson(this.fetch, `${this.config.url}${ruta}`, {
      metodo,
      cabeceras: { 'X-API-Key': this.config.apiKey, ...(sesion ? { 'X-Session-Key': sesion } : {}), ...cabeceras },
      cuerpo,
      tiempoMs: 90_000, // los bancos pueden tardar bastante en responder a Prometeo
    });
    if (estado === 401) throw new ErrorProveedor('Prometeo rechazó la API key (revisá PROMETEO_API_KEY)', { estado, codigo: 'api_key' });
    if (estado === 503) throw new ErrorProveedor('El banco no está disponible en este momento. Probá más tarde', { estado, codigo: 'no_disponible' });
    if ((estado < 200 || estado >= 300) && !aceptar.includes(estado)) {
      throw new ErrorProveedor(`Prometeo respondió ${estado}: ${datos.message || datos.status || 'error'}`, { estado });
    }
    if (datos.status === 'error' && ruta !== '/login/') {
      throw new ErrorProveedor(`Prometeo: ${datos.message || 'error'}`, { estado, codigo: datos.message === 'Invalid key' ? 'sesion' : 'proveedor' });
    }
    return datos;
  }
}

// ------------------------------------------------------------------ Normalización

/** Date → "dd/mm/aaaa" (el formato que pide Prometeo). */
export function fechaPrometeo(fecha) {
  const d = new Date(fecha);
  return `${String(d.getUTCDate()).padStart(2, '0')}/${String(d.getUTCMonth() + 1).padStart(2, '0')}/${d.getUTCFullYear()}`;
}

/** "05/10/2026" → "2026-10-05" */
function fechaIso(texto) {
  const m = /^(\d{1,2})\/(\d{1,2})\/(\d{2,4})/.exec(String(texto ?? '').trim());
  if (!m) throw new Error(`Fecha de Prometeo inválida: ${texto}`);
  const anio = m[3].length === 2 ? `20${m[3]}` : m[3];
  return `${anio}-${m[2].padStart(2, '0')}-${m[1].padStart(2, '0')}`;
}

const monedaValida = (m) => (/^[A-Z]{3}$/.test(String(m ?? '').toUpperCase()) ? String(m).toUpperCase() : 'ARS');
const ultimos4 = (numero) => String(numero ?? '').replace(/\D/g, '').slice(-4);
const nombreCuenta = (nombre, numero) => limpiarTexto(`${nombre || 'Cuenta'} ${ultimos4(numero) ? `·${ultimos4(numero)}` : ''}`, 60);
const leerMontoConSigno = (v) => (String(v).trim().startsWith('-') ? -leerMonto(v) : leerMonto(v));

/** "COMPRA DEBITO SUPERMERCADO DIA" → "Compra debito supermercado dia" */
function prolijo(texto) {
  const t = limpiarTexto(texto).toLowerCase();
  return t.charAt(0).toUpperCase() + t.slice(1);
}

/**
 * Pistas a partir del texto que pone el banco en el extracto: medio de pago, categoría
 * sugerida (con los nombres de las categorías de la app) y si parece un movimiento entre
 * cuentas propias (extracción de efectivo, pasar plata a tu Mercado Pago, plazo fijo…).
 */
export function analizarDetalleBanco(detalle, tipo) {
  const t = String(detalle ?? '').toUpperCase();
  const pista = { medio: tipo === 'GASTO' ? 'DEBITO' : 'TRANSFERENCIA', categoria: null, propia: false, descripcion: null };
  if (/EXTRACC|RETIRO|CAJERO|\bATM\b/.test(t)) {
    return { ...pista, medio: 'EFECTIVO', propia: true, descripcion: 'Extracción de efectivo' };
  }
  if (/\bTRANSF|\bTRF\b|TRANSFERENCIA|\bDEBIN\b|\bCVU\b|\bCBU\b/.test(t)) pista.medio = 'TRANSFERENCIA';
  if (/MERCADO ?PAGO|MERCADOPAGO/.test(t) && pista.medio === 'TRANSFERENCIA') pista.propia = true;
  if (/PAGO (DE )?(TARJ|TC\b|VISA|MASTER|AMEX)|PAGO RESUMEN/.test(t)) pista.categoria = 'Pago de deudas';
  else if (/PLAZO FIJO|\bP\.?F\.?\b|FIMA|\bFCI\b|FONDO COMUN|RESCATE|SUSCRIPCION FCI/.test(t)) {
    pista.categoria = tipo === 'GASTO' ? 'Ahorro e inversión' : 'Rendimientos';
    pista.propia = tipo === 'INGRESO'; // te vuelve tu propio capital
  } else if (/HABERES|SUELDO|\bHAB\b/.test(t) && tipo === 'INGRESO') pista.categoria = 'Sueldo';
  else if (/INTERES/.test(t) && tipo === 'INGRESO') pista.categoria = 'Rendimientos';
  else if (/IMPUESTO|\bIMP\b|LEY 25413|\bIIBB\b|PERCEPCION|SELLOS|AFIP|ARCA|MONOTRIBUTO/.test(t)) pista.categoria = 'Impuestos';
  else if (/PAGO MIS CUENTAS|\bPMC\b|EDENOR|EDESUR|METROGAS|NATURGY|AYSA|MOVISTAR|CLARO|TELECENTRO/.test(t)) pista.categoria = 'Servicios';
  return pista;
}

/** Un movimiento de caja de ahorro / cuenta corriente → esquema unificado. */
export function normalizarMovimiento(mov, cuenta) {
  const debito = leerMonto(mov.debit);
  const credito = leerMonto(mov.credit);
  if (debito === 0 && credito === 0) return null;
  const tipo = debito > 0 ? 'GASTO' : 'INGRESO';
  const pista = analizarDetalleBanco(mov.detail, tipo);
  const id = mov.id != null && String(mov.id) !== ''
    ? String(mov.id)
    : sha256Hex(`${mov.date}|${mov.detail}|${mov.debit}|${mov.credit}|${mov.reference}`).slice(0, 24);
  return transaccion({
    id_transaccion: `${PROVEEDOR_GALICIA}:${ultimos4(cuenta.numero) || 'cuenta'}:${id}`,
    monto: debito > 0 ? debito : credito,
    tipo_movimiento: tipo,
    origen: PROVEEDOR_GALICIA,
    fecha: fechaIso(mov.date),
    descripcion: pista.descripcion ?? prolijo(mov.detail),
    categoria: pista.categoria,
    moneda: cuenta.moneda,
    medio_pago: pista.medio,
    cuenta: cuenta.nombre,
    transferencia_propia: pista.propia,
  });
}

/**
 * Un movimiento de la tarjeta de crédito. Los débitos son consumos (gasto con CREDITO). Los
 * créditos son, casi siempre, el pago del resumen (que ya aparece como débito en la cuenta):
 * se ignoran, salvo devoluciones y bonificaciones, que son ingresos.
 */
export function normalizarMovimientoTarjeta(mov, tarjeta) {
  const debito = leerMonto(mov.debit);
  const credito = leerMonto(mov.credit);
  const devolucion = /DEVOLUCION|REINTEGRO|BONIFICACION|ANULACION/i.test(String(mov.detail ?? ''));
  if (debito === 0 && !(credito > 0 && devolucion)) return null;
  const tipo = debito > 0 ? 'GASTO' : 'INGRESO';
  const id = mov.id != null && String(mov.id) !== ''
    ? String(mov.id)
    : sha256Hex(`${mov.date}|${mov.detail}|${mov.debit}|${mov.credit}|${mov.reference}`).slice(0, 24);
  return transaccion({
    id_transaccion: `${PROVEEDOR_GALICIA}:TC${ultimos4(tarjeta.numero)}:${tarjeta.moneda}:${id}`,
    monto: debito > 0 ? debito : credito,
    tipo_movimiento: tipo,
    origen: PROVEEDOR_GALICIA,
    fecha: fechaIso(mov.date),
    descripcion: prolijo(mov.detail),
    categoria: tipo === 'INGRESO' ? 'Otros ingresos' : null,
    moneda: tarjeta.moneda,
    medio_pago: 'CREDITO',
    cuenta: tarjeta.nombre,
  });
}

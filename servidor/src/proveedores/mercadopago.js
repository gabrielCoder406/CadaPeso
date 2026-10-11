// Mercado Pago: API oficial.
//
// - Vinculación con OAuth 2.0 (+ PKCE): el usuario autoriza a tu aplicación desde la web de
//   Mercado Pago y nosotros canjeamos el "code" por un access_token (vale 180 días) y un
//   refresh_token para renovarlo.
// - GET /v1/payments/search: historial de pagos (lo que cobraste y lo que pagaste).
// - GET /users/me: datos de la cuenta (id, apodo, email).
// - Webhooks: Mercado Pago avisa cada pago nuevo; verificamos la firma x-signature y después
//   pedimos el pago a la API (nunca confiamos en el contenido de la notificación).

import { timingSafeEqual } from 'node:crypto';
import { hmacSha256Hex } from '../cifrado.js';
import { fechaArgentina, transaccion } from '../esquema.js';
import { ErrorProveedor, pedirJson } from './http.js';

export const PROVEEDOR_MP = 'MERCADO_PAGO';

export class MercadoPago {
  /**
   * @param {object} config la sección mercadoPago de leerConfig()
   * @param {typeof fetch} [fetchFn] reemplazable en los tests
   */
  constructor(config, fetchFn = globalThis.fetch) {
    this.config = config;
    this.fetch = fetchFn;
  }

  /** URL a la que se manda al usuario para que autorice el acceso a su cuenta. */
  urlAutorizacion({ estado, desafio }) {
    const params = new URLSearchParams({
      client_id: this.config.clientId,
      response_type: 'code',
      platform_id: 'mp',
      state: estado,
      redirect_uri: this.config.redirectUri,
    });
    if (desafio) {
      params.set('code_challenge', desafio);
      params.set('code_challenge_method', 'S256');
    }
    return `${this.config.authUrl}/authorization?${params}`;
  }

  /** Canjea el código de autorización por los tokens. */
  async canjearCodigo(codigo, verificador) {
    return this.#pedirToken({
      grant_type: 'authorization_code',
      code: codigo,
      redirect_uri: this.config.redirectUri,
      ...(verificador ? { code_verifier: verificador } : {}),
    });
  }

  /** Renueva el access_token antes de que venza. */
  async renovar(refreshToken) {
    return this.#pedirToken({ grant_type: 'refresh_token', refresh_token: refreshToken });
  }

  async #pedirToken(campos) {
    const { estado, datos } = await pedirJson(this.fetch, `${this.config.apiUrl}/oauth/token`, {
      metodo: 'POST',
      cabeceras: { 'Content-Type': 'application/json' },
      cuerpo: JSON.stringify({ client_id: this.config.clientId, client_secret: this.config.clientSecret, ...campos }),
    });
    if (estado !== 200 || !datos.access_token) {
      const detalle = datos.message || datos.error_description || datos.error || `HTTP ${estado}`;
      throw new ErrorProveedor(`Mercado Pago rechazó la autorización: ${detalle}`, {
        estado, codigo: campos.grant_type === 'refresh_token' ? 'token_invalido' : 'autorizacion',
      });
    }
    return {
      accessToken: datos.access_token,
      refreshToken: datos.refresh_token ?? null,
      cuentaExterna: datos.user_id != null ? String(datos.user_id) : null,
      expira: new Date(Date.now() + Number(datos.expires_in ?? 15_552_000) * 1000).toISOString(),
    };
  }

  /** GET /users/me: id, apodo y email de la cuenta vinculada. */
  async usuario(accessToken) {
    const datos = await this.#get(accessToken, '/users/me');
    return {
      id: String(datos.id),
      alias: datos.nickname || datos.email || [datos.first_name, datos.last_name].filter(Boolean).join(' ') || String(datos.id),
    };
  }

  /** GET /v1/payments/{id} */
  async pago(accessToken, id) {
    return this.#get(accessToken, `/v1/payments/${encodeURIComponent(id)}`);
  }

  /**
   * GET /v1/payments/search, recorriendo todas las páginas.
   * Busca por fecha de última actualización: así también aparecen los pagos viejos que
   * cambiaron (por ejemplo, una devolución).
   * @param {object} filtro por ejemplo { 'collector.id': '123' } o { 'payer.id': '123' }
   */
  async buscarPagos(accessToken, { desde, filtro = {}, maximo = 2000 }) {
    const resultados = [];
    const limite = 100;
    for (let offset = 0; offset < maximo; offset += limite) {
      const params = new URLSearchParams({
        sort: 'date_last_updated',
        criteria: 'desc',
        range: 'date_last_updated',
        begin_date: desde.toISOString(),
        end_date: 'NOW',
        limit: String(limite),
        offset: String(offset),
        ...filtro,
      });
      const datos = await this.#get(accessToken, `/v1/payments/search?${params}`);
      const pagina = Array.isArray(datos.results) ? datos.results : [];
      resultados.push(...pagina);
      const total = Number(datos.paging?.total ?? 0);
      if (pagina.length < limite || offset + limite >= total) break;
    }
    return resultados;
  }

  /**
   * Saldo disponible. Mercado Pago no lo documenta para todas las aplicaciones: si la cuenta
   * no tiene permiso devuelve null y la app calcula el saldo con los movimientos.
   */
  async saldo(accessToken, cuentaExterna) {
    try {
      const datos = await this.#get(accessToken, `/users/${encodeURIComponent(cuentaExterna)}/mercadopago_account/balance`);
      const monto = datos.available_balance ?? datos.total_amount;
      if (monto == null || !Number.isFinite(Number(monto))) return null;
      return { monto: Number(monto), moneda: datos.currency_id || 'ARS' };
    } catch (e) {
      if (e instanceof ErrorProveedor && e.codigo !== 'token_invalido') return null;
      throw e;
    }
  }

  async #get(accessToken, ruta) {
    const { estado, datos } = await pedirJson(this.fetch, `${this.config.apiUrl}${ruta}`, {
      cabeceras: { Authorization: `Bearer ${accessToken}` },
    });
    if (estado === 401) throw new ErrorProveedor('Mercado Pago no acepta el token (¿se revocó el acceso?)', { estado, codigo: 'token_invalido' });
    if (estado < 200 || estado >= 300) {
      throw new ErrorProveedor(`Mercado Pago respondió ${estado}: ${datos.message || datos.error || 'error'}`, { estado });
    }
    return datos;
  }
}

// ------------------------------------------------------------------ Normalización

/** payment_type_id de Mercado Pago → medio de pago de la app. */
const MEDIOS = {
  account_money: 'BILLETERA',
  digital_wallet: 'BILLETERA',
  credit_card: 'CREDITO',
  digital_currency: 'CREDITO', // Mercado Crédito / cuotas sin tarjeta
  debit_card: 'DEBITO',
  prepaid_card: 'DEBITO',
  bank_transfer: 'TRANSFERENCIA',
  ticket: 'EFECTIVO',
  atm: 'EFECTIVO',
};

/** operation_type → descripción por defecto y categoría sugerida. */
const OPERACIONES = {
  regular_payment: { texto: 'Pago con Mercado Pago' },
  payment_addition: { texto: 'Pago con Mercado Pago' },
  pos_payment: { texto: 'Pago en un local' },
  money_transfer: { texto: 'Transferencia' },
  recurring_payment: { texto: 'Débito automático', categoria: 'Suscripciones' },
  cellphone_recharge: { texto: 'Recarga de celular', categoria: 'Servicios' },
  account_fund: { texto: 'Ingreso de dinero a Mercado Pago' },
  investment: { texto: 'Inversión en Mercado Pago', categoria: 'Ahorro e inversión' },
  money_exchange: { texto: 'Cambio de moneda' },
};

const ESTADOS_ANULADOS = new Set(['refunded', 'cancelled', 'charged_back']);

/**
 * Convierte un pago de la API de Mercado Pago al esquema unificado, visto desde la cuenta
 * [cuentaPropia]: si la cuenta cobró es un INGRESO, si pagó es un GASTO.
 * Devuelve null para lo que no es un movimiento real (pendiente, rechazado, de otra cuenta).
 */
export function normalizarPago(pago, cuentaPropia) {
  if (!pago || pago.id == null) return null;
  const propia = String(cuentaPropia);
  const cobrador = String(pago.collector_id ?? pago.collector?.id ?? '');
  const pagador = String(pago.payer?.id ?? '');
  const esCobro = cobrador === propia;
  const esPago = pagador === propia;
  if (!esCobro && !esPago) return null;

  let estado;
  if (pago.status === 'approved') estado = 'CONFIRMADA';
  else if (ESTADOS_ANULADOS.has(pago.status)) estado = 'ANULADA';
  else return null; // pendiente, en proceso o rechazado: todavía no movió plata

  const operacion = OPERACIONES[pago.operation_type] ?? { texto: 'Movimiento de Mercado Pago' };
  // Ingresar plata desde tu banco o pagarte a vos mismo no es ni ingreso ni gasto.
  const transferenciaPropia = pago.operation_type === 'account_fund' || (esCobro && esPago);
  const tipo = esCobro ? 'INGRESO' : 'GASTO';

  // Para un cobro importa lo que realmente entró (descontadas las comisiones); para un pago,
  // lo que realmente salió (con el interés de las cuotas, si lo hubo).
  const detalles = pago.transaction_details ?? {};
  const bruto = Number(pago.transaction_amount ?? 0);
  let monto = tipo === 'INGRESO'
    ? Number(detalles.net_received_amount) || bruto
    : Number(detalles.total_paid_amount) || bruto;
  const devuelto = Number(pago.transaction_amount_refunded ?? 0);
  if (estado === 'CONFIRMADA' && devuelto > 0) {
    monto = Math.max(0, monto - devuelto);
    if (monto === 0) estado = 'ANULADA';
  }

  const nombrePagador = [pago.payer?.first_name, pago.payer?.last_name].filter(Boolean).join(' ');
  const item = pago.additional_info?.items?.[0]?.title;
  const descripcion = pago.description || item || pago.statement_descriptor || operacion.texto;

  return transaccion({
    id_transaccion: `${PROVEEDOR_MP}:${pago.id}`,
    monto,
    tipo_movimiento: tipo,
    origen: PROVEEDOR_MP,
    fecha: fechaArgentina(pago.date_approved || pago.date_created),
    descripcion,
    categoria: operacion.categoria ?? null,
    moneda: pago.currency_id || 'ARS',
    medio_pago: tipo === 'INGRESO'
      ? (pago.operation_type === 'money_transfer' ? 'TRANSFERENCIA' : 'BILLETERA')
      : (MEDIOS[pago.payment_type_id] ?? 'BILLETERA'),
    cuenta: 'Mercado Pago',
    estado,
    contraparte: esCobro ? (nombrePagador || pago.payer?.email || null) : null,
    transferencia_propia: transferenciaPropia,
  });
}

// ------------------------------------------------------------------ Firma de los webhooks

/**
 * Verifica la cabecera x-signature de un webhook de Mercado Pago.
 * Formato: "ts=1704908010,v1=<hmac>". El HMAC-SHA256 (hex) se calcula con la clave secreta
 * del webhook sobre el "manifiesto" "id:<data.id>;request-id:<x-request-id>;ts:<ts>;"
 * (las partes que no vinieron se omiten; data.id alfanumérico va en minúsculas).
 */
export function verificarFirma({ firma, idSolicitud, idDato, secreto, toleranciaMs = 0, ahoraMs = Date.now() }) {
  if (!firma || !secreto) return false;
  const partes = {};
  for (const parte of String(firma).split(',')) {
    const i = parte.indexOf('=');
    if (i > 0) partes[parte.slice(0, i).trim()] = parte.slice(i + 1).trim();
  }
  const { ts, v1 } = partes;
  if (!ts || !v1 || !/^[0-9a-f]{64}$/i.test(v1)) return false;

  let id = idDato == null ? '' : String(idDato);
  if (/^[a-z0-9]+$/i.test(id)) id = id.toLowerCase();
  let manifiesto = '';
  if (id) manifiesto += `id:${id};`;
  if (idSolicitud) manifiesto += `request-id:${idSolicitud};`;
  manifiesto += `ts:${ts};`;

  const esperado = Buffer.from(hmacSha256Hex(secreto, manifiesto), 'hex');
  const recibido = Buffer.from(v1.toLowerCase(), 'hex');
  if (esperado.length !== recibido.length || !timingSafeEqual(esperado, recibido)) return false;

  if (toleranciaMs > 0) {
    const tsMs = Number(ts) < 1e12 ? Number(ts) * 1000 : Number(ts);
    if (!Number.isFinite(tsMs) || Math.abs(ahoraMs - tsMs) > toleranciaMs) return false;
  }
  return true;
}

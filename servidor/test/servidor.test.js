// Prueba de punta a punta: levanta el servidor real con una base en memoria y dos APIs falsas
// (Mercado Pago y Prometeo) en localhost, y recorre lo que hace el teléfono: registrarse,
// vincular Mercado Pago con OAuth + PKCE, recibir un webhook, sincronizar Galicia y bajar los
// movimientos con el cursor.

import assert from 'node:assert/strict';
import http from 'node:http';
import { after, before, describe, test } from 'node:test';
import { BaseDatos } from '../src/bd.js';
import { Cifrador, desafioPkce, hmacSha256Hex } from '../src/cifrado.js';
import { leerConfig } from '../src/config.js';
import { MercadoPago } from '../src/proveedores/mercadopago.js';
import { Prometeo } from '../src/proveedores/prometeo.js';
import { crearServidor } from '../src/servidor.js';
import { Sincronizador } from '../src/sincronizador.js';

const CUENTA_MP = '555';
const SECRETO_WEBHOOK = 'secreto-webhook';

/** Levanta un servidor HTTP con un manejador y devuelve su URL. */
async function levantar(manejador) {
  const s = http.createServer(async (req, res) => {
    const partes = [];
    for await (const p of req) partes.push(p);
    const cuerpo = Buffer.concat(partes).toString('utf8');
    const url = new URL(req.url, 'http://x');
    const [estado, datos] = (await manejador(req, url, cuerpo)) ?? [404, {}];
    res.writeHead(estado, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(datos));
  });
  await new Promise((r) => s.listen(0, '127.0.0.1', r));
  return { url: `http://127.0.0.1:${s.address().port}`, cerrar: () => new Promise((r) => s.close(r)) };
}

/** API falsa de Mercado Pago: guarda lo que le piden para poder verificarlo. */
function mercadoPagoFalso() {
  const estado = {
    pedidosToken: [],
    tokenVigente: 'APP_USR-token-1',
    pagos: [
      { id: 1, status: 'approved', date_created: '2026-10-01T12:00:00.000-03:00', date_approved: '2026-10-01T12:00:00.000-03:00',
        operation_type: 'regular_payment', payment_type_id: 'account_money', currency_id: 'ARS', transaction_amount: 3500,
        description: 'Café Martínez', collector_id: 900, payer: { id: CUENTA_MP }, transaction_details: { total_paid_amount: 3500 } },
      { id: 2, status: 'approved', date_created: '2026-10-02T09:00:00.000-03:00', date_approved: '2026-10-02T09:00:00.000-03:00',
        operation_type: 'money_transfer', payment_type_id: 'account_money', currency_id: 'ARS', transaction_amount: 20000,
        collector_id: Number(CUENTA_MP), payer: { id: '777', first_name: 'Juan' }, transaction_details: { net_received_amount: 20000 } },
      { id: 3, status: 'rejected', date_created: '2026-10-03T09:00:00.000-03:00', operation_type: 'regular_payment',
        payment_type_id: 'credit_card', currency_id: 'ARS', transaction_amount: 99, collector_id: 900, payer: { id: CUENTA_MP } },
    ],
  };
  const manejador = (req, url, cuerpo) => {
    const autorizado = req.headers.authorization === `Bearer ${estado.tokenVigente}`;
    if (req.method === 'POST' && url.pathname === '/oauth/token') {
      const datos = JSON.parse(cuerpo);
      estado.pedidosToken.push(datos);
      if (datos.grant_type === 'authorization_code') {
        if (datos.code !== 'CODIGO-OK' || desafioPkce(datos.code_verifier) !== estado.desafio) return [400, { message: 'invalid_grant' }];
        return [200, { access_token: estado.tokenVigente, refresh_token: 'TG-refresh-1', user_id: Number(CUENTA_MP), expires_in: 15552000 }];
      }
      if (datos.grant_type === 'refresh_token' && datos.refresh_token === 'TG-refresh-1') {
        estado.tokenVigente = 'APP_USR-token-2';
        return [200, { access_token: estado.tokenVigente, refresh_token: 'TG-refresh-2', user_id: Number(CUENTA_MP), expires_in: 15552000 }];
      }
      return [400, { message: 'invalid_grant' }];
    }
    if (!autorizado) return [401, { message: 'invalid access token' }];
    if (url.pathname === '/users/me') return [200, { id: Number(CUENTA_MP), nickname: 'GABRIEL.MP' }];
    if (url.pathname === '/v1/payments/search') {
      const collector = url.searchParams.get('collector.id');
      const payer = url.searchParams.get('payer.id');
      const results = estado.pagos.filter((p) => (collector && String(p.collector_id) === collector) || (payer && String(p.payer.id) === payer));
      return [200, { paging: { total: results.length, limit: 100, offset: 0 }, results }];
    }
    const m = /^\/v1\/payments\/(\d+)$/.exec(url.pathname);
    if (m) {
      const pago = estado.pagos.find((p) => String(p.id) === m[1]);
      return pago ? [200, pago] : [404, { message: 'not found' }];
    }
    if (url.pathname === `/users/${CUENTA_MP}/mercadopago_account/balance`) return [200, { available_balance: 15000.5, currency_id: 'ARS' }];
    return [404, {}];
  };
  return { estado, manejador };
}

function prometeoFalso() {
  const estado = { formularios: [] };
  const manejador = (req, url, cuerpo) => {
    if (req.headers['x-api-key'] !== 'clave-prometeo') return [401, { status: 'error', message: 'Invalid API key' }];
    const sesionOk = req.headers['x-session-key'] === 'SESION';
    switch (`${req.method} ${url.pathname}`) {
      case 'GET /provider/':
        return [200, { status: 'success', providers: [{ code: 'galicia', country: 'AR', name: 'Banco Galicia' }] }];
      case 'GET /provider/galicia/':
        return [200, { status: 'success', provider: { auth_fields: [
          { name: 'username', type: 'text', interactive: false, optional: false, label_es: 'Usuario' },
          { name: 'document_number', type: 'text', interactive: false, optional: false, label_es: 'DNI' },
          { name: 'otp', type: 'text', interactive: true, optional: false, label_es: 'Token' },
        ] } }];
      case 'POST /login/': {
        const f = new URLSearchParams(cuerpo);
        estado.formularios.push(Object.fromEntries(f));
        if (f.get('password') !== 'clave-banco') return [403, { status: 'wrong_credentials', message: 'wrong credentials' }];
        if (!f.get('otp')) return [200, { status: 'interaction_required', key: 'SESION', context: 'Ingresá el código que te llegó por SMS', field: 'otp' }];
        return [200, { status: 'logged_in', key: 'SESION' }];
      }
      case 'GET /account/':
        return sesionOk ? [200, { status: 'success', accounts: [{ id: 'a', name: 'CA $', number: '4001234-5 001-9', branch: '1', currency: 'ARS', balance: 250000.75 }] }] : [200, { status: 'error', message: 'Invalid key' }];
      case 'GET /movement/':
        return [200, { status: 'success', movements: [
          { id: 'g1', date: '03/10/2026', detail: 'COMPRA DEBITO COTO', debit: '45.300,00', credit: '', reference: '1' },
          { id: 'g2', date: '04/10/2026', detail: 'TRANSF. A CVU MERCADOPAGO', debit: 10000, credit: '', reference: '2' },
        ] }];
      case 'GET /credit-card/':
        return [200, { status: 'success', credit_cards: [] }];
      case 'GET /logout/':
        return [200, { status: 'logged_out' }];
      default:
        return [404, { status: 'error', message: 'not found' }];
    }
  };
  return { estado, manejador };
}

describe('servidor de punta a punta', () => {
  let api, mp, prom, base, bd, servidor, sincronizador;
  let token;

  const pedir = async (metodo, ruta, { cuerpo, cabeceras = {}, sinAuth = false } = {}) => {
    const r = await fetch(`${base}${ruta}`, {
      method: metodo,
      redirect: 'manual',
      headers: { 'Content-Type': 'application/json', ...(token && !sinAuth ? { Authorization: `Bearer ${token}` } : {}), ...cabeceras },
      body: cuerpo == null ? undefined : JSON.stringify(cuerpo),
    });
    const texto = await r.text();
    let datos = texto;
    try { datos = JSON.parse(texto); } catch { /* HTML */ }
    return { estado: r.status, datos };
  };

  /** Espera a que algo que corre en segundo plano (webhook, primera sincronización) termine. */
  const esperar = async (condicion) => {
    for (let i = 0; i < 100; i++) {
      if (await condicion()) return;
      await new Promise((r) => setTimeout(r, 20));
    }
    throw new Error('Se agotó la espera');
  };

  before(async () => {
    mp = mercadoPagoFalso();
    prom = prometeoFalso();
    api = { mp: await levantar(mp.manejador), prometeo: await levantar(prom.manejador) };
    const config = leerConfig({
      CLAVE_CIFRADO: Buffer.alloc(32, 3).toString('base64'),
      CLAVE_REGISTRO: 'registro-de-prueba',
      URL_PUBLICA: 'https://cadapeso.ejemplo.com',
      MP_CLIENT_ID: 'app-123',
      MP_CLIENT_SECRET: 'secreto-app',
      MP_WEBHOOK_SECRET: SECRETO_WEBHOOK,
      MP_API_URL: api.mp.url,
      PROMETEO_API_KEY: 'clave-prometeo',
      PROMETEO_URL: api.prometeo.url,
    });
    bd = new BaseDatos(':memory:', new Cifrador(config.claveCifrado));
    const mercadoPago = new MercadoPago(config.mercadoPago);
    const prometeo = new Prometeo(config.prometeo);
    const silencioso = { warn: () => {}, error: () => {}, info: () => {} };
    sincronizador = new Sincronizador({ bd, config, mercadoPago, prometeo, registro: silencioso });
    servidor = crearServidor({ config, bd, sincronizador, mercadoPago, prometeo, registro: silencioso });
    await new Promise((r) => servidor.listen(0, '127.0.0.1', r));
    base = `http://127.0.0.1:${servidor.address().port}`;
  });

  after(async () => {
    await new Promise((r) => servidor.close(r));
    await api.mp.cerrar();
    await api.prometeo.cerrar();
    bd.cerrar();
  });

  test('registro del teléfono', async () => {
    assert.equal((await pedir('GET', '/v1/estado')).estado, 401);
    assert.equal((await pedir('POST', '/v1/dispositivos', { cuerpo: { clave_registro: 'otra' } })).estado, 403);
    const r = await pedir('POST', '/v1/dispositivos', { cuerpo: { clave_registro: 'registro-de-prueba', dispositivo: 'Pixel' } });
    assert.equal(r.estado, 201);
    assert.ok(r.datos.token.length >= 40);
    token = r.datos.token;
    const estado = await pedir('GET', '/v1/estado');
    assert.equal(estado.estado, 200);
    assert.deepEqual(estado.datos.servicios, { mercado_pago: true, galicia: true });
    assert.deepEqual(estado.datos.conexiones, []);
  });

  test('vinculación de Mercado Pago con OAuth + PKCE', async () => {
    const { datos } = await pedir('POST', '/v1/mercadopago/vincular');
    const url = new URL(datos.url);
    assert.equal(url.origin, 'https://auth.mercadopago.com.ar');
    assert.equal(url.searchParams.get('redirect_uri'), 'https://cadapeso.ejemplo.com/v1/mercadopago/retorno');
    mp.estado.desafio = url.searchParams.get('code_challenge');
    const state = url.searchParams.get('state');

    // Un state inventado no sirve.
    const falso = await pedir('GET', `/v1/mercadopago/retorno?code=CODIGO-OK&state=inventado`, { sinAuth: true });
    assert.equal(falso.estado, 400);

    // Mercado Pago redirige al navegador con el código: el servidor lo canjea y muestra el botón para volver.
    const r = await pedir('GET', `/v1/mercadopago/retorno?code=CODIGO-OK&state=${state}`, { sinAuth: true });
    assert.equal(r.estado, 200);
    assert.match(r.datos, /Mercado Pago vinculado/);
    assert.match(r.datos, /cadapeso:\/\/conexion\?proveedor=mercadopago&amp;resultado=ok/);
    const canje = mp.estado.pedidosToken[0];
    assert.equal(canje.client_secret, 'secreto-app');
    assert.equal(canje.redirect_uri, 'https://cadapeso.ejemplo.com/v1/mercadopago/retorno');

    // El state sirve una sola vez.
    assert.equal((await pedir('GET', `/v1/mercadopago/retorno?code=CODIGO-OK&state=${state}`, { sinAuth: true })).estado, 400);

    // Los tokens quedan cifrados en la base (no aparecen en texto plano).
    const fila = bd.db.prepare('SELECT access_token, refresh_token FROM conexiones').get();
    assert.ok(!fila.access_token.includes('APP_USR'));
    assert.ok(!fila.refresh_token.includes('TG-refresh'));

    // La primera sincronización corre sola en segundo plano.
    await esperar(() => bd.pendientesDeEntrega(1) === 2);
    const estado = await pedir('GET', '/v1/estado');
    assert.equal(estado.datos.conexiones[0].alias, 'GABRIEL.MP');
    assert.equal(estado.datos.conexiones[0].estado, 'ACTIVA');
    assert.deepEqual(estado.datos.saldos.map((s) => [s.origen, s.monto]), [['MERCADO_PAGO', 15000.5]]);
  });

  test('el teléfono baja los movimientos con el cursor y el servidor borra lo entregado', async () => {
    const primera = await pedir('GET', '/v1/transacciones?desde=0&limite=1');
    assert.equal(primera.datos.transacciones.length, 1);
    assert.equal(primera.datos.hay_mas, true);
    const segunda = await pedir('GET', `/v1/transacciones?desde=${primera.datos.cursor}&instancia=${primera.datos.instancia}`);
    assert.equal(segunda.datos.hay_mas, false);
    const todas = [...primera.datos.transacciones, ...segunda.datos.transacciones];
    assert.deepEqual(
      todas.map((t) => [t.id_transaccion, t.tipo_movimiento, t.monto, t.origen]).sort(),
      [['MERCADO_PAGO:1', 'GASTO', 3500, 'MERCADO_PAGO'], ['MERCADO_PAGO:2', 'INGRESO', 20000, 'MERCADO_PAGO']],
    );
    // Lo de la primera página ya se borró; al confirmar la segunda, no queda nada.
    assert.equal(bd.pendientesDeEntrega(1), 1);
    const tercera = await pedir('GET', `/v1/transacciones?desde=${segunda.datos.cursor}&instancia=${segunda.datos.instancia}`);
    assert.deepEqual(tercera.datos.transacciones, []);
    assert.equal(bd.pendientesDeEntrega(1), 0);

    // Volver a sincronizar lo mismo no reenvía nada (huellas).
    await pedir('POST', '/v1/sincronizar');
    assert.equal(bd.pendientesDeEntrega(1), 0);
  });

  test('webhook firmado: un pago devuelto vuelve a enviarse como ANULADA', async () => {
    const sinFirma = await pedir('POST', '/v1/webhooks/mercadopago?data.id=1&type=payment', { sinAuth: true, cuerpo: { type: 'payment', user_id: CUENTA_MP, data: { id: '1' } } });
    assert.equal(sinFirma.estado, 401);

    mp.estado.pagos[0].status = 'refunded';
    const ts = String(Math.floor(Date.now() / 1000));
    const firma = `ts=${ts},v1=${hmacSha256Hex(SECRETO_WEBHOOK, `id:1;request-id:req-9;ts:${ts};`)}`;
    const r = await pedir('POST', '/v1/webhooks/mercadopago?data.id=1&type=payment', {
      sinAuth: true,
      cabeceras: { 'x-signature': firma, 'x-request-id': 'req-9' },
      cuerpo: { action: 'payment.updated', type: 'payment', user_id: CUENTA_MP, data: { id: '1' } },
    });
    assert.equal(r.estado, 200);
    await esperar(() => bd.pendientesDeEntrega(1) === 1);
    const { datos } = await pedir('GET', '/v1/transacciones?desde=0');
    assert.equal(datos.transacciones[0].id_transaccion, 'MERCADO_PAGO:1');
    assert.equal(datos.transacciones[0].estado, 'ANULADA');
    await pedir('GET', `/v1/transacciones?desde=${datos.cursor}`);
  });

  test('si Mercado Pago rechaza el token, se renueva con el refresh_token y se reintenta', async () => {
    // El token guardado deja de valer (por ejemplo, venció). Al renovarlo con TG-refresh-1, la
    // API falsa emite APP_USR-token-2 y vuelve a aceptar pedidos.
    mp.estado.tokenVigente = 'un-token-que-nadie-tiene';
    mp.estado.pagos.push({ id: 4, status: 'approved', date_created: '2026-10-05T09:00:00.000-03:00', operation_type: 'regular_payment',
      payment_type_id: 'debit_card', currency_id: 'ARS', transaction_amount: 800, collector_id: 900, payer: { id: CUENTA_MP } });
    const r = await pedir('POST', '/v1/sincronizar');
    assert.deepEqual(r.datos.mercado_pago, { ok: true, nuevas: 1 });
    assert.equal(mp.estado.pedidosToken.at(-1).grant_type, 'refresh_token');
    const conexion = bd.conexion(1, 'MERCADO_PAGO');
    assert.equal(conexion.accessToken, 'APP_USR-token-2');
    assert.equal(conexion.refreshToken, 'TG-refresh-2');
    const { datos } = await pedir('GET', '/v1/transacciones?desde=0');
    assert.deepEqual(datos.transacciones.map((t) => [t.id_transaccion, t.medio_pago]), [['MERCADO_PAGO:4', 'DEBITO']]);
    await pedir('GET', `/v1/transacciones?desde=${datos.cursor}`);

    // Si tampoco se puede renovar, la conexión queda REVOCADA y la app pide volver a vincular.
    mp.estado.tokenVigente = 'otro-token-mas';
    const r2 = await pedir('POST', '/v1/sincronizar');
    assert.equal(r2.datos.mercado_pago.ok, false);
    assert.match(r2.datos.mercado_pago.error, /volvé a vincular/);
    assert.equal((await pedir('GET', '/v1/estado')).datos.conexiones[0].estado, 'REVOCADA');
  });

  test('Galicia vía Prometeo: campos, pedido de token por SMS y movimientos', async () => {
    const campos = await pedir('GET', '/v1/galicia/campos');
    assert.deepEqual(campos.datos.campos.map((c) => c.nombre), ['document_number']);

    const mal = await pedir('POST', '/v1/galicia/sincronizar', { cuerpo: { usuario: 'gabi', clave: 'mal', campos: { document_number: '30111222' } } });
    assert.equal(mal.estado, 422);
    assert.match(mal.datos.error, /incorrectos/);

    const paso1 = await pedir('POST', '/v1/galicia/sincronizar', { cuerpo: { usuario: 'gabi', clave: 'clave-banco', campos: { document_number: '30111222' } } });
    assert.equal(paso1.datos.estado, 'interaccion');
    assert.equal(paso1.datos.pregunta, 'Ingresá el código que te llegó por SMS');

    const paso2 = await pedir('POST', '/v1/galicia/sincronizar', {
      cuerpo: { usuario: 'gabi', clave: 'clave-banco', campos: { document_number: '30111222' }, interaccion: { sesion: paso1.datos.sesion, campo: paso1.datos.campo, respuesta: '654321' } },
    });
    assert.equal(paso2.estado, 200);
    assert.deepEqual({ estado: paso2.datos.estado, nuevas: paso2.datos.nuevas, cuentas: paso2.datos.cuentas }, { estado: 'ok', nuevas: 2, cuentas: 1 });
    assert.equal(prom.estado.formularios.at(-1).document_number, '30111222');

    // La clave del banco no queda en ningún lado de la base.
    const volcado = JSON.stringify(bd.db.prepare("SELECT * FROM sqlite_master").all()) +
      JSON.stringify(['conexiones', 'transacciones', 'huellas', 'saldos', 'usuarios'].map((t) => bd.db.prepare(`SELECT * FROM ${t}`).all()));
    assert.ok(!volcado.includes('clave-banco'));

    const { datos } = await pedir('GET', '/v1/transacciones?desde=0');
    const porId = Object.fromEntries(datos.transacciones.map((t) => [t.id_transaccion, t]));
    assert.equal(porId['GALICIA:0019:g1'].monto, 45300);
    assert.equal(porId['GALICIA:0019:g2'].transferencia_propia, true);
    const estado = await pedir('GET', '/v1/estado');
    assert.ok(estado.datos.saldos.some((s) => s.origen === 'GALICIA' && s.monto === 250000.75));
  });

  test('desvincular y borrar el teléfono borra todo', async () => {
    assert.equal((await pedir('DELETE', '/v1/conexiones/mercado-pago')).estado, 204);
    assert.equal(bd.conexion(1, 'MERCADO_PAGO'), null);
    assert.equal((await pedir('DELETE', '/v1/dispositivos/yo')).estado, 204);
    assert.equal((await pedir('GET', '/v1/estado')).estado, 401);
    for (const tabla of ['conexiones', 'transacciones', 'huellas', 'saldos']) {
      assert.equal(bd.db.prepare(`SELECT COUNT(*) AS n FROM ${tabla}`).get().n, 0, tabla);
    }
  });
});

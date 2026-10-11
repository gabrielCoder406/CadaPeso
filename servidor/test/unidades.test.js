// Tests de las piezas sueltas: cifrado, esquema unificado y normalizadores de cada proveedor.

import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { Cifrador, desafioPkce, hmacSha256Hex, igualesSeguro, parPkce } from '../src/cifrado.js';
import { leerConfig } from '../src/config.js';
import { fechaArgentina, leerMonto, transaccion } from '../src/esquema.js';
import { MercadoPago, normalizarPago, verificarFirma } from '../src/proveedores/mercadopago.js';
import {
  Prometeo, analizarDetalleBanco, fechaPrometeo, normalizarMovimiento, normalizarMovimientoTarjeta,
} from '../src/proveedores/prometeo.js';

const CLAVE = Buffer.alloc(32, 7).toString('base64');

describe('cifrado', () => {
  const c = new Cifrador(CLAVE);

  test('AES-256-GCM: ida y vuelta, con IV distinto cada vez', () => {
    const a = c.cifrar('APP_USR-token-secreto', 'conexion:1:MERCADO_PAGO:access');
    const b = c.cifrar('APP_USR-token-secreto', 'conexion:1:MERCADO_PAGO:access');
    assert.notEqual(a, b);
    assert.ok(!a.includes('token-secreto'));
    assert.equal(c.descifrar(a, 'conexion:1:MERCADO_PAGO:access'), 'APP_USR-token-secreto');
  });

  test('un valor copiado a otra fila (otro contexto) no se puede descifrar', () => {
    const a = c.cifrar('secreto', 'conexion:1:MERCADO_PAGO:access');
    assert.throws(() => c.descifrar(a, 'conexion:2:MERCADO_PAGO:access'));
  });

  test('detecta datos alterados', () => {
    const a = c.cifrar('secreto', 'x');
    const alterado = a.slice(0, -2) + (a.at(-2) === 'A' ? 'B' : 'A') + a.at(-1);
    assert.throws(() => c.descifrar(alterado, 'x'));
  });

  test('rechaza claves que no son de 32 bytes', () => {
    assert.throws(() => new Cifrador(Buffer.alloc(16).toString('base64')));
  });

  test('PKCE: vector de prueba del RFC 7636', () => {
    assert.equal(desafioPkce('dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk'), 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM');
    const { verificador, desafio } = parPkce();
    assert.ok(verificador.length >= 43 && verificador.length <= 128);
    assert.equal(desafio, desafioPkce(verificador));
  });

  test('comparación segura', () => {
    assert.ok(igualesSeguro('clave-de-registro', 'clave-de-registro'));
    assert.ok(!igualesSeguro('clave-de-registro', 'clave-de-registrx'));
    assert.ok(!igualesSeguro(undefined, ''));
  });
});

describe('configuración', () => {
  test('explica todo lo que falta', () => {
    assert.throws(() => leerConfig({}), /CLAVE_CIFRADO[\s\S]*CLAVE_REGISTRO/);
  });

  test('Mercado Pago exige URL pública HTTPS', () => {
    const base = { CLAVE_CIFRADO: CLAVE, CLAVE_REGISTRO: 'una-clave-larga', MP_CLIENT_ID: '1', MP_CLIENT_SECRET: 's' };
    assert.throws(() => leerConfig({ ...base, URL_PUBLICA: 'http://ejemplo.com' }), /https/);
    const c = leerConfig({ ...base, URL_PUBLICA: 'https://ejemplo.com/' });
    assert.equal(c.mercadoPago.redirectUri, 'https://ejemplo.com/v1/mercadopago/retorno');
    assert.equal(c.prometeo, null);
  });
});

describe('esquema unificado', () => {
  test('montos en formato argentino e internacional', () => {
    assert.equal(leerMonto('1.234,56'), 1234.56);
    assert.equal(leerMonto('1,234.56'), 1234.56);
    assert.equal(leerMonto('15.000'), 15000);
    assert.equal(leerMonto('12,5'), 12.5);
    assert.equal(leerMonto('$ 3.500'), 3500);
    assert.equal(leerMonto(''), 0);
    assert.equal(leerMonto(null), 0);
    assert.equal(leerMonto(-250.5), 250.5);
  });

  test('la fecha se toma en hora argentina', () => {
    // 23:30 en Chile (-04:00) del día 10 ya es el 11 en Argentina (-03:00).
    assert.equal(fechaArgentina('2026-10-10T23:30:00.000-04:00'), '2026-10-11');
    assert.equal(fechaArgentina('2026-10-11T02:00:00.000Z'), '2026-10-10');
  });

  test('valida los campos', () => {
    const ok = {
      id_transaccion: 'GALICIA:1', monto: 10.005, tipo_movimiento: 'GASTO', origen: 'GALICIA',
      fecha: '2026-10-01', descripcion: '  Compra   en  el super ', medio_pago: 'DEBITO',
    };
    const t = transaccion(ok);
    assert.equal(t.monto, 10.01);
    assert.equal(t.descripcion, 'Compra en el super');
    assert.equal(t.moneda, 'ARS');
    assert.throws(() => transaccion({ ...ok, origen: 'OTRO' }));
    assert.throws(() => transaccion({ ...ok, id_transaccion: 'MERCADO_PAGO:1' }));
    assert.throws(() => transaccion({ ...ok, medio_pago: 'CHEQUE' }));
    assert.throws(() => transaccion({ ...ok, monto: -1 }));
    assert.throws(() => transaccion({ ...ok, fecha: '01/10/2026' }));
  });
});

describe('Mercado Pago', () => {
  const PROPIA = '111';
  const pago = (extra) => ({
    id: 9001,
    status: 'approved',
    date_created: '2026-10-05T10:00:00.000-03:00',
    date_approved: '2026-10-05T10:00:05.000-03:00',
    operation_type: 'regular_payment',
    payment_type_id: 'account_money',
    currency_id: 'ARS',
    transaction_amount: 1000,
    collector_id: 222,
    payer: { id: PROPIA },
    transaction_details: { net_received_amount: 950, total_paid_amount: 1000 },
    ...extra,
  });

  test('un pago hecho por la cuenta es un GASTO por lo que realmente salió', () => {
    const t = normalizarPago(pago({ description: 'Netflix', payment_type_id: 'credit_card', transaction_details: { total_paid_amount: 1100 } }), PROPIA);
    assert.deepEqual(
      { id: t.id_transaccion, tipo: t.tipo_movimiento, monto: t.monto, medio: t.medio_pago, fecha: t.fecha, desc: t.descripcion, origen: t.origen },
      { id: 'MERCADO_PAGO:9001', tipo: 'GASTO', monto: 1100, medio: 'CREDITO', fecha: '2026-10-05', desc: 'Netflix', origen: 'MERCADO_PAGO' },
    );
  });

  test('un cobro es un INGRESO neto de comisiones, con el nombre de quien pagó', () => {
    const t = normalizarPago(pago({ collector_id: Number(PROPIA), payer: { id: '333', first_name: 'Ana', last_name: 'Gómez' }, operation_type: 'money_transfer' }), PROPIA);
    assert.equal(t.tipo_movimiento, 'INGRESO');
    assert.equal(t.monto, 950);
    assert.equal(t.medio_pago, 'TRANSFERENCIA');
    assert.equal(t.contraparte, 'Ana Gómez');
    assert.equal(t.transferencia_propia, false);
  });

  test('ingresar plata desde tu banco se marca como transferencia propia', () => {
    const t = normalizarPago(pago({ collector_id: PROPIA, payer: { id: PROPIA }, operation_type: 'account_fund' }), PROPIA);
    assert.equal(t.transferencia_propia, true);
    assert.equal(t.descripcion, 'Ingreso de dinero a Mercado Pago');
  });

  test('pendientes, rechazados y pagos de otras cuentas no son movimientos', () => {
    assert.equal(normalizarPago(pago({ status: 'pending' }), PROPIA), null);
    assert.equal(normalizarPago(pago({ status: 'rejected' }), PROPIA), null);
    assert.equal(normalizarPago(pago({ payer: { id: '999' } }), PROPIA), null);
  });

  test('devoluciones: total → ANULADA, parcial → monto descontado', () => {
    assert.equal(normalizarPago(pago({ status: 'refunded' }), PROPIA).estado, 'ANULADA');
    const parcial = normalizarPago(pago({ transaction_amount_refunded: 400 }), PROPIA);
    assert.equal(parcial.estado, 'CONFIRMADA');
    assert.equal(parcial.monto, 600);
    assert.equal(normalizarPago(pago({ transaction_amount_refunded: 1000 }), PROPIA).estado, 'ANULADA');
  });

  test('recargas y débitos automáticos traen una categoría sugerida', () => {
    assert.equal(normalizarPago(pago({ operation_type: 'cellphone_recharge' }), PROPIA).categoria, 'Servicios');
    assert.equal(normalizarPago(pago({ operation_type: 'recurring_payment' }), PROPIA).categoria, 'Suscripciones');
  });

  test('firma de webhooks (x-signature)', () => {
    const secreto = 'secreto-del-webhook';
    const ts = '1760200000';
    const v1 = hmacSha256Hex(secreto, `id:abc123;request-id:req-1;ts:${ts};`);
    const firma = `ts=${ts},v1=${v1}`;
    assert.ok(verificarFirma({ firma, idSolicitud: 'req-1', idDato: 'ABC123', secreto }));
    assert.ok(!verificarFirma({ firma, idSolicitud: 'req-2', idDato: 'ABC123', secreto }));
    assert.ok(!verificarFirma({ firma, idSolicitud: 'req-1', idDato: 'abc124', secreto }));
    assert.ok(!verificarFirma({ firma, idSolicitud: 'req-1', idDato: 'abc123', secreto: 'otro' }));
    assert.ok(!verificarFirma({ firma: 'basura', idSolicitud: 'req-1', idDato: 'abc123', secreto }));
    // Con tolerancia: una firma de hace una hora se rechaza.
    assert.ok(!verificarFirma({ firma, idSolicitud: 'req-1', idDato: 'abc123', secreto, toleranciaMs: 60_000, ahoraMs: 1760203600_000 }));
    assert.ok(verificarFirma({ firma, idSolicitud: 'req-1', idDato: 'abc123', secreto, toleranciaMs: 60_000, ahoraMs: 1760200030_000 }));
  });

  test('URL de autorización con PKCE', () => {
    const mp = new MercadoPago({ clientId: '123', authUrl: 'https://auth.mercadopago.com.ar', redirectUri: 'https://x.com/v1/mercadopago/retorno' });
    const url = new URL(mp.urlAutorizacion({ estado: 'est', desafio: 'des' }));
    assert.equal(url.origin + url.pathname, 'https://auth.mercadopago.com.ar/authorization');
    assert.equal(url.searchParams.get('client_id'), '123');
    assert.equal(url.searchParams.get('response_type'), 'code');
    assert.equal(url.searchParams.get('platform_id'), 'mp');
    assert.equal(url.searchParams.get('state'), 'est');
    assert.equal(url.searchParams.get('code_challenge'), 'des');
    assert.equal(url.searchParams.get('code_challenge_method'), 'S256');
  });
});

describe('Galicia (Prometeo)', () => {
  const cuenta = { numero: '4001234-5 123-4', nombre: 'Caja de ahorro ·1234', moneda: 'ARS' };

  test('débito → GASTO y crédito → INGRESO, con fecha ISO', () => {
    const g = normalizarMovimiento({ id: 'm1', date: '05/10/2026', detail: 'COMPRA DEBITO  SUPERMERCADO DIA', debit: '12.345,60', credit: '' }, cuenta);
    assert.equal(g.id_transaccion, 'GALICIA:1234:m1');
    assert.equal(g.tipo_movimiento, 'GASTO');
    assert.equal(g.monto, 12345.6);
    assert.equal(g.fecha, '2026-10-05');
    assert.equal(g.descripcion, 'Compra debito supermercado dia');
    assert.equal(g.medio_pago, 'DEBITO');
    const i = normalizarMovimiento({ id: 7, date: '01/10/2026', detail: 'ACRED. HABERES', debit: '', credit: 950000 }, cuenta);
    assert.equal(i.tipo_movimiento, 'INGRESO');
    assert.equal(i.categoria, 'Sueldo');
  });

  test('sin id, se arma uno estable con los datos del movimiento', () => {
    const mov = { date: '05/10/2026', detail: 'X', debit: 10, credit: '', reference: 'r' };
    assert.equal(normalizarMovimiento(mov, cuenta).id_transaccion, normalizarMovimiento({ ...mov }, cuenta).id_transaccion);
    assert.equal(normalizarMovimiento({ ...mov, debit: '', credit: '' }, cuenta), null);
  });

  test('pistas del extracto: extracciones, Mercado Pago, impuestos, plazo fijo', () => {
    assert.deepEqual(analizarDetalleBanco('EXTRACCION CAJERO LINK', 'GASTO'), { medio: 'EFECTIVO', categoria: null, propia: true, descripcion: 'Extracción de efectivo' });
    const mp = analizarDetalleBanco('TRANSF. A CVU MERCADOPAGO', 'GASTO');
    assert.equal(mp.medio, 'TRANSFERENCIA');
    assert.equal(mp.propia, true);
    assert.equal(analizarDetalleBanco('IMP LEY 25413 DEB', 'GASTO').categoria, 'Impuestos');
    assert.equal(analizarDetalleBanco('CONSTITUCION PLAZO FIJO', 'GASTO').categoria, 'Ahorro e inversión');
    assert.equal(analizarDetalleBanco('PAGO TARJ VISA', 'GASTO').categoria, 'Pago de deudas');
    assert.equal(analizarDetalleBanco('PRESTAMO PERSONAL', 'GASTO').categoria, null);
  });

  test('tarjeta: consumos son gastos con crédito; el pago del resumen se ignora', () => {
    const tarjeta = { numero: '4546********9876', nombre: 'Visa ·9876', moneda: 'ARS' };
    const consumo = normalizarMovimientoTarjeta({ id: 'c1', date: '03/10/2026', detail: 'MERPAGO*NETFLIX', debit: 8999, credit: '' }, tarjeta);
    assert.equal(consumo.medio_pago, 'CREDITO');
    assert.equal(consumo.id_transaccion, 'GALICIA:TC9876:ARS:c1');
    assert.equal(normalizarMovimientoTarjeta({ id: 'c2', date: '10/10/2026', detail: 'SU PAGO EN PESOS', debit: '', credit: 50000 }, tarjeta), null);
    assert.equal(normalizarMovimientoTarjeta({ id: 'c3', date: '10/10/2026', detail: 'DEVOLUCION COMPRA', debit: '', credit: 1500 }, tarjeta).tipo_movimiento, 'INGRESO');
  });

  test('fechas en el formato de Prometeo', () => {
    assert.equal(fechaPrometeo(new Date('2026-01-09T12:00:00Z')), '09/01/2026');
  });

  /** fetch falso: responde según "MÉTODO /ruta" y guarda los pedidos. */
  function fetchFalso(respuestas) {
    const pedidos = [];
    const fn = async (url, opciones) => {
      const u = new URL(url);
      pedidos.push({ metodo: opciones.method, ruta: u.pathname, query: u.searchParams, cabeceras: opciones.headers, cuerpo: opciones.body });
      const r = respuestas[`${opciones.method} ${u.pathname}`];
      if (!r) return new Response('{}', { status: 404 });
      const [estado, cuerpo] = typeof r === 'function' ? r(u, opciones) : r;
      return new Response(JSON.stringify(cuerpo), { status: estado });
    };
    return { fn, pedidos };
  }

  test('busca solo el código de Galicia (personas) en la lista de bancos', async () => {
    const { fn } = fetchFalso({
      'GET /provider/': [200, { status: 'success', providers: [
        { code: 'galicia_office', country: 'AR', name: 'Galicia Office' },
        { code: 'santander', country: 'AR', name: 'Santander' },
        { code: 'galicia', country: 'AR', name: 'Banco Galicia' },
        { code: 'galicia_uy', country: 'UY', name: 'Galicia Uruguay' },
      ] }],
    });
    assert.equal(await new Prometeo({ apiKey: 'k', url: 'https://p' }, fn).codigoProveedor(), 'galicia');
  });

  test('inicio de sesión: elige cliente, pide interacción y avisa credenciales incorrectas', async () => {
    let respuestaLogin = [200, { status: 'select_client', key: 'S1' }];
    const { fn, pedidos } = fetchFalso({
      'POST /login/': () => respuestaLogin,
      'GET /client/': [200, { status: 'success', clients: { 7: 'Gabriel' } }],
      'GET /client/7/': [200, { status: 'success' }],
    });
    const p = new Prometeo({ apiKey: 'clave-api', url: 'https://p', proveedor: 'galicia' }, fn);
    assert.deepEqual(await p.iniciarSesion({ usuario: 'u', clave: 'c', campos: { document_number: '30111222' } }), { estado: 'ok', sesion: 'S1' });
    const login = pedidos[0];
    assert.equal(login.cabeceras['X-API-Key'], 'clave-api');
    const form = new URLSearchParams(login.cuerpo);
    assert.equal(form.get('provider'), 'galicia');
    assert.equal(form.get('document_number'), '30111222');
    assert.equal(pedidos.at(-1).ruta, '/client/7/');
    assert.equal(pedidos.at(-1).cabeceras['X-Session-Key'], 'S1');

    respuestaLogin = [200, { status: 'interaction_required', key: 'S2', context: 'Ingresá el token', field: 'otp' }];
    const r = await p.iniciarSesion({ usuario: 'u', clave: 'c' });
    assert.deepEqual(r, { estado: 'interaccion', sesion: 'S2', pregunta: 'Ingresá el token', campo: 'otp' });

    respuestaLogin = [200, { status: 'logged_in' }];
    assert.deepEqual(await p.iniciarSesion({ usuario: 'u', clave: 'c', interaccion: { sesion: 'S2', campo: 'otp', respuesta: '123456' } }), { estado: 'ok', sesion: 'S2' });
    assert.equal(new URLSearchParams(pedidos.at(-1).cuerpo).get('otp'), '123456');
    assert.equal(pedidos.at(-1).cabeceras['X-Session-Key'], 'S2');

    respuestaLogin = [403, { status: 'wrong_credentials', message: 'wrong credentials' }];
    await assert.rejects(p.iniciarSesion({ usuario: 'u', clave: 'mal' }), (e) => e.codigo === 'credenciales');
  });
});

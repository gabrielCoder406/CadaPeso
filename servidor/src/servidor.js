// API HTTP del servidor. Sin frameworks: node:http y un enrutador mínimo.
//
// Rutas (las marcadas con 🔒 piden "Authorization: Bearer <token del teléfono>"):
//   GET    /salud                          estado del servidor
//   POST   /v1/dispositivos                registrar un teléfono (con la CLAVE_REGISTRO) → token
//   DELETE /v1/dispositivos/yo          🔒 borrar el teléfono y TODOS sus datos
//   GET    /v1/estado                   🔒 conexiones, saldos y movimientos pendientes
//   POST   /v1/mercadopago/vincular     🔒 URL de autorización OAuth de Mercado Pago
//   GET    /v1/mercadopago/retorno         redirect_uri del OAuth (vuelve a la app)
//   POST   /v1/webhooks/mercadopago        notificaciones de Mercado Pago (firma x-signature)
//   POST   /v1/sincronizar              🔒 traer ya los pagos de Mercado Pago
//   GET    /v1/galicia/campos           🔒 campos que pide el banco para iniciar sesión
//   POST   /v1/galicia/sincronizar      🔒 descargar movimientos de Galicia vía Prometeo
//   GET    /v1/transacciones            🔒 movimientos en el formato unificado (con cursor)
//   DELETE /v1/conexiones/:proveedor    🔒 desvincular Mercado Pago o Galicia

import http from 'node:http';
import { igualesSeguro, parPkce, tokenAleatorio } from './cifrado.js';
import { ErrorProveedor } from './proveedores/http.js';
import { PROVEEDOR_MP, verificarFirma } from './proveedores/mercadopago.js';
import { PROVEEDOR_GALICIA } from './proveedores/prometeo.js';

const LIMITE_CUERPO = 64 * 1024;

export class ErrorHttp extends Error {
  constructor(estado, mensaje, codigo = undefined) {
    super(mensaje);
    this.estado = estado;
    this.codigo = codigo;
  }
}

/** Limitador simple por IP (ventana fija), para que nadie pruebe claves de registro a lo loco. */
class Limitador {
  constructor(maximo, ventanaMs) {
    this.maximo = maximo;
    this.ventanaMs = ventanaMs;
    this.contadores = new Map();
  }

  permitir(clave) {
    const ahora = Date.now();
    const c = this.contadores.get(clave);
    if (!c || c.reinicio < ahora) {
      this.contadores.set(clave, { n: 1, reinicio: ahora + this.ventanaMs });
      if (this.contadores.size > 10_000) this.#limpiar(ahora);
      return true;
    }
    c.n++;
    return c.n <= this.maximo;
  }

  #limpiar(ahora) {
    for (const [k, v] of this.contadores) if (v.reinicio < ahora) this.contadores.delete(k);
  }
}

/**
 * @param {object} deps
 * @param {object} deps.config
 * @param {import('./bd.js').BaseDatos} deps.bd
 * @param {import('./sincronizador.js').Sincronizador} deps.sincronizador
 * @param {import('./proveedores/mercadopago.js').MercadoPago | null} deps.mercadoPago
 * @param {import('./proveedores/prometeo.js').Prometeo | null} deps.prometeo
 */
export function crearServidor({ config, bd, sincronizador, mercadoPago, prometeo, registro = console }) {
  const rutas = [];
  const limiteRegistro = new Limitador(10, 60 * 60_000);
  const limiteGeneral = new Limitador(300, 60_000);

  /** Registra una ruta. ":nombre" en el camino es un parámetro. */
  function ruta(metodo, camino, { auth = false } = {}, manejador) {
    const nombres = [];
    const patron = new RegExp(
      '^' + camino.replace(/:([a-z_]+)/g, (_, n) => { nombres.push(n); return '([^/]+)'; }) + '$',
    );
    rutas.push({ metodo, patron, nombres, auth, manejador });
  }

  const requiereMp = () => {
    if (!mercadoPago) throw new ErrorHttp(503, 'Mercado Pago no está configurado en el servidor (MP_CLIENT_ID / MP_CLIENT_SECRET)');
  };
  const requierePrometeo = () => {
    if (!prometeo) throw new ErrorHttp(503, 'Galicia no está configurado en el servidor (PROMETEO_API_KEY)');
  };

  // ------------------------------------------------------------------ Generales

  ruta('GET', '/salud', {}, () => ({
    ok: true,
    instancia: bd.instancia,
    servicios: { mercado_pago: Boolean(mercadoPago), galicia: Boolean(prometeo) },
  }));

  ruta('POST', '/v1/dispositivos', {}, async ({ req, cuerpo, ip }) => {
    if (!limiteRegistro.permitir(ip)) throw new ErrorHttp(429, 'Demasiados intentos. Probá en una hora');
    if (!igualesSeguro(cuerpo.clave_registro, config.claveRegistro)) throw new ErrorHttp(403, 'La clave de registro no es correcta');
    const token = tokenAleatorio(32);
    bd.crearUsuario(token, String(cuerpo.dispositivo ?? req.headers['user-agent'] ?? '').slice(0, 60));
    return { http: 201, cuerpo: { token, instancia: bd.instancia } };
  });

  ruta('DELETE', '/v1/dispositivos/yo', { auth: true }, ({ usuario }) => {
    bd.borrarUsuario(usuario.id);
    return { http: 204 };
  });

  ruta('GET', '/v1/estado', { auth: true }, ({ usuario }) => ({
    instancia: bd.instancia,
    servicios: { mercado_pago: Boolean(mercadoPago), galicia: Boolean(prometeo) },
    conexiones: bd.conexionesPublicas(usuario.id),
    saldos: bd.saldosDe(usuario.id),
    pendientes: bd.pendientesDeEntrega(usuario.id),
  }));

  // ------------------------------------------------------------------ Mercado Pago

  ruta('POST', '/v1/mercadopago/vincular', { auth: true }, ({ usuario }) => {
    requiereMp();
    const estado = tokenAleatorio(24);
    const pkce = config.mercadoPago.usarPkce ? parPkce() : null;
    bd.guardarEstadoOAuth(estado, usuario.id, pkce?.verificador ?? null);
    return { url: mercadoPago.urlAutorizacion({ estado, desafio: pkce?.desafio }) };
  });

  ruta('GET', '/v1/mercadopago/retorno', {}, async ({ query }) => {
    requiereMp();
    const volver = (resultado, mensaje = '') =>
      `${config.esquemaApp}://conexion?${new URLSearchParams({ proveedor: 'mercadopago', resultado, ...(mensaje ? { mensaje } : {}) })}`;
    if (query.get('error')) {
      const mensaje = 'No autorizaste el acceso a Mercado Pago';
      return { html: pagina('No se vinculó Mercado Pago', mensaje, volver('error', mensaje), false) };
    }
    const estado = bd.tomarEstadoOAuth(query.get('state'));
    if (!estado || !query.get('code')) {
      const mensaje = 'El enlace venció o ya se usó. Volvé a tocar "Vincular" en la app';
      return { http: 400, html: pagina('No se vinculó Mercado Pago', mensaje, volver('error', mensaje), false) };
    }
    try {
      const cuenta = await sincronizador.vincularMercadoPago(estado.usuarioId, query.get('code'), estado.verificador);
      // La primera sincronización (historial) corre en segundo plano.
      sincronizador.sincronizarMercadoPago(estado.usuarioId).catch((e) => registro.warn(`Primera sincronización de Mercado Pago: ${e.message}`));
      return {
        html: pagina('¡Mercado Pago vinculado!', `Cuenta: ${cuenta.alias}. Volvé a CadaPeso para revisar tus movimientos.`, volver('ok'), true),
      };
    } catch (e) {
      registro.warn(`OAuth de Mercado Pago: ${e.message}`);
      const mensaje = e instanceof ErrorProveedor ? e.message : 'No se pudo completar la vinculación';
      return { http: 502, html: pagina('No se vinculó Mercado Pago', mensaje, volver('error', mensaje), false) };
    }
  });

  ruta('POST', '/v1/webhooks/mercadopago', {}, ({ req, cuerpo, query }) => {
    requiereMp();
    const secreto = config.mercadoPago.secretoWebhook;
    if (!secreto) throw new ErrorHttp(503, 'Falta MP_WEBHOOK_SECRET: no se pueden verificar las notificaciones');
    const idDato = query.get('data.id') ?? cuerpo.data?.id ?? query.get('id');
    const valida = verificarFirma({
      firma: req.headers['x-signature'],
      idSolicitud: req.headers['x-request-id'],
      idDato,
      secreto,
      toleranciaMs: config.mercadoPago.toleranciaFirmaMin * 60_000,
    });
    if (!valida) throw new ErrorHttp(401, 'Firma inválida');
    const tipo = cuerpo.type ?? query.get('type') ?? query.get('topic');
    // Se responde enseguida (Mercado Pago reintenta si tardamos) y se procesa después.
    if (tipo === 'payment' && idDato && cuerpo.user_id != null) {
      setImmediate(() => {
        sincronizador.procesarPagoNotificado(String(cuerpo.user_id), String(idDato))
          .catch((e) => registro.warn(`Webhook del pago ${idDato}: ${e.message}`));
      });
    }
    return { recibido: true };
  });

  ruta('POST', '/v1/sincronizar', { auth: true }, async ({ usuario }) => {
    const resultado = { mercado_pago: null };
    if (mercadoPago && bd.conexion(usuario.id, PROVEEDOR_MP)) {
      try {
        const r = await sincronizador.sincronizarMercadoPago(usuario.id);
        resultado.mercado_pago = { ok: true, nuevas: r?.nuevas ?? 0 };
      } catch (e) {
        resultado.mercado_pago = { ok: false, error: e.message };
      }
    }
    return resultado;
  });

  // ------------------------------------------------------------------ Banco Galicia (Prometeo)

  ruta('GET', '/v1/galicia/campos', { auth: true }, async () => {
    requierePrometeo();
    return { campos: await prometeo.campos() };
  });

  ruta('POST', '/v1/galicia/sincronizar', { auth: true }, async ({ usuario, cuerpo }) => {
    requierePrometeo();
    const texto = (v) => (typeof v === 'string' ? v : '');
    const usuarioBanco = texto(cuerpo.usuario).trim();
    const clave = texto(cuerpo.clave);
    if (!usuarioBanco || !clave) throw new ErrorHttp(400, 'Faltan el usuario y la clave del home banking');
    const campos = {};
    for (const [k, v] of Object.entries(cuerpo.campos ?? {})) {
      if (/^[a-z_]{1,40}$/i.test(k) && (typeof v === 'string' || typeof v === 'number')) campos[k] = String(v);
    }
    const i = cuerpo.interaccion;
    const interaccion = i && texto(i.sesion) && texto(i.campo) ? { sesion: texto(i.sesion), campo: texto(i.campo), respuesta: texto(i.respuesta) } : null;
    const r = await sincronizador.descargarGalicia(usuario.id, { usuario: usuarioBanco, clave, campos, interaccion, dias: cuerpo.dias });
    return r.estado === 'interaccion'
      ? { estado: 'interaccion', pregunta: r.pregunta, campo: r.campo, sesion: r.sesion }
      : r;
  });

  // ------------------------------------------------------------------ Transacciones (para el teléfono)

  /**
   * Devuelve lo que hay después del cursor [desde]. Pedir "desde=N" confirma que el teléfono
   * ya guardó todo hasta N: eso se borra del servidor.
   */
  ruta('GET', '/v1/transacciones', { auth: true }, ({ usuario, query }) => {
    const entero = (v, d) => (v != null && v !== '' && Number.isSafeInteger(Number(v)) && Number(v) >= 0 ? Number(v) : d);
    const instanciaTelefono = query.get('instancia');
    // Si la base del servidor es otra (se perdió y se creó de nuevo), el cursor del teléfono no sirve.
    const mismaInstancia = !instanciaTelefono || instanciaTelefono === bd.instancia;
    const desde = mismaInstancia ? entero(query.get('desde'), 0) : 0;
    const limite = Math.min(500, Math.max(1, entero(query.get('limite'), 200)));
    if (mismaInstancia && desde > 0) bd.confirmarEntrega(usuario.id, desde);
    const lista = bd.transaccionesDesde(usuario.id, desde, limite + 1);
    const pagina = lista.slice(0, limite);
    return {
      instancia: bd.instancia,
      cursor: pagina.at(-1)?.secuencia ?? desde,
      hay_mas: lista.length > limite,
      transacciones: pagina.map(({ secuencia, ...t }) => t),
    };
  });

  ruta('DELETE', '/v1/conexiones/:proveedor', { auth: true }, ({ usuario, params }) => {
    const proveedor = params.proveedor.toUpperCase().replace('-', '_');
    if (proveedor === PROVEEDOR_MP) bd.borrarConexion(usuario.id, PROVEEDOR_MP);
    else if (proveedor !== PROVEEDOR_GALICIA) throw new ErrorHttp(404, 'Proveedor desconocido');
    bd.borrarSaldos(usuario.id, proveedor);
    return { http: 204 };
  });

  // ------------------------------------------------------------------ Atención de pedidos

  async function atender(req, res) {
    const url = new URL(req.url, 'http://localhost');
    const ip = (config.confiarEnProxy && String(req.headers['x-forwarded-for'] ?? '').split(',')[0].trim()) || req.socket.remoteAddress || '';
    if (!limiteGeneral.permitir(ip)) throw new ErrorHttp(429, 'Demasiados pedidos');

    const candidatas = rutas.filter((r) => r.patron.test(url.pathname));
    if (candidatas.length === 0) throw new ErrorHttp(404, 'No existe');
    const elegida = candidatas.find((r) => r.metodo === req.method);
    if (!elegida) throw new ErrorHttp(405, 'Método no permitido');

    const valores = elegida.patron.exec(url.pathname).slice(1);
    const params = Object.fromEntries(elegida.nombres.map((n, i) => [n, decodeURIComponent(valores[i])]));

    let usuario = null;
    if (elegida.auth) {
      const [tipo, token] = String(req.headers.authorization ?? '').split(' ');
      usuario = tipo === 'Bearer' && token ? bd.usuarioPorToken(token) : null;
      if (!usuario) throw new ErrorHttp(401, 'Teléfono no registrado o token inválido', 'no_autorizado');
    }
    const cuerpo = ['POST', 'PUT', 'PATCH'].includes(req.method) ? await leerJson(req) : {};
    return elegida.manejador({ req, query: url.searchParams, params, cuerpo, usuario, ip });
  }

  return http.createServer(async (req, res) => {
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    try {
      const r = (await atender(req, res)) ?? {};
      // Los manejadores devuelven el JSON a enviar, o { http, cuerpo } / { http, html } para otro código.
      if (r.html != null) return enviar(res, r.http ?? 200, 'text/html; charset=utf-8', r.html);
      if (r.http === 204) return enviar(res, 204);
      if (r.http != null) return enviar(res, r.http, 'application/json; charset=utf-8', JSON.stringify(r.cuerpo ?? {}));
      return enviar(res, 200, 'application/json; charset=utf-8', JSON.stringify(r));
    } catch (e) {
      let estado = 500;
      let cuerpo = { error: 'Error interno del servidor' };
      if (e instanceof ErrorHttp) {
        estado = e.estado;
        cuerpo = { error: e.message, ...(e.codigo ? { codigo: e.codigo } : {}) };
      } else if (e instanceof ErrorProveedor) {
        // Error del banco o de Mercado Pago: no es culpa del teléfono ni del servidor.
        estado = e.codigo === 'credenciales' ? 422 : 502;
        cuerpo = { error: e.message, codigo: e.codigo };
      } else {
        registro.error(e);
      }
      return enviar(res, estado, 'application/json; charset=utf-8', JSON.stringify(cuerpo));
    }
  });
}

function enviar(res, estado, tipo, cuerpo) {
  if (tipo) res.setHeader('Content-Type', tipo);
  res.writeHead(estado);
  res.end(cuerpo);
}

async function leerJson(req) {
  const partes = [];
  let largo = 0;
  for await (const parte of req) {
    largo += parte.length;
    if (largo > LIMITE_CUERPO) throw new ErrorHttp(413, 'El cuerpo es demasiado grande');
    partes.push(parte);
  }
  const texto = Buffer.concat(partes).toString('utf8').trim();
  if (!texto) return {};
  try {
    const valor = JSON.parse(texto);
    return valor && typeof valor === 'object' && !Array.isArray(valor) ? valor : {};
  } catch {
    throw new ErrorHttp(400, 'El cuerpo no es JSON válido');
  }
}

const escapar = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);

/** Página que ve el usuario en el navegador al volver de Mercado Pago, con el botón para volver a la app. */
function pagina(titulo, mensaje, enlaceApp, ok) {
  return `<!doctype html>
<html lang="es"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>CadaPeso</title>
<style>
  body{font-family:system-ui,sans-serif;margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#f4f7f5;color:#1b2b22}
  main{max-width:420px;margin:24px;padding:28px;border-radius:16px;background:#fff;box-shadow:0 2px 12px #0001;text-align:center}
  h1{font-size:1.4rem;margin:.2em 0 .6em} p{line-height:1.5}
  a{display:inline-block;margin-top:12px;padding:12px 22px;border-radius:24px;background:#2e7d32;color:#fff;text-decoration:none;font-weight:600}
  @media (prefers-color-scheme: dark){body{background:#101512;color:#e3ebe5}main{background:#1a211c}}
</style></head>
<body><main>
  <div style="font-size:2.4rem">${ok ? '✅' : '⚠️'}</div>
  <h1>${escapar(titulo)}</h1>
  <p>${escapar(mensaje)}</p>
  <a href="${escapar(enlaceApp)}">Volver a CadaPeso</a>
</main></body></html>`;
}

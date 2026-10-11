// Sincronización: trae los movimientos de cada proveedor, los convierte al esquema unificado
// y los deja en la base para que el teléfono los venga a buscar.

import { ErrorProveedor } from './proveedores/http.js';
import { PROVEEDOR_MP, normalizarPago } from './proveedores/mercadopago.js';
import { PROVEEDOR_GALICIA } from './proveedores/prometeo.js';

const DIA = 86_400_000;
/** Se renueva el access_token de Mercado Pago cuando le quedan menos de estos días. */
const RENOVAR_ANTES_DIAS = 7;
/** Cada sincronización vuelve a mirar unos días hacia atrás: así no se pierde nada que llegó tarde. */
const SOLAPAMIENTO_DIAS = 3;

export class Sincronizador {
  /**
   * @param {object} deps
   * @param {import('./bd.js').BaseDatos} deps.bd
   * @param {object} deps.config resultado de leerConfig()
   * @param {import('./proveedores/mercadopago.js').MercadoPago | null} deps.mercadoPago
   * @param {import('./proveedores/prometeo.js').Prometeo | null} deps.prometeo
   */
  constructor({ bd, config, mercadoPago = null, prometeo = null, registro = console }) {
    this.bd = bd;
    this.config = config;
    this.mp = mercadoPago;
    this.prometeo = prometeo;
    this.registro = registro;
    this.enCurso = new Map();
  }

  /** Si ya hay una sincronización igual corriendo, se espera esa en lugar de lanzar otra. */
  #unaVez(clave, fn) {
    const actual = this.enCurso.get(clave);
    if (actual) return actual;
    const promesa = (async () => {
      try {
        return await fn();
      } finally {
        this.enCurso.delete(clave);
      }
    })();
    this.enCurso.set(clave, promesa);
    return promesa;
  }

  // ------------------------------------------------------------------ Mercado Pago

  /** Fin del OAuth: guarda la conexión (tokens cifrados) y arranca la primera sincronización. */
  async vincularMercadoPago(usuarioId, codigo, verificador) {
    const tokens = await this.mp.canjearCodigo(codigo, verificador);
    const usuario = await this.mp.usuario(tokens.accessToken);
    this.bd.guardarConexion({
      usuarioId,
      proveedor: PROVEEDOR_MP,
      cuentaExterna: tokens.cuentaExterna ?? usuario.id,
      alias: usuario.alias,
      accessToken: tokens.accessToken,
      refreshToken: tokens.refreshToken,
      expira: tokens.expira,
    });
    return usuario;
  }

  async #renovar(conexion) {
    try {
      const nuevos = await this.mp.renovar(conexion.refreshToken);
      const refreshToken = nuevos.refreshToken ?? conexion.refreshToken;
      this.bd.actualizarTokens(conexion.usuarioId, PROVEEDOR_MP, { ...nuevos, refreshToken });
      conexion.accessToken = nuevos.accessToken;
      conexion.refreshToken = refreshToken;
      conexion.expira = nuevos.expira;
    } catch (e) {
      // Si no se puede renovar, el usuario tiene que volver a autorizar.
      throw new ErrorProveedor('Mercado Pago revocó el acceso: volvé a vincular la cuenta', { estado: e.estado, codigo: 'token_invalido' });
    }
  }

  /**
   * Ejecuta fn(token) con un access_token vigente: lo renueva si está por vencer y, si
   * Mercado Pago lo rechaza, lo renueva una vez y reintenta.
   */
  async #conToken(conexion, fn) {
    const venceEn = conexion.expira ? Date.parse(conexion.expira) - Date.now() : Infinity;
    if (venceEn < RENOVAR_ANTES_DIAS * DIA && conexion.refreshToken) await this.#renovar(conexion);
    try {
      return await fn(conexion.accessToken);
    } catch (e) {
      if (e.codigo !== 'token_invalido' || !conexion.refreshToken) throw e;
      await this.#renovar(conexion);
      return fn(conexion.accessToken);
    }
  }

  #normalizar(pagos, cuentaPropia) {
    const vistos = new Set();
    const lista = [];
    for (const pago of pagos) {
      if (vistos.has(String(pago.id))) continue;
      vistos.add(String(pago.id));
      try {
        const t = normalizarPago(pago, cuentaPropia);
        if (t) lista.push(t);
      } catch (e) {
        this.registro.warn(`Pago ${pago.id} de Mercado Pago ignorado: ${e.message}`);
      }
    }
    return lista;
  }

  /** Trae los pagos nuevos o modificados de la cuenta de Mercado Pago del usuario. */
  sincronizarMercadoPago(usuarioId) {
    return this.#unaVez(`${usuarioId}:${PROVEEDOR_MP}`, async () => {
      const conexion = this.bd.conexion(usuarioId, PROVEEDOR_MP);
      if (!conexion || !this.mp) return null;
      try {
        const desde = conexion.ultimaSincronizacion
          ? new Date(Date.parse(conexion.ultimaSincronizacion) - SOLAPAMIENTO_DIAS * DIA)
          : new Date(Date.now() - this.config.diasHistorialInicial * DIA);
        const { pagos, saldo } = await this.#conToken(conexion, async (token) => {
          // Lo que cobraste (sos el "collector") y lo que pagaste (sos el "payer").
          const cobrados = await this.mp.buscarPagos(token, { desde, filtro: { 'collector.id': conexion.cuentaExterna } });
          let pagados = [];
          try {
            pagados = await this.mp.buscarPagos(token, { desde, filtro: { 'payer.id': conexion.cuentaExterna } });
          } catch (e) {
            if (e.codigo === 'token_invalido') throw e;
            this.registro.warn(`Mercado Pago no permitió buscar los pagos hechos por la cuenta: ${e.message}`);
          }
          return { pagos: [...cobrados, ...pagados], saldo: await this.mp.saldo(token, conexion.cuentaExterna) };
        });
        const nuevas = this.bd.guardarTransacciones(usuarioId, this.#normalizar(pagos, conexion.cuentaExterna));
        if (saldo) this.bd.guardarSaldo(usuarioId, { origen: PROVEEDOR_MP, cuenta: 'Mercado Pago', ...saldo });
        this.bd.marcarConexion(usuarioId, PROVEEDOR_MP, { estado: 'ACTIVA', sincronizada: true });
        return { nuevas, revisados: pagos.length, saldo };
      } catch (e) {
        const revocada = e.codigo === 'token_invalido';
        this.bd.marcarConexion(usuarioId, PROVEEDOR_MP, { estado: revocada ? 'REVOCADA' : 'ERROR', error: e.message });
        throw e;
      }
    });
  }

  /**
   * Webhook de un pago: se busca la conexión por el id de cuenta de Mercado Pago (user_id) y se
   * pide el pago a la API con el token de esa cuenta.
   */
  async procesarPagoNotificado(cuentaExterna, idPago) {
    const conexion = this.bd.conexionPorCuenta(PROVEEDOR_MP, cuentaExterna);
    if (!conexion || conexion.estado === 'REVOCADA' || !this.mp) return false;
    const pago = await this.#conToken(conexion, (token) => this.mp.pago(token, idPago));
    const lista = this.#normalizar([pago], conexion.cuentaExterna);
    return this.bd.guardarTransacciones(conexion.usuarioId, lista) > 0;
  }

  // ------------------------------------------------------------------ Banco Galicia (Prometeo)

  /**
   * Descarga los movimientos de Galicia con las credenciales que mandó el teléfono (no se
   * guardan). Devuelve { estado: 'interaccion', … } si el banco pide un dato más.
   */
  descargarGalicia(usuarioId, { usuario, clave, campos = {}, interaccion = null, dias = 30 }) {
    return this.#unaVez(`${usuarioId}:${PROVEEDOR_GALICIA}`, async () => {
      const diasValidos = Math.min(365, Math.max(1, Math.round(Number(dias) || 30)));
      const resultado = await this.prometeo.descargar({
        usuario, clave, campos, interaccion,
        desde: new Date(Date.now() - diasValidos * DIA),
        hasta: new Date(),
        avisar: (m) => this.registro.warn(`Galicia: ${m}`),
      });
      if (resultado.estado !== 'ok') return resultado;
      const nuevas = this.bd.guardarTransacciones(usuarioId, resultado.transacciones);
      this.bd.borrarSaldos(usuarioId, PROVEEDOR_GALICIA);
      for (const s of resultado.saldos) this.bd.guardarSaldo(usuarioId, { origen: PROVEEDOR_GALICIA, ...s });
      return {
        estado: 'ok', nuevas, movimientos: resultado.transacciones.length,
        cuentas: resultado.cuentas, tarjetas: resultado.tarjetas,
      };
    });
  }

  // ------------------------------------------------------------------ Tarea periódica

  /** Sincroniza todas las cuentas de Mercado Pago vinculadas y limpia lo vencido. */
  async sincronizarTodo() {
    const resumen = { cuentas: 0, nuevas: 0, errores: 0 };
    if (this.mp) {
      for (const conexion of this.bd.conexionesParaSincronizar(PROVEEDOR_MP)) {
        resumen.cuentas++;
        try {
          resumen.nuevas += (await this.sincronizarMercadoPago(conexion.usuarioId))?.nuevas ?? 0;
        } catch (e) {
          resumen.errores++;
          this.registro.warn(`Sincronización de Mercado Pago (usuario ${conexion.usuarioId}): ${e.message}`);
        }
      }
    }
    const purgado = this.bd.purgar(this.config.retencionDias);
    return { ...resumen, purgado };
  }
}

// Base de datos del servidor (SQLite con el módulo node:sqlite que trae Node 22).
//
// Qué se guarda y cómo:
// - usuarios: un "usuario" es un teléfono registrado. Del token de acceso solo se guarda su
//   hash SHA-256 (si alguien roba la base, no puede usar los tokens).
// - conexiones: la cuenta de Mercado Pago vinculada. access_token y refresh_token van
//   cifrados con AES-256-GCM.
// - transacciones: movimientos ya convertidos al formato unificado que esperan que el
//   teléfono los venga a buscar. Van cifrados y se BORRAN apenas el teléfono confirma que los
//   guardó (o después de RETENCION_DIAS si nunca los busca).
// - huellas: id + hash de cada movimiento ya visto, para no reenviar lo que no cambió.
// - Las claves del home banking de Galicia NUNCA se guardan acá: viajan en cada pedido desde el
//   teléfono, se usan en memoria para hablar con Prometeo y se descartan.

import fs from 'node:fs';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { sha256Hex, tokenAleatorio } from './cifrado.js';

const ESQUEMA = `
CREATE TABLE IF NOT EXISTS meta (
  clave TEXT PRIMARY KEY,
  valor TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS usuarios (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  token_hash TEXT NOT NULL UNIQUE,
  dispositivo TEXT NOT NULL DEFAULT '',
  creado TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS conexiones (
  usuario_id INTEGER NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
  proveedor TEXT NOT NULL,
  cuenta_externa TEXT,
  alias TEXT,
  access_token TEXT,
  refresh_token TEXT,
  expira TEXT,
  estado TEXT NOT NULL DEFAULT 'ACTIVA',
  ultimo_error TEXT,
  ultima_sincronizacion TEXT,
  creado TEXT NOT NULL,
  PRIMARY KEY (usuario_id, proveedor)
);
CREATE INDEX IF NOT EXISTS conexiones_cuenta ON conexiones (proveedor, cuenta_externa);
CREATE TABLE IF NOT EXISTS estados_oauth (
  estado TEXT PRIMARY KEY,
  usuario_id INTEGER NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
  verificador TEXT,
  expira TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS transacciones (
  secuencia INTEGER PRIMARY KEY,
  usuario_id INTEGER NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
  id_transaccion TEXT NOT NULL,
  datos TEXT NOT NULL,
  recibido TEXT NOT NULL,
  UNIQUE (usuario_id, id_transaccion)
);
CREATE INDEX IF NOT EXISTS transacciones_cursor ON transacciones (usuario_id, secuencia);
CREATE TABLE IF NOT EXISTS huellas (
  usuario_id INTEGER NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
  id_transaccion TEXT NOT NULL,
  huella TEXT NOT NULL,
  visto TEXT NOT NULL,
  PRIMARY KEY (usuario_id, id_transaccion)
);
CREATE TABLE IF NOT EXISTS saldos (
  usuario_id INTEGER NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
  origen TEXT NOT NULL,
  cuenta TEXT NOT NULL,
  moneda TEXT NOT NULL,
  monto REAL NOT NULL,
  actualizado TEXT NOT NULL,
  PRIMARY KEY (usuario_id, origen, cuenta, moneda)
);
`;

const ahoraIso = () => new Date().toISOString();

export class BaseDatos {
  /**
   * @param {string} ruta archivo SQLite o ":memory:"
   * @param {import('./cifrado.js').Cifrador} cifrador
   */
  constructor(ruta, cifrador) {
    if (ruta !== ':memory:') fs.mkdirSync(path.dirname(path.resolve(ruta)), { recursive: true });
    this.db = new DatabaseSync(ruta);
    this.cifrador = cifrador;
    this.db.exec('PRAGMA journal_mode = WAL; PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 5000;');
    this.db.exec(ESQUEMA);
    this.db.prepare("INSERT OR IGNORE INTO meta (clave, valor) VALUES ('instancia', ?)").run(tokenAleatorio(9));
    this.db.prepare("INSERT OR IGNORE INTO meta (clave, valor) VALUES ('secuencia', '0')").run();
    /**
     * Identifica ESTA base. Si la base se pierde y se crea otra, la secuencia vuelve a empezar:
     * el teléfono ve que cambió la instancia y reinicia su cursor (si no, nunca recibiría nada).
     */
    this.instancia = this.db.prepare("SELECT valor FROM meta WHERE clave = 'instancia'").get().valor;
  }

  cerrar() {
    this.db.close();
  }

  /** Ejecuta fn dentro de una transacción de SQLite (todo o nada). */
  enTransaccion(fn) {
    this.db.exec('BEGIN IMMEDIATE');
    try {
      const resultado = fn();
      this.db.exec('COMMIT');
      return resultado;
    } catch (e) {
      this.db.exec('ROLLBACK');
      throw e;
    }
  }

  // ------------------------------------------------------------------ Usuarios (teléfonos)

  crearUsuario(token, dispositivo = '') {
    const r = this.db
      .prepare('INSERT INTO usuarios (token_hash, dispositivo, creado) VALUES (?, ?, ?)')
      .run(sha256Hex(token), String(dispositivo).slice(0, 60), ahoraIso());
    return Number(r.lastInsertRowid);
  }

  usuarioPorToken(token) {
    return this.db.prepare('SELECT id, dispositivo, creado FROM usuarios WHERE token_hash = ?').get(sha256Hex(token)) ?? null;
  }

  /** Borra el usuario y, en cascada, sus conexiones, tokens, movimientos y saldos. */
  borrarUsuario(id) {
    this.db.prepare('DELETE FROM usuarios WHERE id = ?').run(id);
  }

  // ------------------------------------------------------------------ Estados OAuth (state + PKCE)

  guardarEstadoOAuth(estado, usuarioId, verificador, minutos = 15) {
    const expira = new Date(Date.now() + minutos * 60_000).toISOString();
    this.db
      .prepare('INSERT INTO estados_oauth (estado, usuario_id, verificador, expira) VALUES (?, ?, ?, ?)')
      .run(estado, usuarioId, this.cifrador.cifrarOpcional(verificador, `oauth:${estado}`), expira);
  }

  /** Devuelve y borra el estado (un "state" sirve una sola vez). null si no existe o venció. */
  tomarEstadoOAuth(estado) {
    const fila = this.db.prepare('SELECT * FROM estados_oauth WHERE estado = ?').get(String(estado ?? ''));
    if (!fila) return null;
    this.db.prepare('DELETE FROM estados_oauth WHERE estado = ?').run(fila.estado);
    if (fila.expira < ahoraIso()) return null;
    return {
      usuarioId: fila.usuario_id,
      verificador: this.cifrador.descifrarOpcional(fila.verificador, `oauth:${fila.estado}`),
    };
  }

  // ------------------------------------------------------------------ Conexiones (Mercado Pago)

  /** Crea o reemplaza la conexión. Los tokens se cifran atados al usuario y al proveedor. */
  guardarConexion({ usuarioId, proveedor, cuentaExterna, alias, accessToken, refreshToken, expira }) {
    const ctx = `conexion:${usuarioId}:${proveedor}`;
    this.db
      .prepare(
        `INSERT INTO conexiones (usuario_id, proveedor, cuenta_externa, alias, access_token, refresh_token, expira,
           estado, ultimo_error, ultima_sincronizacion, creado)
         VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVA', NULL, NULL, ?)
         ON CONFLICT (usuario_id, proveedor) DO UPDATE SET
           cuenta_externa = excluded.cuenta_externa, alias = excluded.alias,
           access_token = excluded.access_token, refresh_token = excluded.refresh_token,
           expira = excluded.expira, estado = 'ACTIVA', ultimo_error = NULL`,
      )
      .run(
        usuarioId, proveedor, cuentaExterna ?? null, alias ?? null,
        this.cifrador.cifrarOpcional(accessToken, `${ctx}:access`),
        this.cifrador.cifrarOpcional(refreshToken, `${ctx}:refresh`),
        expira ?? null, ahoraIso(),
      );
  }

  /** Actualiza solo los tokens (después de renovarlos con el refresh_token). */
  actualizarTokens(usuarioId, proveedor, { accessToken, refreshToken, expira }) {
    const ctx = `conexion:${usuarioId}:${proveedor}`;
    this.db
      .prepare('UPDATE conexiones SET access_token = ?, refresh_token = ?, expira = ? WHERE usuario_id = ? AND proveedor = ?')
      .run(
        this.cifrador.cifrarOpcional(accessToken, `${ctx}:access`),
        this.cifrador.cifrarOpcional(refreshToken, `${ctx}:refresh`),
        expira ?? null, usuarioId, proveedor,
      );
  }

  marcarConexion(usuarioId, proveedor, { estado, error = null, sincronizada = false }) {
    this.db
      .prepare(
        `UPDATE conexiones SET estado = ?, ultimo_error = ?,
           ultima_sincronizacion = CASE WHEN ? THEN ? ELSE ultima_sincronizacion END
         WHERE usuario_id = ? AND proveedor = ?`,
      )
      .run(estado, error, sincronizada ? 1 : 0, ahoraIso(), usuarioId, proveedor);
  }

  /** La conexión con los tokens ya descifrados (o null). */
  conexion(usuarioId, proveedor) {
    const fila = this.db.prepare('SELECT * FROM conexiones WHERE usuario_id = ? AND proveedor = ?').get(usuarioId, proveedor);
    return fila ? this.#conTokens(fila) : null;
  }

  conexionPorCuenta(proveedor, cuentaExterna) {
    const fila = this.db
      .prepare('SELECT * FROM conexiones WHERE proveedor = ? AND cuenta_externa = ? ORDER BY creado DESC LIMIT 1')
      .get(proveedor, String(cuentaExterna));
    return fila ? this.#conTokens(fila) : null;
  }

  /** Para la sincronización periódica: todas las conexiones que no fueron revocadas. */
  conexionesParaSincronizar(proveedor) {
    return this.db
      .prepare("SELECT * FROM conexiones WHERE proveedor = ? AND estado != 'REVOCADA'")
      .all(proveedor)
      .map((f) => this.#conTokens(f));
  }

  /** Lo que se le muestra al teléfono: sin tokens. */
  conexionesPublicas(usuarioId) {
    return this.db
      .prepare('SELECT proveedor, alias, estado, ultimo_error, ultima_sincronizacion FROM conexiones WHERE usuario_id = ?')
      .all(usuarioId)
      .map((f) => ({ ...f }));
  }

  borrarConexion(usuarioId, proveedor) {
    return this.db.prepare('DELETE FROM conexiones WHERE usuario_id = ? AND proveedor = ?').run(usuarioId, proveedor).changes > 0;
  }

  #conTokens(fila) {
    const ctx = `conexion:${fila.usuario_id}:${fila.proveedor}`;
    return {
      usuarioId: fila.usuario_id,
      proveedor: fila.proveedor,
      cuentaExterna: fila.cuenta_externa,
      alias: fila.alias,
      accessToken: this.cifrador.descifrarOpcional(fila.access_token, `${ctx}:access`),
      refreshToken: this.cifrador.descifrarOpcional(fila.refresh_token, `${ctx}:refresh`),
      expira: fila.expira,
      estado: fila.estado,
      ultimoError: fila.ultimo_error,
      ultimaSincronizacion: fila.ultima_sincronizacion,
    };
  }

  // ------------------------------------------------------------------ Transacciones (formato unificado)

  /**
   * Guarda transacciones del esquema unificado. Cada una nueva o modificada recibe un número
   * de secuencia nuevo (mayor que todos los anteriores): así el teléfono pide "lo que hay
   * después de mi cursor" y recibe tanto lo nuevo como lo que cambió (un pago devuelto).
   * Lo que llega idéntico a como ya estaba se ignora.
   * @returns {number} cuántas eran nuevas o cambiaron
   */
  guardarTransacciones(usuarioId, lista) {
    if (lista.length === 0) return 0;
    const huellaGuardada = this.db.prepare('SELECT huella FROM huellas WHERE usuario_id = ? AND id_transaccion = ?');
    const siguiente = this.db.prepare(
      "UPDATE meta SET valor = CAST(valor AS INTEGER) + 1 WHERE clave = 'secuencia' RETURNING CAST(valor AS INTEGER) AS valor",
    );
    const insertar = this.db.prepare(
      `INSERT INTO transacciones (secuencia, usuario_id, id_transaccion, datos, recibido) VALUES (?, ?, ?, ?, ?)
       ON CONFLICT (usuario_id, id_transaccion) DO UPDATE SET
         secuencia = excluded.secuencia, datos = excluded.datos, recibido = excluded.recibido`,
    );
    const guardarHuella = this.db.prepare(
      `INSERT INTO huellas (usuario_id, id_transaccion, huella, visto) VALUES (?, ?, ?, ?)
       ON CONFLICT (usuario_id, id_transaccion) DO UPDATE SET huella = excluded.huella, visto = excluded.visto`,
    );
    return this.enTransaccion(() => {
      let cambios = 0;
      const ahora = ahoraIso();
      for (const t of lista) {
        const json = JSON.stringify(t);
        const huella = sha256Hex(json);
        if (huellaGuardada.get(usuarioId, t.id_transaccion)?.huella === huella) continue;
        const secuencia = Number(siguiente.get().valor);
        const datos = this.cifrador.cifrar(json, `transaccion:${usuarioId}:${t.id_transaccion}`);
        insertar.run(secuencia, usuarioId, t.id_transaccion, datos, ahora);
        guardarHuella.run(usuarioId, t.id_transaccion, huella, ahora);
        cambios++;
      }
      return cambios;
    });
  }

  /** Las transacciones con secuencia mayor a [desde], ya descifradas, en orden. */
  transaccionesDesde(usuarioId, desde, limite) {
    return this.db
      .prepare('SELECT secuencia, id_transaccion, datos FROM transacciones WHERE usuario_id = ? AND secuencia > ? ORDER BY secuencia LIMIT ?')
      .all(usuarioId, desde, limite)
      .map((f) => ({
        secuencia: Number(f.secuencia),
        ...JSON.parse(this.cifrador.descifrar(f.datos, `transaccion:${usuarioId}:${f.id_transaccion}`)),
      }));
  }

  /** El teléfono confirmó que guardó todo hasta [hasta]: se borra del servidor. */
  confirmarEntrega(usuarioId, hasta) {
    return this.db.prepare('DELETE FROM transacciones WHERE usuario_id = ? AND secuencia <= ?').run(usuarioId, hasta).changes;
  }

  pendientesDeEntrega(usuarioId) {
    return Number(this.db.prepare('SELECT COUNT(*) AS n FROM transacciones WHERE usuario_id = ?').get(usuarioId).n);
  }

  // ------------------------------------------------------------------ Saldos

  guardarSaldo(usuarioId, { origen, cuenta, moneda, monto }) {
    this.db
      .prepare(
        `INSERT INTO saldos (usuario_id, origen, cuenta, moneda, monto, actualizado) VALUES (?, ?, ?, ?, ?, ?)
         ON CONFLICT (usuario_id, origen, cuenta, moneda) DO UPDATE SET monto = excluded.monto, actualizado = excluded.actualizado`,
      )
      .run(usuarioId, origen, cuenta, moneda, Math.round(Number(monto) * 100) / 100, ahoraIso());
  }

  saldosDe(usuarioId) {
    return this.db
      .prepare('SELECT origen, cuenta, moneda, monto, actualizado FROM saldos WHERE usuario_id = ? ORDER BY origen, cuenta')
      .all(usuarioId)
      .map((f) => ({ ...f }));
  }

  borrarSaldos(usuarioId, origen) {
    this.db.prepare('DELETE FROM saldos WHERE usuario_id = ? AND origen = ?').run(usuarioId, origen);
  }

  // ------------------------------------------------------------------ Limpieza

  /** Borra lo vencido: estados OAuth viejos, movimientos nunca retirados y huellas muy antiguas. */
  purgar(retencionDias) {
    const ahora = Date.now();
    const limite = new Date(ahora - retencionDias * 86_400_000).toISOString();
    const limiteHuellas = new Date(ahora - Math.max(400, retencionDias) * 86_400_000).toISOString();
    return {
      estados: this.db.prepare('DELETE FROM estados_oauth WHERE expira < ?').run(new Date(ahora).toISOString()).changes,
      transacciones: this.db.prepare('DELETE FROM transacciones WHERE recibido < ?').run(limite).changes,
      huellas: this.db.prepare('DELETE FROM huellas WHERE visto < ?').run(limiteHuellas).changes,
    };
  }
}

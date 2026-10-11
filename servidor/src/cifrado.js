// Cifrado y utilidades criptográficas. Todo con node:crypto, sin librerías externas.
//
// Los secretos (tokens de Mercado Pago y los movimientos que esperan ser entregados al
// teléfono) se guardan cifrados con AES-256-GCM: confidencialidad + detección de cambios.
// Cada valor lleva un "contexto" (AAD) que lo ata a su fila: un token cifrado de un usuario
// no se puede copiar a la fila de otro, porque al descifrarlo con otro contexto falla.

import crypto from 'node:crypto';

const VERSION = 'v1';
const LARGO_IV = 12;
const LARGO_TAG = 16;

export class Cifrador {
  /** @param {string} claveBase64 clave AES-256 (32 bytes) en base64 */
  constructor(claveBase64) {
    this.clave = Buffer.from(claveBase64, 'base64');
    if (this.clave.length !== 32) throw new Error('La clave de cifrado tiene que tener 32 bytes (AES-256)');
  }

  /** Devuelve "v1.<base64url(iv | tag | datos)>". */
  cifrar(texto, contexto) {
    const iv = crypto.randomBytes(LARGO_IV);
    const cifra = crypto.createCipheriv('aes-256-gcm', this.clave, iv);
    cifra.setAAD(Buffer.from(contexto, 'utf8'));
    const datos = Buffer.concat([cifra.update(String(texto), 'utf8'), cifra.final()]);
    return `${VERSION}.${Buffer.concat([iv, cifra.getAuthTag(), datos]).toString('base64url')}`;
  }

  /** Lanza un error si el valor fue alterado o el contexto no coincide. */
  descifrar(valor, contexto) {
    const [version, cuerpo] = String(valor).split('.');
    if (version !== VERSION || !cuerpo) throw new Error('Formato de dato cifrado desconocido');
    const bytes = Buffer.from(cuerpo, 'base64url');
    if (bytes.length < LARGO_IV + LARGO_TAG) throw new Error('Dato cifrado incompleto');
    const iv = bytes.subarray(0, LARGO_IV);
    const tag = bytes.subarray(LARGO_IV, LARGO_IV + LARGO_TAG);
    const descifra = crypto.createDecipheriv('aes-256-gcm', this.clave, iv);
    descifra.setAAD(Buffer.from(contexto, 'utf8'));
    descifra.setAuthTag(tag);
    return Buffer.concat([descifra.update(bytes.subarray(LARGO_IV + LARGO_TAG)), descifra.final()]).toString('utf8');
  }

  /** Atajos para valores opcionales (null se guarda como null). */
  cifrarOpcional(texto, contexto) {
    return texto == null || texto === '' ? null : this.cifrar(texto, contexto);
  }

  descifrarOpcional(valor, contexto) {
    return valor == null ? null : this.descifrar(valor, contexto);
  }
}

/** Token aleatorio apto para URLs (por defecto 256 bits). */
export function tokenAleatorio(bytes = 32) {
  return crypto.randomBytes(bytes).toString('base64url');
}

export function sha256Hex(texto) {
  return crypto.createHash('sha256').update(String(texto), 'utf8').digest('hex');
}

export function hmacSha256Hex(secreto, mensaje) {
  return crypto.createHmac('sha256', secreto).update(mensaje, 'utf8').digest('hex');
}

/**
 * Compara dos textos en tiempo constante (para claves y firmas): se comparan sus hashes,
 * así el largo distinto tampoco filtra información. Un valor vacío nunca coincide.
 */
export function igualesSeguro(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || a === '' || b === '') return false;
  const ha = crypto.createHash('sha256').update(a, 'utf8').digest();
  const hb = crypto.createHash('sha256').update(b, 'utf8').digest();
  return crypto.timingSafeEqual(ha, hb);
}

/**
 * PKCE (RFC 7636): el verificador es un texto aleatorio de 43 a 128 caracteres y el desafío
 * es BASE64URL(SHA256(verificador)). Mercado Pago lo pide si activás PKCE en tu aplicación.
 */
export function desafioPkce(verificador) {
  return crypto.createHash('sha256').update(verificador, 'ascii').digest('base64url');
}

export function parPkce() {
  const verificador = crypto.randomBytes(48).toString('base64url'); // 64 caracteres
  return { verificador, desafio: desafioPkce(verificador) };
}

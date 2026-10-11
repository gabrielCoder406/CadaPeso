// Utilidades para hablar con las APIs externas (fetch con tiempo límite y errores claros).

export class ErrorProveedor extends Error {
  /**
   * @param {string} mensaje texto para mostrar al usuario
   * @param {object} [extra]
   * @param {number} [extra.estado] código HTTP que devolvió el proveedor
   * @param {string} [extra.codigo] código interno ("token_invalido", "credenciales"…)
   */
  constructor(mensaje, { estado = 0, codigo = 'proveedor' } = {}) {
    super(mensaje);
    this.name = 'ErrorProveedor';
    this.estado = estado;
    this.codigo = codigo;
  }
}

/**
 * fetch con tiempo límite. Devuelve { estado, datos } donde datos es el JSON (o {} si la
 * respuesta no era JSON). No lanza por códigos HTTP de error: cada proveedor decide.
 */
export async function pedirJson(fetchFn, url, { metodo = 'GET', cabeceras = {}, cuerpo, tiempoMs = 20_000 } = {}) {
  let respuesta;
  try {
    respuesta = await fetchFn(url, {
      method: metodo,
      headers: { Accept: 'application/json', ...cabeceras },
      body: cuerpo,
      signal: AbortSignal.timeout(tiempoMs),
    });
  } catch (e) {
    const motivo = e?.name === 'TimeoutError' ? 'no respondió a tiempo' : 'no se pudo conectar';
    throw new ErrorProveedor(`${new URL(url).host} ${motivo}`, { codigo: 'red' });
  }
  const texto = await respuesta.text();
  let datos = {};
  if (texto) {
    try {
      datos = JSON.parse(texto);
    } catch {
      datos = { mensaje_crudo: texto.slice(0, 300) };
    }
  }
  return { estado: respuesta.status, datos };
}

// Configuración del servidor: todo sale de variables de entorno (ver .env.example).
// Se valida al arrancar para fallar rápido y con un mensaje claro, no a mitad de un pedido.

/** Lee y valida la configuración. Lanza Error con la lista de problemas si algo falta. */
export function leerConfig(env = process.env) {
  const problemas = [];
  const texto = (nombre, porDefecto = '') => (env[nombre] ?? porDefecto).toString().trim();
  const numero = (nombre, porDefecto) => {
    const valor = texto(nombre);
    if (valor === '') return porDefecto;
    const n = Number(valor);
    if (!Number.isFinite(n) || n < 0) problemas.push(`${nombre} tiene que ser un número positivo`);
    return n;
  };

  const claveCifrado = texto('CLAVE_CIFRADO');
  if (Buffer.from(claveCifrado, 'base64').length !== 32) {
    problemas.push(
      'CLAVE_CIFRADO tiene que ser una clave AES-256 (32 bytes en base64). Generala con: ' +
        `node -e "console.log(require('crypto').randomBytes(32).toString('base64'))"`,
    );
  }

  const claveRegistro = texto('CLAVE_REGISTRO');
  if (claveRegistro.length < 12) problemas.push('CLAVE_REGISTRO tiene que tener al menos 12 caracteres');

  const urlPublica = texto('URL_PUBLICA').replace(/\/+$/, '');
  const mpClientId = texto('MP_CLIENT_ID');
  const mpClientSecret = texto('MP_CLIENT_SECRET');
  const mercadoPagoActivo = mpClientId !== '' && mpClientSecret !== '';
  if (mercadoPagoActivo && !/^https:\/\//.test(urlPublica) && !texto('PERMITIR_HTTP')) {
    problemas.push('URL_PUBLICA tiene que ser https://… (Mercado Pago solo redirige y notifica a direcciones HTTPS)');
  }

  if (problemas.length > 0) {
    throw new Error('Configuración incompleta:\n  - ' + problemas.join('\n  - '));
  }

  return {
    puerto: numero('PUERTO', 8080),
    urlPublica,
    claveCifrado,
    claveRegistro,
    rutaBd: texto('RUTA_BD', './datos/cadapeso.db'),
    /** Esquema del deep link para volver a la app después de vincular ("cadapeso://…"). */
    esquemaApp: texto('ESQUEMA_APP', 'cadapeso'),
    /** Detrás de un proxy (Render, Railway, Nginx) la IP real viene en X-Forwarded-For. */
    confiarEnProxy: texto('CONFIAR_EN_PROXY') === '1',
    sincronizarCadaMin: numero('SINCRONIZAR_CADA_MIN', 60),
    /** Movimientos que el teléfono nunca vino a buscar se borran después de estos días. */
    retencionDias: numero('RETENCION_DIAS', 30),
    diasHistorialInicial: numero('DIAS_HISTORIAL_INICIAL', 90),
    mercadoPago: mercadoPagoActivo
      ? {
          clientId: mpClientId,
          clientSecret: mpClientSecret,
          secretoWebhook: texto('MP_WEBHOOK_SECRET'),
          usarPkce: texto('MP_PKCE', '1') !== '0',
          apiUrl: texto('MP_API_URL', 'https://api.mercadopago.com').replace(/\/+$/, ''),
          authUrl: texto('MP_AUTH_URL', 'https://auth.mercadopago.com.ar').replace(/\/+$/, ''),
          redirectUri: `${urlPublica}/v1/mercadopago/retorno`,
          /** 0 = no se controla la antigüedad de la firma (el pago igual se vuelve a pedir a la API). */
          toleranciaFirmaMin: numero('MP_WEBHOOK_TOLERANCIA_MIN', 0),
        }
      : null,
    prometeo: texto('PROMETEO_API_KEY')
      ? {
          apiKey: texto('PROMETEO_API_KEY'),
          url: texto('PROMETEO_URL', 'https://banking.prometeoapi.net').replace(/\/+$/, ''),
          /** Código del proveedor en Prometeo. Vacío = se busca solo (Argentina + "Galicia"). */
          proveedor: texto('PROMETEO_PROVEEDOR'),
        }
      : null,
  };
}

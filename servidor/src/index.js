// Arranque del servidor de CadaPeso.
//   npm start   (lee .env si existe; ver .env.example)

import { BaseDatos } from './bd.js';
import { Cifrador } from './cifrado.js';
import { leerConfig } from './config.js';
import { MercadoPago } from './proveedores/mercadopago.js';
import { Prometeo } from './proveedores/prometeo.js';
import { crearServidor } from './servidor.js';
import { Sincronizador } from './sincronizador.js';

let config;
try {
  config = leerConfig();
} catch (e) {
  console.error(e.message);
  process.exit(1);
}

const bd = new BaseDatos(config.rutaBd, new Cifrador(config.claveCifrado));
const mercadoPago = config.mercadoPago ? new MercadoPago(config.mercadoPago) : null;
const prometeo = config.prometeo ? new Prometeo(config.prometeo) : null;
const sincronizador = new Sincronizador({ bd, config, mercadoPago, prometeo });
const servidor = crearServidor({ config, bd, sincronizador, mercadoPago, prometeo });

servidor.listen(config.puerto, () => {
  console.log(`CadaPeso servidor escuchando en el puerto ${config.puerto}`);
  console.log(`  Mercado Pago: ${mercadoPago ? `sí (redirect_uri ${config.mercadoPago.redirectUri})` : 'no configurado'}`);
  if (mercadoPago && !config.mercadoPago.secretoWebhook) console.log('  ⚠️  Sin MP_WEBHOOK_SECRET: los webhooks se rechazan (la sincronización periódica sigue funcionando)');
  console.log(`  Galicia (Prometeo): ${prometeo ? `sí (${config.prometeo.url})` : 'no configurado'}`);
});

// Sincronización periódica de Mercado Pago (cubre los pagos que no llegan por webhook, como
// los que hacés vos) y limpieza de datos vencidos.
async function tareaPeriodica() {
  try {
    const r = await sincronizador.sincronizarTodo();
    if (r.cuentas > 0 || r.purgado.transacciones > 0) {
      console.log(`Sincronización: ${r.cuentas} cuentas, ${r.nuevas} movimientos nuevos, ${r.errores} errores; purgados ${r.purgado.transacciones}`);
    }
  } catch (e) {
    console.error('Error en la sincronización periódica:', e);
  }
}
const intervalo = config.sincronizarCadaMin > 0 ? setInterval(tareaPeriodica, config.sincronizarCadaMin * 60_000) : null;
setTimeout(tareaPeriodica, 10_000).unref();

function apagar() {
  console.log('Cerrando…');
  if (intervalo) clearInterval(intervalo);
  servidor.close(() => {
    bd.cerrar();
    process.exit(0);
  });
  setTimeout(() => process.exit(0), 5_000).unref();
}
process.on('SIGTERM', apagar);
process.on('SIGINT', apagar);

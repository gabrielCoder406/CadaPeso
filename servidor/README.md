# Servidor de CadaPeso — Mercado Pago y Banco Galicia

Servidor chico (Node.js 22, **sin dependencias**) que conecta la app con:

- **Mercado Pago** por su **API oficial**: vinculación con **OAuth 2.0 + PKCE**, historial con
  `GET /v1/payments/search`, datos de la cuenta con `GET /users/me` y **webhooks** firmados para
  enterarse de cada pago al instante.
- **Banco Galicia** a través de **Prometeo** (agregador de Open Banking con cobertura en
  Argentina): cuentas, saldos, movimientos y consumos de tarjeta.

Todo se convierte a un **esquema unificado** y el teléfono lo baja con un cursor. El servidor no
calcula nada de tus finanzas: eso lo sigue haciendo la app.

> ¿No querés un servidor? La app también puede **leer las notificaciones** de las apps de Galicia y
> Mercado Pago directamente en el teléfono (Ajustes › Cuentas conectadas). Es menos completo
> (solo lo que el banco avisa por notificación), pero no necesita nada más.

## Cómo funciona

```
 Teléfono (CadaPeso)                      Tu servidor                       Proveedores
 ───────────────────                      ───────────                       ───────────
 Registrarse (CLAVE_REGISTRO) ──────────► token del teléfono (se guarda su hash)
 "Vincular Mercado Pago" ───────────────► URL OAuth (state + PKCE) ───────► auth.mercadopago.com.ar
            navegador ◄──────────────────────────────────────────────────── el usuario autoriza
                                          /v1/mercadopago/retorno ◄──────── code
                                          canjea code + code_verifier ────► POST /oauth/token
                                          guarda tokens CIFRADOS (AES-256-GCM)
 cadapeso://conexion?resultado=ok ◄────── página "volver a la app"
                                          webhook firmado (x-signature) ◄── "payment.created"
                                          pide el pago a la API ──────────► GET /v1/payments/{id}
                                          cada hora: /v1/payments/search ─► (lo que no llega por webhook)
 "Conectar Galicia" (usuario, clave) ───► login en Prometeo (en memoria) ─► Prometeo ──► Galicia
                                          cuentas + movimientos; logout; NO guarda la clave
 GET /v1/transacciones?desde=cursor ────► movimientos en el esquema unificado
 (pedir desde=N confirma lo anterior) ──► se BORRAN del servidor
```

## Esquema unificado

Cada movimiento de cualquier origen llega al teléfono así:

| Campo | Ejemplo | Notas |
|---|---|---|
| `id_transaccion` | `MERCADO_PAGO:123456789` · `GALICIA:1234:abc` | único; empieza con el origen |
| `monto` | `12345.6` | siempre positivo, 2 decimales |
| `tipo_movimiento` | `GASTO` / `INGRESO` | |
| `origen` | `MERCADO_PAGO` / `GALICIA` | |
| `fecha` | `2026-10-05` | día en hora argentina |
| `categoria` | `Servicios` o `null` | pista (nombre de una categoría de la app) |
| `descripcion` | `Café Martínez` | |
| `moneda` | `ARS` / `USD` | |
| `medio_pago` | `DEBITO` `CREDITO` `TRANSFERENCIA` `BILLETERA` `EFECTIVO` | mismos nombres que la app |
| `cuenta` | `Caja de ahorro ·1234` | |
| `estado` | `CONFIRMADA` / `ANULADA` | ANULADA = devuelto o cancelado después |
| `contraparte` | `Juan Pérez` o `null` | quién te pagó, si se sabe |
| `transferencia_propia` | `true` | plata entre tus cuentas (extracción, pasar a tu MP…) |

La app pone todo en una **bandeja de revisión** (aceptar, editar o descartar), detecta el mismo
movimiento llegado por dos vías (notificación + API) y avisa si ya lo habías cargado a mano.

## Instalación

Requisitos: **Node.js 22.13 o superior** (trae SQLite incorporado) y una dirección **HTTPS**
pública (Mercado Pago solo redirige y notifica a HTTPS).

```bash
cd servidor
cp .env.example .env      # completalo (ver abajo)
npm start                 # o: docker build -t cadapeso-servidor . && docker run -p 8080:8080 -v cadapeso:/datos --env-file .env cadapeso-servidor
npm test                  # 33 tests: cifrado, normalizadores y recorrido completo con APIs falsas
```

Opciones para tenerlo con HTTPS: cualquier hosting de Node o Docker con disco persistente
(Railway, Fly.io, Render con disco, un VPS con Caddy/Nginx), o tu PC con un túnel
(`cloudflared tunnel`). Si está detrás de un proxy, poné `CONFIAR_EN_PROXY=1`.

### Variables (`.env`)

| Variable | Para qué |
|---|---|
| `URL_PUBLICA` | `https://…` donde se ve el servidor. De ahí salen el redirect y el webhook |
| `CLAVE_CIFRADO` | clave AES-256 (32 bytes base64): `node -e "console.log(require('crypto').randomBytes(32).toString('base64'))"`. **Guardala**: sin ella no se pueden leer los tokens |
| `CLAVE_REGISTRO` | frase (12+ caracteres) que pide la app para registrar un teléfono |
| `MP_CLIENT_ID`, `MP_CLIENT_SECRET` | credenciales de producción de tu aplicación de Mercado Pago |
| `MP_WEBHOOK_SECRET` | clave secreta de los webhooks (sin ella se rechazan) |
| `MP_PKCE` | `1` (por defecto) si activaste PKCE en la aplicación |
| `PROMETEO_API_KEY`, `PROMETEO_URL` | API key de Prometeo; URL de producción o de sandbox |
| `PROMETEO_PROVEEDOR` | código de Galicia en Prometeo (vacío = se busca solo) |
| `SINCRONIZAR_CADA_MIN`, `RETENCION_DIAS`, `DIAS_HISTORIAL_INICIAL` | 60, 30 y 90 por defecto |

## Configurar Mercado Pago

1. Entrá a **Mercado Pago Developers › Tus integraciones** y creá una aplicación.
2. En la aplicación:
   - **URL de redireccionamiento**: `https://TU-SERVIDOR/v1/mercadopago/retorno`
   - Activá el **flujo de autorización con PKCE** (o poné `MP_PKCE=0`).
   - **Webhooks**: URL `https://TU-SERVIDOR/v1/webhooks/mercadopago`, evento **Pagos**. Copiá la
     **clave secreta** a `MP_WEBHOOK_SECRET`.
3. Copiá el **Client ID** y el **Client Secret** de producción a `MP_CLIENT_ID` / `MP_CLIENT_SECRET`.
4. En la app: Ajustes › Cuentas conectadas › **Vincular Mercado Pago**.

Detalles:
- El `access_token` dura 180 días; el servidor lo renueva solo con el `refresh_token` (7 días antes
  de vencer, o si Mercado Pago lo rechaza). Si no puede, la app te pide volver a vincular.
- Se buscan los pagos donde la cuenta **cobró** (`collector.id`) y donde **pagó** (`payer.id`). Los
  webhooks avisan sobre todo de lo cobrado; lo pagado entra con la sincronización periódica.
- Para un cobro se usa el monto neto (descontadas las comisiones); para un pago, lo que realmente
  salió (con el interés de las cuotas). Pendientes y rechazados se ignoran.
- **Saldo**: Mercado Pago no documenta un endpoint de saldo para todas las aplicaciones. El servidor
  prueba `/users/{id}/mercadopago_account/balance` y, si tu aplicación no tiene permiso, simplemente
  no muestra saldo (los movimientos llegan igual).

## Configurar Banco Galicia (Prometeo)

Los bancos argentinos no ofrecen APIs públicas de lectura para cuentas personales. Prometeo ya
está conectado con Galicia: entra con tu usuario del home banking y devuelve cuentas, saldos y
movimientos por su API REST.

1. Creá una cuenta en [Prometeo](https://prometeoapi.com) y obtené una API key (hay sandbox).
2. Poné `PROMETEO_API_KEY` y `PROMETEO_URL`.
3. En la app: Cuentas conectadas › **Conectar Galicia**. La app pide los campos extra que necesite
   el banco (por ejemplo, el DNI) según lo que informa Prometeo, y si el banco pide un código por
   SMS o una pregunta de seguridad, te la muestra.

**Tu clave del banco no se guarda en el servidor**: viaja por HTTPS en cada sincronización, se usa
en memoria para abrir la sesión en Prometeo y se cierra la sesión al terminar. Si elegís
"Recordar en este teléfono", queda cifrada con el Keystore de Android solo en tu teléfono (para
que la sincronización automática pueda traer Galicia).

## API

Las rutas con 🔒 piden `Authorization: Bearer <token del teléfono>`.

| Ruta | |
|---|---|
| `GET /salud` | estado y qué proveedores están configurados |
| `POST /v1/dispositivos` `{clave_registro, dispositivo}` | registra un teléfono → `{token, instancia}` |
| 🔒 `DELETE /v1/dispositivos/yo` | borra el teléfono y **todos** sus datos |
| 🔒 `GET /v1/estado` | conexiones, saldos y movimientos pendientes de entrega |
| 🔒 `POST /v1/mercadopago/vincular` | URL de autorización (con `state` de un solo uso y PKCE) |
| `GET /v1/mercadopago/retorno` | redirect del OAuth; canjea el código y vuelve a la app |
| `POST /v1/webhooks/mercadopago` | notificaciones (verifica `x-signature`) |
| 🔒 `POST /v1/sincronizar` | trae ya los pagos de Mercado Pago |
| 🔒 `GET /v1/galicia/campos` | campos extra que pide el banco para entrar |
| 🔒 `POST /v1/galicia/sincronizar` `{usuario, clave, campos, dias, interaccion?}` | descarga Galicia |
| 🔒 `GET /v1/transacciones?desde=N&instancia=X` | lo nuevo después del cursor; confirma (y borra) hasta N |
| 🔒 `DELETE /v1/conexiones/mercado-pago` | desvincula (borra los tokens) |

## Seguridad

- **Cifrado en reposo (AES-256-GCM)** de los tokens de Mercado Pago y de los movimientos que
  esperan al teléfono. Cada valor está atado a su fila (AAD): no se puede copiar a otro usuario.
- De los tokens de los teléfonos solo se guarda el **hash SHA-256**.
- OAuth con `state` aleatorio de un solo uso (vence a los 15 minutos) y **PKCE S256**.
- Webhooks con verificación **HMAC-SHA256** de `x-signature`; además nunca se confía en su
  contenido: el pago se vuelve a pedir a la API con el token de la cuenta.
- Los movimientos se **borran** apenas el teléfono confirma que los guardó (y a los
  `RETENCION_DIAS` si nunca los busca). Quedan solo ids y hashes para no reenviar duplicados.
- Límite de intentos de registro por IP; cuerpos de hasta 64 KB.

## Lo que se decidió no hacer

- **Web scraping del home banking con Playwright/Puppeteer** (opción C): obliga a guardar tus
  claves en un servidor, se rompe con cada cambio del sitio y con el 2FA, y probablemente viola los
  términos del banco. Prometeo hace ese trabajo con acuerdos y mantenimiento propios.
- **Leer los mails del banco con la API de Gmail** (opción B2): el permiso `gmail.readonly` es
  "restringido" y Google exige una verificación de seguridad para usarlo fuera de modo prueba (en
  modo prueba el acceso vence cada 7 días). Las notificaciones del teléfono cubren lo mismo sin eso.
- **Notificaciones push al teléfono (FCM)**: requiere un proyecto de Firebase. El teléfono baja lo
  nuevo al abrir la app y cada 6 horas en segundo plano.

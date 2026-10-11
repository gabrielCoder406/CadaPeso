# Guía de uso de CadaPeso

CadaPeso es una app para Android que ordena tus gastos con un presupuesto, te muestra tu
patrimonio y te aconseja con un asesor financiero (con Gemma 2 de Google, opcional). Desde la
versión 1.1 además importa tus movimientos de **Mercado Pago** y **Banco Galicia**.

**Contenido**

1. [Instalar o actualizar](#1-instalar-o-actualizar)
2. [Primeros pasos](#2-primeros-pasos)
3. [Uso diario](#3-uso-diario)
4. [Cuentas conectadas: Mercado Pago y Galicia](#4-cuentas-conectadas-mercado-pago-y-galicia)
5. [Asesor con IA (Gemma 2)](#5-asesor-con-ia-gemma-2)
6. [Respaldo y privacidad](#6-respaldo-y-privacidad)
7. [Problemas frecuentes](#7-problemas-frecuentes)

---

## 1. Instalar o actualizar

Requiere **Android 8.0 o superior**.

### Descargar el APK

1. Desde el teléfono, entrá a la página del repositorio en GitHub › **Releases** (a la derecha) ›
   la versión más nueva (por ejemplo **CadaPeso v1.1.0**).
2. En **Assets**, tocá **CadaPeso-1.1.0.apk** para descargarlo.
3. Abrilo desde las descargas. Android va a pedir permitir **"Instalar apps desconocidas"** para el
   navegador o el administrador de archivos: activalo y volvé.
4. Tocá **Instalar**.

> También podés compilarlo vos en la PC con doble clic en `compilar-apk.bat` (ver el README).

### Actualizar sin perder datos

Android solo instala una versión nueva **encima** de la anterior si las dos están firmadas con la
misma clave. Tu versión 1.0 la compilaste en la PC con tu firma (`cadapeso-release.jks`).

**Opción A — Compilar en tu PC (lo más simple).** Bajá el código nuevo (`git pull`) y hacé doble
clic en `compilar-apk.bat`. Usa tu misma firma, así que el APK se instala encima y no perdés nada.

**Opción B — Que GitHub firme con tu clave.** Una sola vez, cargá tu firma como secretos del
repositorio (GitHub › **Settings › Secrets and variables › Actions › New repository secret**):

| Secreto | Valor |
|---|---|
| `FIRMA_JKS_BASE64` | el archivo `cadapeso-release.jks` en base64. En PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("cadapeso-release.jks")) \| Set-Clipboard` y pegalo |
| `FIRMA_CLAVE` | el valor de `storePassword` en `keystore.properties` |
| `FIRMA_CLAVE_LLAVE` | el valor de `keyPassword` (es el mismo que el anterior si usaste el script) |
| `FIRMA_ALIAS` | `cadapeso` |

Desde ese momento, cada APK que compila GitHub se instala encima de tu versión. Para generar un
Release nuevo con tu firma: subí la versión en `app/build.gradle.kts` (`versionName`) y creá la
etiqueta correspondiente (por ejemplo `v1.1.1`: `git tag v1.1.1 && git push origin v1.1.1`). Cada
compilación también deja el APK en **Actions › (la ejecución) › Artifacts**.

**Opción C — Si el APK está firmado con otra clave** (dice "clave de prueba" en el Release, o
Android muestra "La app no se instaló porque el paquete entra en conflicto con uno existente"):

1. En la versión vieja: **Ajustes › Tus datos › Exportar respaldo** y guardá el archivo `.json`
   (por ejemplo en Drive).
2. Desinstalá CadaPeso.
3. Instalá el APK nuevo.
4. En la bienvenida tocá **Explorar con datos de ejemplo**, después **Ajustes › Tus datos ›
   Restaurar respaldo** y elegí el `.json`. Se reemplazan los datos de ejemplo por los tuyos.

Lo que el respaldo **no** incluye: el chat con el asesor, la configuración de la IA y el archivo
del modelo Gemma (2,6 GB): si lo usabas, volvé a importarlo.

---

## 2. Primeros pasos

Al abrir la app por primera vez:

1. **Empezar**: tu nombre y edad (sirve para la etapa de vida).
2. **Ingreso mensual neto**: lo que cobrás en mano.
3. **Regla de presupuesto**: 50/30/20 (necesidades/deseos/ahorro) o 60/10/10/20. Dejá marcado
   "armar el presupuesto" y la app reparte todo tu ingreso: **cada peso con una tarea**.
4. **¡Listo!**

¿Querés ver cómo se ve con datos? **Explorar con datos de ejemplo** carga 3 meses inventados
(después los borrás en Ajustes › Tus datos › Borrar datos).

La app tiene 5 secciones abajo:

| Sección | Para qué |
|---|---|
| **Inicio** | Cuánto te queda este mes, la regla 50/30/20, tu semana, alertas y la hoja de ruta |
| **Movimientos** | Todos tus gastos e ingresos, con búsqueda y filtros |
| **Presupuesto** | Cuánto asignaste a cada categoría, panel semanal, proyección y gastos hormiga |
| **Patrimonio** | Activos y deudas, ahorro y metas, simulador de inversión |
| **Asesor** | Diagnóstico, "¿lo compro?" (regla de las 24 h) y chat con la IA |

La ⚙️ de Inicio abre **Ajustes**.

---

## 3. Uso diario

### Cargar un gasto o un ingreso

Tocá **+ Movimiento** y escribí (o dictá con el micrófono) como hablás:

- `café 3500 con débito`
- `ayer 12 lucas de nafta con la tarjeta`
- `cobré el sueldo 1.200.000`
- `el lunes 15.000,50 en el super`

La app completa monto, categoría, medio de pago y fecha. Revisá y tocá **Guardar**. Si elegís otra
categoría, la app **aprende** y la próxima vez acierta.

### Antes de un gasto grande: "¿Lo compro?"

En **Asesor › 🛍️ ¿Lo compro?** cargás lo que querés comprar y respondés dos preguntas. Si es un
impulso, te sugiere esperar 24 o 48 horas y te avisa cuando termina la espera.

### Presupuesto, deudas y metas

- **Presupuesto**: tocá una categoría para cambiar cuánto le asignás.
- **Patrimonio › Deudas › Registrar pago**: baja el saldo de la deuda y registra el gasto.
- **Patrimonio › Ahorro y metas**: metas SMART con plan de ahorro, y "págate a ti primero" el día
  de cobro.

---

## 4. Cuentas conectadas: Mercado Pago y Galicia

En **Ajustes › 🔗 Cuentas conectadas** hay dos formas de traer tus movimientos. Podés usar una,
la otra o las dos.

| | Avisos del banco | Con servidor |
|---|---|---|
| Qué necesita | Solo el teléfono | Un servidor tuyo con HTTPS |
| Mercado Pago | Lo que avisan las notificaciones | Todo el historial (API oficial) + avisos al instante |
| Galicia | Lo que avisan las notificaciones | Cuentas, saldos, movimientos y tarjetas (vía Prometeo) |
| Privacidad | Nada sale del teléfono | Pasa por tu servidor (cifrado) |

Todo lo que llega va a la **bandeja de revisión**: nada cuenta en tu presupuesto hasta que lo
aceptás.

### 4.1. Avisos del banco (sin servidor)

1. En **Avisos del banco en tu teléfono**, activá **Banco Galicia** y/o **Mercado Pago**.
2. Tocá **Dar acceso** y, en la pantalla de Android, activá **CadaPeso: avisos de Galicia y
   Mercado Pago**.
3. Si Android dice **"Configuración restringida"** (pasa siempre con apps instaladas desde un APK):
   tocá **Abrir información de la app** › menú **⋮** arriba a la derecha › **Permitir
   configuración restringida** › confirmá con tu huella o PIN, y volvé a tocar **Dar acceso**.
4. Listo: cuando la app de Galicia o de Mercado Pago te avise "Compraste $ 12.345 en …" o
   "Recibiste $ 5.000 de …", aparece en la bandeja y te llega un aviso de CadaPeso.

Las promociones, los códigos de seguridad, los vencimientos y las operaciones rechazadas se
ignoran. CadaPeso solo mira las notificaciones de esas dos apps: las demás no las lee.

### 4.2. Conectar tu servidor

El servidor (carpeta `servidor/` del repositorio) es el que habla con Mercado Pago y Prometeo. Cómo
instalarlo y configurarlo está en [`servidor/README.md`](servidor/README.md). En resumen:

1. Publicalo en una dirección HTTPS (Railway, Fly.io, un VPS, o tu PC con `cloudflared tunnel`).
2. Elegí una **CLAVE_REGISTRO** (una frase de 12 caracteres o más).
3. En la app, en **Servidor de CadaPeso**: escribí la dirección (`https://…`) y la clave de
   registro, y tocá **Conectar este teléfono**.

Desde ahí la app sincroniza sola cada 6 horas y cada vez que la abrís (si pasaron 15 minutos).
También podés tocar **Sincronizar ahora** o el ícono ⟳ de arriba.

### 4.3. Mercado Pago

1. Tocá **Vincular Mercado Pago**. Se abre Mercado Pago en el navegador.
2. Iniciá sesión y **autorizá** el acceso.
3. Tocá **Volver a CadaPeso**. La app avisa "¡Mercado Pago vinculado!" y trae los últimos 90 días.

Desde ese momento cada pago o cobro llega al servidor al instante y el teléfono lo baja en la
próxima sincronización. Si Mercado Pago revoca el acceso, la app muestra **Volver a vincular**.

### 4.4. Banco Galicia

1. En **Banco Galicia (vía Prometeo)** escribí tu **usuario** y **clave** del home banking, y los
   datos extra que pida el banco (por ejemplo, el DNI).
2. Dejá activado **Recordar en este teléfono** si querés que se sincronice solo (la clave queda
   cifrada en el teléfono, nunca en el servidor).
3. Tocá **Conectar Galicia**. Puede tardar un minuto.
4. Si el banco pide un código (por SMS o una pregunta de seguridad), la app te lo pregunta.

La primera vez trae 90 días; después, los últimos 10 días en cada sincronización. Si el banco pide
un código cada vez, la sincronización automática no puede contestarlo: tocá **Sincronizar
Galicia** cuando quieras traer lo nuevo.

### 4.5. La bandeja de revisión

En **Inicio** o **Movimientos** aparece **"📥 N movimientos para revisar"**. Tocalo:

| Botón | Qué hace |
|---|---|
| **Aceptar** | Lo agrega a tus movimientos con la categoría que se ve |
| Chip de categoría | Elegí otra categoría antes de aceptar (la app aprende) |
| **Editar** | Abre el editor para cambiar monto, fecha o descripción antes de guardarlo |
| **Descartar** | No se agrega (se puede **Deshacer**) |
| **Es el mismo** | Aparece si ya lo habías cargado a mano: lo vincula sin duplicarlo |
| **Aceptar todos** | Acepta los que no necesitan decisión |

Avisos que vas a ver:

- **"Parece plata entre tus cuentas"**: una extracción o pasar plata de Galicia a tu Mercado Pago.
  No es ni gasto ni ingreso: lo normal es **Descartar**.
- **"¿Ya lo cargaste?"**: hay un movimiento cargado a mano con el mismo monto y fecha cercana.
- **"Está en USD"**: al aceptarlo se abre el editor para pasarlo a pesos.
- **"Devolución: …"**: un pago que aceptaste se devolvió; aceptalo como ingreso.

Si el mismo movimiento llega por la notificación y por la API, queda uno solo.

### 4.6. Opciones

- **Agregar sin revisar**: lo que llega de la cuenta conectada (no de notificaciones) entra directo,
  salvo transferencias propias o posibles duplicados.
- **Saldos en Patrimonio**: crea y mantiene un activo "Cuenta o billetera" con el saldo en pesos de
  cada cuenta. Si lo borrás, no se vuelve a crear.
- **Desvincular / Olvidar mi clave / Desconectar**: el servidor borra los tokens y los datos de tu
  teléfono. Lo que ya está en la app se queda.

Los movimientos importados muestran su origen ("· Mercado Pago", "· Banco Galicia") en la lista y
en el CSV.

---

## 5. Asesor con IA (Gemma 2)

Sin IA la app funciona completa: los atajos del asesor responden con el motor de reglas. Para el
chat libre, en **Ajustes › Inteligencia artificial**:

- **Gemma 2 en el teléfono**: descargá `Gemma2-2B-IT_multi-prefill-seq_q8_ekv1280.task` (~2,6 GB)
  de Hugging Face (hay que aceptar la licencia) y tocá **Importar archivo**. Recomendado: 6 GB de
  RAM o más.
- **Gemma 2 en tu PC con Ollama**: `ollama pull gemma2:2b`, `OLLAMA_HOST=0.0.0.0`, y en la app
  `http://<IP-de-la-PC>:11434` › **Probar conexión**.

Los detalles están en el [README](README.md#activar-gemma-2).

---

## 6. Respaldo y privacidad

- **Ajustes › Tus datos › Exportar respaldo**: un `.json` con todo (para cambiar de teléfono).
- **Exportar a Excel (CSV)**: tus movimientos para abrir en Excel.
- Tus datos financieros viven en el teléfono. Con cuentas conectadas:
  - los **avisos del banco** se procesan en el teléfono;
  - tu **servidor** guarda los tokens de Mercado Pago cifrados (AES-256) y los movimientos solo
    hasta que el teléfono los baja;
  - la **clave de Galicia** nunca se guarda en el servidor.
- La conexión con el servidor no viaja en el backup de Google: en un teléfono nuevo se vuelve a
  conectar.

CadaPeso es una herramienta educativa: no es asesoramiento financiero profesional.

---

## 7. Problemas frecuentes

**"La app no se instaló porque el paquete entra en conflicto con uno existente".**
El APK está firmado con otra clave. Ver [Actualizar sin perder datos](#actualizar-sin-perder-datos).

**No aparece "CadaPeso" en Acceso a notificaciones, o el interruptor está gris.**
Es la "configuración restringida" de Android 13+: Información de la app › ⋮ › Permitir
configuración restringida (sección 4.1).

**Activé los avisos pero no llega nada a la bandeja.**
Revisá que diga "✅ Acceso a notificaciones activado", que las notificaciones de la app del banco
estén encendidas y que el interruptor de ese banco esté activo en CadaPeso. Algunos avisos no se
reconocen (por ejemplo, si no dicen el monto); esos cargalos a mano. Si un aviso real no se detecta,
copiá su texto y reportalo para mejorar el lector.

**"No pude conectar con el servidor".**
La dirección tiene que empezar con `https://` (sin cifrar solo se permite dentro de tu red, como
`http://192.168.0.10:8080`). Probá abrir `https://TU-SERVIDOR/salud` en el navegador: tiene que
mostrar `"ok":true`.

**"La clave de registro no es correcta".**
Es la `CLAVE_REGISTRO` del `.env` del servidor. Después de 10 intentos fallidos hay que esperar una
hora.

**"El servidor ya no reconoce este teléfono".**
Se borró la base del servidor o se desconectó el teléfono. Volvé a conectarlo con la clave de
registro y a vincular Mercado Pago.

**Mercado Pago: "Hay que volver a vincular".**
Se revocó el permiso o venció la renovación. Tocá **Volver a vincular**.

**Galicia: "Usuario o clave del home banking incorrectos" o "El banco no está disponible".**
Revisá tus datos entrando al home banking. Si el banco está en mantenimiento, probá más tarde.

**No veo el saldo de Mercado Pago.**
Mercado Pago no da el saldo a todas las aplicaciones. Los movimientos llegan igual.

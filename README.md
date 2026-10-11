# CadaPeso — gestor de gastos con asesor IA (Gemma 2)

App Android (APK) para gestionar gastos con un asesor financiero que usa **Gemma 2 de Google**.
Funciona 100 % en el teléfono: los datos financieros nunca salen del dispositivo, salvo que
conectes tus cuentas de Mercado Pago o Galicia a través de tu propio servidor (opcional).

> Idea central: **el motor de reglas calcula, Gemma explica.** Todas las cifras (presupuesto,
> deudas, proyecciones, alertas) las calcula código Kotlin testeado. Gemma 2 (2B parámetros)
> recibe esos números ya hechos y los convierte en consejos en lenguaje natural. Así el modelo
> chico no puede "inventar" un número, y la app sigue funcionando aunque no haya IA configurada.

## Qué hace

| Módulo | Funciones |
|---|---|
| **Presupuesto y flujo** | "Cada peso con una tarea" (asignar todo el ingreso antes de gastar), reglas **50/30/20** y **60/10/10/20**, categorización automática con aprendizaje, **gastos hormiga** y **suscripciones**, panel **semanal**, comparación **mensual** real vs. presupuesto, **proyección a 3 y 9 meses** con gastos extraordinarios (patente, vacaciones, aguinaldo…) |
| **Patrimonio y salud financiera** | Patrimonio neto (activos a valor de mercado − pasivos), **capacidad de reacción**, **ratio de endeudamiento** y uso de tarjeta con alerta al 30 %, meses de supervivencia |
| **Ahorro** | **Págate a ti primero** (recordatorio el día de cobro), **fondo de emergencia** dinámico (3–6 meses si el ingreso es estable, 6–12 si es variable), **metas SMART** con plan de **ahorro incremental** |
| **Deudas** | Clasificador **deuda buena / mala** (usa tasa real si cargás la inflación; contempla créditos UVA), plan **avalancha** vs. bola de nieve, detección de la **trampa del pago mínimo** |
| **Asesor IA** | Perfil de **conducta** (impulsivo/ahorrador), test de **tolerancia al riesgo** con "factor sueño", **etapa de vida**; **regla de las 24/48 h** con las dos preguntas anti-gasto y recordatorio; **plan de ajuste progresivo** si los gastos básicos superan el 80 %; **hoja de ruta** en 4 pasos; simulador de **DCA e interés compuesto**; chat libre con Gemma 2 |
| **Cuentas conectadas** | **Mercado Pago** (API oficial, OAuth 2.0 + webhooks) y **Banco Galicia** (vía Prometeo), o sin servidor leyendo las **notificaciones** de sus apps. Todo pasa por una **bandeja de revisión** con categoría sugerida, detección de duplicados y de transferencias entre tus cuentas; los saldos se reflejan en Patrimonio |
| **Extras** | Carga por texto o **voz** ("ayer 12 lucas de nafta con la tarjeta"), notificaciones, respaldo JSON, exportación a Excel (CSV), modo oscuro |

## Cómo generar el APK

Requisitos (ya están en esta PC): JDK 21 y Android SDK (los mismos de App-Matematicas).

1. Doble clic en **`compilar-apk.bat`**.
2. La primera vez crea la firma `cadapeso-release.jks` + `keystore.properties`.
   **Guardá una copia de esos dos archivos**: sin ellos no podés instalar actualizaciones encima.
3. El APK queda en `release\CadaPeso-1.1.0.apk`.

Las carpetas de compilación van a `%LOCALAPPDATA%\cadapeso-build` (fuera de OneDrive, que bloquea
los archivos intermedios de Gradle — el mismo problema que ya resolviste en App-Matematicas).

## Instalar en el teléfono

Pasá el APK al teléfono (cable, Drive, WhatsApp) y abrilo. Android va a pedir permitir
"instalar apps desconocidas" para la app desde la que lo abrís. Requiere Android 8.0 o superior.

## Activar Gemma 2

Sin IA la app funciona completa (los atajos del asesor responden con el motor de reglas).
Para el chat libre y las explicaciones, en **Ajustes › Inteligencia artificial**:

### Opción 1 — Gemma 2 en el teléfono (privado, sin internet)
Modelo: [`litert-community/Gemma2-2B-IT`](https://huggingface.co/litert-community/Gemma2-2B-IT),
archivo `Gemma2-2B-IT_multi-prefill-seq_q8_ekv1280.task` (~2,6 GB). Es "gated": hay que tener
cuenta en Hugging Face y aceptar la licencia de Gemma.

- **A (recomendada):** desde el navegador del teléfono, iniciá sesión en Hugging Face, aceptá la
  licencia, descargá el `.task` y en la app tocá **Importar archivo**. (Después podés borrar la copia
  de Descargas.)
- **B:** generá un token de lectura en huggingface.co/settings/tokens y usá **Descargar Gemma 2**
  en la app (el token se usa una vez y no se guarda).

Recomendado: teléfono con 6 GB de RAM o más. La primera carga tarda unos segundos; las
respuestas, de 10 a 40 segundos según el teléfono. Si tu teléfono lo soporta, probá "Usar GPU".

### Opción 2 — Gemma 2 en tu PC con Ollama
Ollama ya está instalado en esta PC. En una terminal:

```bash
ollama pull gemma2:2b
```

Para que el teléfono lo alcance, Ollama tiene que escuchar en la red: definí la variable de
entorno de usuario `OLLAMA_HOST=0.0.0.0`, reiniciá Ollama y permití el puerto 11434 en el
firewall. En la app poné `http://<IP-de-la-PC>:11434` (la IP la ves con `ipconfig`) y tocá
**Probar conexión**. Con Ollama también podés usar `gemma2:9b` si la PC lo banca.

## Cuentas conectadas: Mercado Pago y Banco Galicia

En **Ajustes › Cuentas conectadas** hay dos formas de importar movimientos (se pueden usar juntas):

1. **Avisos del banco en el teléfono (sin servidor).** CadaPeso lee las notificaciones de las apps
   *Galicia* y *Mercado Pago* ("Compraste $ 12.345 en SUPERMERCADO DIA", "Recibiste $ 5.000 de
   Juan") y las convierte en movimientos. Todo se procesa en el teléfono. Hay que darle a la app el
   **Acceso a notificaciones**; como se instala desde un APK, Android 13+ primero pide
   *Información de la app › ⋮ › Permitir configuración restringida*.
2. **Con tu servidor** (carpeta [`servidor/`](servidor/README.md)): Mercado Pago por su **API
   oficial** (OAuth 2.0 con PKCE, `/v1/payments/search`, `/users/me` y **webhooks**) y Galicia a
   través de **Prometeo**, un agregador de Open Banking. El servidor guarda los tokens cifrados
   (AES-256-GCM), no guarda la clave del banco y borra cada movimiento apenas el teléfono lo baja.

Lo que llega entra a la **bandeja de revisión** (se ve en Inicio y en Movimientos):

- Categoría sugerida con el mismo categorizador que aprende de tus correcciones.
- Si el mismo movimiento llega por la notificación y por la API, queda uno solo.
- Si ya lo cargaste a mano, te pregunta si "es el mismo".
- Extracciones y transferencias a tu propia cuenta (Galicia → tu Mercado Pago) se marcan para
  descartar: no son ni ingreso ni gasto.
- Si un pago que aceptaste se devuelve, aparece la devolución como ingreso a revisar.
- Opcional: "Agregar sin revisar" lo que viene de la API (nunca lo de notificaciones).

Los movimientos importados guardan su **origen** (se ve en la lista y en el CSV). La base pasó a
la versión 2 con una migración que conserva todos los datos (`MigracionTest` lo verifica).

## Arquitectura

```
UI (Jetpack Compose)          ui/pantallas/*  ← muestran el Analisis y llaman al ViewModel
   │
ViewModels                    ui/AppViewModel (datos y acciones) · ui/AsesorViewModel (IA)
   │
Repositorio  ──────────────── datos/Repositorio (única puerta a la base; reglas de guardado)
   │                               │
Room (SQLite)                 datos/BaseDatos, Entidades
   │
MotorFinanciero.analizar(datos, hoy) → Analisis      dominio/* (Kotlin puro, testeado)
   ├─ MotorPresupuesto   reglas 50/30/20 y 60/10/10/20, semana, cada peso con una tarea
   ├─ MotorFugas         gastos hormiga, suscripciones, proyección 3/9 meses
   ├─ MotorAhorro        págate primero, fondo de emergencia, metas SMART, DCA
   ├─ MotorDeudas        clasificación, avalancha / bola de nieve
   ├─ MotorConsejos      alertas proactivas, hoja de ruta, plan de ajuste
   ├─ Perfilado          tests de conducta y riesgo, etapa de vida
   └─ ParserMovimiento   texto libre → movimiento (sin IA, instantáneo)
   │
AsesorIA (ia/*)               arma prompts compactos con el Analisis y elige el motor:
   ├─ GemmaLocal          MediaPipe LLM Inference 0.10.35 (archivo .task en el teléfono)
   └─ GemmaOllama         HTTP a Ollama (/api/chat, streaming)

Cuentas conectadas (integraciones/*)     todo se convierte a TransaccionExterna (esquema unificado)
   ├─ OyenteNotificaciones + LectorNotificaciones   avisos de Galicia y Mercado Pago (en el teléfono)
   ├─ ClienteServidor ── HTTPS ──► servidor/ (Node) ──► API de Mercado Pago · Prometeo (Galicia)
   ├─ Conciliador        bandeja: categoría, duplicados, transferencias propias, devoluciones
   ├─ GestorIntegraciones + SincronizacionWorker    configuración y sincronización cada 6 h
   └─ Cofre              token y clave de Galicia cifrados con AES-256-GCM (Android Keystore)
```

Cada vez que cambia algo en la base, Room emite los datos nuevos, el motor recalcula el
`Analisis` completo (milisegundos) y todas las pantallas se actualizan solas.

### Detalles de la integración con Gemma 2
- Motor en el teléfono: **MediaPipe LLM Inference** (`tasks-genai` 0.10.35). Google la marcó como
  obsoleta en favor de LiteRT-LM, pero LiteRT-LM usa otro formato (`.litertlm`) y no ofrece
  Gemma 2, así que para Gemma 2 sigue siendo la vía oficial. Si más adelante querés Gemma 3/3n,
  conviene migrar `GemmaLocal.kt` a LiteRT-LM (el resto de la app no cambia).
- Gemma 2 no tiene rol "system": las instrucciones y "MIS DATOS" van en el turno del usuario,
  con el formato `<start_of_turn>user … <end_of_turn><start_of_turn>model`.
- El modelo LiteRT tiene **1280 tokens de contexto en total**: el prompt se mide con el
  tokenizador real (`sizeInTokens`); si no entra, se usa una versión breve de los datos y se
  recorta el historial.
- Para cargar movimientos por texto, primero actúa el parser por reglas (entiende "lucas",
  "palos", "15.000,50", "ayer", "el lunes", "5/9"…). Gemma solo interviene si lo pedís con "Con IA",
  y responde un JSON que se valida antes de usarlo.

## Estructura de carpetas

```
app/src/main/java/com/gabriel/cadapeso/
  CadaPesoApp.kt, MainActivity.kt     arranque, contenedor de dependencias
  dominio/                            motor financiero (sin Android: se testea en la PC)
  datos/                              Room, repositorio, categorías base, respaldo, datos de ejemplo
  ia/                                 Gemma local, Ollama, prompts, gestor de modelos
  integraciones/                      Mercado Pago, Galicia, notificaciones del banco, bandeja
  notificaciones/                     regla 24/48 h, día de cobro, resumen semanal (WorkManager)
  ui/                                 navegación, ViewModels, tema, componentes y pantallas
app/src/test/                         tests del motor + recorrido completo de la app (Robolectric)
servidor/                             servidor opcional (Node.js 22, sin dependencias) para Mercado Pago y Galicia
```

## Tests

```bash
gradlew testDebugUnitTest
```

- `ParserMovimientoTest` y `MotoresTest`: lógica financiera (presupuesto, fugas, avalancha,
  metas, fondo de emergencia, perfilado, análisis completo).
- `LectorNotificacionesTest`, `ConciliadorTest`, `JsonServidorTest`: lectura de avisos del banco
  (compras, transferencias, promos y códigos que se ignoran), reglas de la bandeja y respuestas del
  servidor.
- `MigracionTest`: una base de la versión 1 se actualiza a la 2 sin perder datos.
- `PromptsTest`: los prompts entran en los 1280 tokens de Gemma2-2B-IT y usan bien el formato de
  turnos de Gemma (además imprime un prompt real para revisarlo).
- `RecorridoAppTest`: abre la app real en un Android simulado (Robolectric, sin emulador) y la
  recorre como un usuario: bienvenida, datos de ejemplo, las 5 secciones con sus pestañas, cargar
  un movimiento escrito, el asesor, la regla de las 24 h, asignar presupuesto, pagar una deuda,
  crear una meta, los ajustes y la bandeja de cuentas conectadas. Verifica en la base de datos que
  cada acción quedó guardada.

El servidor tiene sus propios tests: `cd servidor && npm test`.

Nota de diseño: los formularios usan hojas que suben desde abajo (`HojaFormulario`) en vez de
diálogos centrados: son más cómodos con el teclado del celular y, además, Robolectric no puede
probar campos de texto dentro de diálogos (se quedan "esperando" indefinidamente).

## Descargo

CadaPeso es una herramienta educativa: no es asesoramiento financiero profesional ni recomienda
productos de inversión. Gemma es un modelo de Google sujeto a los Términos de uso de Gemma.

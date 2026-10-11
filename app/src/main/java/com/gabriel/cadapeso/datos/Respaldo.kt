package com.gabriel.cadapeso.datos

import com.gabriel.cadapeso.dominio.DatosFinancieros
import com.gabriel.cadapeso.dominio.EstadoCompra
import com.gabriel.cadapeso.dominio.FrecuenciaAhorro
import com.gabriel.cadapeso.dominio.GrupoGasto
import com.gabriel.cadapeso.dominio.MedioPago
import com.gabriel.cadapeso.dominio.OrigenMovimiento
import com.gabriel.cadapeso.dominio.ReglaPresupuesto
import com.gabriel.cadapeso.dominio.TipoActivo
import com.gabriel.cadapeso.dominio.TipoDeuda
import com.gabriel.cadapeso.dominio.TipoIngreso
import com.gabriel.cadapeso.dominio.TipoMovimiento
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

/**
 * Respaldo de todos los datos en un archivo JSON (para cambiar de teléfono o no perder nada
 * si desinstalás la app) y exportación de movimientos a CSV (para abrir en Excel).
 * Se escribe a mano con org.json, que ya viene con Android: no hace falta otra librería.
 */
object Respaldo {

    private const val VERSION = 1

    fun exportar(d: DatosFinancieros): String {
        val raiz = JSONObject()
        raiz.put("app", "CadaPeso")
        raiz.put("version", VERSION)
        raiz.put("exportado", LocalDate.now().toString())
        raiz.put("perfil", perfil(d.perfil))
        raiz.put("categorias", JSONArray(d.categorias.map { c ->
            JSONObject().put("id", c.id).put("nombre", c.nombre).put("emoji", c.emoji).put("tipo", c.tipo.name)
                .put("grupo", c.grupo.name).put("recortable", c.recortable).put("fijo", c.fijo)
                .put("palabrasClave", c.palabrasClave).put("color", c.color).put("archivada", c.archivada).put("orden", c.orden)
        }))
        raiz.put("movimientos", JSONArray(d.movimientos.map { m ->
            JSONObject().put("id", m.id).put("fecha", m.fecha.toString()).put("monto", m.monto).put("tipo", m.tipo.name)
                .put("categoriaId", m.categoriaId).put("descripcion", m.descripcion).put("medioPago", m.medioPago.name)
                .put("nota", m.nota).put("metaId", m.metaId ?: JSONObject.NULL).put("deudaId", m.deudaId ?: JSONObject.NULL)
                .put("creadoConIA", m.creadoConIA).put("origen", m.origen.name)
        }))
        raiz.put("asignaciones", JSONArray(d.asignaciones.map { JSONObject().put("mes", it.mes).put("categoriaId", it.categoriaId).put("monto", it.monto) }))
        raiz.put("planes", JSONArray(d.planes.map { JSONObject().put("mes", it.mes).put("ingresoPrevisto", it.ingresoPrevisto) }))
        raiz.put("activos", JSONArray(d.activos.map { a ->
            JSONObject().put("id", a.id).put("nombre", a.nombre).put("tipo", a.tipo.name).put("valor", a.valor).put("liquido", a.liquido)
                .put("generaIngresos", a.generaIngresos).put("esFondoEmergencia", a.esFondoEmergencia).put("actualizado", a.actualizado.toString())
        }))
        raiz.put("deudas", JSONArray(d.deudas.map { x ->
            JSONObject().put("id", x.id).put("nombre", x.nombre).put("tipo", x.tipo.name).put("saldo", x.saldo).put("tasaAnual", x.tasaAnual)
                .put("cuotaMensual", x.cuotaMensual).put("ajustaPorInflacion", x.ajustaPorInflacion)
                .put("rendimientoActivo", x.rendimientoActivo ?: JSONObject.NULL).put("actualizado", x.actualizado.toString())
        }))
        raiz.put("metas", JSONArray(d.metas.map { m ->
            JSONObject().put("id", m.id).put("nombre", m.nombre).put("emoji", m.emoji).put("especifica", m.especifica)
                .put("relevancia", m.relevancia).put("montoObjetivo", m.montoObjetivo).put("montoInicial", m.montoInicial)
                .put("fechaLimite", m.fechaLimite.toString()).put("aporteInicial", m.aporteInicial).put("incremento", m.incremento)
                .put("frecuencia", m.frecuencia.name).put("creada", m.creada.toString()).put("archivada", m.archivada)
        }))
        raiz.put("compras", JSONArray(d.compras.map { c ->
            JSONObject().put("id", c.id).put("descripcion", c.descripcion).put("monto", c.monto).put("categoriaId", c.categoriaId)
                .put("creadaMillis", c.creadaMillis).put("horasEspera", c.horasEspera).put("estado", c.estado.name)
                .put("respuestaLibertad", c.respuestaLibertad).put("respuestaNecesidad", c.respuestaNecesidad)
                .put("comentarioIA", c.comentarioIA).put("resueltaMillis", c.resueltaMillis ?: JSONObject.NULL)
        }))
        raiz.put("eventos", JSONArray(d.eventos.map { e ->
            JSONObject().put("id", e.id).put("nombre", e.nombre).put("monto", e.monto).put("tipo", e.tipo.name)
                .put("mes", e.mes).put("repetirAnual", e.repetirAnual)
        }))
        return raiz.toString(2)
    }

    private fun perfil(p: Perfil) = JSONObject()
        .put("nombre", p.nombre).put("edad", p.edad).put("ingresoMensual", p.ingresoMensual).put("tipoIngreso", p.tipoIngreso.name)
        .put("diaCobro", p.diaCobro).put("moneda", p.moneda).put("regla", p.regla.name)
        .put("porcentajePagatePrimero", p.porcentajePagatePrimero).put("inflacionAnual", p.inflacionAnual)
        .put("umbralTasaMala", p.umbralTasaMala).put("umbralHormiga", p.umbralHormiga).put("umbralImpulso", p.umbralImpulso)
        .put("puntajeConducta", p.puntajeConducta ?: JSONObject.NULL).put("puntajeRiesgo", p.puntajeRiesgo ?: JSONObject.NULL)

    /** Lee un respaldo. Lanza IllegalArgumentException con un mensaje claro si no es válido. */
    fun importar(texto: String): DatosFinancieros {
        val raiz = runCatching { JSONObject(texto) }.getOrElse { throw IllegalArgumentException("El archivo no es un JSON válido") }
        require(raiz.optString("app") == "CadaPeso") { "El archivo no es un respaldo de CadaPeso" }
        val p = raiz.optJSONObject("perfil") ?: JSONObject()
        return DatosFinancieros(
            perfil = Perfil(
                nombre = p.optString("nombre"), edad = p.optInt("edad", 30), ingresoMensual = p.optDouble("ingresoMensual", 0.0),
                tipoIngreso = enumDe(p.optString("tipoIngreso"), TipoIngreso.ESTABLE), diaCobro = p.optInt("diaCobro", 1),
                moneda = p.optString("moneda", "$"), regla = enumDe(p.optString("regla"), ReglaPresupuesto.R_50_30_20),
                porcentajePagatePrimero = p.optDouble("porcentajePagatePrimero", 10.0), inflacionAnual = p.optDouble("inflacionAnual", 0.0),
                umbralTasaMala = p.optDouble("umbralTasaMala", 16.0), umbralHormiga = p.optDouble("umbralHormiga", 0.0),
                umbralImpulso = p.optDouble("umbralImpulso", 0.0), puntajeConducta = p.intONull("puntajeConducta"),
                puntajeRiesgo = p.intONull("puntajeRiesgo"), onboardingCompleto = true,
            ),
            categorias = raiz.lista("categorias") { o ->
                Categoria(
                    id = o.getLong("id"), nombre = o.getString("nombre"), emoji = o.optString("emoji", "📦"),
                    tipo = enumDe(o.optString("tipo"), TipoMovimiento.GASTO), grupo = enumDe(o.optString("grupo"), GrupoGasto.DESEO),
                    recortable = o.optBoolean("recortable"), fijo = o.optBoolean("fijo"), palabrasClave = o.optString("palabrasClave"),
                    color = o.optLong("color", 0xFF607D8BL), archivada = o.optBoolean("archivada"), orden = o.optInt("orden"),
                )
            },
            movimientos = raiz.lista("movimientos") { o ->
                Movimiento(
                    id = o.getLong("id"), fecha = LocalDate.parse(o.getString("fecha")), monto = o.getDouble("monto"),
                    tipo = enumDe(o.optString("tipo"), TipoMovimiento.GASTO), categoriaId = o.getLong("categoriaId"),
                    descripcion = o.optString("descripcion"), medioPago = enumDe(o.optString("medioPago"), MedioPago.DEBITO),
                    nota = o.optString("nota"), metaId = o.longONull("metaId"), deudaId = o.longONull("deudaId"),
                    creadoConIA = o.optBoolean("creadoConIA"), origen = enumDe(o.optString("origen"), OrigenMovimiento.MANUAL),
                )
            },
            asignaciones = raiz.lista("asignaciones") { o -> Asignacion(o.getInt("mes"), o.getLong("categoriaId"), o.getDouble("monto")) },
            planes = raiz.lista("planes") { o -> PlanMensual(o.getInt("mes"), o.getDouble("ingresoPrevisto")) },
            activos = raiz.lista("activos") { o ->
                Activo(
                    id = o.getLong("id"), nombre = o.getString("nombre"), tipo = enumDe(o.optString("tipo"), TipoActivo.OTRO),
                    valor = o.getDouble("valor"), liquido = o.optBoolean("liquido"), generaIngresos = o.optBoolean("generaIngresos"),
                    esFondoEmergencia = o.optBoolean("esFondoEmergencia"), actualizado = fecha(o, "actualizado"),
                )
            },
            deudas = raiz.lista("deudas") { o ->
                Deuda(
                    id = o.getLong("id"), nombre = o.getString("nombre"), tipo = enumDe(o.optString("tipo"), TipoDeuda.OTRO),
                    saldo = o.getDouble("saldo"), tasaAnual = o.optDouble("tasaAnual", 0.0), cuotaMensual = o.optDouble("cuotaMensual", 0.0),
                    ajustaPorInflacion = o.optBoolean("ajustaPorInflacion"), rendimientoActivo = o.doubleONull("rendimientoActivo"),
                    actualizado = fecha(o, "actualizado"),
                )
            },
            metas = raiz.lista("metas") { o ->
                Meta(
                    id = o.getLong("id"), nombre = o.getString("nombre"), emoji = o.optString("emoji", "🎯"),
                    especifica = o.optString("especifica"), relevancia = o.optString("relevancia"),
                    montoObjetivo = o.getDouble("montoObjetivo"), montoInicial = o.optDouble("montoInicial", 0.0),
                    fechaLimite = fecha(o, "fechaLimite"), aporteInicial = o.optDouble("aporteInicial", 0.0),
                    incremento = o.optDouble("incremento", 0.0), frecuencia = enumDe(o.optString("frecuencia"), FrecuenciaAhorro.MENSUAL),
                    creada = fecha(o, "creada"), archivada = o.optBoolean("archivada"),
                )
            },
            compras = raiz.lista("compras") { o ->
                CompraEnEspera(
                    id = o.getLong("id"), descripcion = o.getString("descripcion"), monto = o.getDouble("monto"),
                    categoriaId = o.getLong("categoriaId"), creadaMillis = o.getLong("creadaMillis"), horasEspera = o.optInt("horasEspera", 24),
                    estado = enumDe(o.optString("estado"), EstadoCompra.EN_ESPERA), respuestaLibertad = o.optString("respuestaLibertad"),
                    respuestaNecesidad = o.optString("respuestaNecesidad"), comentarioIA = o.optString("comentarioIA"),
                    resueltaMillis = o.longONull("resueltaMillis"),
                )
            },
            eventos = raiz.lista("eventos") { o ->
                EventoPlanificado(
                    id = o.getLong("id"), nombre = o.getString("nombre"), monto = o.getDouble("monto"),
                    tipo = enumDe(o.optString("tipo"), TipoMovimiento.GASTO), mes = o.getInt("mes"), repetirAnual = o.optBoolean("repetirAnual"),
                )
            },
        )
    }

    /** Movimientos en CSV con punto y coma y coma decimal (lo que espera Excel en español). */
    fun csvMovimientos(d: DatosFinancieros): String {
        val sb = StringBuilder("﻿") // BOM: Excel reconoce los acentos
        sb.append("Fecha;Tipo;Monto;Categoría;Grupo;Descripción;Medio de pago;Nota;Origen\n")
        for (m in d.movimientos.sortedBy { it.fecha }) {
            val c = d.categoria(m.categoriaId)
            sb.append(m.fecha).append(';')
                .append(m.tipo.etiqueta).append(';')
                .append(String.format(Locale.ROOT, "%.2f", m.monto).replace('.', ',')).append(';')
                .append(csv(c?.nombre ?: "")).append(';')
                .append(csv(c?.grupo?.etiqueta ?: "")).append(';')
                .append(csv(m.descripcion)).append(';')
                .append(m.medioPago.etiqueta).append(';')
                .append(csv(m.nota)).append(';')
                .append(m.origen.etiqueta).append('\n')
        }
        return sb.toString()
    }

    private fun csv(s: String): String = if (s.any { it == ';' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    private inline fun <T> JSONObject.lista(nombre: String, convertir: (JSONObject) -> T): List<T> {
        val arreglo = optJSONArray(nombre) ?: return emptyList()
        return (0 until arreglo.length()).map { convertir(arreglo.getJSONObject(it)) }
    }

    private inline fun <reified E : Enum<E>> enumDe(nombre: String?, porDefecto: E): E =
        runCatching { enumValueOf<E>(nombre ?: "") }.getOrDefault(porDefecto)

    private fun JSONObject.longONull(k: String): Long? = if (isNull(k) || !has(k)) null else optLong(k)
    private fun JSONObject.intONull(k: String): Int? = if (isNull(k) || !has(k)) null else optInt(k)
    private fun JSONObject.doubleONull(k: String): Double? = if (isNull(k) || !has(k)) null else optDouble(k)
    private fun fecha(o: JSONObject, k: String): LocalDate = runCatching { LocalDate.parse(o.getString(k)) }.getOrDefault(LocalDate.now())
}

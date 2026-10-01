package com.gabriel.cadapeso.datos

import com.gabriel.cadapeso.dominio.GrupoGasto
import com.gabriel.cadapeso.dominio.TipoMovimiento

/**
 * Categorías que se crean la primera vez que se abre la app. Las palabras clave permiten
 * reconocer la categoría al escribir "gasté 4500 en el chino" o "nafta 20 lucas".
 * Ojo con las claves ambiguas: "factura" no está en Café porque también es "factura de luz".
 */
object CategoriasBase {

    private fun gasto(
        nombre: String, emoji: String, grupo: GrupoGasto, recortable: Boolean, fijo: Boolean,
        claves: String, color: Long, orden: Int,
    ) = Categoria(
        nombre = nombre, emoji = emoji, tipo = TipoMovimiento.GASTO, grupo = grupo, recortable = recortable,
        fijo = fijo, palabrasClave = claves, color = color, orden = orden,
    )

    private fun ingreso(nombre: String, emoji: String, claves: String, color: Long, orden: Int) = Categoria(
        nombre = nombre, emoji = emoji, tipo = TipoMovimiento.INGRESO, grupo = GrupoGasto.INGRESO, recortable = false,
        fijo = false, palabrasClave = claves, color = color, orden = orden,
    )

    fun lista(): List<Categoria> = listOf(
        // ---- Necesidades -------------------------------------------------------------
        gasto("Vivienda", "🏠", GrupoGasto.NECESIDAD, false, true,
            "alquiler,expensas,hipoteca,inmobiliaria,plomero,electricista,gasista,arreglo casa", 0xFF5C6BC0, 1),
        gasto("Servicios", "💡", GrupoGasto.NECESIDAD, false, true,
            "luz,gas,agua,internet,celular,telefono,cable,edenor,edesur,epec,ecogas,metrogas,naturgy,aysa,fibertel,telecentro,claro,movistar,flow,directv,wifi,factura de luz,factura de gas",
            0xFF26A69A, 2),
        gasto("Supermercado", "🛒", GrupoGasto.NECESIDAD, false, false,
            "super,supermercado,almacen,verduleria,carniceria,chino,coto,carrefour,disco,jumbo,vea,changomas,walmart,mayorista,makro,vital,diarco,dietetica,panaderia,fiambreria,mercaderia,compras del mes",
            0xFF66BB6A, 3),
        gasto("Transporte", "🚌", GrupoGasto.NECESIDAD, false, false,
            "nafta,combustible,ypf,shell,axion,puma,gnc,sube,colectivo,bondi,subte,tren,uber,cabify,didi,taxi,remis,peaje,estacionamiento,cochera,mecanico,service del auto,gomeria",
            0xFF29B6F6, 4),
        gasto("Salud", "💊", GrupoGasto.NECESIDAD, false, false,
            "farmacia,medico,obra social,prepaga,osde,swiss medical,galeno,dentista,odontologo,remedio,medicamento,psicologo,kinesiologo,optica,consulta medica,turno medico",
            0xFFEF5350, 5),
        gasto("Impuestos", "🧾", GrupoGasto.NECESIDAD, false, true,
            "impuesto,afip,arca,monotributo,abl,patente,inmobiliario,ingresos brutos,rentas,ganancias,bienes personales,autonomos",
            0xFF8D6E63, 6),
        gasto("Colegio y estudios", "🏫", GrupoGasto.NECESIDAD, false, true,
            "colegio,escuela,jardin,universidad,facultad,matricula,cuota del colegio,utiles,apuntes",
            0xFF7E57C2, 7),
        gasto("Mascotas", "🐾", GrupoGasto.NECESIDAD, false, false,
            "veterinaria,veterinario,balanceado,alimento para perro,alimento para gato,mascota,piedras sanitarias",
            0xFFA1887F, 8),
        // ---- Deseos -----------------------------------------------------------------
        gasto("Comida afuera y delivery", "🍔", GrupoGasto.DESEO, true, false,
            "restaurante,resto,restoran,bar,cerveceria,delivery,pedidosya,pedidos ya,rappi,mcdonald,burger,pizza,pizzeria,empanadas,sushi,hamburguesa,parrilla,almuerzo,cena,merienda,brunch,lomito,sandwich,heladeria,helado",
            0xFFFFA726, 10),
        gasto("Café y kiosco", "☕", GrupoGasto.DESEO, true, false,
            "cafe,cafeteria,starbucks,havanna,martinez,kiosco,golosina,alfajor,gaseosa,coca,chicle,cigarrillos,puchos,snack,medialuna,mate cocido",
            0xFF8D6E63, 11),
        gasto("Ocio y salidas", "🎬", GrupoGasto.DESEO, true, false,
            "cine,teatro,recital,show,entrada,boliche,salida,juego,steam,playstation,xbox,nintendo,futbol,cancha,padel,paddle,escapada,museo,bowling,parque",
            0xFFEC407A, 12),
        gasto("Suscripciones", "📺", GrupoGasto.DESEO, true, true,
            "netflix,spotify,disney,hbo,max,prime video,amazon prime,youtube premium,youtube,icloud,google one,chatgpt,apple music,paramount,star plus,crunchyroll,deezer,suscripcion,membresia,gimnasio,gym",
            0xFFAB47BC, 13),
        gasto("Ropa y compras", "👕", GrupoGasto.DESEO, true, false,
            "ropa,zapatillas,zapatos,remera,pantalon,jean,campera,buzo,shopping,mercadolibre,mercado libre,tienda,shein,temu,amazon,accesorios,perfume,maquillaje,peluqueria,electronica,celular nuevo",
            0xFF42A5F5, 14),
        gasto("Regalos", "🎁", GrupoGasto.DESEO, true, false,
            "regalo,cumpleanos,cumple,obsequio,donacion", 0xFFFF7043, 15),
        gasto("Otros gastos", "📦", GrupoGasto.DESEO, true, false,
            "varios,otros", 0xFF90A4AE, 19),
        // ---- Educación financiera -------------------------------------------------------
        gasto("Formación", "📚", GrupoGasto.EDUCACION, true, false,
            "curso,libro,capacitacion,taller,udemy,coursera,platzi,seminario,certificacion,clase,ingles,idioma,educacion financiera",
            0xFF5E35B1, 20),
        // ---- Deudas y ahorro -----------------------------------------------------------
        gasto("Pago de deudas", "💳", GrupoGasto.DEUDA, false, true,
            "resumen,cuota del prestamo,prestamo,pago de tarjeta,refinanciacion,deuda", 0xFF78909C, 30),
        gasto("Ahorro e inversión", "🐷", GrupoGasto.AHORRO, false, true,
            "ahorro,ahorre,plazo fijo,fci,fondo comun,inversion,invertir,compre dolares,dolar mep,mep,cedear,acciones,bonos,cripto,bitcoin,usdt,fondo de emergencia,colchon",
            0xFF2E7D32, 40),
        // ---- Ingresos ----------------------------------------------------------------
        ingreso("Sueldo", "💼", "sueldo,salario,haberes,aguinaldo,sac,bono,quincena,recibo de sueldo", 0xFF43A047, 50),
        ingreso("Trabajo independiente", "🧑‍💻", "honorarios,honorario,freelance,cliente,proyecto,factura,changa,venta,vendi,comision,trabajo", 0xFF00897B, 51),
        ingreso("Rendimientos", "📈", "intereses,rendimiento,dividendos,plazo fijo,cupon,renta,alquiler cobrado", 0xFF1E88E5, 52),
        ingreso("Otros ingresos", "➕", "reintegro,devolucion,premio,regalo,otros", 0xFF8E24AA, 59),
    )
}

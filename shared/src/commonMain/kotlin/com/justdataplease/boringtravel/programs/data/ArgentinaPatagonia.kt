package com.justdataplease.boringtravel.programs.data

import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.model.Vehicle.Bike
import com.justdataplease.boringtravel.model.Vehicle.Car
import com.justdataplease.boringtravel.model.Vehicle.Ferry
import com.justdataplease.boringtravel.model.Vehicle.Plane
import com.justdataplease.boringtravel.model.Vehicle.Train
import com.justdataplease.boringtravel.model.Vehicle.Walk
import com.justdataplease.boringtravel.programs.Destination
import com.justdataplease.boringtravel.programs.Focus
import com.justdataplease.boringtravel.programs.Pace
import com.justdataplease.boringtravel.programs.Program
import com.justdataplease.boringtravel.programs.ProgramDay
import com.justdataplease.boringtravel.programs.ProgramItem

/**
 * Argentina & Patagonia: three weeks from Buenos Aires (Ezeiza) to Santiago de Chile, the demo trip's route
 * (data/SampleArgentina.kt) without the Torres del Paine trek: Buenos Aires, Mendoza (with two nights in the Valle de
 * Uco for Eat & drink), Bariloche, El Chaltén, El Calafate and the Perito Moreno Glacier, then a flight to Santiago and
 * home from there. Each focus spends its days where it matters: Sights & history has five days in Buenos Aires and
 * three in El Calafate, Eat & drink five in wine country, Nature & views five in Bariloche and five under Fitz Roy.
 *
 * Points are from Wikipedia, Wikidata and OpenStreetMap; opening days, seasons and bookings were checked in November
 * 2026 (notes in research/boringtravel/programs/argentina-patagonia.md). All text is our own.
 */
object ArgentinaPatagonia {
    private const val ID = "argentina-patagonia"
    private val B = Pace.Balanced
    private val E = Pace.Energetic

    // Day bases (the route line: "Buenos Aires → Mendoza → … → Santiago").
    private const val BA = "Buenos Aires"
    private const val MENDOZA = "Mendoza"
    private const val UCO = "Valle de Uco"
    private const val BARILOCHE = "Bariloche"
    private const val CHALTEN = "El Chaltén"
    private const val CALAFATE = "El Calafate"
    private const val SANTIAGO = "Santiago"

    /**
     * A real place: the name for the place line, its point and its website (its own, English where there is one, else
     * the city, province or national tourism board's page for it; null for areas, streets, stays and places with
     * neither). Links checked in November 2026 (research/boringtravel/programs/argentina-patagonia.md, "Links").
     */
    private class Spot(val name: String, val lat: Double, val lng: Double, val url: String? = null)

    // ---- Tourism boards (each page is about the place it is used for) ----
    private const val BA_EN = "https://turismo.buenosaires.gob.ar/en/otros-establecimientos/"
    private const val BA_ES = "https://turismo.buenosaires.gob.ar/es/"
    private const val MZA = "https://mendoza.tur.ar/"
    private const val BRC_EN = "https://barilocheturismo.gob.ar/en/"
    private const val CHALTEN_TUR = "https://elchalten.tur.ar/"
    private const val CALAFATE_TUR = "https://www.elcalafate.tur.ar/"
    private const val CHILE_EN = "https://chile.travel/en/"

    // ---- Airports and stations ----
    private val eze = Spot("Ezeiza International Airport, Buenos Aires, Argentina", -34.82222, -58.53583, "https://www.aeropuertosargentina.com/en/EZE")
    private val mdz = Spot("Mendoza Airport, Mendoza, Argentina", -32.83167, -68.79278, "https://www.aeropuertosargentina.com/en/MDZ")
    private val brc = Spot("Bariloche Airport, Bariloche, Argentina", -41.15111, -71.1575, "https://www.aeropuertosargentina.com/en/BRC")
    private val fte = Spot("El Calafate Airport, El Calafate, Argentina", -50.28028, -72.05333, "https://www.aeropuertoelcalafate.com/en/")
    private val scl = Spot("Santiago Airport, Santiago, Chile", -33.39278, -70.78556, "https://www.aeropuertosantiagodechile.cl/en")
    private val tigreStation = Spot("Tigre Station, Tigre, Argentina", -34.42299, -58.58028)
    private val chaltenTerminal = Spot("Bus terminal, El Chaltén, Argentina", -49.33364, -72.88289)
    private val calafateTerminal = Spot("Bus terminal, El Calafate, Argentina", -50.33938, -72.26499)

    // ---- Buenos Aires: areas for stays ----
    private val recoleta = Spot("Recoleta, Buenos Aires, Argentina", -34.59056, -58.39056)
    private val palermoSoho = Spot("Plaza Serrano, Palermo Soho, Buenos Aires, Argentina", -34.58879, -58.43011)

    // ---- Buenos Aires: centre and Retiro ----
    private val plazaDeMayo = Spot("Plaza de Mayo, Buenos Aires, Argentina", -34.60833, -58.37194, BA_EN + "plaza-de-mayo")
    private val catedralBa = Spot("Metropolitan Cathedral, Buenos Aires, Argentina", -34.60741, -58.37328, BA_EN + "metropolitan-cathedral")
    private val cabildo = Spot("Cabildo, Buenos Aires, Argentina", -34.60889, -58.37361, BA_EN + "cabildo")
    private val manzana = Spot("Manzana de las Luces, Buenos Aires, Argentina", -34.61056, -58.37444, BA_EN + "manzana-de-las-luces")
    private val tortoni = Spot("Café Tortoni, Buenos Aires, Argentina", -34.60869, -58.37814, BA_ES + "gastronomico/caf%C3%A9-tortoni")
    private val barolo = Spot("Palacio Barolo, Buenos Aires, Argentina", -34.60956, -58.38586, "https://palaciobarolo.com.ar/")
    private val congreso = Spot("National Congress, Buenos Aires, Argentina", -34.60965, -58.39258, "https://www.senado.gob.ar/visitasguiadas")
    private val colon = Spot("Teatro Colón, Buenos Aires, Argentina", -34.60108, -58.38308, "https://teatrocolon.org.ar/guided-tours/")
    private val obelisco = Spot("Obelisco, Buenos Aires, Argentina", -34.60361, -58.38167, BA_ES + "article/mirador-obelisco")
    private val guerrin = Spot("Pizzería Güerrín, Buenos Aires, Argentina", -34.6041, -58.38599, BA_ES + "gastronomico/pizzer%C3%ADa-g%C3%BCerr%C3%ADn")
    private val cadore = Spot("Cadore, Buenos Aires, Argentina", -34.60417, -58.39065)
    private val elCuartito = Spot("El Cuartito, Buenos Aires, Argentina", -34.59782, -58.38547, BA_ES + "otros-establecimientos/el-cuartito")
    private val kavanagh = Spot("Edificio Kavanagh, Retiro, Buenos Aires, Argentina", -34.59532, -58.3746, BA_EN + "kavanagh-building")
    private val floreria = Spot("Florería Atlántico, Retiro, Buenos Aires, Argentina", -34.59182, -58.37934, "https://floreriaatlantico.com.ar/")

    // ---- Buenos Aires: Recoleta and Palermo ----
    private val recoletaCemetery = Spot("Recoleta Cemetery, Buenos Aires, Argentina", -34.58806, -58.39306, BA_EN + "recoleta-cemetery")
    private val pilar = Spot("Basílica del Pilar, Buenos Aires, Argentina", -34.5868, -58.392, BA_EN + "bas%C3%ADlica-de-nuestra-se%C3%B1ora-del-pilar")
    private val laBiela = Spot("La Biela, Recoleta, Buenos Aires, Argentina", -34.58784, -58.39085, "https://labiela.com/")
    private val floralis = Spot("Floralis Genérica, Buenos Aires, Argentina", -34.58167, -58.39389, BA_EN + "floralis-gen%C3%A9rica")
    private val ateneo = Spot("El Ateneo Grand Splendid, Buenos Aires, Argentina", -34.59598, -58.39426, BA_EN + "el-ateneo-grand-splendid-bookstore")
    private val mnba = Spot("Museo Nacional de Bellas Artes, Buenos Aires, Argentina", -34.58399, -58.39297, BA_EN + "museo-nacional-de-bellas-artes-fine-arts-museum")
    private val mnad = Spot("Museo Nacional de Arte Decorativo, Buenos Aires, Argentina", -34.58261, -58.40108, BA_EN + "national-museum-decorative-art")
    private val malba = Spot("MALBA, Buenos Aires, Argentina", -34.57697, -58.40339, "https://malba.org.ar/en/")
    private val evita = Spot("Museo Evita, Buenos Aires, Argentina", -34.5806, -58.4148, "https://museoevita.org.ar/")
    private val jardinJapones = Spot("Jardín Japonés, Buenos Aires, Argentina", -34.57514, -58.40926, "https://jardinjapones.org.ar/en/home/")
    private val rosedal = Spot("Rosedal, Palermo, Buenos Aires, Argentina", -34.57063, -58.41729, BA_EN + "palermo-rose-garden")
    private val bosques = Spot("Parque Tres de Febrero, Buenos Aires, Argentina", -34.57333, -58.41472, BA_EN + "parque-tres-de-febrero")
    private val botanico = Spot("Jardín Botánico, Palermo, Buenos Aires, Argentina", -34.58259, -58.41726, BA_EN + "botanical-garden")
    private val planetario = Spot("Planetario Galileo Galilei, Buenos Aires, Argentina", -34.56965, -58.41172, BA_EN + "galileo-galilei-planetarium")
    private val esma = Spot("ESMA Museum and Site of Memory, Buenos Aires, Argentina", -34.53663, -58.46547, "https://www.argentina.gob.ar/derechoshumanos/museo-sitio-de-memoria-esma/en")
    private val donJulio = Spot("Don Julio, Palermo, Buenos Aires, Argentina", -34.58629, -58.42428, "https://www.parrilladonjulio.com/")
    private val preferido = Spot("El Preferido de Palermo, Buenos Aires, Argentina", -34.58544, -58.42539, BA_ES + "atractivo/el-preferido-de-palermo")
    private val lasVioletas = Spot("Las Violetas, Almagro, Buenos Aires, Argentina", -34.61117, -58.42086, BA_ES + "gastronomico/las-violetas")

    // ---- Buenos Aires: San Telmo, La Boca, the river ----
    private val dorrego = Spot("Plaza Dorrego, San Telmo, Buenos Aires, Argentina", -34.62056, -58.37167, BA_EN + "plaza-dorrego")
    private val zanjon = Spot("El Zanjón de Granados, San Telmo, Buenos Aires, Argentina", -34.61669, -58.3718, "https://elzanjon.com.ar/")
    private val mercadoSanTelmo = Spot("Mercado de San Telmo, Buenos Aires, Argentina", -34.61905, -58.37251, BA_EN + "san-telmo-market")
    private val lezama = Spot("Museo Histórico Nacional, Parque Lezama, Buenos Aires, Argentina", -34.62694, -58.37056, BA_EN + "parque-lezama")
    private val querandi = Spot("El Querandí, San Telmo, Buenos Aires, Argentina", -34.61177, -58.37466, "https://querandi.com.ar/en/")
    private val caminito = Spot("Caminito, La Boca, Buenos Aires, Argentina", -34.6393, -58.3628, BA_EN + "caminito")
    private val quinquela = Spot("Museo Benito Quinquela Martín, La Boca, Buenos Aires, Argentina", -34.6393, -58.3615, BA_EN + "quinquela-mart%C3%ADn-museum-fine-arts")
    private val proa = Spot("Fundación Proa, La Boca, Buenos Aires, Argentina", -34.6397, -58.362, "https://proa.org/eng/")
    private val elObrero = Spot("El Obrero, La Boca, Buenos Aires, Argentina", -34.62968, -58.35683)
    private val banchero = Spot("Banchero, La Boca, Buenos Aires, Argentina", -34.63534, -58.35852)
    private val parqueLezama = Spot("Parque Lezama, Buenos Aires, Argentina", -34.62694, -58.37056, BA_EN + "parque-lezama")
    private val reserva = Spot("Reserva Ecológica Costanera Sur, Buenos Aires, Argentina", -34.60389, -58.3525, BA_EN + "ecological-reserve")
    private val costaneraSur = Spot("Costanera Sur, Buenos Aires, Argentina", -34.61601, -58.35684, BA_EN + "costanera-sur-southern-riverside")
    private val puenteMujer = Spot("Puente de la Mujer, Puerto Madero, Buenos Aires, Argentina", -34.60794, -58.36491, BA_EN + "puente-de-la-mujer")
    private val mataderos = Spot("Feria de Mataderos, Buenos Aires, Argentina", -34.66239, -58.50003, "https://buenosaires.gob.ar/feriademataderos")

    // ---- Around Buenos Aires ----
    private val tigreDelta = Spot("Estación Fluvial, Tigre, Argentina", -34.42201, -58.58028, "https://vivitigre.gob.ar/en/home/")
    private val puertoFrutos = Spot("Puerto de Frutos, Tigre, Argentina", -34.41892, -58.57257, "https://vivitigre.gob.ar/en/actividades/puerto-de-frutos-en/")
    private val mat = Spot("Museo de Arte Tigre, Tigre, Argentina", -34.40923, -58.59104, "https://www.mat.gov.ar/")
    private val areco = Spot("San Antonio de Areco, Buenos Aires Province, Argentina", -34.25, -59.46667, "https://www.argentina.travel/actividades/san-antonio-de-areco")

    // ---- Mendoza city ----
    private val plazaIndependencia = Spot("Plaza Independencia, Mendoza, Argentina", -32.88948, -68.84478, MZA + "destino/ciudad-de-mendoza/")
    private val plazaEspana = Spot("Plaza España, Mendoza, Argentina", -32.89306, -68.84222, MZA + "destino/ciudad-de-mendoza/")
    private val areaFundacional = Spot("Museo del Área Fundacional, Mendoza, Argentina", -32.87977, -68.82797, MZA + "destino/area-fundacional/")
    private val ruinasSanFrancisco = Spot("Ruinas de San Francisco, Mendoza, Argentina", -32.87873, -68.82986, MZA + "destino/area-fundacional/")
    private val villanueva = Spot("Arístides Villanueva, Mendoza, Argentina", -32.89201, -68.85654)
    private val mercadoMendoza = Spot("Mercado Central, Mendoza, Argentina", -32.88513, -68.84164, MZA + "lugar/mercado-central/")
    private val parqueSanMartin = Spot("Parque General San Martín, Mendoza, Argentina", -32.89036, -68.87121, MZA + "destino/parque-general-san-martin/")
    private val cerroGloria = Spot("Cerro de la Gloria, Mendoza, Argentina", -32.8886, -68.8858, MZA + "destino/parque-general-san-martin/")

    // ---- Mendoza wine country: Maipú and Luján de Cuyo ----
    private val maipuBikes = Spot("Urquiza street, Coquimbito, Maipú, Argentina", -32.96257, -68.7557, MZA + "destino/maipu/")
    private val laRural = Spot("Bodega La Rural, Maipú, Argentina", -32.95786, -68.74556, "https://bodegalarural.com.ar/en/")
    private val trapiche = Spot("Trapiche, Maipú, Argentina", -32.9721, -68.74758, MZA + "lugar/bodega-trapiche/")
    private val laur = Spot("Olivícola Laur, Maipú, Argentina", -33.03297, -68.76962, MZA + "lugar/olivicola-laur-2/")
    private val lopez = Spot("Bodegas López, Maipú, Argentina", -32.96325, -68.7838, "https://experiencias.bodegaslopez.com.ar/")
    private val catena = Spot("Bodega Catena Zapata, Luján de Cuyo, Argentina", -33.16396, -68.91939, "https://catenazapata.com/")
    private val norton = Spot("Bodega Norton, Luján de Cuyo, Argentina", -33.08689, -68.89119, "https://www.norton.com.ar/en/tourism/")
    private val lagarde = Spot("Bodega Lagarde, Luján de Cuyo, Argentina", -33.02316, -68.87332, "https://lagarde.com.ar/visitas-degustaciones/")
    private val fader = Spot("Museo Casa de Fader, Luján de Cuyo, Argentina", -33.00509, -68.86652, MZA + "lugar/museo-provincial-de-bellas-artes-emiliano-guinazu-casa-de-fader/")

    // ---- Valle de Uco ----
    private val uco = Spot("Valle de Uco, Mendoza, Argentina", -33.62, -69.2)
    private val bousquet = Spot("Domaine Bousquet, Tupungato, Argentina", -33.42924, -69.19851, "https://domainebousquet.com/en/")
    private val andeluna = Spot("Andeluna, Tupungato, Argentina", -33.45251, -69.22338, "https://www.andeluna.com.ar/en/")
    private val salentein = Spot("Bodegas Salentein, Tunuyán, Argentina", -33.49847, -69.25215, MZA + "lugar/bodega-salentein/")
    private val zuccardi = Spot("Zuccardi Valle de Uco, San Carlos, Argentina", -33.77029, -69.15864, "https://zuccardiwines.com/en/turismo/")
    private val manzano = Spot("Manzano Histórico, Tunuyán, Argentina", -33.6037, -69.38223, MZA + "destino/tunuyan/")

    // ---- The Andes road (Ruta 7) and the foothills ----
    private val potrerillos = Spot("Potrerillos Dam, Mendoza, Argentina", -32.995, -69.12583, MZA + "destino/precordillera-y-alta-montana/")
    private val uspallata = Spot("Uspallata, Mendoza, Argentina", -32.59093, -69.34796)
    private val puenteInca = Spot("Puente del Inca, Mendoza, Argentina", -32.82662, -69.9111, MZA + "destino/precordillera-y-alta-montana/")
    private val horcones = Spot("Laguna de Horcones, Aconcagua Park, Mendoza, Argentina", -32.80563, -69.94322, MZA + "destino/cerro-aconcagua/")
    private val confluencia = Spot("Confluencia, Aconcagua Park, Mendoza, Argentina", -32.75825, -69.96723, MZA + "destino/cerro-aconcagua/")
    private val cristo = Spot("Cristo Redentor de los Andes, Mendoza, Argentina", -32.82528, -70.07075, MZA + "lugar/cristo-redentor-de-los-andes/")
    private val cacheuta = Spot("Termas de Cacheuta, Luján de Cuyo, Argentina", -33.01338, -69.11689, "https://termascacheuta.com/")
    private val cerroArco = Spot("Cerro Arco, Mendoza, Argentina", -32.83609, -68.93473)
    private val villavicencio = Spot("Reserva Natural Villavicencio, Mendoza, Argentina", -32.52694, -69.01833, "https://rnvillavicencio.com.ar/")

    // ---- Bariloche: town ----
    private val centroCivico = Spot("Centro Cívico, Bariloche, Argentina", -41.13343, -71.30953, BRC_EN + "civic-centre")
    private val museoPatagonia = Spot("Museo de la Patagonia, Bariloche, Argentina", -41.13325, -71.3095, BRC_EN + "civic-centre")
    private val catedralBariloche = Spot("Bariloche Cathedral, Bariloche, Argentina", -41.1328, -71.3028)
    private val mitre = Spot("Calle Mitre, Bariloche, Argentina", -41.13385, -71.30565)
    private val rapanui = Spot("Rapanui, Calle Mitre, Bariloche, Argentina", -41.13372, -71.30681, "https://www.rapanui.com.ar/")
    private val ottoTop = Spot("Cerro Otto, Bariloche, Argentina", -41.14466, -71.37582, "https://www.telefericobariloche.com.ar/en")
    private val ottoBase = Spot("Cerro Otto cable car base, Bariloche, Argentina", -41.12911, -71.36934, BRC_EN + "mount-otto")
    private val playaBonita = Spot("Playa Bonita, Bariloche, Argentina", -41.12042, -71.39475)

    // ---- Bariloche: the Circuito Chico and the lake ----
    private val berlina = Spot("Cervecería Berlina, Bariloche, Argentina", -41.10823, -71.43484, "https://cervezaberlina.com/")
    private val campanario = Spot("Cerro Campanario chairlift, Bariloche, Argentina", -41.07306, -71.4759, "https://cerrocampanario.com.ar/la-aerosilla/")
    private val coloniaSuiza = Spot("Colonia Suiza, Bariloche, Argentina", -41.095, -71.50611, BRC_EN + "colonia-suiza")
    private val llaoLlao = Spot("Llao Llao Hotel, Bariloche, Argentina", -41.0577, -71.53112, "https://www.llaollao.com/")
    private val sanEduardo = Spot("Capilla San Eduardo, Bariloche, Argentina", -41.05468, -71.5277)
    private val puertoPanuelo = Spot("Puerto Pañuelo, Bariloche, Argentina", -41.05228, -71.531, BRC_EN + "victoria-island-and-myrtle-woods")
    private val cerroLlaoLlao = Spot("Parque Municipal Llao Llao, Bariloche, Argentina", -41.04966, -71.54473, BRC_EN + "short-circuit")
    private val escondido = Spot("Lago Escondido, Circuito Chico, Bariloche, Argentina", -41.06058, -71.56781, BRC_EN + "short-circuit")
    private val bahiaLopez = Spot("Bahía López, Bariloche, Argentina", -41.07406, -71.56759)
    private val patagoniaBrewery = Spot("Cervecería Patagonia, Circuito Chico, Bariloche, Argentina", -41.07018, -71.53813)
    private val puntoPanoramico = Spot("Punto Panorámico, Circuito Chico, Bariloche, Argentina", -41.07175, -71.52239, BRC_EN + "short-circuit")
    private val arrayanes = Spot("Bosque de Arrayanes, Villa La Angostura, Argentina", -40.85951, -71.61934, BRC_EN + "victoria-island-and-myrtle-woods")
    private val islaVictoria = Spot("Isla Victoria, Lake Nahuel Huapi, Argentina", -40.9531, -71.5381, BRC_EN + "victoria-island-and-myrtle-woods")

    // ---- Bariloche: mountains ----
    private val catedralBase = Spot("Cerro Catedral, Bariloche, Argentina", -41.16959, -71.44401, BRC_EN + "mount-catedral")
    private val frey = Spot("Refugio Frey, Bariloche, Argentina", -41.19824, -71.48625, BRC_EN + "frey-hut")
    private val gutierrez = Spot("Lago Gutiérrez, Bariloche, Argentina", -41.18667, -71.41028)
    private val pampaLinda = Spot("Pampa Linda, Nahuel Huapi National Park, Argentina", -41.23057, -71.7732, BRC_EN + "mount-tronador")
    private val ventisquero = Spot("Ventisquero Negro, Nahuel Huapi National Park, Argentina", -41.20234, -71.82758, BRC_EN + "mount-tronador")
    private val castanoOvera = Spot("Castaño Overa lookout, Nahuel Huapi National Park, Argentina", -41.18932, -71.82186, BRC_EN + "mount-tronador")
    private val mascardi = Spot("Lago Mascardi, Nahuel Huapi National Park, Argentina", -41.30894, -71.57306, BRC_EN + "mount-tronador")

    // ---- El Chaltén ----
    private val chalten = Spot("El Chaltén, Argentina", -49.33141, -72.88613)
    private val visitorCentre = Spot("Visitor centre, Los Glaciares National Park, El Chaltén, Argentina", -49.33754, -72.88136, "https://www.argentina.gob.ar/parquesnacionales/losglaciares")
    private val condores = Spot("Mirador de los Cóndores, El Chaltén, Argentina", -49.33952, -72.88036, CHALTEN_TUR + "mirador-de-los-condores-y-aguilas/")
    private val aguilas = Spot("Mirador de las Águilas, El Chaltén, Argentina", -49.35033, -72.87099, CHALTEN_TUR + "mirador-de-los-condores-y-aguilas/")
    private val chorrillo = Spot("Chorrillo del Salto, El Chaltén, Argentina", -49.29552, -72.90771, CHALTEN_TUR + "el-chorrillo-del-salto/")
    private val capri = Spot("Laguna Capri, El Chaltén, Argentina", -49.30485, -72.93608, CHALTEN_TUR + "laguna-capri/")
    private val miradorFitzRoy = Spot("Mirador Fitz Roy, El Chaltén, Argentina", -49.30023, -72.92007, CHALTEN_TUR + "laguna-de-los-tres/")
    private val losTres = Spot("Laguna de los Tres, El Chaltén, Argentina", -49.27846, -72.98945, CHALTEN_TUR + "laguna-de-los-tres/")
    private val sucia = Spot("Laguna Sucia, El Chaltén, Argentina", -49.2849, -73.00085, CHALTEN_TUR + "laguna-de-los-tres/")
    private val margarita = Spot("Cascada Margarita lookout, El Chaltén, Argentina", -49.33618, -72.90434, CHALTEN_TUR + "mirador-laguna-torre/")
    private val miradorTorre = Spot("Mirador Laguna Torre, El Chaltén, Argentina", -49.33278, -72.9271, CHALTEN_TUR + "mirador-laguna-torre/")
    private val lagunaTorre = Spot("Laguna Torre, El Chaltén, Argentina", -49.32318, -73.00243, CHALTEN_TUR + "laguna-torre/")
    private val maestri = Spot("Mirador Maestri, El Chaltén, Argentina", -49.3182, -73.00176, CHALTEN_TUR + "laguna-torre/")
    private val pliegue = Spot("Loma del Pliegue Tumbado, El Chaltén, Argentina", -49.35944, -72.96752, CHALTEN_TUR + "loma-del-pliegue-tumbado-laguna-toro/")
    private val vueltas = Spot("Mirador Río de las Vueltas, El Chaltén, Argentina", -49.31456, -72.9013)
    private val desierto = Spot("Lago del Desierto, Santa Cruz, Argentina", -49.03694, -72.86361, CHALTEN_TUR + "ruta-escenica-area-protegida-recreativa-lago-del-desierto/")
    private val huemul = Spot("Glaciar Huemul, Lago del Desierto, Argentina", -49.06655, -72.91527, CHALTEN_TUR + "ruta-escenica-area-protegida-recreativa-lago-del-desierto/")
    private val escaladores = Spot("Capilla de los Escaladores, El Chaltén, Argentina", -49.33055, -72.882)
    private val laCerveceria = Spot("La Cervecería, El Chaltén, Argentina", -49.32766, -72.88941)

    // ---- El Calafate and the glaciers ----
    private val calafate = Spot("El Calafate, Argentina", -50.33778, -72.26)
    private val glaciarium = Spot("Glaciarium, El Calafate, Argentina", -50.33663, -72.33982, CALAFATE_TUR + "in-the-city-of-el-calafate/glaciarium-centro-de-interpretacion-en.htm")
    private val interpretacion = Spot("Centro de Interpretación Histórica, El Calafate, Argentina", -50.33121, -72.26519, CALAFATE_TUR + "museum/centro-de-interpretacion-historica.htm")
    private val walichu = Spot("Punta Walichu, El Calafate, Argentina", -50.29483, -72.20257, CALAFATE_TUR + "in-the-city-of-el-calafate/punta-walichu-en.htm")
    private val nimez = Spot("Reserva Laguna Nimez, El Calafate, Argentina", -50.32837, -72.26829, CALAFATE_TUR + "bird-watching-bird-watching/bird-watching.htm")
    private val moreno = Spot("Perito Moreno Glacier, Los Glaciares National Park, Argentina", -50.46903, -73.02986, CALAFATE_TUR + "in-perito-moreno-glacier.htm")
    private val bajoSombras = Spot("Puerto Bajo de las Sombras, Los Glaciares National Park, Argentina", -50.47461, -72.99368, CALAFATE_TUR + "in-perito-moreno-glacier/minitrekking-en.htm")
    private val nibepo = Spot("Estancia Nibepo Aike, Santa Cruz, Argentina", -50.56428, -72.86385, "https://nibepoaike.com.ar/")
    private val puertoBandera = Spot("Puerto Bandera, Lago Argentino, Argentina", -50.31372, -72.79188, CALAFATE_TUR + "northern-area-navigations/navegacion-estancia-cristina-en.htm")
    private val estanciaCristina = Spot("Estancia Cristina, Los Glaciares National Park, Argentina", -49.96204, -73.13084, "https://estanciacristina.com/")

    // ---- Santiago ----
    private val lastarria = Spot("Barrio Lastarria, Santiago, Chile", -33.43833, -70.64028, CHILE_EN + "attractions/lastarria-and-bellas-artes-neighborhood/")
    private val santaLucia = Spot("Cerro Santa Lucía, Santiago, Chile", -33.44, -70.644)
    private val gam = Spot("GAM, Santiago, Chile", -33.43908, -70.63973, "https://gam.cl/es/")
    private val parqueForestal = Spot("Parque Forestal, Santiago, Chile", -33.43583, -70.64111)
    private val mac = Spot("Museo de Arte Contemporáneo, Santiago, Chile", -33.43511, -70.64448, "https://mac.uchile.cl/")
    private val mnbaChile = Spot("Museo Nacional de Bellas Artes, Santiago, Chile", -33.43532, -70.64356, "https://www.mnba.gob.cl/")
    private val plazaDeArmas = Spot("Plaza de Armas, Santiago, Chile", -33.43791, -70.65036)
    private val catedralSantiago = Spot("Metropolitan Cathedral, Santiago, Chile", -33.43766, -70.65181)
    private val precolombino = Spot("Museo Chileno de Arte Precolombino, Santiago, Chile", -33.43891, -70.65217, "https://precolombino.cl/en/")
    private val moneda = Spot("La Moneda Palace, Santiago, Chile", -33.44302, -70.65387, "https://visitaspatrimonio.presidencia.cl/")
    private val cclm = Spot("Centro Cultural La Moneda, Santiago, Chile", -33.44389, -70.65361, "https://www.cclm.cl/")
    private val mercadoCentral = Spot("Mercado Central, Santiago, Chile", -33.43368, -70.65103)
    private val vega = Spot("La Vega Central, Santiago, Chile", -33.43024, -70.64966)
    private val piojera = Spot("La Piojera, Santiago, Chile", -33.43355, -70.65212)
    private val fuenteAlemana = Spot("Fuente Alemana, Santiago, Chile", -33.43664, -70.63639)
    private val chascona = Spot("La Chascona, Santiago, Chile", -33.43111, -70.63444, "https://fundacionneruda.org/museos-casa-museo-la-chascona/")
    private val bellavista = Spot("Bellavista, Santiago, Chile", -33.43278, -70.63583, CHILE_EN + "attractions/barrio-italia-and-bellavista/")
    private val sanCristobal = Spot("Cerro San Cristóbal, Santiago, Chile", -33.42034, -70.6299, "https://telefericosantiago.cl/?lang=en")
    private val cementerio = Spot("Cementerio General, Santiago, Chile", -33.41321, -70.64823, "https://cementeriogeneral.cl/")
    private val memoria = Spot("Museum of Memory and Human Rights, Santiago, Chile", -33.43984, -70.67937, "https://mmdh.cl/")
    private val yungay = Spot("Barrio Yungay, Santiago, Chile", -33.4376, -70.6736)
    private val barrioItalia = Spot("Barrio Italia, Santiago, Chile", -33.44323, -70.62599, CHILE_EN + "attractions/barrio-italia-and-bellavista/")
    private val sky = Spot("Sky Costanera, Santiago, Chile", -33.41672, -70.60683, "https://www.skycostanera.cl/en")
    private val bicentenario = Spot("Parque Bicentenario, Vitacura, Santiago, Chile", -33.40058, -70.60248)
    private val cousino = Spot("Viña Cousiño Macul, Santiago, Chile", -33.48584, -70.56677, "https://cousinomacul.com/pages/tours")
    private val conchaToro = Spot("Concha y Toro, Pirque, Chile", -33.63558, -70.57455, "https://enoturismo.conchaytoro.com/")
    private val sanJoseMaipo = Spot("San José de Maipo, Chile", -33.63333, -70.36667, CHILE_EN + "destinations/cajon-del-maipo/")
    private val yeso = Spot("Embalse El Yeso, Cajón del Maipo, Chile", -33.65, -70.06667, CHILE_EN + "attractions/embalse-el-yeso/")
    private val morado = Spot("El Morado Natural Monument, Cajón del Maipo, Chile", -33.76667, -70.06667, CHILE_EN + "attractions/el-morado-natural-monument/")
    private val valparaiso = Spot("Valparaíso, Chile", -33.04611, -71.61972, CHILE_EN + "destinations/valparaiso/")

    // ---- Further away (ideas) ----
    private val iguazu = Spot("Iguazú Falls, Misiones, Argentina", -25.68667, -54.44472, "https://iguazuargentina.com/en/")
    private val tierraDelFuego = Spot("Tierra del Fuego National Park, Ushuaia, Argentina", -54.83333, -68.5, "https://www.argentina.gob.ar/parquesnacionales/tierradelfuego")
    private val torresDelPaine = Spot("Torres del Paine National Park, Chile", -51.0, -73.0, CHILE_EN + "attractions/torres-del-paine-national-park/")
    private val elBolson = Spot("El Bolsón, Río Negro, Argentina", -41.96667, -71.53333, BRC_EN + "el-bolson")
    private val puertoBlest = Spot("Puerto Blest, Nahuel Huapi National Park, Argentina", -41.01667, -71.81667, BRC_EN + "puerto-blest-and-cascada-de-los-cantaros")

    // ---- Builders ----
    // Places, meals, activities and ideas take their spot's website; stays never have one (they are generic), and a
    // flight or bus only has the one it is given (the airport's page, not the town's).
    private fun item(kind: String, title: String, note: String, spot: Spot?, via: Vehicle?, pace: Pace, time: String?, url: String?) =
        ProgramItem(kind, title, note, spot?.let { it.lat to it.lng }, via, time, pace, spot?.name ?: "", url)

    private fun place(title: String, note: String, spot: Spot, via: Vehicle?, pace: Pace = Pace.Chill, time: String? = null) =
        item("place", title, note, spot, via, pace, time, spot.url)

    private fun activity(
        title: String, note: String, spot: Spot? = null, via: Vehicle? = null, pace: Pace = Pace.Chill, time: String? = null, url: String? = spot?.url,
    ) = item("activity", title, note, spot, via, pace, time, url)

    private fun food(
        title: String, note: String, spot: Spot? = null, via: Vehicle? = null, pace: Pace = Pace.Chill, time: String? = null, url: String? = spot?.url,
    ) = item("food", title, note, spot, via, pace, time, url)

    private fun stay(title: String, note: String, spot: Spot, via: Vehicle) = item("stay", title, note, spot, via, Pace.Chill, null, null)
    private fun transport(title: String, note: String, spot: Spot, via: Vehicle?, url: String? = null) =
        item("transport", title, note, spot, via, Pace.Chill, null, url)
    private fun note(title: String, note: String) = ProgramItem("note", title, note)
    private fun idea(kind: String, title: String, note: String, spot: Spot? = null, url: String? = spot?.url) =
        item(kind, title, note, spot, null, Pace.Chill, null, url)
    private fun day(title: String, base: String, vararg items: ProgramItem) = ProgramDay(title, base, items.toList())

    // Shared legs. Flights land at the airport (its page); buses and transfers have no company named.
    private fun landAtEze() = transport("Fly to Buenos Aires", "Official taxi desks in arrivals · ≈ 45–60 min to the centre", eze, null, eze.url)
    private fun flyToMendoza() = transport("Fly to Mendoza", "From Aeroparque ≈ 1 h 50 · many flights a day", mdz, Plane, mdz.url)
    private fun flyToBariloche() = transport("Fly to Bariloche", "Direct from Mendoza on some days, ≈ 1 h 45; else via Buenos Aires", brc, Plane, brc.url)
    private fun flyToCalafate() = transport("Fly to El Calafate", "Direct from Bariloche on some days, ≈ 2 h; else via Buenos Aires", fte, Plane, fte.url)
    private fun busToChalten() = transport("Bus to El Chaltén", "≈ 3 h north past Lago Viedma · several buses a day", chaltenTerminal, Car)
    private fun busToCalafate() = transport("Bus back to El Calafate", "≈ 3 h · book a seat the day before", calafateTerminal, Car)
    private fun flyToSantiago() = transport("Fly to Santiago", "SKY has flown direct twice a week in summer, ≈ 3 h: check this season; else via Buenos Aires", scl, Plane, scl.url)
    private fun flyHome() = transport("Fly home from Santiago", "Airport bus or taxi ≈ 30–45 min", scl, Car, scl.url)
    private fun boatToArrayanes() =
        activity("Boat to the Arrayanes and Isla Victoria", "From Puerto Pañuelo, all year · half or full day; lunch not included", puertoPanuelo, Car)
    private fun boatBack() = transport("Boat back to Puerto Pañuelo", "", puertoPanuelo, Ferry)

    // ---- Eat & drink ----
    private val eats = Program(
        id = "$ID-food",
        destinationId = ID,
        focus = Focus.Food,
        title = "Asado, Malbec and Patagonian lamb",
        blurb = "Buenos Aires grills and cafés, five days of Mendoza wine, Bariloche chocolate and curanto, lamb in " +
            "Patagonia, then Santiago's markets.",
        days = listOf(
            day(
                "Arrive, a Palermo parrilla", BA,
                landAtEze(),
                stay("Small hotel in Palermo Soho", "Grills, cafés and bars on foot", palermoSoho, Car),
                food("Coffee and medialunas", "Small sweet croissants with a café con leche", pace = B),
                place("Plaza Serrano", "Palermo Soho's café square", palermoSoho, Walk),
                food("Vermouth at El Preferido de Palermo", "Corner bodegón of 1952 · vermouth, tortilla, milanesa", preferido, Walk, B),
                food("Dinner at Don Julio", "Palermo's famous parrilla · book online weeks ahead", donJulio, Walk),
                food("Ice cream after dinner", "Heladerías stay open late; start with dulce de leche", pace = E),
            ),
            day(
                "Cafés, pizza and the Centro", BA,
                food("Breakfast at Café Tortoni", "Café of 1858 · medialunas and café con leche", tortoni, Train),
                food("Pizza at Güerrín", "Corrientes pizzeria of 1932 · a slice of muzzarella at the counter", guerrin, Walk),
                food("Fainá on top", "A slice of chickpea flatbread laid on the pizza, the local way", pace = B),
                food("Ice cream at Cadore", "Corrientes ice cream shop of 1957 · dulce de leche", cadore, Walk, B),
                food("El Cuartito", "Pizzeria of 1934, its walls hung with sports posters", elCuartito, Walk, E),
                food("Cocktails at Florería Atlántico", "Bar behind a flower shop, one of the world's best · book", floreria, Car, E),
            ),
            day(
                "San Telmo, La Boca and tango", BA,
                food("Mercado de San Telmo", "Iron market hall of 1897 · cheese, empanadas, coffee", mercadoSanTelmo, Car),
                place("Plaza Dorrego", "San Telmo's old square · antiques on Sundays", dorrego, Walk, B),
                food("Lunch at El Obrero", "La Boca bodegón of 1954 · go by taxi", elObrero, Car, B),
                food("Fugazzeta at Banchero", "La Boca pizzeria of 1932, known for its onion-and-cheese pizza", banchero, Car, E),
                activity("Empanada class", "Fold, bake and eat them with wine · book online"),
                food("Tango dinner at El Querandí", "Venue of 1920 · dinner, then the 21:30 show; book", querandi, Car, time = "19:30"),
            ),
            day(
                "Choripán, merienda, a last asado", BA,
                food("Choripán on the Costanera", "Sausage sandwiches from the grill carts by the reserve gate", costaneraSur, Car),
                food("Merienda at Las Violetas", "Confitería of 1884 · tea, sandwiches de miga, cakes", lasVioletas, Train),
                food("Alfajores and dulce de leche", "Biscuits and milk caramel to take home", pace = B),
                food("Fernet and cola", "The country's favourite drink, bitter and dark", pace = B),
                food("A last asado", "Provoleta and chorizo first, then the beef"),
                activity("A milonga after dinner", "Watch the dancers, or take the early class", pace = E),
            ),
            day(
                "Fly to Mendoza: the market", MENDOZA,
                flyToMendoza(),
                stay("Hotel near Plaza Independencia", "Central: plazas, cafés and wine bars on foot", plazaIndependencia, Car),
                food("Mercado Central", "Cured meats, cheeses, olives · most stalls shut ≈ 13:30–17:00", mercadoMendoza, Walk),
                food("Empanadas mendocinas", "Beef, onion, olive and egg, baked", pace = B),
                food("Tortitas mendocinas", "Flaky breakfast breads from any bakery", pace = B),
                food("Dinner on Arístides Villanueva", "The city's evening street of bars and grills", villanueva, Car),
                food("A Malbec flight", "Three or four Mendoza Malbecs side by side at a wine bar", pace = E),
            ),
            day(
                "Maipú by bike: wine and olive oil", MENDOZA,
                activity("Bike the Maipú wine road", "Hire a bike on Urquiza street, Coquimbito · flat lanes", maipuBikes, Car),
                food("Tasting at La Rural", "Winery of 1885 with a wine museum · Mon–Sat, ask ahead", laRural, Bike),
                place("Trapiche", "Winery in a building of 1912 · tours and lunch, book", trapiche, Bike, B),
                food("Olive oil tasting at Laur", "Olive grove and oil mill since 1906 · book", laur, Bike, B),
                food("Lunch among the vines", "A long winery lunch, then the ride back"),
                place("Bodegas López", "Family winery since 1898 · tours daily, book", lopez, Car, E),
            ),
            day(
                "Luján de Cuyo: Malbec country", MENDOZA,
                note("Not on a Sunday or Monday", "Catena Zapata opens Tuesday to Saturday only"),
                place("Bodega Catena Zapata", "Winery built as a pyramid · tours Tue–Sat, book by email", catena, Car),
                food("Lunch at Bodega Norton", "Terrace restaurant of a winery of 1895 · book online", norton, Car),
                place("Bodega Lagarde", "Winery of 1897 · tours daily, Sundays too; book", lagarde, Car, B),
                food("Chivito at a parrilla", "Kid goat from the grill, a Cuyo dish", pace = B),
                food("A last glass in town", "Wine bars along Arístides Villanueva", villanueva, Car, E),
            ),
            day(
                "To the Valle de Uco", UCO,
                stay("Wine lodge in the Valle de Uco", "≈ 1 h 30 south of Mendoza by car or transfer", uco, Car),
                place("Domaine Bousquet", "Organic winery in Gualtallary · tours and lunch, book", bousquet, Car, E),
                place("Andeluna", "Winery at ≈ 1,300 m facing the Andes · book", andeluna, Car, B),
                food("Lunch at Salentein's Killka", "Winery restaurant with an art gallery · book ahead", salentein, Car),
                activity("Sunset over the vines", "The Andes turn pink behind the rows", pace = B),
                food("Asado at the lodge", "Beef, chorizo and provoleta from the grill"),
            ),
            day(
                "Altamira: Zuccardi", UCO,
                note("Slow morning", "The pool, a walk in the vines"),
                activity("Zuccardi Valle de Uco tour", "Winery built of the valley's stones · tours daily, book", zuccardi, Car, time = "11:00"),
                food("Lunch at Zuccardi", "Piedra Infinita's restaurant · book weeks ahead", zuccardi, Walk),
                activity("Horse ride among the vines", "Ask the lodge · ≈ 1–2 h", pace = B),
                food("Cheese and olive oil tasting", "Goat cheeses and oils from the valley", pace = B),
                place("Manzano Histórico", "Where San Martín came back from Chile in 1823", manzano, Car, E),
            ),
            day(
                "Fly to Bariloche: chocolate", BARILOCHE,
                flyToBariloche(),
                stay("Hotel in the centre", "Lake views; Mitre street on foot", centroCivico, Car),
                food("Chocolate on Mitre", "Chocolate shops one after another on the main street", mitre, Walk),
                food("Rapanui", "Chocolate and ice cream · try the raspberries in chocolate", rapanui, Walk, B),
                food("A submarino", "Hot milk with a bar of chocolate to melt in it", pace = B),
                food("Trout for dinner", "Lake trout, often with butter and lemon"),
                food("A pint of local craft beer", "Bariloche has dozens of small breweries", pace = E),
            ),
            day(
                "Circuito Chico and the breweries", BARILOCHE,
                place("Cerro Campanario chairlift", "≈ 7 min up to 1,050 m: the classic view over the lakes", campanario, Car),
                food("Lunch at Cervecería Patagonia", "Brewery with a lake view on the Circuito Chico", patagoniaBrewery, Car),
                food("Tea at the Llao Llao Hotel", "The hotel's afternoon tea in Bustillo's lodge · book ahead", llaoLlao, Car, B),
                place("Punto Panorámico", "Lookout over Lago Moreno on the loop", puntoPanoramico, Car, B),
                food("Cervecería Berlina", "Brewery on the lake road back to town", berlina, Car, E),
            ),
            day(
                "Colonia Suiza: curanto", BARILOCHE,
                note("On a Wednesday or Sunday", "The fair is on Wednesdays and Sundays; the curanto goes in on Sunday mornings"),
                food("Curanto at Colonia Suiza", "Meat and vegetables steamed under hot stones", coloniaSuiza, Car),
                place("Colonia Suiza fair", "Crafts and tastings of local produce · Wed and Sun", coloniaSuiza, Walk, B),
                place("Cerro Otto cable car", "≈ 12 min up to 1,405 m, to a revolving café", ottoTop, Car),
                food("Smoked trout and venison", "From Bariloche's smokehouses: a board to share", pace = B),
                food("Cheese fondue", "The Swiss settlers' dish, for a cold evening", pace = E),
            ),
            day(
                "Lake day: Isla Victoria", BARILOCHE,
                boatToArrayanes(),
                place("Bosque de Arrayanes", "Cinnamon-barked myrtle trees on a boardwalk loop", arrayanes, Ferry),
                food("Picnic on Isla Victoria", "Lunch is not included on the boats: bring it", islaVictoria, Ferry, B, url = null),
                boatBack(),
                food("Berries and jams", "Raspberries and cherries from the valleys in summer", pace = B),
                food("Venison or wild boar for dinner", "Game from the lake country, often stewed", pace = E),
            ),
            day(
                "Fly south to El Chaltén", CHALTEN,
                flyToCalafate(),
                food("Lunch in El Calafate", "Between the plane and the bus", calafate, Car, E),
                busToChalten(),
                stay("Small lodge in El Chaltén", "The trails start at the edge of town", chalten, Walk),
                place("Park visitor centre", "Rangers' talk on trails and weather · the trail fee is paid by card or QR", visitorCentre, Walk, B),
                place("Mirador de los Cóndores", "≈ 1 h 30 up from the visitor centre · condors, and Fitz Roy at sunset", condores, Walk),
                food("Calafate berries", "Eat them and you will come back, says the local saying", pace = B),
                food("Dinner at La Cervecería", "Small brewery: its own beer and a bowl of locro", laCerveceria, Walk),
            ),
            day(
                "Laguna Capri and Fitz Roy", CHALTEN,
                activity("Laguna Capri walk", "≈ 3 h 30 return, medium · Fitz Roy over the water on clear days", capri, Walk),
                food("Picnic by the lake", "Bakery sandwiches and a thermos of mate"),
                place("Mirador Fitz Roy", "Open view of the massif, a short walk on", miradorFitzRoy, Walk, B),
                activity("On to Laguna de los Tres", "≈ 2 h more each way; the last hour is very steep", losTres, Walk, E),
                food("Lamb stew and a stout", "Patagonian lamb, slow-cooked, with a local dark beer", pace = B),
            ),
            day(
                "A slow day in El Chaltén", CHALTEN,
                food("Breakfast at a bakery", "Facturas and coffee before the walk"),
                place("Chorrillo del Salto", "A 20 m waterfall in the woods ≈ 4 km north of town", chorrillo, Car),
                food("Calafate jam and honey", "From the town's small shops, for home", pace = B),
                place("Mirador de las Águilas", "On past the condor lookout, over Lago Viedma", aguilas, Car, E),
                food("Craft beer and pizza in town", "A slow last evening below Fitz Roy", pace = B),
            ),
            day(
                "Back to El Calafate: lamb", CALAFATE,
                busToCalafate(),
                stay("Hotel in El Calafate", "Near Avenida del Libertador", calafate, Car),
                place("Reserva Laguna Nimez", "Bird reserve by Lago Argentino, 800 m from the main street", nimez, Walk),
                food("Calafate ice cream", "The purple berry, made into ice cream", pace = B),
                food("Patagonian beer", "Local craft beers in a bar on the main street", pace = B),
                food("Centolla", "King crab from the far south, on many menus", pace = E),
                food("Lamb on the cross", "Cordero al asador: a whole lamb roasted by the fire"),
            ),
            day(
                "Perito Moreno and estancia lamb", CALAFATE,
                place("Perito Moreno Glacier", "≈ 80 km west · walkways facing the ice wall", moreno, Car),
                activity("Boat to the ice face", "≈ 1 h on the lake below the glacier", bajoSombras, Car),
                activity("Minitrekking", "Boat across, ≈ 1 h 30 on the ice in crampons, with guides · ages 8–65; book", bajoSombras, Walk, B),
                food("Lunch with a glacier view", "Packed lunch or the café by the walkways", pace = B),
                food("Lamb at Estancia Nibepo Aike", "Century-old sheep farm on Lago Roca · country day with lamb; book", nibepo, Car, E),
            ),
            day(
                "Fly to Santiago: Lastarria", SANTIAGO,
                flyToSantiago(),
                stay("Hotel in Lastarria", "Old quarter by Cerro Santa Lucía; the centre on foot", lastarria, Car),
                food("Pisco sour in Lastarria", "The local cocktail on a café terrace", lastarria, Walk),
                place("Cerro Santa Lucía", "Hill where Santiago was founded in 1541 · sign in at the gate", santaLucia, Walk, B),
                food("Completo at Fuente Alemana", "Counter famous for lomitos and loaded hot dogs", fuenteAlemana, Walk, B),
                food("Pastel de choclo", "Corn pie baked over beef and chicken, a summer dish"),
                food("Mote con huesillo", "Peach and husked-wheat drink from a street stand", pace = E),
            ),
            day(
                "La Vega and the Mercado Central", SANTIAGO,
                food("La Vega Central", "Big produce market: fruit, juices, sopaipillas", vega, Car),
                food("Seafood at the Mercado Central", "Iron hall of 1872 · caldillo de congrio, machas", mercadoCentral, Walk),
                food("A terremoto at La Piojera", "Bar of 1896 · sweet wine with pineapple ice cream", piojera, Walk, B),
                place("Plaza de Armas", "The founding square, a few blocks south", plazaDeArmas, Walk, B),
                activity("Cerro San Cristóbal by cable car", "Views to the Andes · the lifts close on Mondays", sanCristobal, Car, E),
            ),
            day(
                "Maipo wine, then home", SANTIAGO,
                place("Viña Cousiño Macul", "Family winery since 1856 · tours in English, book", cousino, Car),
                place("Concha y Toro", "Old cellars in Pirque · book a tour", conchaToro, Car, E),
                food("Lunch in Barrio Italia", "Cafés and small restaurants among design shops", barrioItalia, Car),
                food("Empanadas de pino", "Beef, onion, egg and olive: one for the road", pace = B),
                food("Carménère to take home", "Chile's own grape, from a wine shop", pace = B),
                flyHome(),
            ),
        ),
        ideas = listOf(
            idea("food", "Feria de Mataderos", "Sundays: food from the provinces, folk music and gauchos", mataderos),
            idea("activity", "Vendimia in Mendoza", "The grape harvest festival, early March: parade and night show", url = MZA + "destino/fiesta-nacional-de-la-vendimia/"),
            idea("place", "El Bolsón", "Valley town 2 h south of Bariloche: berries, beer, a crafts fair", elBolson),
            idea("food", "Lunch at Estancia Cristina", "Full day by boat past the Upsala Glacier to the Masters' farm", estanciaCristina),
            idea("place", "Valparaíso", "Port city 2 h from Santiago: seafood and funiculars", valparaiso),
        ),
        sources = listOf(
            "https://en.wikivoyage.org/wiki/Buenos_Aires",
            "https://en.wikivoyage.org/wiki/Mendoza",
            "https://en.wikivoyage.org/wiki/Bariloche",
            "https://en.wikivoyage.org/wiki/El_Chalt%C3%A9n",
            "https://en.wikivoyage.org/wiki/Santiago_de_Chile",
            "https://turismo.buenosaires.gob.ar/es/gastronomico/caf%C3%A9-tortoni",
            "https://turismo.buenosaires.gob.ar/es/gastronomico/pizzer%C3%ADa-g%C3%BCerr%C3%ADn",
            "https://turismo.buenosaires.gob.ar/es/gastronomico/las-violetas",
            "https://turismo.buenosaires.gob.ar/en/otros-establecimientos/san-telmo-market",
            "https://www.parrilladonjulio.com/",
            "https://querandi.com.ar/en/",
            "https://teatrocolon.org.ar/guided-tours/",
            "https://buenosaires.gob.ar/feriademataderos",
            "https://www.aeropuertosargentina.com/en/MDZ",
            "https://mendoza.tur.ar/lugar/mercado-central/",
            "https://bodegalarural.com.ar/turismo/",
            "https://experiencias.bodegaslopez.com.ar/",
            "https://mendoza.tur.ar/lugar/bodega-trapiche/",
            "https://catenazapata.com/",
            "https://www.norton.com.ar/en/tourism/",
            "https://lagarde.com.ar/visitas-degustaciones/",
            "https://zuccardiwines.com/en/turismo/",
            "https://mendoza.tur.ar/destino/fiesta-nacional-de-la-vendimia/",
            "https://barilocheturismo.gob.ar/en/short-circuit",
            "https://barilocheturismo.gob.ar/en/colonia-suiza",
            "https://barilocheturismo.gob.ar/en/victoria-island-and-myrtle-woods",
            "https://cerrocampanario.com.ar/la-aerosilla/",
            "https://www.telefericobariloche.com.ar/en",
            "https://elchalten.tur.ar/laguna-capri/",
            "https://www.elcalafate.tur.ar/in-perito-moreno-glacier/minitrekking-en.htm",
            "https://www.elcalafate.tur.ar/gastronomy-in-el-calafate.htm",
            "https://nibepoaike.com.ar/",
            "https://cousinomacul.com/pages/tours",
            "https://enoturismo.conchaytoro.com/",
            "https://www.skycostanera.cl/en",
        ),
    )

    // ---- Sights & history (the default) ----
    private val sights = Program(
        id = "$ID-culture",
        destinationId = ID,
        focus = Focus.Culture,
        title = "Plazas, tango and the Andes road",
        blurb = "Five days of Buenos Aires history and tango, Mendoza and the road over the Andes, Bariloche's stone " +
            "town, estancias by the glaciers, then Santiago.",
        days = listOf(
            day(
                "Arrive, Recoleta at dusk", BA,
                landAtEze(),
                stay("Small hotel in Recoleta", "Cemetery, museums and cafés on foot", recoleta, Car),
                food("Coffee at La Biela", "Old corner café facing the cemetery wall", laBiela, Walk, E),
                place("Basílica del Pilar", "White colonial church, consecrated in 1732", pilar, Walk, B),
                place("Plaza Francia and Floralis Genérica", "A giant steel flower in the park", floralis, Walk),
                place("El Ateneo Grand Splendid", "Bookshop in a theatre of 1919 · free, open to 21:00", ateneo, Walk, B),
                food("Late parrilla dinner", "Steak and Malbec · tables fill after 21:00"),
            ),
            day(
                "Plaza de Mayo and the Avenida", BA,
                note("Not on a Monday or Tuesday", "The Cabildo and Manzana de las Luces close both days"),
                place("Plaza de Mayo", "Square of the May Revolution of 1810, facing the Casa Rosada", plazaDeMayo, Train),
                place("Metropolitan Cathedral", "San Martín's tomb, with a grenadier guard · free", catedralBa, Walk),
                place("Cabildo", "Colonial town hall where 1810 began · Wed–Sun, free", cabildo, Walk, B),
                place("Manzana de las Luces", "Jesuit block of the 1700s · Wed–Sun; its tunnels are shut for repairs", manzana, Walk, E),
                food("Café Tortoni", "Café of 1858 on Avenida de Mayo · tango shows in the evening", tortoni, Walk),
                place("Palacio Barolo", "Tower of 1923 built on Dante's Divine Comedy · guided tours only", barolo, Walk, B),
                place("National Congress", "Free Senate tours weekdays at 12:00 and 17:00 · bring your passport", congreso, Walk, E),
            ),
            day(
                "Teatro Colón and Recoleta", BA,
                activity("Teatro Colón tour", "50 min · in English at 11, 12, 13 and 15 h; tickets online", colon, Car, time = "11:00"),
                place("Obelisco lookout", "A lift to the top, open since 2025 · timed tickets online", obelisco, Walk, E),
                place("Plaza San Martín and the Kavanagh", "Hilltop park and an Art Deco tower of 1936", kavanagh, Car, B),
                place("Recoleta Cemetery", "Evita's family vault · daily 9–17; visitors from abroad pay by card", recoletaCemetery, Car),
                place("Museo Nacional de Bellas Artes", "Argentine and European art · free; closed Mondays", mnba, Walk, B),
                place("Museo Nacional de Arte Decorativo", "French-style palace of 1917 · Wed–Sun 13–19", mnad, Walk, E),
            ),
            day(
                "San Telmo and La Boca", BA,
                note("Best on a Sunday", "Antiques on Plaza Dorrego, stalls all along Defensa street"),
                place("Plaza Dorrego", "San Telmo's old square · the Sunday antiques fair", dorrego, Car),
                place("El Zanjón de Granados", "Tunnels and cisterns under an old house · tours daily, book online", zanjon, Walk, B),
                food("Lunch at the Mercado de San Telmo", "Iron market hall of 1897 · stalls and small counters", mercadoSanTelmo, Walk),
                place("Museo Histórico Nacional", "In Parque Lezama: the wars of independence · Wed–Sun, free", lezama, Walk, E),
                place("Caminito", "Painted tin houses · go by day, by taxi; keep to the main streets", caminito, Car),
                place("Museo Benito Quinquela Martín", "The painter of La Boca's port, with roof terraces · closed Tuesdays", quinquela, Walk, B),
                place("Fundación Proa", "Contemporary art by the old harbour · Wed–Sun 12–19", proa, Walk, E),
            ),
            day(
                "Evita, MALBA and a tango night", BA,
                place("ESMA Museum and Site of Memory", "A secret detention centre of the dictatorship · Tue–Sun, free", esma, Car, E),
                place("Museo Evita", "Her life, dresses and speeches · Tue–Sun; English tour Fri 15:00", evita, Car),
                place("Jardín Japonés", "Japanese garden with koi ponds · daily 10–18:45", jardinJapones, Walk, B),
                place("Rosedal", "Rose garden by the Palermo lakes · closed Mondays; best Nov–Dec", rosedal, Walk, B),
                place("MALBA", "Latin American art: Kahlo, Berni, Xul Solar · closed Tuesdays", malba, Walk),
                activity("Tango show at El Querandí", "In a venue of 1920 · dinner first; book ahead", querandi, Car, time = "21:30"),
            ),
            day(
                "Fly to Mendoza", MENDOZA,
                flyToMendoza(),
                stay("Hotel near Plaza Independencia", "Central: plazas and cafés on foot", plazaIndependencia, Car),
                place("Plaza Independencia", "Heart of the city rebuilt after the 1861 earthquake", plazaIndependencia, Walk),
                place("Plaza España", "Benches and murals in Spanish tiles", plazaEspana, Walk, B),
                place("Museo del Área Fundacional", "The old town the 1861 quake destroyed · closed Mondays", areaFundacional, Car),
                place("Ruinas de San Francisco", "Ruins of a Jesuit church across the square", ruinasSanFrancisco, Walk, B),
                food("Dinner on Arístides Villanueva", "The city's evening street of bars and grills", villanueva, Car, E),
            ),
            day(
                "Cerro de la Gloria, old wineries", MENDOZA,
                place("Parque General San Martín", "Park of 1896 with a lake and a rose garden", parqueSanMartin, Car),
                place("Cerro de la Gloria", "Monument to San Martín's Army of the Andes · walk up or taxi", cerroGloria, Car),
                place("Wine museum at La Rural", "Presses and tools in a winery of 1885 · Mon–Sat, ask ahead", laRural, Car, B),
                place("Trapiche", "Winery in a Renaissance-style building of 1912 · book", trapiche, Car, B),
                food("Lunch at a Maipú winery", "A long lunch among the vines · book ahead"),
                place("Museo Casa de Fader", "A painter's house with murals in Luján · Wed–Sun 10–18", fader, Car, E),
            ),
            day(
                "The Andes road to Aconcagua", MENDOZA,
                note("A long day", "≈ 3 h each way on Ruta 7 · a tour or a hired car; start early"),
                place("Potrerillos dam", "Turquoise reservoir where the road enters the mountains", potrerillos, Car, B),
                place("Puente del Inca", "Natural stone bridge and a ruined spa · seen from the lookout", puenteInca, Car),
                activity("Laguna de Horcones walk", "Aconcagua park · book the slot online, nothing is sold at the gate", horcones, Car),
                place("Cristo Redentor", "Statue of 1904 on the border pass · the track opens in summer", cristo, Car, E),
                food("Lunch in Uspallata", "Last village before the pass, on the way back", uspallata, Car, B),
                transport("Back to Mendoza", "", plazaIndependencia, Car),
            ),
            day(
                "Valle de Uco: San Martín and art", MENDOZA,
                note("A day out south", "≈ 1 h 30 each way by car or with a tour"),
                place("Manzano Histórico", "Where San Martín came back over the Andes in 1823 · monument", manzano, Car),
                place("Killka gallery at Salentein", "Argentine and Dutch art in a winery · book a visit", salentein, Car),
                food("Lunch among the Uco vines", "A winery lunch with the Andes in view · book", pace = B),
                place("Zuccardi's stone winery", "Built from the valley's own stones · tours daily, book", zuccardi, Car, B),
                activity("Andes at dusk", "The peaks turn pink over the vines on the drive back", pace = E),
                transport("Back to Mendoza", "", plazaIndependencia, Car),
            ),
            day(
                "Fly to Bariloche: the Civic Centre", BARILOCHE,
                flyToBariloche(),
                stay("Hotel in the centre", "Lake views; the Civic Centre on foot", centroCivico, Car),
                place("Centro Cívico", "Stone and timber square of 1940, open to the lake", centroCivico, Walk),
                place("Museo de la Patagonia", "The region's museum, in the Civic Centre · check its hours", museoPatagonia, Walk, B),
                place("Bariloche Cathedral", "Stone church of the 1940s above the lake", catedralBariloche, Walk),
                food("Chocolate on Mitre", "Chocolate shops one after another on the main street", mitre, Walk, B),
                food("Rapanui", "Chocolate and ice cream · try the raspberries in chocolate", rapanui, Walk, E),
            ),
            day(
                "Circuito Chico and the Llao Llao", BARILOCHE,
                place("Cerro Campanario chairlift", "≈ 7 min up to 1,050 m: the classic view over the lakes", campanario, Car),
                activity("Circuito Chico", "≈ 60 km loop by car or bus past lakes and bays", puntoPanoramico, Car),
                place("Capilla San Eduardo", "Bustillo's timber chapel on a hill by the lake", sanEduardo, Car, B),
                place("Llao Llao Hotel", "Bustillo's lodge between two lakes, rebuilt in 1940", llaoLlao, Walk, B),
                activity("Cerro Llao Llao trail", "Forest loop to a lookout · ≈ 1 h 30", cerroLlaoLlao, Car, E),
            ),
            day(
                "Colonia Suiza and Cerro Otto", BARILOCHE,
                place("Colonia Suiza", "The area's first European settlement, late 1800s · fair Wed and Sun", coloniaSuiza, Car),
                food("Curanto at Colonia Suiza", "Meat and vegetables steamed under hot stones", coloniaSuiza, Walk, B),
                food("Cervecería Berlina", "Brewery on the lake road back to town", berlina, Car, B),
                place("Cerro Otto cable car", "≈ 12 min up to a revolving café and an art gallery", ottoTop, Car),
                food("Smoked trout and venison", "From Bariloche's smokehouses: a board to share", pace = E),
            ),
            day(
                "Fly south to El Chaltén", CHALTEN,
                flyToCalafate(),
                food("Lunch in El Calafate", "Between the plane and the bus", calafate, Car, E),
                busToChalten(),
                stay("Small lodge in El Chaltén", "Argentina's youngest town, founded in 1985", chalten, Walk),
                place("Park visitor centre", "Rangers' talk on trails and weather · the trail fee is paid by card or QR", visitorCentre, Walk),
                place("Capilla de los Escaladores", "Small chapel for the climbers who died on these peaks", escaladores, Walk, B),
                place("Mirador de los Cóndores", "≈ 1 h 30 up from the visitor centre · condors, and Fitz Roy at sunset", condores, Walk),
                food("Dinner at La Cervecería", "Small brewery: its own beer and a bowl of locro", laCerveceria, Walk, B),
            ),
            day(
                "Cerro Torre and the waterfall", CHALTEN,
                place("Mirador Laguna Torre", "≈ 1 h 15 (5 km) up: the first view of Cerro Torre", miradorTorre, Walk),
                place("Laguna Torre", "≈ 11 km each way, a medium walk · icebergs under Cerro Torre", lagunaTorre, Walk, B),
                place("Mirador Maestri", "On along the moraine ridge to the old Maestri camp", maestri, Walk, E),
                place("Chorrillo del Salto", "A 20 m waterfall in the woods ≈ 4 km north of town", chorrillo, Car),
                food("Picnic at Laguna Torre", "Out of the wind behind the moraine", pace = B),
            ),
            day(
                "Back to El Calafate", CALAFATE,
                busToCalafate(),
                stay("Hotel in El Calafate", "Near Avenida del Libertador", calafate, Car),
                place("Centro de Interpretación Histórica", "Patagonia's people, from the Tehuelche to the sheep farms", interpretacion, Walk),
                place("Punta Walichu", "Rock paintings ≈ 4,000 years old, 8 km east of town · audio guide", walichu, Car),
                food("Lamb on the cross", "Cordero al asador: a whole lamb roasted by the fire", pace = B),
                food("Calafate ice cream", "The purple berry, made into ice cream", pace = B),
                place("Reserva Laguna Nimez", "Bird reserve by Lago Argentino, 800 m from the main street", nimez, Walk, E),
            ),
            day(
                "Perito Moreno Glacier", CALAFATE,
                place("Perito Moreno Glacier", "≈ 80 km west · walkways facing the ice wall", moreno, Car),
                activity("Boat to the ice face", "≈ 1 h on the lake below the glacier", bajoSombras, Car),
                place("Estancia Nibepo Aike", "A century-old sheep farm on Lago Roca, in the park · book a day visit", nibepo, Car, B),
                food("Lamb asado at Nibepo Aike", "Lamb roasted on iron crosses by the fire", nibepo, Walk, B, url = null),
                activity("Watch the ice fall", "Wait on the lowest walkway for a block to calve", moreno, Car, E),
            ),
            day(
                "Estancia Cristina by boat", CALAFATE,
                activity("Boat to Estancia Cristina", "Leaves Puerto Bandera at 8:30, past the Upsala Glacier · from October", puertoBandera, Car),
                place("Estancia Cristina", "The Masters family's farm, reached by boat; a museum in the shearing shed", estanciaCristina, Ferry),
                food("Lunch at the estancia", "About an hour at the farm's restaurant", pace = B),
                activity("Upsala lookout", "4×4 up the Feruglio range, then ≈ 20 min on foot to the Upsala view", estanciaCristina, Car, B, url = null),
                food("Dinner in El Calafate", "Trout or lamb on Avenida del Libertador", pace = E),
            ),
            day(
                "Fly to Santiago: Lastarria", SANTIAGO,
                flyToSantiago(),
                stay("Hotel in Lastarria", "Old quarter by Cerro Santa Lucía; the centre on foot", lastarria, Car),
                place("Barrio Lastarria", "Cobbled streets of cafés, bookshops and small museums", lastarria, Walk),
                place("Cerro Santa Lucía", "Hill where Santiago was founded in 1541 · sign in at the gate", santaLucia, Walk),
                place("GAM", "Cultural centre of 1972 · galleries closed Mondays", gam, Walk, B),
                food("Pisco sour", "The local cocktail on a terrace", pace = B),
                food("Dinner in Lastarria", "Small restaurants around Plaza Mulato Gil", pace = E),
            ),
            day(
                "Plaza de Armas and La Moneda", SANTIAGO,
                place("Plaza de Armas", "The founding square · its history museum is shut for works to ≈ 2029", plazaDeArmas, Train),
                place("Santiago Cathedral", "Cathedral rebuilt in the 1700s on the square", catedralSantiago, Walk, B),
                place("Museo Chileno de Arte Precolombino", "Art of the Andes before Spain · closed Mondays", precolombino, Walk),
                food("Lunch at the Mercado Central", "Iron market hall of 1872 · seafood at the stalls", mercadoCentral, Walk),
                place("La Moneda Palace", "Free weekday tours, booked online · bring your passport", moneda, Walk, B),
                place("Centro Cultural La Moneda", "Exhibitions under the square in front of the palace", cclm, Walk, E),
            ),
            day(
                "Neruda, the hill and Memory", SANTIAGO,
                note("Not on a Monday", "La Chascona, the museums and the hill's cable cars close"),
                place("La Chascona", "Neruda's house · Tue–Sun, no booking; audio guide", chascona, Car),
                place("Bellavista", "Murals and bars below the hill", bellavista, Walk, B),
                activity("Cerro San Cristóbal by cable car", "A new line from Pío Nono since 2026 · views to the Andes", sanCristobal, Walk),
                place("Museum of Memory and Human Rights", "The 1973–1990 dictatorship · Tue–Sun, free", memoria, Train),
                place("Barrio Yungay", "1800s quarter of old mansions and murals", yungay, Walk, E),
            ),
            day(
                "Bellas Artes, then home", SANTIAGO,
                place("Chile's Bellas Artes museum", "Free · in a palace of 1910 · closed Mondays", mnbaChile, Car),
                place("Museo de Arte Contemporáneo", "Chilean art in the back of the same palace", mac, Walk, B),
                food("Last lunch: pastel de choclo", "Corn pie baked over beef and chicken"),
                place("Barrio Italia", "Antique and design shops, cafés", barrioItalia, Car, B),
                place("Cementerio General", "Presidents' tombs and the memorial to the disappeared", cementerio, Car, E),
                flyHome(),
            ),
        ),
        ideas = listOf(
            idea("place", "San Antonio de Areco", "Gaucho town ≈ 2 h from Buenos Aires · its Tradición festival is in November", areco),
            idea("place", "Feria de Mataderos", "Sundays: gaucho crafts, folk music and food from the provinces", mataderos),
            idea("activity", "Vendimia in Mendoza", "The grape harvest festival, early March: parade and night show", url = MZA + "destino/fiesta-nacional-de-la-vendimia/"),
            idea("place", "Tigre and the delta", "Train from Retiro, then a river launch through the islands", tigreDelta),
            idea("place", "Valparaíso", "Hill port of funiculars and murals, ≈ 2 h from Santiago by bus", valparaiso),
        ),
        sources = listOf(
            "https://en.wikivoyage.org/wiki/Buenos_Aires",
            "https://en.wikivoyage.org/wiki/Mendoza",
            "https://en.wikivoyage.org/wiki/Bariloche",
            "https://en.wikivoyage.org/wiki/El_Calafate",
            "https://en.wikivoyage.org/wiki/Santiago_de_Chile",
            "https://teatrocolon.org.ar/guided-tours/",
            "https://turismo.buenosaires.gob.ar/en/otros-establecimientos/recoleta-cemetery",
            "https://turismo.buenosaires.gob.ar/es/article/mirador-obelisco",
            "https://www.senado.gob.ar/visitasguiadas",
            "https://palaciobarolo.com.ar/visitas-guiadas/",
            "https://malba.org.ar/en/visitar/",
            "https://museoevita.org.ar/",
            "https://jardinjapones.org.ar/en/home/",
            "https://elzanjon.com.ar/",
            "https://www.argentina.gob.ar/derechoshumanos/museo-sitio-de-memoria-esma/en",
            "https://querandi.com.ar/en/",
            "https://mendoza.tur.ar/destino/area-fundacional/",
            "https://mendoza.tur.ar/destino/cerro-aconcagua/",
            "https://mendoza.tur.ar/lugar/cristo-redentor-de-los-andes/",
            "https://bodegalarural.com.ar/turismo/",
            "https://barilocheturismo.gob.ar/en/civic-centre",
            "https://barilocheturismo.gob.ar/en/colonia-suiza",
            "https://barilocheturismo.gob.ar/en/short-circuit",
            "https://www.elcalafate.tur.ar/museum/centro-de-interpretacion-historica.htm",
            "https://www.elcalafate.tur.ar/northern-area-navigations/navegacion-estancia-cristina-en.htm",
            "https://www.elcalafate.tur.ar/in-perito-moreno-glacier.htm",
            "https://www.mhn.gob.cl/",
            "https://museo.precolombino.cl/planifica-tu-visita-2/",
            "https://visitaspatrimonio.presidencia.cl/",
            "https://fundacionneruda.org/museos-casa-museo-la-chascona/",
            "https://mmdh.cl/",
            "https://www.mnba.gob.cl/",
        ),
    )

    // ---- Nature & views ----
    private val outdoors = Program(
        id = "$ID-nature",
        destinationId = ID,
        focus = Focus.Nature,
        title = "Lakes, peaks and ice",
        blurb = "Buenos Aires' river parks and delta, the high Andes from Mendoza, five days on the Bariloche lakes and " +
            "five under Fitz Roy, the glacier, then Santiago's mountains.",
        days = listOf(
            day(
                "Arrive, Palermo's parks", BA,
                landAtEze(),
                stay("Small hotel in Palermo", "Close to the parks and lakes", palermoSoho, Car),
                place("Bosques de Palermo", "Lakes, paddle boats and long lawns", bosques, Car),
                place("Rosedal", "Rose garden on the lake · closed Mondays; best Nov–Dec", rosedal, Walk),
                activity("Paddle boat on the lake", "Hire one by the Rosedal", pace = B),
                place("Planetario Galileo Galilei", "Round planetarium of 1966 in the park, lit up at night", planetario, Walk, E),
                food("Parrilla dinner", "Steak and Malbec · tables fill after 21:00", pace = B),
            ),
            day(
                "The Paraná delta at Tigre", BA,
                transport("Train to Tigre", "Mitre line from Retiro ≈ 1 h", tigreStation, Train),
                activity("Boat through the delta", "Wooden river launches from the Estación Fluvial · 1–2 h", tigreDelta, Walk),
                place("Puerto de Frutos", "Riverside market of wicker, plants and delta produce", puertoFrutos, Walk),
                place("Museo de Arte Tigre", "Riverside palace of 1912 · Wed–Sun afternoons", mat, Walk, B),
                food("Lunch by the river", "Grills and cafés along the Río Luján", pace = B),
                activity("Kayak in the delta", "Hire a kayak or join a guided paddle", pace = E),
                transport("Back to Buenos Aires", "", palermoSoho, Train),
            ),
            day(
                "Costanera Sur reserve", BA,
                note("Not on a Monday", "The reserve and the Rosedal close on Mondays"),
                place("Jardín Botánico", "Garden of 1898 with old glasshouses · closed Mondays and in rain", botanico, Car, B),
                place("Reserva Ecológica Costanera Sur", "≈ 350 ha of marsh and river shore · birds", reserva, Car),
                food("Choripán by the reserve", "Sausage sandwiches from the grill carts on the Costanera", costaneraSur, Walk),
                place("Puente de la Mujer", "Footbridge over Puerto Madero's old docks", puenteMujer, Walk, B),
                place("Parque Lezama", "Hilly park of old trees above San Telmo", parqueLezama, Car, E),
            ),
            day(
                "Fly to Mendoza: Cerro de la Gloria", MENDOZA,
                flyToMendoza(),
                stay("Hotel near Plaza Independencia", "Central; the park is a short taxi ride", plazaIndependencia, Car),
                place("Parque General San Martín", "Park of 1896 with a lake and a rose garden", parqueSanMartin, Car),
                place("Cerro de la Gloria", "Hilltop monument with the Andes behind the city", cerroGloria, Car),
                activity("Cerro Arco hike", "≈ 1 h 30–2 h return above El Challao · tell someone your route", cerroArco, Car, B),
                activity("Paddle on the park lake", "Boats on Parque San Martín's lake", parqueSanMartin, Car, E),
                food("Dinner under the plane trees", "Mendoza eats outdoors on warm evenings", pace = B),
            ),
            day(
                "Aconcagua and the high Andes", MENDOZA,
                note("An early start", "≈ 3 h each way on Ruta 7 · a tour or a hired car"),
                place("Potrerillos dam", "Turquoise reservoir where the road enters the mountains", potrerillos, Car, B),
                place("Puente del Inca", "Natural stone bridge and a ruined spa · seen from the lookout", puenteInca, Car),
                activity("Laguna de Horcones walk", "Easy loop under Aconcagua · book the slot online first", horcones, Car),
                activity("Confluencia day trek", "6–8 h return under the south face · Nov–Apr, permit online, 16+", confluencia, Walk, E),
                food("Lunch in Uspallata", "Last village before the pass, on the way back", uspallata, Car, B),
                transport("Back to Mendoza", "", plazaIndependencia, Car),
            ),
            day(
                "River, hot springs, foothills", MENDOZA,
                place("Reserva Natural Villavicencio", "Old spa hotel and trails in the foothills · Wed–Sun", villavicencio, Car, E),
                activity("Rafting on the Mendoza river", "Short white-water runs from Potrerillos", potrerillos, Car, B),
                activity("Termas de Cacheuta", "Hot pools in the gorge · daily 10–18; book weekends", cacheuta, Car),
                food("Lunch at a Luján winery", "Vines with the Andes behind · book ahead"),
                food("Picnic by the Mendoza river", "Bread, cheese and olives below the gorge", pace = B),
            ),
            day(
                "Fly to Bariloche: Cerro Otto", BARILOCHE,
                flyToBariloche(),
                stay("Hotel by the lake", "In town or on the lake road towards Llao Llao", centroCivico, Car),
                place("Cerro Otto cable car", "≈ 12 min up to 1,405 m: a revolving café, the lake below", ottoTop, Car),
                activity("Walk down Cerro Otto", "≈ 1 h 30 down the forest track instead of the cable car", ottoBase, Walk, E),
                place("Playa Bonita", "Beach on the lake road, facing Isla Huemul", playaBonita, Car, B),
                activity("Lakeshore walk", "Along the Costanera to the cathedral", catedralBariloche, Car, url = null),
                food("Dinner by the lake", "Trout or lamb with Nahuel Huapi in the window", pace = B),
            ),
            day(
                "Circuito Chico by bike", BARILOCHE,
                place("Cerro Campanario chairlift", "≈ 7 min up to 1,050 m: the classic view over the lakes", campanario, Car),
                activity("Circuito Chico by bike", "≈ 25 km loop from the bike hires near km 18 · some hills", puntoPanoramico, Bike),
                activity("Cerro Llao Llao trail", "Forest loop to a lookout · ≈ 1 h 30", cerroLlaoLlao, Bike, B),
                place("Bahía López", "Quiet bay under Cerro López", bahiaLopez, Bike, B),
                place("Lago Escondido", "Small hidden lake down a forest path off the loop", escondido, Bike, E),
            ),
            day(
                "Isla Victoria and the Arrayanes", BARILOCHE,
                boatToArrayanes(),
                place("Bosque de Arrayanes", "Cinnamon-barked myrtle trees on a boardwalk loop", arrayanes, Ferry),
                place("Isla Victoria", "Island trails and Playa del Toro's old rock paintings", islaVictoria, Ferry, B),
                boatBack(),
                food("Picnic on the beach", "Bakery bread, cheese and smoked trout", pace = B),
                place("Lago Gutiérrez at sunset", "Beach on a calmer lake south of town", gutierrez, Car, E),
            ),
            day(
                "Cerro Catedral and Refugio Frey", BARILOCHE,
                place("Cerro Catedral chairlifts", "Summer lifts to the ridge for lake views", catedralBase, Car),
                activity("Hike to Refugio Frey", "≈ 4 h up from the Cerro Catedral car park, ≈ 700 m of climb · free registration online first", frey, Walk, B),
                food("Lunch at the hut", "The hut serves meals, by its small lake", frey, Walk, B, url = null),
                place("Lago Gutiérrez beach", "A swim or a rest on the way back", gutierrez, Car),
                food("Craft beer back in town", "A pint after the walk", pace = E),
            ),
            day(
                "Tronador and the black glacier", BARILOCHE,
                note("Check the road first", "A bridge fell in Sep 2026: check it reopened · one-way, up 10:30–14:00, down 16–18"),
                place("Pampa Linda", "Meadow under Tronador, with a lodge and food", pampaLinda, Car),
                place("Ventisquero Negro", "The last lookout: a glacier dark with rock and grit", ventisquero, Car),
                place("Castaño Overa lookout", "Short walk to the view of the hanging glaciers", castanoOvera, Walk, B),
                food("Lunch at Pampa Linda", "Lodge café in the meadow", pampaLinda, Car, B, url = null),
                place("Lago Mascardi", "Turquoise lake on the way back", mascardi, Car, E),
            ),
            day(
                "Fly south to El Chaltén", CHALTEN,
                flyToCalafate(),
                food("Lunch in El Calafate", "Between the plane and the bus", calafate, Car, E),
                busToChalten(),
                stay("Small lodge in El Chaltén", "Five nights: the trails start at the edge of town", chalten, Walk),
                place("Park visitor centre", "Rangers' talk on trails and weather · the trail fee is paid by card or QR", visitorCentre, Walk),
                place("Mirador de los Cóndores", "≈ 1 h 30 up from the visitor centre · condors, and Fitz Roy at sunset", condores, Walk),
                place("Mirador de las Águilas", "On past the condor lookout, over Lago Viedma", aguilas, Walk, B),
                food("Patagonian lamb", "Cordero slow-roasted by the fire", pace = B),
            ),
            day(
                "Laguna Torre", CHALTEN,
                place("Cascada Margarita lookout", "A waterfall view early on the trail", margarita, Walk),
                place("Mirador Laguna Torre", "≈ 1 h 15 (5 km) up: the first view of Cerro Torre", miradorTorre, Walk),
                activity("Laguna Torre", "≈ 11 km each way, a medium walk · icebergs under Cerro Torre", lagunaTorre, Walk, B),
                food("Picnic at the lake", "Out of the wind behind the moraine", pace = B),
                place("Mirador Maestri", "On along the moraine ridge to the old Maestri camp", maestri, Walk, E),
            ),
            day(
                "Laguna de los Tres", CHALTEN,
                note("Start early", "The last hour is steep and can close in wind or snow"),
                activity("Laguna Capri", "≈ 1 h 45 up through the forest · Fitz Roy over the water", capri, Walk),
                place("Mirador Fitz Roy", "Open view of the massif", miradorFitzRoy, Walk),
                activity("Laguna de los Tres", "≈ 4 h each way, a hard walk · the last hour is a steep climb", losTres, Walk, B),
                food("Lunch at the foot of the climb", "Bring it all: no food on the trails", pace = B),
                place("Laguna Sucia lookout", "Over the moraine to a glacier lake below Fitz Roy", sucia, Walk, E),
            ),
            day(
                "Lago del Desierto", CHALTEN,
                place("Chorrillo del Salto", "A 20 m waterfall in the woods ≈ 4 km north of town", chorrillo, Car),
                activity("Lago del Desierto", "≈ 37 km north on Ruta 41 up the Río de las Vueltas · a tour or transfer", desierto, Car),
                activity("Glaciar Huemul walk", "≈ 1 h from the lake to the glacier front · entry fee", huemul, Walk, B),
                food("Mate and tortas fritas", "Fried dough with mate, a Patagonian afternoon", pace = B),
                place("Mirador Río de las Vueltas", "Valley view on the road back to town", vueltas, Car, E),
            ),
            day(
                "Spare day: Pliegue Tumbado", CHALTEN,
                note("Keep it for the weather", "Fitz Roy often hides: use this day for the hike the clouds took"),
                activity("Loma del Pliegue Tumbado", "≈ 4 h each way · Lago Viedma, Cerro Torre and Fitz Roy in one view", pliegue, Walk, B),
                food("Coffee and cake in town", "A slow morning before or after", pace = Pace.Chill),
                place("Mirador de los Cóndores again", "Sunset, if the sky clears", condores, Walk),
                food("Last dinner under Fitz Roy", "Lamb from the steppe estancias, by the fire", pace = B),
                place("Capilla de los Escaladores", "Small chapel for the climbers who died on these peaks", escaladores, Walk, E),
            ),
            day(
                "Back to El Calafate: Nimez", CALAFATE,
                busToCalafate(),
                stay("Hotel in El Calafate", "Near Avenida del Libertador", calafate, Car),
                place("Reserva Laguna Nimez", "Bird reserve by Lago Argentino, 800 m from the main street", nimez, Walk),
                place("Glaciarium", "Ice museum 6 km west · an hourly van from the centre", glaciarium, Car),
                food("Dinner by Lago Argentino", "Lamb or trout with the lake in view", pace = B),
                activity("Lago Argentino shore", "Walk the lakefront below town at dusk", calafate, Car, B, url = null),
                activity("Bike along the lake", "Hire a bike for the flat lakeside path", pace = E),
            ),
            day(
                "Perito Moreno: boat and ice", CALAFATE,
                place("Perito Moreno Glacier", "≈ 80 km west · walkways facing the ice wall", moreno, Car),
                activity("Boat to the ice face", "≈ 1 h on the lake below the glacier", bajoSombras, Car),
                activity("Minitrekking", "≈ 1 h 30 on the ice in crampons, with guides · all year, ages 8–65; book ahead", bajoSombras, Walk, B),
                food("Picnic at the glacier", "Bring lunch, or use the café by the walkways", pace = B),
                activity("Watch the ice fall", "Wait on the lowest walkway for a block to calve", moreno, Walk, E),
            ),
            day(
                "Fly to Santiago: San Cristóbal", SANTIAGO,
                flyToSantiago(),
                stay("Hotel in Lastarria", "Between the two city hills", lastarria, Car),
                activity("Cerro San Cristóbal by cable car", "Views to the Andes · the lifts close on Mondays", sanCristobal, Car),
                place("Cerro Santa Lucía", "Hill where Santiago was founded in 1541 · sign in at the gate", santaLucia, Car, E),
                place("Parque Forestal", "Riverside park of plane trees", parqueForestal, Walk, B),
                place("Sky Costanera", "Deck at 300 m: the city and the Andes at sunset", sky, Train),
                food("Dinner in Providencia", "Restaurants around the tower's neighbourhood", pace = B),
            ),
            day(
                "Cajón del Maipo: El Yeso", SANTIAGO,
                note("A mountain day", "Go with a tour: the last stretch is gravel and needs a 4×4"),
                place("San José de Maipo", "Old village at the mouth of the canyon", sanJoseMaipo, Car, B),
                place("Embalse El Yeso", "Turquoise reservoir high in the Andes · seen from the road", yeso, Car),
                food("Picnic in the valley", "Bread, cheese and empanadas from the village"),
                activity("El Morado glacier walk", "Trail to a lake under a hanging glacier · tickets online; last entry ≈ 12:30", morado, Car, E),
                food("Empanadas de pino", "Beef, onion, egg and olive, on the way back", pace = B),
            ),
            day(
                "Flamingos and murals, then home", SANTIAGO,
                place("Parque Bicentenario", "Lagoon of flamingos and black-necked swans", bicentenario, Car),
                place("Bellavista murals", "Painted streets below Cerro San Cristóbal", bellavista, Car),
                food("Lunch in Bellavista", "Small restaurants below the hill", pace = B),
                activity("Vineyard walk at Cousiño Macul", "City vineyard under the Andes · tours, book", cousino, Car, B),
                place("Barrio Italia", "Antique and design shops, cafés", barrioItalia, Car, E),
                flyHome(),
            ),
        ),
        ideas = listOf(
            idea("place", "Iguazú Falls", "≈ 2 h flight from Buenos Aires · two days for both sides of the falls", iguazu),
            idea("place", "Tierra del Fuego National Park", "Ushuaia, at the end of the world · fly from El Calafate", tierraDelFuego),
            idea("place", "Torres del Paine", "Chile's granite towers · ≈ 5–6 h by bus from El Calafate", torresDelPaine),
            idea("place", "Puerto Blest by boat", "Full day across Nahuel Huapi to a fjord-like arm and waterfalls", puertoBlest),
            idea("place", "Estancia Cristina", "Boat and 4×4 to a lookout over the Upsala Glacier · from October", estanciaCristina),
        ),
        sources = listOf(
            "https://en.wikivoyage.org/wiki/Bariloche",
            "https://en.wikivoyage.org/wiki/El_Chalt%C3%A9n",
            "https://en.wikivoyage.org/wiki/El_Calafate",
            "https://en.wikivoyage.org/wiki/Mendoza",
            "https://turismo.buenosaires.gob.ar/en/article/costanera-sur-ecological-reserve",
            "https://turismo.buenosaires.gob.ar/en/otros-establecimientos/palermo-rose-garden",
            "https://vivitigre.gob.ar/en/actividades/puerto-de-frutos-en/",
            "https://www.mat.gov.ar/",
            "https://mendoza.tur.ar/destino/cerro-aconcagua/",
            "https://mendoza.tur.ar/destino/precordillera-y-alta-montana/",
            "https://rnvillavicencio.com.ar/",
            "https://termascacheuta.com/",
            "https://barilocheturismo.gob.ar/en/mount-otto",
            "https://barilocheturismo.gob.ar/en/mount-campanario",
            "https://barilocheturismo.gob.ar/en/short-circuit",
            "https://barilocheturismo.gob.ar/en/victoria-island-and-myrtle-woods",
            "https://barilocheturismo.gob.ar/en/mount-catedral",
            "https://barilocheturismo.gob.ar/en/frey-hut",
            "https://barilocheturismo.gob.ar/en/mount-tronador",
            "https://www.argentina.gob.ar/parquesnacionales/losglaciares",
            "https://elchalten.tur.ar/laguna-torre/",
            "https://elchalten.tur.ar/laguna-de-los-tres/",
            "https://elchalten.tur.ar/loma-del-pliegue-tumbado-laguna-toro/",
            "https://elchalten.tur.ar/ruta-escenica-area-protegida-recreativa-lago-del-desierto/",
            "https://www.elcalafate.tur.ar/in-perito-moreno-glacier.htm",
            "https://www.elcalafate.tur.ar/in-perito-moreno-glacier/minitrekking-en.htm",
            "https://chile.travel/en/attractions/embalse-el-yeso/",
            "https://chile.travel/en/attractions/el-morado-natural-monument/",
            "https://chile.travel/en/attractions/cerro-san-cristobal/",
        ),
    )

    val destination: Destination = Destination(
        id = ID,
        title = "Argentina & Patagonia",
        blurb = "Buenos Aires, Mendoza's wine country, the Bariloche lakes, Fitz Roy and the Perito Moreno Glacier, " +
            "ending in Santiago de Chile.",
        countryCodes = listOf("AR", "CL"),
        zoneId = "America/Argentina/Buenos_Aires",
        bestTime = "Best November–March: Patagonia's trails, boats and buses run then; winter (June–August) shuts much of it",
        programs = listOf(eats, sights, outdoors),
    )
}

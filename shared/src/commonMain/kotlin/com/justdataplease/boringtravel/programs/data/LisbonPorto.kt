package com.justdataplease.boringtravel.programs.data

import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.model.Vehicle.Bike
import com.justdataplease.boringtravel.model.Vehicle.Car
import com.justdataplease.boringtravel.model.Vehicle.Ferry
import com.justdataplease.boringtravel.model.Vehicle.Train
import com.justdataplease.boringtravel.model.Vehicle.Walk
import com.justdataplease.boringtravel.programs.Destination
import com.justdataplease.boringtravel.programs.Focus
import com.justdataplease.boringtravel.programs.Pace
import com.justdataplease.boringtravel.programs.Pace.Balanced
import com.justdataplease.boringtravel.programs.Pace.Energetic
import com.justdataplease.boringtravel.programs.Program
import com.justdataplease.boringtravel.programs.ProgramDay
import com.justdataplease.boringtravel.programs.ProgramItem

/**
 * Ready-made programs for Lisbon & Porto: nine days each, the shape of the demo trip (Lisbon with a day in Sintra, the
 * train north, Porto with a day in the Douro valley, fly home from Porto). Sights & history is the demo's plan: its
 * days keep the journal's places on the same day numbers (see research/boringtravel/programs/lisbon-porto.md).
 *
 * Points are checked against OpenStreetMap and Wikipedia; facts that shape a day (closed days, timed entries, works)
 * were checked in November 2026 against the pages in [Program.sources]. All text is our own.
 */
object LisbonPorto {
    const val ID = "lisbon-porto"
    private const val LISBON = "Lisbon"
    private const val PORTO = "Porto"

    // Trains, trams and the ferry: the operator's page for the service (checked November 2026).
    private const val CP_SINTRA = "https://www.cp.pt/info/en/w/discover-sintra"
    private const val CP_CASCAIS = "https://www.cp.pt/info/en/w/discover-cascais"
    private const val CP_ALFA = "https://www.cp.pt/info/en/alfa-pendular-train"
    private const val CP_DOURO = "https://www.cp.pt/info/en/w/institutional/the-douro-line"
    private const val FERTAGUS = "https://www.fertagus.pt/en"
    private const val CACILHAS_FERRY = "https://ttsl.pt/passageiros/horarios-de-ligacoes-fluviais/ligacao-cacilhas-cais-do-sodre/"
    private const val TRAM_28 = "https://www.carris.pt/en/travel/carreiras/28e/"
    private const val TRAM_1 = "https://stcp.pt/en/lines/1"

    /** Pages behind facts every program uses: transport, Sintra tickets, Belém, Lello, the Douro line. */
    private val SHARED_SOURCES = listOf(
        "https://www.visitlisboa.com/en/places/jeronimos-monastery",
        "https://www.sabado.pt/gps/detalhe/torre-de-belem-reabre-a-brilhar-e-com-novo-sistema-de-entradas-para-reduzir-filas",
        "https://bilheteira.parquesdesintra.pt/info/parque-e-palacio-da-pena/1202/en",
        "https://www.parquesdesintra.pt/en/plan-your-visit/new-tickets-pnp/",
        "https://www.parquesdesintra.pt/en/parks-monuments/park-and-palace-of-monserrate/",
        "https://www.regaleira.pt/en/visitas",
        "https://en.wikivoyage.org/wiki/Sintra",
        "https://www.livrarialello.pt/en/",
        "https://www.cp.pt/info/pt/w/linha-douro",
        "https://www.seat61.com/places-of-interest/douro-valley-by-train.htm",
        "https://en.wikipedia.org/wiki/Pinh%C3%A3o_railway_station",
        "https://www.symington.com/visit/quinta-do-bomfim/",
        "https://www.openstreetmap.org",
    )

    // ---- Eat & drink ----

    private val food = Program(
        id = "$ID-food",
        destinationId = ID,
        focus = Focus.Food,
        title = "Markets, tascas and port",
        blurb = "Lisbon's markets and seafood halls, Setúbal fish and Azeitão Moscatel, then Porto's Bolhão, " +
            "the port lodges and a Douro quinta.",
        days = listOf(
            ProgramDay(
                "Baixa and a first ginjinha", LISBON, listOf(
                    transport("Fly to Lisbon", "Taxi to the Baixa ≈ 20 min", S.LIS, null, S.LIS.url),
                    stay("Small hotel in Baixa or Chiado", "Five nights, walking distance to markets and trams", S.CHIADO, Car),
                    food("Confeitaria Nacional", "Pastry shop on Praça da Figueira since 1829", S.CONFEITARIA, Walk, Balanced),
                    food("Ginjinha at Largo de São Domingos", "Sour-cherry liqueur by the shot, at a counter open since c. 1840", S.GINJINHA, Walk),
                    place("Praça do Comércio", "The riverside square; out through the arch at dusk", S.COMERCIO, Walk, Balanced),
                    food("Dinner in a tasca", "Grilled fish, house wine. Sardines in summer"),
                    activity("Fado in Alfama", "A late set in a small house. Book ahead", S.ALFAMA, Walk, Energetic, "21:00"),
                ),
            ),
            ProgramDay(
                "Markets and tinned fish", LISBON, listOf(
                    food("Conserveira de Lisboa", "Tinned sardines and mackerel in paper wrappers, a shop since 1930", S.CONSERVEIRA, Walk, Balanced),
                    food("Time Out Market", "Lunch from the stalls in the old Ribeira market hall. Open daily", S.TIME_OUT, Walk),
                    food("Pastel de nata at Manteigaria", "Custard tarts baked in sight all day", S.MANTEIGARIA, Walk, Energetic),
                    food("Wine bar in Príncipe Real", "Portuguese wines by the glass with petiscos", S.PRINCIPE_REAL, Walk, Balanced),
                    food("Cervejaria Ramiro", "Seafood hall: garlic prawns, clams, a steak roll to finish. Closed Mondays", S.RAMIRO, Car),
                ),
            ),
            ProgramDay(
                "Belém and the riverside", LISBON, listOf(
                    food("Pastéis de Belém", "Custard tarts sold here since 1837. Go early, eat them warm inside", S.PASTEIS, Car),
                    place("Jerónimos Monastery", "Book a timed entry for the cloister. Closed Mondays", S.JERONIMOS, Walk, Balanced),
                    place("Belém Tower", "Reopened in May 2026 with timed slots. Closed Mondays", S.BELEM_TOWER, Walk, Energetic),
                    food("Lunch at LX Factory", "Old print works under the bridge, full of places to eat", S.LX_FACTORY, Car),
                    place("Miradouro de Santa Catarina", "A drink at the kiosk as the sun goes down over the river", S.SANTA_CATARINA, Car, Balanced),
                    food("Petiscos in Bairro Alto", "Small plates: octopus salad, pica-pau, peixinhos da horta", S.BAIRRO_ALTO, Walk, Energetic),
                ),
            ),
            ProgramDay(
                "Sintra: pastries, palaces and Colares wine", LISBON, listOf(
                    transport("Train to Sintra", "From Rossio, ≈ 40 min, about every 20 min", S.SINTRA_STATION, Train, CP_SINTRA),
                    place("Pena Palace", "Bus 434 up. Book the timed entry: late means no entry", S.PENA, Car, Balanced, "10:30"),
                    food("Travesseiros at Piriquita", "Almond puff pastry from a bakery open since 1862. Try a queijada too", S.PIRIQUITA, Car),
                    place("Quinta da Regaleira", "Timed tickets. Gardens, grottoes and the spiral well", S.REGALEIRA, Walk),
                    activity("Colares wine at the Adega Regional", "Wine from vines rooted in sand by the sea. Weekdays, book a visit", S.COLARES, Car, Balanced),
                    food("Late lunch at Azenhas do Mar", "Fish with the view of the cliff village and its sea pool", S.AZENHAS, Car, Energetic),
                    transport("Back to Lisbon", "Bus or taxi to Sintra, then the train to Rossio", S.ROSSIO, Train, CP_SINTRA),
                ),
            ),
            ProgramDay(
                "Setúbal and Azeitão", LISBON, listOf(
                    transport("Train to Setúbal", "Fertagus from Roma-Areeiro or Entrecampos over the bridge, ≈ 1 h", S.SETUBAL_STATION, Train, FERTAGUS),
                    food("Mercado do Livramento", "Tiled fish market. Mornings to about 14:00; closed Mondays", S.LIVRAMENTO, Walk, time = "09:30"),
                    food("Choco frito for lunch", "Setúbal's fried cuttlefish, near the harbour"),
                    place("Portinho da Arrábida", "Bay under green hills. Summer: no private cars, buses from Setúbal", S.PORTINHO, Car, Energetic),
                    activity("Moscatel at José Maria da Fonseca", "Old cellars in Azeitão; visits with a tasting, book ahead", S.JMF, Car, Balanced),
                    food("Azeitão cheese and tortas", "Soft sheep's cheese and rolled egg-cake tortas at a village café", S.AZEITAO, Walk, Balanced),
                    transport("Back to Lisbon", "Train from Setúbal; from Azeitão take a bus or taxi first", S.ROMA_AREEIRO, Train, FERTAGUS),
                ),
            ),
            ProgramDay(
                "North to Porto", PORTO, listOf(
                    transport("Train to Porto", "Alfa Pendular from Santa Apolónia or Oriente, ≈ 3 h. Book seats", S.CAMPANHA, Train, CP_ALFA),
                    stay("Small hotel in Ribeira or near São Bento", "Three nights. Old houses: ask about stairs", S.RIBEIRA, Train),
                    place("São Bento Station", "Look in at the tiled hall", S.SAO_BENTO, Walk, Energetic),
                    food("Coffee at Majestic Café", "Belle Époque café on Rua de Santa Catarina, from 1921", S.MAJESTIC, Walk, Balanced),
                    food("Francesinha at Café Santiago", "Porto's layered sandwich under cheese and spicy sauce. Closed Sundays", S.SANTIAGO, Walk),
                    place("Cais da Ribeira", "Evening walk on the riverfront", S.CAIS_RIBEIRA, Walk),
                    food("Vinho verde by the river", "A glass of the young, light northern wine"),
                ),
            ),
            ProgramDay(
                "Bolhão and the port lodges", PORTO, listOf(
                    place("Livraria Lello", "Timed tickets; the price comes off a book. Opening time is quietest", S.LELLO, Walk, Energetic, "09:00"),
                    food("Mercado do Bolhão", "Restored market hall: fish, cheese, ham, a glass of wine. Closed Sundays", S.BOLHAO, Walk),
                    food("Pork roll at Casa Guedes", "Slow-roast pork in a roll, at the counter", S.CASA_GUEDES, Walk, Balanced),
                    place("Dom Luís I Bridge", "Walk the top deck across to Gaia", S.DOM_LUIS, Walk, Balanced),
                    activity("Port lodge tour and tasting", "One of the old lodges on the Gaia side; book in summer", S.GAIA, Walk),
                    food("Tripas à moda do Porto", "The city's tripe and bean stew, for the brave"),
                ),
            ),
            ProgramDay(
                "Douro valley: a quinta and the river", PORTO, listOf(
                    transport("Train up the Douro", "From São Bento, ≈ 2 h 30. Winter works can put a bus on part of it", S.PINHAO_STATION, Train, CP_DOURO),
                    place("Pinhão station tiles", "24 tile panels of the vineyards and rabelo boats, from 1937", S.PINHAO_STATION, Walk, Balanced),
                    activity("Tasting at Quinta do Bomfim", "Port house a few minutes from the station. Book a visit", S.BOMFIM, Walk),
                    food("Lunch in Pinhão", "Roast kid or veal with a Douro red"),
                    activity("Boat trip from Pinhão", "An hour on the river from the piers by the bridge", S.PINHAO_PIERS, Walk, Balanced),
                    place("Casal de Loivos viewpoint", "The big view of the bends. Taxi up, or a steep hour on foot", S.CASAL_LOIVOS, Car, Energetic),
                    transport("Back to Porto", "Evening train to São Bento", S.SAO_BENTO, Train, CP_DOURO),
                ),
            ),
            ProgramDay(
                "Matosinhos fish and home", PORTO, listOf(
                    place("Foz do Douro", "Bus 500 out to the river mouth. Coffee on the seafront", S.FELGUEIRAS, Car),
                    activity("Seafront walk to Matosinhos", "North along the ocean promenade", S.MATOSINHOS_BEACH, Walk, Balanced),
                    food("Mercado de Matosinhos", "Fish market with the morning catch. Closed Sundays", S.MATOSINHOS_MARKET, Walk, Balanced),
                    food("Grilled fish in Matosinhos", "Fish grilled on the pavement outside the restaurants", S.MATOSINHOS_FISH, Walk),
                    place("Piscina das Marés", "Siza's tidal pools in the rocks. Swimming June to mid-September", S.MARES, Walk, Energetic),
                    transport("Fly home", "Airport ≈ 15 min by taxi", S.OPO, Car, S.OPO.url),
                ),
            ),
        ),
        ideas = listOf(
            activity("Cooking class in Lisbon", "Bacalhau or custard tarts in a small class. Book ahead"),
            food("Mercado de Campo de Ourique", "Neighbourhood market with food counters and wine", S.CAMPO_OURIQUE),
            food("Bifana", "Pork in garlic and wine in a soft roll, at any old counter"),
            place("Aveiro", "Canals and ovos moles, a stop on the way north", S.AVEIRO),
        ),
        sources = SHARED_SOURCES + listOf(
            "https://en.wikipedia.org/wiki/Pastel_de_nata",
            "https://pt.wikipedia.org/wiki/Confeitaria_Nacional",
            "https://www.lojascomhistoria.pt/shops/a-ginjinha",
            "https://www.lojascomhistoria.pt/shops/conserveira-de-lisboa",
            "https://www.timeout.com/time-out-market-lisboa/how-to-get-there",
            "https://visitsintra.travel/en/eat-drink/regional-flavours/travesseiros",
            "https://visitsetubal.com/en/mercado-do-livramento-2/",
            "https://www.jmf.pt",
            "https://visitporto.travel/poi/5ff5e2da9296f500018d0904",
            "https://mercadobolhao.pt/en/faqs/",
        ),
    )

    // ---- Sights & history (the demo trip's plan) ----

    private val culture = Program(
        id = "$ID-culture",
        destinationId = ID,
        focus = Focus.Culture,
        title = "Monasteries, palaces and tiles",
        blurb = "Alfama and the castle, Belém's monastery, the Sintra palaces, then Porto's tiled churches, " +
            "Lello and the port cellars, and a day up the Douro.",
        days = listOf(
            ProgramDay(
                "Alfama, first evening", LISBON, listOf(
                    transport("Fly to Lisbon", "Taxi to Alfama ≈ 20 min; the metro needs a change", S.LIS, null, S.LIS.url),
                    stay("Guesthouse or small hotel in Alfama", "Five nights. Steep lanes and stairs: pack light", S.ALFAMA_STAY, Car),
                    place("Santo António Church", "Small church where the saint was born, by the cathedral", S.SANTO_ANTONIO, Walk, Energetic),
                    place("Lisbon Cathedral", "The Sé, begun in 1147. Tourist visits Monday to Saturday", S.SE, Walk, Balanced),
                    activity("Alfama lanes", "Wander down towards the river: stairs, laundry, small squares", S.ALFAMA, Walk, Balanced),
                    place("Miradouro de Santa Luzia", "Tiled terrace over the roofs and the river. Best near sunset", S.SANTA_LUZIA, Walk),
                    food("Dinner in a tasca", "Grilled fish or bacalhau and the house wine"),
                ),
            ),
            ProgramDay(
                "Tram 28 and the castle", LISBON, listOf(
                    activity("Tram 28 from Martim Moniz", "Board at the first stop before 9, it fills fast. Off at Portas do Sol", S.MARTIM_MONIZ, Walk, Balanced, "08:30", TRAM_28),
                    place("São Jorge Castle", "Walls, towers and the city below. Open daily from 9:00", S.CASTLE, Walk),
                    place("Miradouro da Graça", "Pine shade, a kiosk and the castle across the roofs", S.GRACA, Walk),
                    place("São Vicente de Fora", "Tiled cloisters and a walk on the roof. Closed Mondays", S.SAO_VICENTE, Walk, Balanced),
                    place("National Pantheon", "Up to the terrace round the dome. Closed Mondays", S.PANTHEON, Walk, Energetic),
                ),
            ),
            ProgramDay(
                "Belém", LISBON, listOf(
                    place("Jerónimos Monastery", "Tram 15 out. Book a timed entry for the cloister; closed Mondays", S.JERONIMOS, Car, time = "09:30"),
                    food("Pastéis de Belém", "Custard tarts sold here since 1837. Eat them warm inside", S.PASTEIS, Walk),
                    place("Belém Tower", "Reopened in May 2026 after a year of works. Timed slots; closed Mondays", S.BELEM_TOWER, Walk, Balanced),
                    place("MAAT", "Walk over the curved roof by the river. Museum closed Tuesdays", S.MAAT, Walk, Energetic),
                    place("LX Factory", "Old print works under the bridge: a big bookshop and lunch", S.LX_FACTORY, Car, Balanced),
                ),
            ),
            ProgramDay(
                "Day trip to Sintra", LISBON, listOf(
                    transport("Train to Sintra", "From Rossio, ≈ 40 min, about every 20 min", S.SINTRA_STATION, Train, CP_SINTRA),
                    place("Pena Palace", "Bus 434 up. Book the timed entry: late means no entry", S.PENA, Car, time = "10:30"),
                    place("Moorish Castle", "Path down from Pena to the walls. Narrow, few railings", S.MOUROS, Walk, Balanced),
                    place("Sintra National Palace", "The two white chimneys in town; tiled rooms inside", S.SINTRA_PALACE, Walk, Energetic),
                    food("Travesseiros at Piriquita", "Almond puff pastry from a bakery open since 1862", S.PIRIQUITA, Walk, Balanced),
                    place("Quinta da Regaleira", "Timed tickets. Walk down the spiral initiation well", S.REGALEIRA, Walk),
                    place("Monserrate", "Bus 435. A garden of plants from far away; roof works into 2027", S.MONSERRATE, Car, Energetic),
                    transport("Back to Lisbon", "Train to Rossio", S.ROSSIO, Train, CP_SINTRA),
                ),
            ),
            ProgramDay(
                "Gulbenkian, Chiado and Bairro Alto", LISBON, listOf(
                    note("Slow day", "Long lunch, a rest, then out late"),
                    place("Gulbenkian Museum", "Egypt to Lalique in calm rooms, and a garden. Closed Tuesdays", S.GULBENKIAN, Train, Balanced),
                    food("Time Out Market", "Lunch at the long tables in the old Ribeira market hall", S.TIME_OUT, Car),
                    place("Carmo Convent", "Gothic church left roofless by the 1755 earthquake. Closed Sundays", S.CARMO, Walk, Balanced),
                    place("São Roque Church", "Plain front, gilded chapels inside", S.SAO_ROQUE, Walk, Energetic),
                    place("Miradouro de São Pedro de Alcântara", "Garden terrace looking across to the castle", S.SAO_PEDRO, Walk, Energetic),
                    activity("Bairro Alto evening", "Small bars, then fado: book a table, the songs come between courses", S.BAIRRO_ALTO, Walk, time = "21:00"),
                ),
            ),
            ProgramDay(
                "North to Porto", PORTO, listOf(
                    transport("Train to Porto", "Alfa Pendular from Santa Apolónia or Oriente, ≈ 3 h. Book seats", S.CAMPANHA, Train, CP_ALFA),
                    place("São Bento Station", "Short train from Campanhã. About 20,000 tiles in the hall", S.SAO_BENTO, Train),
                    stay("Flat or small hotel in Ribeira", "Three nights. Old houses: ask about stairs", S.RIBEIRA, Walk),
                    place("Porto Cathedral", "Fortress-like Sé on the hill; small fee for the cloister", S.SE_PORTO, Walk, Balanced),
                    place("Church of São Francisco", "Gothic outside, carved gilt wood inside, catacombs below", S.SAO_FRANCISCO, Walk, Energetic),
                    place("Palácio da Bolsa", "Guided visits only. The Arab Room is the one to see", S.BOLSA, Walk, Energetic),
                    place("Cais da Ribeira", "The riverfront at dusk: boats, washing lines, the bridge", S.CAIS_RIBEIRA, Walk),
                    food("Francesinha for dinner", "Porto's layered sandwich under cheese and sauce. Share one", pace = Balanced),
                ),
            ),
            ProgramDay(
                "Lello and the cellars", PORTO, listOf(
                    place("Livraria Lello", "Timed tickets; the price comes off a book. Opening time is quietest", S.LELLO, Walk, time = "09:00"),
                    place("Igreja do Carmo", "The blue-and-white tile wall on the side street", S.CARMO_PORTO, Walk, Energetic),
                    place("Clérigos Tower", "240 steps up Nasoni's baroque tower for the rooftops", S.CLERIGOS, Walk, Balanced),
                    place("Capela das Almas", "Small chapel covered in tiles on Rua de Santa Catarina", S.ALMAS, Walk, Energetic),
                    place("Dom Luís I Bridge", "Walk the top deck across to Gaia, beside the metro", S.DOM_LUIS, Walk),
                    place("Serra do Pilar", "Monastery terrace looking straight down on Ribeira", S.SERRA_PILAR, Walk, Balanced),
                    activity("Port cellars in Gaia", "Tour and tasting in one of the old lodges; book in summer", S.GAIA, Walk),
                ),
            ),
            ProgramDay(
                "Douro valley", PORTO, listOf(
                    transport("Train up the Douro", "From São Bento, ≈ 2 h 30. Winter works can put a bus on part of it", S.PINHAO_STATION, Train, CP_DOURO),
                    place("Pinhão station tiles", "24 tile panels of the vineyards and rabelo boats, from 1937", S.PINHAO_STATION, Walk),
                    activity("Vineyard walk above Pinhão", "Up the lanes into the terraces. Little shade: take water"),
                    place("Quinta do Bomfim", "Port house a few minutes from the station. Book a visit and tasting", S.BOMFIM, Walk, Balanced),
                    activity("Boat trip from Pinhão", "An hour on the river from the piers by the bridge", S.PINHAO_PIERS, Walk, Balanced),
                    place("Casal de Loivos viewpoint", "The classic view of the bends. A steep hour up, ≈ 330 m", S.CASAL_LOIVOS, Walk, Energetic),
                    transport("Back to Porto", "Evening train to São Bento", S.SAO_BENTO, Train, CP_DOURO),
                ),
            ),
            ProgramDay(
                "Foz and home", PORTO, listOf(
                    food("Mercado do Bolhão", "Fish, cheese, fruit and a glass of wine. Closed Sundays", S.BOLHAO, Walk),
                    place("Casa da Música", "Rem Koolhaas's concert hall, opened in 2005", S.CASA_MUSICA, Train, Energetic),
                    place("Serralves", "Art Deco villa, Siza's museum and a large park", S.SERRALVES, Car, Balanced),
                    place("Foz do Douro", "Where the river meets the sea. Out to the lighthouse pier", S.FELGUEIRAS, Car),
                    food("Grilled fish in Matosinhos", "Fish grilled on the pavement outside the restaurants", S.MATOSINHOS_FISH, Car, Balanced),
                    transport("Fly home", "Airport ≈ 15 min by taxi from Matosinhos", S.OPO, Car, S.OPO.url),
                ),
            ),
        ),
        ideas = listOf(
            place("Guimarães", "Castle and old town where Portugal began. Train from Porto", S.GUIMARAES),
            place("Bom Jesus do Monte", "Baroque stairway up a hill above Braga, and an old water-driven funicular", S.BOM_JESUS),
            place("Évora", "Roman temple, cathedral and the bone chapel. A long day from Lisbon", S.EVORA),
            place("Mafra National Palace", "Huge palace-convent with a famous library", S.MAFRA),
            place("National Azulejo Museum", "Tiles in a convent. Closed for works since November 2025: check first", S.AZULEJO),
            place("National Museum of Ancient Art", "Reopening in stages from 21 November 2026", S.MNAA),
        ),
        sources = SHARED_SOURCES + listOf(
            "https://www.visitlisboa.com/en/places/national-pantheon",
            "https://www.visitlisboa.com/en/places/carmo-archaeological-museum",
            "https://www.visitlisboa.com/pt-pt/locais/castelo-de-sao-jorge",
            "https://www.visitlisboa.com/pt-pt/locais/se-catedral",
            "https://gulbenkian.pt/en/visit/opening-hours/",
            "https://en.wikipedia.org/wiki/Lisbon_Cathedral",
            "https://en.wikipedia.org/wiki/S%C3%A3o_Bento_railway_station",
            "https://en.wikipedia.org/wiki/Cl%C3%A9rigos_Church",
            "https://en.wikipedia.org/wiki/Casa_da_M%C3%BAsica",
            "https://mercadobolhao.pt/en/faqs/",
            "https://www.rtp.pt/noticias/cultura/museu-de-arte-antiga-reabre-no-2o-semestre-de-2026-com-paineis-de-sao-vicente-restaurados_n1685403",
            "https://mnazulejo.gov.pt/",
        ),
    )

    // ---- Nature & views ----

    private val nature = Program(
        id = "$ID-nature",
        destinationId = ID,
        focus = Focus.Nature,
        title = "Coast, hills and the river",
        blurb = "Lisbon's viewpoints, the Cascais coast and Cabo da Roca, Sintra's forest gardens, dolphins off " +
            "Setúbal, then Porto's gardens, the Atlantic front and the Douro vineyards.",
        days = listOf(
            ProgramDay(
                "Viewpoints of Alfama and Graça", LISBON, listOf(
                    transport("Fly to Lisbon", "Taxi to Alfama ≈ 20 min; the metro needs a change", S.LIS, null, S.LIS.url),
                    stay("Small hotel in Alfama or Graça", "Five nights. Steep lanes: pack light", S.ALFAMA_STAY, Car),
                    place("Miradouro de Santa Luzia", "Tiled terrace over the roofs and the river", S.SANTA_LUZIA, Walk, Balanced),
                    place("São Jorge Castle", "Walk the walls. Open to 21:00 from March to October", S.CASTLE, Walk, Energetic),
                    place("Miradouro da Graça", "Pine shade and the castle across the roofs", S.GRACA, Walk, Balanced),
                    place("Miradouro da Senhora do Monte", "The highest of the viewpoints. Come for sunset", S.SENHORA_MONTE, Walk),
                    food("Dinner in Graça", "A tasca near Largo da Graça"),
                ),
            ),
            ProgramDay(
                "Across the river to Almada", LISBON, listOf(
                    place("Gulbenkian Garden", "Ponds, ducks and old trees. Free, open daily from sunrise", S.GULBENKIAN_GARDEN, Train, Balanced),
                    place("Parque Eduardo VII", "Up the slope for the long view down to the river", S.EDUARDO_VII, Walk, Energetic),
                    transport("Ferry to Cacilhas", "From Cais do Sodré across the Tagus, ≈ 10 min", S.CACILHAS, Ferry, CACILHAS_FERRY),
                    activity("Walk the Ginjal quay", "Old warehouses under the cliff, the city across the water", S.GINJAL, Walk),
                    place("Cristo Rei", "Lift to the top of the statue's base: the bridge and all of Lisbon", S.CRISTO_REI, Car),
                    place("Miradouro de Santa Catarina", "Ferry back, then sunset over the river and the bridge", S.SANTA_CATARINA, Ferry, Balanced),
                ),
            ),
            ProgramDay(
                "Cascais and the wild coast", LISBON, listOf(
                    transport("Train to Cascais", "From Cais do Sodré along the river and the beaches, ≈ 40 min", S.CASCAIS_STATION, Train, CP_CASCAIS),
                    place("Boca do Inferno", "Sea cave in the cliffs just out of town. Best on a rough day", S.BOCA_INFERNO, Walk),
                    place("Praia do Guincho", "Bike path by the sea, ≈ 10 km, or a bus. Big surf beach, strong wind", S.GUINCHO, Bike),
                    food("Fish lunch at Guincho", "Grilled fish above the beach", pace = Balanced),
                    place("Cabo da Roca", "Westernmost point of mainland Europe: cliffs and a lighthouse", S.CABO_ROCA, Car, Balanced),
                    activity("Seafront walk to Estoril", "Back by bus to Cascais, then the promenade along the beaches", S.PAREDAO, Car, Energetic),
                    transport("Back to Lisbon", "Train to Cais do Sodré", S.CAIS_SODRE, Train, CP_CASCAIS),
                ),
            ),
            ProgramDay(
                "Sintra hills and gardens", LISBON, listOf(
                    transport("Train to Sintra", "From Rossio, ≈ 40 min, about every 20 min", S.SINTRA_STATION, Train, CP_SINTRA),
                    place("Moorish Castle", "Bus 434 up, or the Villa Sassetti path from town (≈ 45 min)", S.MOUROS, Car),
                    place("Pena Park", "Lakes, ferns and huge trees round the palace. Palace entry is timed", S.PENA_PARK, Walk),
                    place("Cruz Alta", "The highest point of the hills, a short climb inside Pena Park", S.CRUZ_ALTA, Walk, Balanced),
                    place("Monserrate", "Bus 435. A garden of plants from far away; roof works into 2027", S.MONSERRATE, Car, Balanced),
                    place("Quinta da Regaleira", "Timed tickets. Wooded gardens, grottoes and the spiral well", S.REGALEIRA, Car, Energetic),
                    transport("Back to Lisbon", "Train to Rossio", S.ROSSIO, Train, CP_SINTRA),
                ),
            ),
            ProgramDay(
                "Dolphins and the Arrábida coast", LISBON, listOf(
                    transport("Train to Setúbal", "Fertagus from Roma-Areeiro or Entrecampos over the bridge, ≈ 1 h", S.SETUBAL_STATION, Train, FERTAGUS),
                    activity("Dolphin trip on the Sado", "Bottlenose dolphins live in the estuary all year. 2–3 h boat; book", S.FONTAINHAS, Walk),
                    food("Choco frito for lunch", "Setúbal's fried cuttlefish, near the harbour", pace = Balanced),
                    place("Praia da Figueirinha", "Sandy beach under the hills, on the way to Portinho. A swim", S.FIGUEIRINHA, Car, Energetic),
                    place("Portinho da Arrábida", "Clear bay under green hills. Summer: no private cars, buses only", S.PORTINHO, Car),
                    place("Miradouro do Portinho da Arrábida", "Ridge road viewpoint over the bay and the Tróia sands", S.PORTINHO_VIEW, Car, Balanced),
                    transport("Back to Lisbon", "Taxi or bus to Setúbal, then the train", S.ROMA_AREEIRO, Train, FERTAGUS),
                ),
            ),
            ProgramDay(
                "North to Porto and the river gardens", PORTO, listOf(
                    transport("Train to Porto", "Alfa Pendular from Santa Apolónia or Oriente, ≈ 3 h. Book seats", S.CAMPANHA, Train, CP_ALFA),
                    stay("Small hotel in Ribeira or Miragaia", "Three nights. Short train from Campanhã to São Bento", S.RIBEIRA, Train),
                    place("Miradouro da Vitória", "Quiet terrace over the roofs to the river", S.VITORIA, Walk, Balanced),
                    place("Jardim das Virtudes", "Terraced garden stepping down towards the river", S.VIRTUDES, Walk, Energetic),
                    place("Jardins do Palácio de Cristal", "Shady gardens with long views over the Douro", S.CRISTAL, Walk, Balanced),
                    place("Cais da Ribeira", "Down to the riverfront", S.CAIS_RIBEIRA, Walk),
                    place("Jardim do Morro", "Over the bridge's top deck; sunset over Ribeira", S.MORRO, Walk),
                ),
            ),
            ProgramDay(
                "Serralves and the Atlantic", PORTO, listOf(
                    place("Serralves Park", "Woods, a farm, the pink villa and Siza's museum. Opens 10:00", S.SERRALVES, Car, time = "10:00"),
                    place("Parque da Cidade", "Porto's biggest park: lakes and meadows down to the sea", S.PARQUE_CIDADE, Walk, Balanced),
                    activity("Seafront walk to Foz", "South along the ocean promenade to the river mouth", S.PERGOLA, Walk),
                    place("Farolim de Felgueiras", "Small lighthouse at the end of the breakwater; big waves in winter", S.FELGUEIRAS, Walk, Energetic),
                    place("Jardim do Passeio Alegre", "Palm garden by the river mouth", S.PASSEIO_ALEGRE, Walk, Energetic),
                    activity("Tram 1 back along the river", "Old tram from Passeio Alegre to the Infante, ≈ 40 min", S.INFANTE, Car, Balanced, url = TRAM_1),
                ),
            ),
            ProgramDay(
                "Douro valley vineyards", PORTO, listOf(
                    transport("Train up the Douro", "From São Bento, ≈ 2 h 30, river on the right. Winter works: check", S.PINHAO_STATION, Train, CP_DOURO),
                    place("Pinhão station tiles", "24 tile panels of the vineyards and rabelo boats", S.PINHAO_STATION, Walk, Balanced),
                    activity("Vineyard walk at Quinta do Bomfim", "Walk the terraces with the quinta's map and water. Book", S.BOMFIM, Walk),
                    food("Lunch by the river", "A terrace in Pinhão, a Douro white", pace = Balanced),
                    activity("Boat trip from Pinhão", "An hour on the river from the piers by the bridge", S.PINHAO_PIERS, Walk),
                    place("Casal de Loivos viewpoint", "The classic view of the bends. A steep hour up, ≈ 330 m", S.CASAL_LOIVOS, Walk, Energetic),
                    transport("Back to Porto", "Evening train to São Bento", S.SAO_BENTO, Train, CP_DOURO),
                ),
            ),
            ProgramDay(
                "Leça's rocks and home", PORTO, listOf(
                    place("Porto Botanical Garden", "University garden, free. Quiet in the morning", S.BOTANICO, Car, Energetic),
                    activity("Walk Matosinhos beach", "Long sandy beach; surfers out early", S.MATOSINHOS_BEACH, Car),
                    food("Grilled fish in Matosinhos", "Fish grilled on the pavement outside the restaurants", S.MATOSINHOS_FISH, Walk),
                    place("Piscina das Marés", "Siza's tidal pools in the rocks. Swimming June to mid-September", S.MARES, Walk, Balanced),
                    place("Boa Nova rocks", "Siza's tea house on the rocks below the lighthouse", S.BOA_NOVA, Walk, Balanced),
                    transport("Fly home", "Airport ≈ 15 min by taxi", S.OPO, Car, S.OPO.url),
                ),
            ),
        ),
        ideas = listOf(
            place("Passadiços do Paiva", "8.7 km of boardwalk above the river. Book; needs a car from Porto", S.PAIVA),
            place("Convento dos Capuchos", "Cork-lined friary in the Sintra forest. Taxi from town", S.CAPUCHOS),
            place("Panorâmico de Monsanto", "Shell of a 1960s restaurant in Lisbon's forest park; free views", S.MONSANTO),
            place("Peneda-Gerês National Park", "Granite hills, rivers and oak woods in the far north", S.GERES),
        ),
        sources = SHARED_SOURCES + listOf(
            "https://www.visitlisboa.com/pt-pt/locais/castelo-de-sao-jorge",
            "https://gulbenkian.pt/en/visit/opening-hours/",
            "https://en.wikivoyage.org/wiki/Cascais",
            "https://echoboomer.pt/arrabida-sem-carros-setubal-mantem-restricoes-epoca-balnear-2026/",
            "https://www.passadicosdopaiva.pt/en/",
            "https://www.cm-matosinhos.pt/pages/929",
            "https://www.agenda-porto.pt/en/local/jardim-botanico/",
        ),
    )

    /** The destination with its three programs; the demo trip uses [Destination.default] (Sights & history). */
    val destination: Destination = Destination(
        id = ID,
        title = "Lisbon & Porto",
        blurb = "Two river cities on the Atlantic: Lisbon's hills and Sintra, then Porto and the Douro valley.",
        countryCodes = listOf("PT"),
        zoneId = "Europe/Lisbon",
        bestTime = "Best in spring (April–June) and early autumn (September–October); July and August are hot and busy",
        programs = listOf(food, culture, nature),
    )

    // ---- Builders ----
    // Places, meals and activities take their spot's website; stays never have one (they are generic), and a train,
    // ferry or flight only has the one it is given (the operator's page for that service, not the station's).

    private fun place(title: String, note: String, spot: Spot, via: Vehicle? = null, pace: Pace = Pace.Chill, time: String? = null) =
        ProgramItem("place", title, note, spot.at, via, time, pace, spot.name, spot.url)

    private fun food(
        title: String, note: String, spot: Spot? = null, via: Vehicle? = null, pace: Pace = Pace.Chill, time: String? = null, url: String? = spot?.url,
    ) = ProgramItem("food", title, note, spot?.at, via, time, pace, spot?.name.orEmpty(), url)

    private fun activity(
        title: String, note: String, spot: Spot? = null, via: Vehicle? = null, pace: Pace = Pace.Chill, time: String? = null, url: String? = spot?.url,
    ) = ProgramItem("activity", title, note, spot?.at, via, time, pace, spot?.name.orEmpty(), url)

    private fun stay(title: String, note: String, spot: Spot, via: Vehicle?) =
        ProgramItem("stay", title, note, spot.at, via, placeName = spot.name)

    private fun transport(title: String, note: String, spot: Spot, via: Vehicle?, url: String? = null) =
        ProgramItem("transport", title, note, spot.at, via, placeName = spot.name, url = url)

    private fun note(title: String, note: String) = ProgramItem("note", title, note)

    /**
     * A real place: the name for its place line and its point, checked against OpenStreetMap and Wikipedia, and its
     * website (its own, English where there is one, else the tourism board's page for it; null for areas, stations
     * and places with neither). Links checked in November 2026 (research/boringtravel/programs/lisbon-porto.md, "Links").
     */
    private class Spot(val name: String, lat: Double, lng: Double, val url: String? = null) {
        val at: Pair<Double, Double> = lat to lng
    }

    /** The places these programs use. */
    private object S {
        // Lisbon
        val LIS = Spot("Lisbon Airport, Lisbon, Portugal", 38.77417, -9.13417, "https://www.aeroportolisboa.pt/en/lis/home")
        val ALFAMA_STAY = Spot("Alfama, Lisbon, Portugal", 38.7115, -9.13)
        val ALFAMA = Spot("Alfama, Lisbon, Portugal", 38.71222, -9.13, "https://www.visitlisboa.com/en/places/alfama")
        val CHIADO = Spot("Chiado, Lisbon, Portugal", 38.71051, -9.14222)
        val SANTO_ANTONIO = Spot("Igreja de Santo António, Lisbon, Portugal", 38.70998, -9.1337, "https://www.visitlisboa.com/en/places/igreja-de-santo-antonio")
        val SE = Spot("Lisbon Cathedral, Lisbon, Portugal", 38.70982, -9.1328, "https://www.sedelisboa.pt/")
        val SANTA_LUZIA = Spot("Miradouro de Santa Luzia, Lisbon, Portugal", 38.71174, -9.13013, "https://www.visitlisboa.com/en/places/miradouro-de-santa-luzia")
        val MARTIM_MONIZ = Spot("Martim Moniz, Lisbon, Portugal", 38.716, -9.13635)
        val CASTLE = Spot("São Jorge Castle, Lisbon, Portugal", 38.71393, -9.13348, "https://castelodesaojorge.pt/en/")
        val GRACA = Spot("Miradouro da Graça, Lisbon, Portugal", 38.71637, -9.13157, "https://www.visitlisboa.com/en/places/miradouro-da-graca")
        val SENHORA_MONTE = Spot("Miradouro da Senhora do Monte, Lisbon, Portugal", 38.71908, -9.13291, "https://www.visitlisboa.com/en/places/miradouro-da-senhora-do-monte")
        val SAO_VICENTE = Spot("Monastery of São Vicente de Fora, Lisbon, Portugal", 38.71482, -9.12788, "https://mosteirodesaovicentedefora.com/en/inicio-english/")
        val PANTHEON = Spot("National Pantheon, Lisbon, Portugal", 38.71498, -9.12468, "https://www.panteaonacional.gov.pt/en")
        val JERONIMOS = Spot("Jerónimos Monastery, Lisbon, Portugal", 38.69775, -9.20559, "https://mosteirojeronimos.torrebelem.gov.pt/en/mosteiro-dos-jeronimos")
        val PASTEIS = Spot("Pastéis de Belém, Lisbon, Portugal", 38.69748, -9.20332, "https://pasteisdebelem.pt/en/")
        val BELEM_TOWER = Spot("Belém Tower, Lisbon, Portugal", 38.69159, -9.21593, "https://mosteirojeronimos.torrebelem.gov.pt/en/torre-de-belem")
        val MAAT = Spot("MAAT, Lisbon, Portugal", 38.69593, -9.19362, "https://www.maat.pt/en")
        val LX_FACTORY = Spot("LX Factory, Lisbon, Portugal", 38.7026, -9.17866, "https://www.lxfactory.com/")
        val GULBENKIAN = Spot("Calouste Gulbenkian Museum, Lisbon, Portugal", 38.73782, -9.15351, "https://gulbenkian.pt/museu/en/")
        val GULBENKIAN_GARDEN = Spot("Gulbenkian Garden, Lisbon, Portugal", 38.73689, -9.15413, "https://gulbenkian.pt/jardim/en/")
        val TIME_OUT = Spot("Time Out Market, Lisbon, Portugal", 38.70709, -9.1459, "https://www.timeout.com/time-out-market-lisboa")
        val CARMO = Spot("Carmo Convent, Lisbon, Portugal", 38.71218, -9.14016, "https://www.museuarqueologicodocarmo.pt/")
        val SAO_ROQUE = Spot("Church of São Roque, Lisbon, Portugal", 38.71362, -9.14343, "https://museusaoroque.scml.pt/en/home/")
        val SAO_PEDRO = Spot("Miradouro de São Pedro de Alcântara, Lisbon, Portugal", 38.71546, -9.14404, "https://www.visitlisboa.com/en/places/miradouro-de-s-pedro-de-alcantara")
        val BAIRRO_ALTO = Spot("Bairro Alto, Lisbon, Portugal", 38.71339, -9.14555, "https://www.visitlisboa.com/en/places/bairro-alto")
        val ROSSIO = Spot("Rossio station, Lisbon, Portugal", 38.71459, -9.14184)
        val CAIS_SODRE = Spot("Cais do Sodré station, Lisbon, Portugal", 38.70615, -9.14683)
        val ROMA_AREEIRO = Spot("Roma-Areeiro station, Lisbon, Portugal", 38.74569, -9.13551)
        val CACILHAS = Spot("Cacilhas ferry terminal, Almada, Portugal", 38.68815, -9.14768)
        val GINJAL = Spot("Cais do Ginjal, Almada, Portugal", 38.6847, -9.15881)
        val CRISTO_REI = Spot("Cristo Rei, Almada, Portugal", 38.67855, -9.17134, "https://cristorei.pt/")
        val EDUARDO_VII = Spot("Parque Eduardo VII, Lisbon, Portugal", 38.72821, -9.15301, "https://www.visitlisboa.com/en/places/miradouro-do-parque-eduardo-vii")
        val GINJINHA = Spot("A Ginjinha, Largo de São Domingos, Lisbon, Portugal", 38.7147, -9.13906, "https://ginjinhaespinheira.com/en")
        val CONFEITARIA = Spot("Confeitaria Nacional, Lisbon, Portugal", 38.71314, -9.13808, "https://confeitarianacional.com/")
        val COMERCIO = Spot("Praça do Comércio, Lisbon, Portugal", 38.70772, -9.13652, "https://www.visitlisboa.com/en/places/praca-do-comercio-terreiro-do-paco")
        val CONSERVEIRA = Spot("Conserveira de Lisboa, Lisbon, Portugal", 38.70932, -9.13473, "https://www.comerciocomhistoria.gov.pt/en/listings/conserveira-de-lisboa-3243/")
        val RAMIRO = Spot("Cervejaria Ramiro, Lisbon, Portugal", 38.7202, -9.13571, "https://www.cervejariaramiro.com/")
        val SANTA_CATARINA = Spot("Miradouro de Santa Catarina, Lisbon, Portugal", 38.70945, -9.14772, "https://www.visitlisboa.com/en/places/miradouro-de-santa-catarina")
        val PRINCIPE_REAL = Spot("Príncipe Real, Lisbon, Portugal", 38.71628, -9.14862)
        val MANTEIGARIA = Spot("Manteigaria, Chiado, Lisbon, Portugal", 38.71076, -9.14406, "https://manteigaria.com/")
        val CAMPO_OURIQUE = Spot("Mercado de Campo de Ourique, Lisbon, Portugal", 38.71621, -9.16678, "https://www.lisboa.pt/espacos-e-servicos/detalhe/mercado-campo-de-ourique")
        val MONSANTO = Spot("Panorâmico de Monsanto, Lisbon, Portugal", 38.72863, -9.18457)
        val AZULEJO = Spot("National Azulejo Museum, Lisbon, Portugal", 38.72472, -9.11391, "https://mnazulejo.gov.pt/")
        val MNAA = Spot("National Museum of Ancient Art, Lisbon, Portugal", 38.70479, -9.16147, "https://www.museusemonumentos.pt/en/museus-e-monumentos/national-museum-of-ancient-art")

        // Sintra and Cascais
        val SINTRA_STATION = Spot("Sintra station, Sintra, Portugal", 38.79923, -9.38574)
        val PENA = Spot("Pena Palace, Sintra, Portugal", 38.78757, -9.39056, "https://www.parquesdesintra.pt/en/parks-monuments/park-and-national-palace-of-pena/")
        val PENA_PARK = Spot("Pena Park, Sintra, Portugal", 38.78545, -9.39257, "https://www.parquesdesintra.pt/en/parks-monuments/park-and-national-palace-of-pena/")
        val CRUZ_ALTA = Spot("Cruz Alta, Sintra, Portugal", 38.78205, -9.39133, "https://www.parquesdesintra.pt/en/parks-monuments/park-and-national-palace-of-pena/")
        val MOUROS = Spot("Castle of the Moors, Sintra, Portugal", 38.79241, -9.38867, "https://www.parquesdesintra.pt/en/parks-monuments/the-moorish-castle/")
        val REGALEIRA = Spot("Quinta da Regaleira, Sintra, Portugal", 38.79511, -9.3962, "https://www.regaleira.pt/en/")
        val SINTRA_PALACE = Spot("Sintra National Palace, Sintra, Portugal", 38.79777, -9.39083, "https://www.parquesdesintra.pt/en/parks-monuments/national-palace-of-sintra/")
        val PIRIQUITA = Spot("Piriquita, Sintra, Portugal", 38.79671, -9.39053, "https://piriquita.pt/")
        val MONSERRATE = Spot("Monserrate Palace, Sintra, Portugal", 38.79423, -9.42065, "https://www.parquesdesintra.pt/en/parks-monuments/park-and-palace-of-monserrate/")
        val COLARES = Spot("Adega Regional de Colares, Colares, Portugal", 38.80538, -9.44943, "https://arcolares.com/")
        val AZENHAS = Spot("Azenhas do Mar, Sintra, Portugal", 38.84096, -9.46191, "https://visitsintra.travel/en/visit/beaches/azenhas-do-mar-beach")
        val CAPUCHOS = Spot("Convento dos Capuchos, Sintra, Portugal", 38.78434, -9.43818, "https://www.parquesdesintra.pt/en/parks-monuments/convent-of-the-capuchos/")
        val CASCAIS_STATION = Spot("Cascais station, Cascais, Portugal", 38.70076, -9.41861)
        val BOCA_INFERNO = Spot("Boca do Inferno, Cascais, Portugal", 38.69112, -9.43082, "https://www.visitcascais.com/en/partners/boca-do-inferno")
        val GUINCHO = Spot("Praia do Guincho, Cascais, Portugal", 38.73316, -9.47278, "https://www.visitcascais.com/en/partners/guincho-beach")
        val CABO_ROCA = Spot("Cabo da Roca, Sintra, Portugal", 38.78049, -9.49885, "https://visitsintra.travel/en/visit/viewpoints-and-waterfalls/cabo-da-roca")
        val PAREDAO = Spot("Paredão, Estoril, Portugal", 38.70374, -9.40476)

        // Setúbal, Arrábida and Azeitão
        val SETUBAL_STATION = Spot("Setúbal station, Setúbal, Portugal", 38.53063, -8.88513)
        val LIVRAMENTO = Spot("Mercado do Livramento, Setúbal, Portugal", 38.52232, -8.89426, "https://visitsetubal.com/en/compras/mercado-do-livramento-2/")
        val FONTAINHAS = Spot("Doca das Fontaínhas, Setúbal, Portugal", 38.521, -8.88662)
        val FIGUEIRINHA = Spot("Praia da Figueirinha, Setúbal, Portugal", 38.48428, -8.94504, "https://visitsetubal.com/en/praias/praia-da-figueirinha/")
        val PORTINHO = Spot("Portinho da Arrábida, Setúbal, Portugal", 38.47535, -8.98436, "https://visitsetubal.com/en/praias/praia-do-creiro-e-portinho-da-arrabida/")
        val PORTINHO_VIEW = Spot("Miradouro do Portinho da Arrábida, Setúbal, Portugal", 38.48125, -8.98931)
        val JMF = Spot("José Maria da Fonseca, Azeitão, Portugal", 38.51787, -9.01569, "https://www.jmf.pt/index.php?id=211")
        val AZEITAO = Spot("Vila Nogueira de Azeitão, Portugal", 38.51868, -9.01386, "https://visitsetubal.com/en/atracoes-e-novidades/aldeias-de-azeitao/")

        // Porto, Gaia and Matosinhos
        val CAMPANHA = Spot("Porto Campanhã station, Porto, Portugal", 41.14874, -8.58546)
        val SAO_BENTO = Spot("São Bento station, Porto, Portugal", 41.14548, -8.61052, "https://visitporto.travel/en-GB/poi/5cd04b4ef979e00001fe480a")
        val RIBEIRA = Spot("Ribeira, Porto, Portugal", 41.14069, -8.61301)
        val CAIS_RIBEIRA = Spot("Cais da Ribeira, Porto, Portugal", 41.14055, -8.61184, "https://visitporto.travel/en-GB/poi/5cd04b4ff979e00001f6f762")
        val SE_PORTO = Spot("Porto Cathedral, Porto, Portugal", 41.1428, -8.61121, "https://visitporto.travel/en-GB/poi/5cd04b4bf979e0000193909e")
        val SAO_FRANCISCO = Spot("Church of São Francisco, Porto, Portugal", 41.14098, -8.61571, "https://ordemsaofranciscoporto.pt/igrejas/")
        val BOLSA = Spot("Palácio da Bolsa, Porto, Portugal", 41.14141, -8.61573, "https://palaciodabolsa.com/en/intro/")
        val LELLO = Spot("Livraria Lello, Porto, Portugal", 41.14683, -8.61485, "https://livrarialello.pt/en/")
        val CARMO_PORTO = Spot("Igreja do Carmo, Porto, Portugal", 41.14746, -8.61625, "https://ordemdocarmo.pt/en/circuito")
        val CLERIGOS = Spot("Clérigos Tower, Porto, Portugal", 41.14565, -8.61463, "https://www.torredosclerigos.pt/en/")
        val ALMAS = Spot("Capela das Almas, Porto, Portugal", 41.14983, -8.60558, "https://visitporto.travel/en-GB/poi/5cd04b4af979e000013ddc9b")
        val DOM_LUIS = Spot("Dom Luís I Bridge, Porto, Portugal", 41.13986, -8.60934, "https://visitporto.travel/en-GB/poi/5cd04b46f979e00001234af3")
        val SERRA_PILAR = Spot("Monastery of Serra do Pilar, Vila Nova de Gaia, Portugal", 41.13835, -8.60806, "https://visitporto.travel/en-GB/poi/5cd04b4ff979e00001f6f74c")
        val GAIA = Spot("Cais de Gaia, Vila Nova de Gaia, Portugal", 41.13786, -8.6193, "https://www.cm-gaia.pt/pt/turismo/caves-de-vinho-do-porto/")
        val MORRO = Spot("Jardim do Morro, Vila Nova de Gaia, Portugal", 41.13762, -8.60869)
        val BOLHAO = Spot("Mercado do Bolhão, Porto, Portugal", 41.14932, -8.60695, "https://mercadobolhao.pt/en/")
        val MAJESTIC = Spot("Majestic Café, Porto, Portugal", 41.14717, -8.60664, "https://www.cafemajestic.com/")
        val SANTIAGO = Spot("Café Santiago, Porto, Portugal", 41.14645, -8.60478, "https://cafesantiago.pt/")
        val CASA_GUEDES = Spot("Casa Guedes, Porto, Portugal", 41.146, -8.60346, "https://casaguedes.pt/")
        val CASA_MUSICA = Spot("Casa da Música, Porto, Portugal", 41.1589, -8.63077, "https://www.casadamusica.com/en/")
        val SERRALVES = Spot("Serralves, Porto, Portugal", 41.15904, -8.65935, "https://www.serralves.pt/en/")
        val PARQUE_CIDADE = Spot("Parque da Cidade, Porto, Portugal", 41.16936, -8.67979, "https://visitporto.travel/en-GB/poi/5cd04b48f979e000016c560d")
        val PERGOLA = Spot("Pérgola da Foz, Porto, Portugal", 41.15778, -8.68303, "https://visitporto.travel/en-GB/poi/5cd04b4ef979e00001fe47e9")
        val FELGUEIRAS = Spot("Foz do Douro, Porto, Portugal", 41.14673, -8.6773, "https://visitporto.travel/en-GB/poi/5cd04b51f979e000019b65b2")
        val PASSEIO_ALEGRE = Spot("Jardim do Passeio Alegre, Porto, Portugal", 41.1483, -8.67102, "https://visitporto.travel/en-GB/poi/5cd04b48f979e000016c560c")
        val INFANTE = Spot("Praça do Infante Dom Henrique, Porto, Portugal", 41.14116, -8.61486)
        val VITORIA = Spot("Miradouro da Vitória, Porto, Portugal", 41.14312, -8.61577, "https://visitporto.travel/en-GB/poi/5cd04b4df979e000019f80d5")
        val VIRTUDES = Spot("Jardim das Virtudes, Porto, Portugal", 41.14519, -8.61912, "https://visitporto.travel/en-GB/poi/5cd04b48f979e000016c55f4")
        val CRISTAL = Spot("Jardins do Palácio de Cristal, Porto, Portugal", 41.14703, -8.62628, "https://visitporto.travel/en-GB/poi/5cd04b48f979e000016c560f")
        val BOTANICO = Spot("Porto Botanical Garden, Porto, Portugal", 41.15289, -8.64284, "https://mhnc.up.pt/en/porto-s-botanical-garden/")
        val MATOSINHOS_FISH = Spot("Rua Heróis de França, Matosinhos, Portugal", 41.18233, -8.69424)
        val MATOSINHOS_MARKET = Spot("Mercado Municipal de Matosinhos, Matosinhos, Portugal", 41.187, -8.6931, "https://www.cm-matosinhos.pt/pages/426?poi_id=8062")
        val MATOSINHOS_BEACH = Spot("Praia de Matosinhos, Matosinhos, Portugal", 41.17648, -8.69301, "https://www.cm-matosinhos.pt/servicos-municipais/ambiente/praias/matosinhos")
        val MARES = Spot("Piscina das Marés, Leça da Palmeira, Portugal", 41.19308, -8.70729, "https://www.cm-matosinhos.pt/pages/929")
        val BOA_NOVA = Spot("Casa de Chá da Boa Nova, Leça da Palmeira, Portugal", 41.203, -8.71511, "https://www.casadechadaboanova.pt/")
        val OPO = Spot("Porto Airport, Porto, Portugal", 41.24896, -8.68028, "https://www.aeroportoporto.pt/en/opo/home")

        // Douro valley
        val PINHAO_STATION = Spot("Pinhão station, Pinhão, Portugal", 41.1904, -7.54513)
        val BOMFIM = Spot("Quinta do Bomfim, Pinhão, Portugal", 41.18925, -7.54094, "https://www.symington.com/visit/quinta-do-bomfim/")
        val PINHAO_PIERS = Spot("Pinhão riverside, Pinhão, Portugal", 41.18938, -7.54424)
        val CASAL_LOIVOS = Spot("Miradouro de Casal de Loivos, Alijó, Portugal", 41.19853, -7.53214)

        // Further afield (ideas)
        val GUIMARAES = Spot("Guimarães Castle, Guimarães, Portugal", 41.44791, -8.29041, "https://pacodosduques.gov.pt/monumentos/castelo-de-guimaraes/?lang=en")
        val BOM_JESUS = Spot("Bom Jesus do Monte, Braga, Portugal", 41.55447, -8.37781, "https://bomjesus.pt/?lang=en")
        val EVORA = Spot("Roman Temple of Évora, Évora, Portugal", 38.57263, -7.90734, "https://www.cm-evora.pt/en/visitante/")
        val MAFRA = Spot("Mafra National Palace, Mafra, Portugal", 38.93689, -9.32577, "https://www.pnmafra.gov.pt/")
        val AVEIRO = Spot("Aveiro, Portugal", 40.64134, -8.65482, "https://www.centerofportugal.com/destination/aveiro")
        val PAIVA = Spot("Passadiços do Paiva, Arouca, Portugal", 40.95291, -8.17691, "https://www.passadicosdopaiva.pt/en/")
        val GERES = Spot("Peneda-Gerês National Park, Portugal", 41.72472, -8.16278, "https://www.icnf.pt/conservacao/rnapareasprotegidas/parquenacional/pnpenedageres")
    }
}

package com.justdataplease.boringtravel.programs.data

import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.model.Vehicle.Car
import com.justdataplease.boringtravel.model.Vehicle.Ferry
import com.justdataplease.boringtravel.model.Vehicle.Train
import com.justdataplease.boringtravel.model.Vehicle.Walk
import com.justdataplease.boringtravel.programs.Destination
import com.justdataplease.boringtravel.programs.Focus
import com.justdataplease.boringtravel.programs.Pace
import com.justdataplease.boringtravel.programs.Program
import com.justdataplease.boringtravel.programs.ProgramDay
import com.justdataplease.boringtravel.programs.ProgramItem

/**
 * Kyoto & Osaka: twelve days from Kansai airport back to Kansai airport, seven nights in Kyoto and four in Osaka,
 * in three versions (Sights & history, Eat & drink, Nature & views). Points are from Wikipedia, Wikidata and
 * OpenStreetMap; opening days and seasons were checked in November 2026 (notes in research/boringtravel/programs).
 */
object KyotoOsaka {
    private const val ID = "kyoto-osaka"
    private val B = Pace.Balanced
    private val E = Pace.Energetic

    /**
     * A real place: the name for the place line, its point and its website (its own, English where there is one, else
     * the tourism board's page for it; null for areas, stations and places with neither). Links checked in November
     * 2026 (research/boringtravel/programs/kyoto-osaka.md, "Links").
     */
    private class Spot(val name: String, val lat: Double, val lng: Double, val url: String? = null)

    // ---- Trains: the operator's page for the service ----
    private const val HARUKA = "https://www.westjr.co.jp/travel-information/en/tickets-passes/oneway/haruka/"
    private const val KINTETSU = "https://www.kintetsu.co.jp/foreign/english/"
    private const val SHINKANSEN = "https://www.westjr.co.jp/global/en/train/shinkansen/tokaido-sanyo-shinkansen/"
    private const val HANKYU = "https://www.hankyu.co.jp/en/"
    private const val EIZAN = "https://eizandensha.co.jp/en/"

    // ---- Getting there and bases ----
    private val kix = Spot("Kansai International Airport, Osaka, Japan", 34.43056, 135.23028, "https://osaka-info.jp/en/spot/kansai-international-airport/")
    private val kyotoStation = Spot("Kyoto Station, Kyoto, Japan", 34.98556, 135.75861)
    private val shinOsaka = Spot("Shin-Osaka Station, Osaka, Japan", 34.73361, 135.5)
    private val osakaStation = Spot("Osaka Station, Umeda, Osaka, Japan", 34.7025, 135.49611)
    private val umeda = Spot("Umeda, Osaka, Japan", 34.7025, 135.49611)
    private val namba = Spot("Namba, Osaka, Japan", 34.66711, 135.49914)
    private val gion = Spot("Gion, Kyoto, Japan", 35.0035, 135.77505)
    private val kawaramachi = Spot("Shijo-Kawaramachi, Kyoto, Japan", 35.00375, 135.76931)
    private val sanjo = Spot("Sanjo, Kyoto, Japan", 35.00917, 135.77222)
    private val kintetsuNara = Spot("Kintetsu Nara Station, Nara, Japan", 34.68444, 135.82742)
    private val kibuneguchi = Spot("Kibuneguchi Station, Kyoto, Japan", 35.1059, 135.7639)
    private val himejiStation = Spot("Himeji Station, Himeji, Japan", 34.82764, 134.69085)

    // ---- Kyoto: east ----
    private val fushimiInari = Spot("Fushimi Inari Taisha, Kyoto, Japan", 34.96722, 135.77278, "https://inari.jp/en/")
    private val tofukuji = Spot("Tofuku-ji, Kyoto, Japan", 34.97705, 135.77409, "https://tofukuji.jp/")
    private val sanjusangendo = Spot("Sanjusangen-do, Kyoto, Japan", 34.98778, 135.77167, "https://www.sanjusangendo.jp/")
    private val kiyomizudera = Spot("Kiyomizu-dera, Kyoto, Japan", 34.99483, 135.785, "https://www.kiyomizudera.or.jp/en/")
    private val sannenzaka = Spot("Sannenzaka, Kyoto, Japan", 34.99647, 135.78093)
    private val kodaiji = Spot("Kodai-ji, Kyoto, Japan", 35.00076, 135.78111, "https://www.kodaiji.com/e_index.html")
    private val yasaka = Spot("Yasaka Shrine, Kyoto, Japan", 35.00361, 135.77861, "https://www.yasaka-jinja.or.jp/en/")
    private val maruyama = Spot("Maruyama Park, Kyoto, Japan", 35.00376, 135.78148, "https://kyoto.travel/en/destinations/maruyama-park/")
    private val kenninji = Spot("Kennin-ji, Kyoto, Japan", 35.00099, 135.77366, "https://kyoto.travel/en/destinations/kenninjitemple/")
    private val hanamikoji = Spot("Hanamikoji, Gion, Kyoto, Japan", 35.00448, 135.77514, "https://www.japan.travel/en/spot/84/")
    private val shirakawa = Spot("Shirakawa, Gion, Kyoto, Japan", 35.00551, 135.77381, "https://www.japan.travel/en/spot/84/")
    private val pontocho = Spot("Pontocho, Kyoto, Japan", 35.00425, 135.77121, "https://www.japan.travel/en/spot/79/")
    private val kiyamachi = Spot("Kiyamachi, Kyoto, Japan", 35.00345, 135.77055)
    private val kamoRiver = Spot("Kamo River, Kyoto, Japan", 35.00384, 135.77153, "https://www.japan.travel/en/spot/1183/")
    private val shogunzuka = Spot("Shogunzuka Seiryuden, Kyoto, Japan", 35.00208, 135.78695, "https://www.shogunzuka.com/english/")
    private val shorenin = Spot("Shoren-in, Kyoto, Japan", 35.00731, 135.7832, "https://www.shorenin.com/english/")
    private val ginkakuji = Spot("Ginkaku-ji, Kyoto, Japan", 35.02667, 135.79833, "https://www.shokoku-ji.jp/en/ginkakuji/")
    private val daimonji = Spot("Daimonji fire bed, Mt Daimonji, Kyoto, Japan", 35.02306, 135.80437, "https://kyoto.travel/en/destinations/trail-running-trekking-mt-daimonjiyama-hiking-course/")
    private val philosophersPath = Spot("Philosopher's Path, Kyoto, Japan", 35.02513, 135.79617, "https://kyoto.travel/en/destinations/philosophers-path-tetsugakunomichi/")
    private val honenin = Spot("Honen-in, Kyoto, Japan", 35.024, 135.7974)
    private val eikando = Spot("Eikan-do, Kyoto, Japan", 35.01452, 135.79529, "https://www.eikando.or.jp/English/index_eng.html")
    private val nanzenji = Spot("Nanzen-ji, Kyoto, Japan", 35.01198, 135.79439, "https://www.nanzenji.or.jp/")
    private val heian = Spot("Heian Shrine, Kyoto, Japan", 35.01667, 135.78222, "https://www.heianjingu.or.jp/english/")
    private val keage = Spot("Keage Incline, Kyoto, Japan", 35.00732, 135.79117, "https://biwakososui-museum.city.kyoto.lg.jp/en/walk/01/")
    private val okutan = Spot("Okutan, Nanzen-ji, Kyoto, Japan", 35.01244, 135.79307)

    // ---- Kyoto: centre and north ----
    private val nishiki = Spot("Nishiki Market, Kyoto, Japan", 35.00502, 135.76557, "https://www.kyoto-nishiki.or.jp/en/")
    private val aritsugu = Spot("Aritsugu, Nishiki Market, Kyoto, Japan", 35.00509, 135.76623, "https://www.kyoto-nishiki.or.jp/en/stores/aritsugu/")
    private val nishikiTenmangu = Spot("Nishiki Tenmangu, Kyoto, Japan", 35.00505, 135.7674, "https://nishikitenmangu.or.jp/")
    private val daimaru = Spot("Daimaru Kyoto, Kyoto, Japan", 35.00437, 135.7619, "https://www.daimaru.co.jp/kyoto/")
    private val takashimayaKyoto = Spot("Takashimaya Kyoto, Kyoto, Japan", 35.00351, 135.76812, "https://www.takashimaya.co.jp/kyoto/departmentstore/")
    private val inoda = Spot("Inoda Coffee, Kyoto, Japan", 35.00819, 135.76306, "https://www.inoda-coffee.co.jp/shop/honten/")
    private val nijo = Spot("Nijo Castle, Kyoto, Japan", 35.01417, 135.7475, "https://nijo-jocastle.city.kyoto.lg.jp/?lang=en")
    private val gosho = Spot("Kyoto Imperial Palace, Kyoto, Japan", 35.02528, 135.76222, "https://kyoto-gosho.kunaicho.go.jp/en/")
    private val demachiFutaba = Spot("Demachi Futaba, Kyoto, Japan", 35.03011, 135.76963)
    private val shimogamo = Spot("Shimogamo Shrine, Kyoto, Japan", 35.03889, 135.7725, "https://www.shimogamo-jinja.or.jp/english")
    private val kamoDelta = Spot("Kamo River Delta, Kyoto, Japan", 35.0299, 135.7717, "https://www.city.kyoto.lg.jp/sakyo/page/0000342867.html")
    private val mitarashi = Spot("Kamo Mitarashi Chaya, Kyoto, Japan", 35.03965, 135.77076)
    private val ichijoji = Spot("Ichijoji, Kyoto, Japan", 35.04408, 135.78736)
    private val botanical = Spot("Kyoto Botanical Gardens, Kyoto, Japan", 35.04833, 135.76111, "https://www.kyotobotanicalgardens.jp/english")
    private val ramenKoji = Spot("Kyoto Ramen Koji, Kyoto Station, Kyoto, Japan", 34.98556, 135.75861, "https://www.kyoto-station-building.co.jp/ramen_koji/")
    private val toji = Spot("To-ji, Kyoto, Japan", 34.98056, 135.74778, "https://toji.or.jp/en/")

    // ---- Kyoto: north-west ----
    private val kitano = Spot("Kitano Tenmangu, Kyoto, Japan", 35.03111, 135.73528, "https://kitanotenmangu.or.jp/en/")
    private val kinkakuji = Spot("Kinkaku-ji, Kyoto, Japan", 35.0395, 135.7285, "https://www.shokoku-ji.jp/en/kinkakuji/")
    private val ryoanji = Spot("Ryoan-ji, Kyoto, Japan", 35.03444, 135.71833, "https://kyoto.travel/en/destinations/ryoanji-temple/")
    private val ninnaji = Spot("Ninna-ji, Kyoto, Japan", 35.03106, 135.71381, "https://ninnaji.jp/en/")
    private val imamiya = Spot("Imamiya Shrine, Kyoto, Japan", 35.04578, 135.74205, "https://kyoto-irodoru.city.kyoto.lg.jp/kitaku/imamiyajinja-n.html")
    private val daitokuji = Spot("Daitoku-ji, Kyoto, Japan", 35.04389, 135.74611, "https://kyoto.travel/en/destinations/daitokuji-temple/")

    // ---- Arashiyama, Kameoka, Takao, Katsura ----
    private val bamboo = Spot("Arashiyama Bamboo Grove, Kyoto, Japan", 35.01765, 135.67308, "https://www.japan.travel/en/spot/1141/")
    private val tenryuji = Spot("Tenryu-ji, Kyoto, Japan", 35.01596, 135.67377, "https://www.tenryuji.com/en/")
    private val shigetsu = Spot("Shigetsu, Tenryu-ji, Kyoto, Japan", 35.01513, 135.6736, "https://www.tenryuji.com/en/shigetsu/")
    private val okochi = Spot("Okochi Sanso, Kyoto, Japan", 35.01727, 135.66935, "https://kyoto-museums.city.kyoto.lg.jp/museum/90/")
    private val jojakkoji = Spot("Jojakko-ji, Kyoto, Japan", 35.01964, 135.66868, "https://jojakko-ji.or.jp/")
    private val adashino = Spot("Adashino Nenbutsu-ji, Kyoto, Japan", 35.02681, 135.66452, "https://www.japan.travel/en/spot/1143/")
    private val togetsukyo = Spot("Togetsukyo Bridge, Kyoto, Japan", 35.01281, 135.67772, "https://kyoto.travel/en/destinations/togetsukyo-bridge/")
    private val monkeyPark = Spot("Iwatayama Monkey Park, Kyoto, Japan", 35.00894, 135.67468, "https://www.city.kyoto.lg.jp/nisikyo/page/0000280184.html")
    private val torokkoArashiyama = Spot("Torokko Arashiyama Station, Kyoto, Japan", 35.01704, 135.67053, "https://www.sagano-kanko.co.jp/en/")
    private val hozuPier = Spot("Hozugawa river boat pier, Kameoka, Japan", 35.01743, 135.58664, "https://www.hozugawakudari.jp/")
    private val jingoji = Spot("Jingo-ji, Takao, Kyoto, Japan", 35.05502, 135.67087, "https://kyoto.travel/en/destinations/jingoji-temple/")
    private val saihoji = Spot("Saiho-ji, Kyoto, Japan", 34.9925, 135.68417, "https://saihoji-kokedera.com/en/")
    private val katsura = Spot("Katsura Imperial Villa, Kyoto, Japan", 34.98389, 135.70944, "https://kyoto-gosho.kunaicho.go.jp/en/katsura-rikyu")

    // ---- Kurama, Kibune, Ohara, Mt Hiei ----
    private val kuramadera = Spot("Kurama-dera, Kyoto, Japan", 35.11806, 135.77074, "https://www.kuramadera.or.jp/")
    private val kuramaOnsen = Spot("Kurama Onsen, Kyoto, Japan", 35.11925, 135.77646, "https://kurama-spa.com/")
    private val kibuneShrine = Spot("Kibune Shrine, Kyoto, Japan", 35.12172, 135.76283, "https://kifunejinja.jp/en/")
    private val sanzenin = Spot("Sanzen-in, Ohara, Kyoto, Japan", 35.11982, 135.83442, "https://www.sanzenin.or.jp/en/")
    private val hosenin = Spot("Hosen-in, Ohara, Kyoto, Japan", 35.12135, 135.83401, "https://kyoto.travel/en/destinations/hosenin-temple/")
    private val jakkoin = Spot("Jakko-in, Ohara, Kyoto, Japan", 35.1241, 135.82104, "https://www.jakkoin.jp/")
    private val enryakuji = Spot("Enryaku-ji, Mt Hiei, Otsu, Japan", 35.07056, 135.84111, "https://www.hieizan.or.jp/")

    // ---- Uji and Fushimi ----
    private val byodoin = Spot("Byodo-in, Uji, Japan", 34.88929, 135.80769, "https://www.byodoin.or.jp/en/")
    private val teaStreet = Spot("Byodo-in Omotesando, Uji, Japan", 34.89099, 135.80652)
    private val tokichi = Spot("Nakamura Tokichi Honten, Uji, Japan", 34.88941, 135.80171, "https://tokichi.jp/pages/honten-store-page")
    private val taihoan = Spot("Taiho-an, Uji, Japan", 34.88898, 135.80867, "https://www.kyoto-uji-kankou.or.jp/taihoan.html")
    private val ujigami = Spot("Ujigami Shrine, Uji, Japan", 34.89211, 135.8115, "https://www.kyototourism.org/en/sightseeing/451/")
    private val koshoji = Spot("Kosho-ji, Uji, Japan", 34.89003, 135.81374, "https://www.uji-koushouji.jp/en/")
    private val gekkeikan = Spot("Gekkeikan Okura Sake Museum, Fushimi, Kyoto, Japan", 34.92969, 135.76217, "https://www.gekkeikan.com/museum/")
    private val jikkokubune = Spot("Jikkokubune pier, Fushimi, Kyoto, Japan", 34.92847, 135.76152)
    private val yumeHyakushu = Spot("Fushimi Yume Hyakushu, Fushimi, Kyoto, Japan", 34.93002, 135.7614)
    private val kizakura = Spot("Kizakura Kappa Country, Fushimi, Kyoto, Japan", 34.93111, 135.76043, "https://kizakura.co.jp/restaurant/country/")

    // ---- Nara ----
    private val kofukuji = Spot("Kofuku-ji, Nara, Japan", 34.68325, 135.83117, "https://www.kohfukuji.com/english/")
    private val naraPark = Spot("Nara Park, Nara, Japan", 34.68556, 135.84333, "https://www.visitnara.jp/destinations/area/nara-park/")
    private val todaiji = Spot("Todai-ji, Nara, Japan", 34.68917, 135.83972, "https://www.todaiji.or.jp/en/")
    private val nigatsudo = Spot("Nigatsu-do, Todai-ji, Nara, Japan", 34.68928, 135.84428, "https://www.todaiji.or.jp/en/information/nigatsudo/")
    private val kasuga = Spot("Kasuga Taisha, Nara, Japan", 34.68139, 135.84833, "https://www.kasugataisha.or.jp/en/about_en/")
    private val naramachi = Spot("Naramachi, Nara, Japan", 34.67584, 135.82998, "https://www.visitnara.jp/destinations/area/naramachi/")
    private val isuien = Spot("Isuien Garden, Nara, Japan", 34.68563, 135.83756, "https://isuien.or.jp/")
    private val wakakusa = Spot("Mt Wakakusa, Nara, Japan", 34.69111, 135.85444, "https://www.visitnara.jp/venues/A01078/")
    private val kasugayama = Spot("Kasugayama Primeval Forest, Nara, Japan", 34.68431, 135.86453, "https://www.visitnara.jp/venues/A00490/")
    private val horyuji = Spot("Horyu-ji, Ikaruga, Nara, Japan", 34.6144, 135.7342, "https://www.horyuji.or.jp/en/")

    // ---- Osaka: Minami ----
    private val dotonbori = Spot("Dotonbori, Osaka, Japan", 34.66871, 135.50131, "https://osaka-info.jp/en/spot/dotonbori/")
    private val hozenji = Spot("Hozen-ji Yokocho, Osaka, Japan", 34.66818, 135.50267, "https://osaka-info.jp/en/spot/hozenji-yokocho/")
    private val kamigata = Spot("Kamigata Ukiyoe Museum, Osaka, Japan", 34.66797, 135.5022, "https://osaka-info.jp/en/spot/kamigata-ukiyoe-museum/")
    private val nambaYasaka = Spot("Namba Yasaka Shrine, Osaka, Japan", 34.66148, 135.49673, "https://nambayasaka.jp/")
    private val kuromon = Spot("Kuromon Market, Osaka, Japan", 34.66528, 135.50694, "https://osaka-info.jp/en/spot/kuromon-market/")
    private val bunraku = Spot("National Bunraku Theatre, Osaka, Japan", 34.6675, 135.50861, "https://www.ntj.jac.go.jp/en/bunraku/")
    private val doguyasuji = Spot("Sennichimae Doguyasuji, Osaka, Japan", 34.66393, 135.50352, "https://osaka-info.jp/en/spot/sennichimae-doguyasuji-shopping-street/")
    private val uraNamba = Spot("Ura-Namba, Osaka, Japan", 34.66553, 135.50473, "https://osaka-info.jp/en/spot/gastronomy-urananba/")
    private val matsubaya = Spot("Usami-tei Matsubaya, Osaka, Japan", 34.67796, 135.50232, "https://osaka-info.jp/en/special/dc/event/detail648/")
    private val takashimayaOsaka = Spot("Takashimaya Osaka, Namba, Osaka, Japan", 34.66463, 135.5018, "https://www.takashimaya.co.jp/osaka/")
    private val kizu = Spot("Kizu Market, Osaka, Japan", 34.65722, 135.5, "https://kizu-ichiba.com/")

    // ---- Osaka: castle, Tenma, Umeda ----
    private val osakaCastle = Spot("Osaka Castle, Osaka, Japan", 34.68722, 135.52583, "https://www.osakacastle.net/")
    private val castlePark = Spot("Osaka Castle Park, Osaka, Japan", 34.68197, 135.53106, "https://www.osakacastlepark.jp/")
    private val nishinomaru = Spot("Nishinomaru Garden, Osaka, Japan", 34.6871, 135.52311, "https://osaka-info.jp/en/spot/osaka-castle-nishinomaru-garden/")
    private val historyMuseum = Spot("Osaka Museum of History, Osaka, Japan", 34.68263, 135.5208, "https://www.osakamushis.jp/eng/")
    private val tenmangu = Spot("Osaka Tenmangu, Osaka, Japan", 34.69602, 135.51262, "https://osakatemmangu.or.jp/")
    private val tenjinbashi = Spot("Tenjinbashi-suji, Osaka, Japan", 34.6964, 135.51177, "https://osaka-info.jp/en/spot/tenjimbashisuji-shopping-street/")
    private val housingMuseum = Spot("Osaka Museum of Housing and Living, Osaka, Japan", 34.71031, 135.51169, "https://www.osaka-angenet.jp/konjyakukan/")
    private val kema = Spot("Kema Sakuranomiya Park, Osaka, Japan", 34.70072, 135.52307, "https://osaka-info.jp/en/spot/kema-sakuranomiya-park/")
    private val umedaSky = Spot("Umeda Sky Building, Osaka, Japan", 34.70528, 135.48972, "https://www.skybldg.co.jp/en/")
    private val hankyuUmeda = Spot("Hankyu Umeda, Osaka, Japan", 34.70228, 135.49881, "https://global.hankyu-hanshin-dept.co.jp/store/hankyuhonten/")
    private val hanshinUmeda = Spot("Hanshin Umeda, Osaka, Japan", 34.70116, 135.49861, "https://www.hanshin-dept.jp/hshonten/index.html")
    private val shinUmeda = Spot("Shin-Umeda Shokudogai, Osaka, Japan", 34.70363, 135.49581)
    private val fukushima = Spot("Fukushima, Osaka, Japan", 34.69713, 135.48658)
    private val yodoyabashi = Spot("Yodoyabashi, Osaka, Japan", 34.69232, 135.501, "https://suijo-bus.osaka/cruiselist/aqualiner/")
    private val nakanoshimaRoses = Spot("Nakanoshima Park Rose Garden, Osaka, Japan", 34.69201, 135.50898, "https://osaka-info.jp/en/spot/nakanoshima-park/")
    private val utsubo = Spot("Utsubo Park, Osaka, Japan", 34.6851, 135.493, "https://osaka-info.jp/en/spot/utsubo-park/")

    // ---- Osaka: south, Sakai, the bay ----
    private val shitennoji = Spot("Shitenno-ji, Osaka, Japan", 34.6539, 135.51645, "https://www.shitennoji.or.jp/")
    private val tsutenkaku = Spot("Tsutenkaku, Shinsekai, Osaka, Japan", 34.65222, 135.50611, "https://www.tsutenkaku.co.jp/")
    private val janjan = Spot("Janjan Yokocho, Shinsekai, Osaka, Japan", 34.64989, 135.50612, "https://osaka-info.jp/en/spot/janjan-yokocho-alley/")
    private val tennojiPark = Spot("Tennoji Park, Osaka, Japan", 34.64985, 135.51111, "https://osaka-info.jp/en/spot/tennoji-park-entrance-area-ten-shiba/")
    private val harukas = Spot("Abeno Harukas, Osaka, Japan", 34.64595, 135.51427, "https://www.abenoharukas-300.jp/en/")
    private val tsuruhashi = Spot("Tsuruhashi, Osaka, Japan", 34.66535, 135.5313, "https://osaka-info.jp/en/spot/osaka-tsuruhashi-market/")
    private val sumiyoshi = Spot("Sumiyoshi Taisha, Osaka, Japan", 34.61239, 135.49378, "https://www.sumiyoshitaisha.net/en/")
    private val daisen = Spot("Daisen Kofun, Sakai, Japan", 34.56393, 135.48731, "https://www.city.sakai.lg.jp/foreign-language/english/visitors/topics/world_heritage_site/notable_tombs/nintoku-ryo.html")
    private val sakaiCityHall = Spot("Sakai City Hall, Sakai, Japan", 34.57369, 135.48287, "https://osaka-info.jp/en/spot/21st-floor-observation-lobby-sakai-hall/")
    private val denshokan = Spot("Sakai Traditional Crafts Museum, Sakai, Japan", 34.58289, 135.47749, "https://www.sakaidensan.jp/")
    private val rinku = Spot("Rinku Park, Izumisano, Japan", 34.40327, 135.28986, "https://osaka-info.jp/en/spot/rinku-park/")

    // ---- North of Osaka ----
    private val minooFalls = Spot("Minoo Falls, Minoo, Japan", 34.85389, 135.47194, "https://www.mino-park.jp/about/182/")
    private val ryuanji = Spot("Ryuan-ji, Minoo, Japan", 34.84252, 135.47243, "https://www.ryuanji.org/")
    private val expoPark = Spot("Expo '70 Commemorative Park, Suita, Japan", 34.81012, 135.5276, "https://www.expo70-park.jp/languages/english/")
    private val towerOfSun = Spot("Tower of the Sun, Suita, Japan", 34.80964, 135.53231, "https://taiyounotou-expo70.jp/en/")
    private val cupNoodles = Spot("Cup Noodles Museum Osaka Ikeda, Ikeda, Japan", 34.81804, 135.42667, "https://www.cupnoodles-museum.jp/en/osaka_ikeda/")

    // ---- Kobe ----
    private val hakutsuru = Spot("Hakutsuru Sake Brewery Museum, Kobe, Japan", 34.7102, 135.2617, "https://www.hakutsuru.co.jp/english/culture/museum.html")
    private val kikumasamune = Spot("Kiku-Masamune Sake Brewery Museum, Kobe, Japan", 34.70862, 135.26791, "https://www.kikumasamune.co.jp/kinenkan/en/")
    private val nankinmachi = Spot("Nankinmachi, Kobe, Japan", 34.68806, 135.18806, "https://www.nankinmachi.or.jp/")
    private val harborland = Spot("Kobe Harborland, Kobe, Japan", 34.67906, 135.18181, "https://harborland.co.jp/")
    private val kitanoIjinkan = Spot("Kitano Ijinkan, Kobe, Japan", 34.70138, 135.1895, "https://www.kobeijinkan.com/en/")
    private val nunobiki = Spot("Nunobiki Falls, Kobe, Japan", 34.70983, 135.19385, "https://www.feel-kobe.jp/en/attractions/detail_1014.html")
    private val herbGardens = Spot("Nunobiki Herb Gardens, Kobe, Japan", 34.71528, 135.19222, "https://www.kobeherb.com/en/")
    private val rokkoTerrace = Spot("Rokko Garden Terrace, Kobe, Japan", 34.76361, 135.24611, "https://www.rokkosan.com/gt/")
    private val arimaRopeway = Spot("Mt Rokko Arima Ropeway, Arima Onsen, Kobe, Japan", 34.79096, 135.24569, "https://koberope.jp/en/rokko")
    private val kinnoyu = Spot("Kin-no-yu, Arima Onsen, Kobe, Japan", 34.79678, 135.24786, "https://arimaspa-kingin.jp/")
    private val arima = Spot("Arima Onsen, Kobe, Japan", 34.79806, 135.2475, "https://www.arima-onsen.com/")
    private val kikuseidai = Spot("Kikuseidai, Mt Maya, Kobe, Japan", 34.73395, 135.2067, "https://koberope.jp/en/maya")
    private val maiko = Spot("Maiko Park, Kobe, Japan", 34.63411, 135.03422, "https://hyogo-maikopark.jp/en/")

    // ---- Himeji, Koyasan ----
    private val himejiCastle = Spot("Himeji Castle, Himeji, Japan", 34.83938, 134.6941, "https://www.himejicastle.jp/en/")
    private val kokoen = Spot("Koko-en Garden, Himeji, Japan", 34.83814, 134.68964, "https://himeji-machishin.jp/ryokka/kokoen/en/")
    private val engyoji = Spot("Engyo-ji, Mt Shosha, Himeji, Japan", 34.89133, 134.65772, "https://visit-himeji.com/en/sightseeing/shoshazan-engyo-ji-temple/")
    private val okunoin = Spot("Okunoin, Koyasan, Japan", 34.2175, 135.605, "https://www.koyasan.or.jp/en/")

    // ---- Builders ----
    // Places, meals, activities and ideas take their spot's website; stays never have one (they are generic), and a
    // train or flight only has the one it is given (the operator's page, not the station's).
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

    // Shared first and last legs.
    private fun landAtKix() = transport("Fly to Osaka Kansai", "Get an ICOCA card at the JR ticket office in the airport station", kix, null, kix.url)
    private fun harukaToKyoto() = transport("Haruka to Kyoto", "Limited express · ≈ 75–80 min, about every 30 min", kyotoStation, Train, HARUKA)
    private fun naraByKintetsu() = transport("Kintetsu to Nara", "From Kyoto Station · limited express 35 min, express ≈ 45–50", kintetsuNara, Train, KINTETSU)

    // ---- Sights & history (the demo trip) ----
    private val sights = Program(
        id = "$ID-culture",
        destinationId = ID,
        focus = Focus.Culture,
        title = "Temples, castles and old streets",
        blurb = "Seven nights in Kyoto with days in Nara and Uji, then four in Osaka with Sakai and Himeji.",
        days = listOf(
            day(
                "Arrive, Gion at dusk", "Kyoto",
                landAtKix(),
                harukaToKyoto(),
                stay("Small hotel or ryokan near Gion", "Taxi from the station: big cases are hard on city buses", gion, Car),
                place("Kennin-ji", "Kyoto's oldest Zen temple, if you land early · twin dragons on the ceiling", kenninji, Walk, E),
                place("Yasaka Shrine", "The shrine of Gion; its lanterns are lit after dark", yasaka, Walk, B),
                activity("Gion at dusk", "Hanamikoji and Shirakawa · side lanes are private: no entry, no photos", hanamikoji, Walk),
                food("Dinner in Pontocho", "Narrow lane of small restaurants · river platforms May to mid-October", pontocho, Walk),
                place("Kamo River at night", "Walk the bank between the Shijo and Sanjo bridges", kamoRiver, Walk, B),
            ),
            day(
                "Fushimi Inari and Higashiyama", "Kyoto",
                activity("Fushimi Inari at first light", "Open 24 h · the full loop ≈ 2–3 h; most turn back at Yotsutsuji", fushimiInari, Train, time = "07:00"),
                place("Tofuku-ji", "Zen temple with the Tsutenkyo bridge over a maple valley", tofukuji, Walk, B),
                place("Sanjusangen-do", "1,001 wooden Kannon statues in one long hall · no photos inside", sanjusangendo, Train, E),
                place("Kiyomizu-dera", "The wooden stage over the hillside · opens at 6:00", kiyomizudera, Car),
                place("Sannenzaka and Ninenzaka", "Stone lanes of old shops and tea houses down from the temple", sannenzaka, Walk),
                place("Kodai-ji", "Temple and gardens founded by Hideyoshi's widow", kodaiji, Walk, B),
            ),
            day(
                "Arashiyama and Saga", "Kyoto",
                place("Arashiyama bamboo grove", "Go early, before the groups · carving the bamboo is fined", bamboo, Train, time = "07:30"),
                place("Tenryu-ji", "Zen garden from the 1300s · opens 8:30; the north gate faces the grove", tenryuji, Walk),
                place("Okochi Sanso", "A film actor's hillside villa and garden · matcha and a sweet included", okochi, Walk, B),
                place("Jojakko-ji", "Moss and maples on the slope of Mt Ogura", jojakkoji, Walk, E),
                place("Adashino Nenbutsu-ji", "Thousands of stone Buddhas for the unnamed dead", adashino, Walk, E),
                food("Yudofu lunch", "Simmered tofu, a Saga speciality, often by a temple garden"),
                place("Togetsukyo Bridge", "Long bridge over the Katsura River, the hills behind", togetsukyo, Walk, B),
            ),
            day(
                "Philosopher's Path", "Kyoto",
                place("Ginkaku-ji", "The silver pavilion and its sand garden · opens 8:30", ginkakuji, Car),
                activity("Philosopher's Path", "Canal walk under cherry trees · ≈ 2 km, 30–45 min", philosophersPath, Walk),
                place("Honen-in", "Thatched gate and raked sand mounds · grounds free", honenin, Walk, B),
                place("Eikan-do", "Hillside temple, busiest in autumn for its maples", eikando, Walk, E),
                place("Nanzen-ji", "The great Sanmon gate and a brick aqueduct in the grounds", nanzenji, Walk),
                place("Heian Shrine", "Huge vermilion gate; the garden behind the halls is the best part", heian, Walk, B),
            ),
            day(
                "Golden Pavilion and Nijo Castle", "Kyoto",
                place("Kitano Tenmangu", "Shrine of scholars, open from 7:00 · flea market on the 25th", kitano, Car, E),
                place("Kinkaku-ji", "The golden pavilion over its pond · 9:00–17:00", kinkakuji, Car),
                place("Ryoan-ji", "Rock garden of fifteen stones, never all seen at once", ryoanji, Walk),
                place("Ninna-ji", "Former imperial temple with a five-storey pagoda", ninnaji, Walk, B),
                place("Nijo Castle", "Nightingale floors · Ninomaru Palace shut Tuesdays in Jan, Jul, Aug, Dec", nijo, Car, B),
                food("Nishiki Market", "Snacks for a late lunch · eat at the stall; many shut on Wednesdays", nishiki, Car),
            ),
            day(
                "Day trip to Nara", "Kyoto",
                naraByKintetsu(),
                place("Kofuku-ji", "Its pagoda is covered for repairs until 2034; the museum is open", kofukuji, Walk, B),
                place("Nara Park", "Deer everywhere: feed them only the crackers sold in the park", naraPark, Walk),
                place("Todai-ji", "The Great Buddha in its giant wooden hall", todaiji, Walk),
                place("Nigatsu-do", "Hillside hall above Todai-ji with a free view over Nara", nigatsudo, Walk, B),
                place("Kasuga Taisha", "Shrine of thousands of stone and bronze lanterns", kasuga, Walk, B),
                place("Naramachi", "Old merchant quarter of narrow wooden townhouses", naramachi, Walk, E),
                transport("Back to Kyoto", "", kyotoStation, Train),
            ),
            day(
                "Uji and the Fushimi breweries", "Kyoto",
                note("Slow morning", "Late breakfast · Uji is ≈ 20 min by JR from Kyoto Station"),
                place("To-ji", "Kyoto's tallest pagoda, near the station · market on the 21st", toji, Walk, E),
                place("Byodo-in", "The Phoenix Hall of 1053 · inside by timed group, tickets at the gate", byodoin, Train),
                food("Matcha on the tea street", "Uji tea shops line the lane to Byodo-in", teaStreet, Walk),
                place("Ujigami Shrine", "Across the river, one of Japan's oldest shrine buildings", ujigami, Walk, B),
                place("Kosho-ji", "Zen temple up a maple-lined slope", koshoji, Walk, E),
                place("Fushimi sake breweries", "Willow canal and old breweries · Gekkeikan museum 9:30–16:30, cards only", gekkeikan, Train, B),
            ),
            day(
                "To Osaka: Namba", "Osaka",
                transport("Train to Osaka", "Shinkansen to Shin-Osaka ≈ 15 min, or the cheaper JR Special Rapid ≈ 25 min", shinOsaka, Train, SHINKANSEN),
                stay("Hotel in Namba", "Central for Minami and for Nankai trains to the airport", namba, Train),
                food("Lunch at Kuromon Market", "Seafood grilled at the stalls · most close by late afternoon", kuromon, Walk, B),
                place("National Bunraku Theatre", "Puppet theatre · runs in January, April, summer and autumn; book online", bunraku, Walk, E),
                place("Kamigata Ukiyoe Museum", "Small museum of Osaka kabuki prints · 11:00–18:00, closed Mondays", kamigata, Walk, B),
                place("Hozen-ji Yokocho", "Stone alley by a moss-covered statue that people splash with water", hozenji, Walk),
                place("Dotonbori at night", "Canal, neon signs and street food", dotonbori, Walk),
            ),
            day(
                "Osaka Castle and Tenma", "Osaka",
                place("Osaka Castle", "The keep is a museum, 9:00–18:00 · walk the moats and stone walls", osakaCastle, Train),
                place("Osaka Museum of History", "Next to the castle, with views over the park · closed Tuesdays", historyMuseum, Walk, B),
                place("Osaka Tenmangu", "Shrine of the scholar Sugawara no Michizane", tenmangu, Train, B),
                place("Tenjinbashi-suji", "Japan's longest shopping street, ≈ 2.6 km of arcade", tenjinbashi, Walk, E),
                place("Museum of Housing and Living", "Life-size Edo-era Osaka street · closed Tuesdays; shut until 5 Jan 2027", housingMuseum, Walk, E),
                place("Umeda Sky Building", "Open-air rooftop at sunset · open to 22:30", umedaSky, Train),
            ),
            day(
                "Sumiyoshi, Sakai and Shinsekai", "Osaka",
                place("Sumiyoshi Taisha", "Shrine with a steep arched bridge · Nankai local from Namba ≈ 10 min", sumiyoshi, Train),
                place("Sakai City Hall lookout", "Free 21st-floor lobby looking over the tomb area", sakaiCityHall, Train, B),
                place("Daisen Kofun", "Japan's largest keyhole tomb · ≈ 2.8 km path around the moat", daisen, Car, B),
                place("Sakai Traditional Crafts Museum", "Sakai knives and how they are made · closed 3rd Tuesday", denshokan, Car, E),
                place("Shinsekai and Tsutenkaku", "Old Osaka under the tower, best at dusk · tower tickets are timed", tsutenkaku, Train),
            ),
            day(
                "Day trip to Himeji", "Osaka",
                transport("Train to Himeji", "Shinkansen from Shin-Osaka ≈ 30 min, or JR Special Rapid from Osaka ≈ 60", himejiStation, Train, SHINKANSEN),
                place("Himeji Castle", "The white castle · 9:00–17:00, last entry 16:00; shoes off inside", himejiCastle, Walk),
                place("Koko-en Garden", "Nine walled gardens beside the castle", kokoen, Walk),
                food("Himeji oden", "Oden with a ginger-soy dip, the local way", pace = B),
                place("Engyo-ji", "Mountain temple of The Last Samurai · bus, then the Mt Shosha ropeway", engyoji, Car, B),
                place("Kitano Ijinkan", "Kobe on the way back: foreign merchants' houses from the 1900s", kitanoIjinkan, Train, E),
                transport("Back to Osaka", "", namba, Train),
            ),
            day(
                "Tennoji, then home", "Osaka",
                place("Namba Yasaka Shrine", "Shrine with a stage shaped like a giant lion's head", nambaYasaka, Walk, E),
                place("Shitenno-ji", "Founded in 593 by Prince Shotoku, one of Japan's oldest temples", shitennoji, Train),
                place("Tennoji Park", "Lawns and the Chausuyama burial mound", tennojiPark, Walk, B),
                place("Abeno Harukas 300", "Glass-walled deck 300 m up · 9:00–22:00", harukas, Walk),
                food("Bento for the flight", "From a department-store food hall at Tennoji", pace = B),
                transport("Fly home from Kansai", "Haruka from Tennoji, or Nankai from Namba", kix, Train, kix.url),
            ),
        ),
        ideas = listOf(
            idea("place", "Koyasan", "Temple town in the mountains · ≈ 2 h each way; best with a night in a temple", okunoin),
            idea("place", "Katsura Imperial Villa", "Guided tours only · book online 3 months ahead; closed Mondays", katsura),
            idea("place", "Horyu-ji", "Some of the oldest wooden buildings in the world, south-west of Nara", horyuji),
            idea("place", "Kyoto Imperial Palace", "Free, no booking · closed Mondays; main hall under cover from late 2026", gosho),
            idea("place", "Daitoku-ji", "Walled Zen temple town; Daisen-in and Zuiho-in open all year", daitokuji),
        ),
        sources = listOf(
            "https://en.wikivoyage.org/wiki/Kyoto",
            "https://en.wikivoyage.org/wiki/Osaka",
            "https://kyoto.travel/en/news/4-guidelines/",
            "https://kyoto.travel/en/travel-inspiration/kyoto-reservation-guide/",
            "https://inari.jp/en/",
            "https://tofukuji.jp/guide/",
            "https://www.sanjusangendo.jp/",
            "https://www.kiyomizudera.or.jp/en/visit",
            "https://www.kodaiji.com/haikan.html",
            "https://www.tenryuji.com/en/visit/",
            "https://www.shokoku-ji.jp/en/ginkakuji/",
            "https://www.shokoku-ji.jp/en/kinkakuji/",
            "https://www.nanzenji.or.jp/",
            "https://www.heianjingu.or.jp/map/garden/",
            "https://nijo-jocastle.city.kyoto.lg.jp/?lang=en",
            "https://kitanotenmangu.or.jp/",
            "https://kyoto-gosho.kunaicho.go.jp/en/visit",
            "https://toji.or.jp/en/",
            "https://www.todaiji.or.jp/en/information/haikan/",
            "https://www.kohfukuji.com/info/info-guide/",
            "https://www.kasugataisha.or.jp/",
            "https://www.byodoin.or.jp/en/guide/",
            "https://www.gekkeikan.com/museum/",
            "https://www.westjr.co.jp/travel-information/en/tickets-passes/oneway/haruka/",
            "https://www.westjr.co.jp/global/en/howto/icoca/",
            "https://www.osakacastle.net/",
            "https://www.museum.or.jp/museum/10620",
            "https://www.osaka-angenet.jp/konjyakukan/",
            "https://www.skybldg.co.jp/en/observatory/",
            "https://osaka-info.jp/en/spot/kamigata-ukiyoe-museum/",
            "https://www.ntj.jac.go.jp/bunraku.html",
            "https://www.sumiyoshitaisha.net/access/",
            "https://www.city.sakai.lg.jp/kanko/rekishi/dkofun/tansaku/index.html",
            "https://osaka-info.jp/en/spot/21st-floor-observation-lobby-sakai-hall/",
            "https://osaka-info.jp/en/spot/sakai-traditional-crafts-museum/",
            "https://www.tsutenkaku.co.jp/annai/index.html",
            "https://www.shitennoji.or.jp/admission.html",
            "https://www.abenoharukas-300.jp/en/observatory/",
            "https://www.himejicastle.jp/en/",
            "https://www.himeji-kanko.jp/spot/5/",
            "https://www.kobeijinkan.com/ijinkan_list/kazamidori",
            "https://www.nankai.co.jp/traffic/express/kouya.html",
        ),
    )

    // ---- Eat & drink ----
    private val eats = Program(
        id = "$ID-food",
        destinationId = ID,
        focus = Focus.Food,
        title = "Markets, tea and sake",
        blurb = "Kyoto's markets, temple food, Uji tea and Fushimi sake, then Osaka street food and a day in Kobe.",
        days = listOf(
            day(
                "Arrive, Pontocho by the river", "Kyoto",
                landAtKix(),
                harukaToKyoto(),
                stay("Small hotel near Shijo-Kawaramachi", "Nishiki, Pontocho and Gion all on foot", kawaramachi, Car),
                food("Takashimaya food hall", "Basement food hall: bento, pickles, sweets · open to 20:00", takashimayaKyoto, Walk, E),
                place("Gion Shirakawa", "Willows, a stream and old tea houses", shirakawa, Walk, B),
                activity("Kamo River at dusk", "Walk the bank between the Shijo and Sanjo bridges", kamoRiver, Walk, B),
                food("Izakaya dinner in Pontocho", "Small places in a narrow lane · river platforms May to mid-October", pontocho, Walk),
                food("A drink on Kiyamachi", "Small bars along the Takase canal", kiyamachi, Walk),
            ),
            day(
                "Nishiki Market and a cooking class", "Kyoto",
                note("Not on a Wednesday", "Many Nishiki stalls and Aritsugu close on Wednesdays"),
                food("Nishiki Market", "≈ 400 m of food stalls · eat where you buy, not while walking", nishiki, Walk),
                place("Aritsugu", "Knife maker since 1560 · they can engrave your name on the blade", aritsugu, Walk, B),
                place("Nishiki Tenmangu", "Small shrine at the market's east end", nishikiTenmangu, Walk, E),
                activity("Kyoto home-cooking class", "Obanzai or washoku with a local cook · book online ahead"),
                food("Daimaru food hall", "Two basement floors of pickles, sweets and bento", daimaru, Walk, B),
                food("Obanzai dinner", "Kyoto home cooking: small seasonal dishes along the counter"),
            ),
            day(
                "Fushimi: foxes and sake", "Kyoto",
                note("Not on a Monday", "The canal boat and the tasting café close on Mondays"),
                activity("Fushimi Inari early", "Open 24 h · the full loop ≈ 2–3 h; quiet before 8:00", fushimiInari, Train, time = "07:30"),
                food("Inari-zushi and kitsune udon", "Fried tofu, the fox's food, in the lanes by the shrine gate"),
                place("Gekkeikan Okura Sake Museum", "Old brewery, tools and a tasting · 9:30–16:30, cards only", gekkeikan, Train),
                activity("Jikkokubune canal boat", "≈ 50 min past the breweries · spring to early December, not Mondays", jikkokubune, Walk, B),
                food("Sake tasting at Fushimi Yume Hyakushu", "Tasting sets from Fushimi's breweries · closed Mondays", yumeHyakushu, Walk, B),
                food("Dinner at Kizakura Kappa Country", "Brewery restaurant: sake and its own craft beer", kizakura, Walk, E),
            ),
            day(
                "Uji tea country", "Kyoto",
                place("Byodo-in", "The Phoenix Hall of 1053 · Uji is ≈ 20 min by JR", byodoin, Train),
                food("Uji tea street", "Matcha sweets and tastings on the lane to Byodo-in", teaStreet, Walk),
                food("Nakamura Tokichi", "Tea merchant since 1854 · no bookings: join the waiting list", tokichi, Walk),
                activity("Tea at Taiho-an", "City tea house: matcha and a sweet · 10 Jan–20 Dec, 10:00–16:00", taihoan, Walk, B),
                place("Ujigami Shrine", "Across the river, one of Japan's oldest shrine buildings", ujigami, Walk, B),
                food("Kyoto Ramen Koji", "Ramen from around Japan on the 10th floor of Kyoto Station", ramenKoji, Train, E),
            ),
            day(
                "Arashiyama: Zen temple food", "Kyoto",
                place("Arashiyama bamboo grove", "Go early, before the groups", bamboo, Train, time = "08:00"),
                place("Okochi Sanso", "Hillside villa garden · matcha and a sweet included", okochi, Walk, B),
                place("Tenryu-ji garden", "Pond garden from the 1300s · the north gate faces the grove", tenryuji, Walk, B),
                food("Shojin lunch at Shigetsu", "Zen vegetarian meal in Tenryu-ji · book 2–3 days ahead, closed Thursdays", shigetsu, Walk, time = "11:00"),
                place("Togetsukyo Bridge", "A walk by the river after lunch", togetsukyo, Walk, E),
                food("Yakitori dinner", "Grilled skewers and a cold beer at a counter"),
            ),
            day(
                "Higashiyama: tea and kaiseki", "Kyoto",
                place("Kiyomizu-dera", "The wooden stage over the hillside · opens 6:00, quieter early", kiyomizudera, Car),
                food("Sweets on Sannenzaka and Ninenzaka", "Tea houses and sweet shops on the stone lanes", sannenzaka, Walk),
                activity("Tea ceremony", "A bowl of matcha made in front of you · 45–90 min, book online", pace = B),
                place("Yasaka Shrine", "Through the shrine down to Gion", yasaka, Walk, B),
                food("Kuzukiri in Gion", "Cold kudzu noodles dipped in black-sugar syrup", pace = E),
                food("Kaiseki dinner", "Kyoto's seasonal course meal · book ahead; lunch costs far less"),
            ),
            day(
                "Old sweet shops of the north", "Kyoto",
                note("Not on a Tuesday or Wednesday", "Futaba closes Tuesdays; the dango and aburi-mochi shops Wednesdays"),
                food("Breakfast at Inoda Coffee", "Kyoto coffee house since 1940 · open from 7:00", inoda, Walk),
                food("Demachi Futaba", "Mame-mochi, rice cakes with red peas · expect a queue", demachiFutaba, Train),
                place("Shimogamo Shrine", "Through the old Tadasu forest", shimogamo, Walk, B),
                food("Kamo Mitarashi Chaya", "Said to be where mitarashi dango began", mitarashi, Walk, B),
                food("Aburi-mochi at Imamiya Shrine", "Small grilled rice cakes in white miso, at two old shops", imamiya, Car, E),
                food("Ramen in Ichijoji", "A street of ramen shops by Ichijoji station", ichijoji, Car),
            ),
            day(
                "To Osaka: Namba street food", "Osaka",
                transport("Train to Osaka", "Hankyu from Kawaramachi to Umeda ≈ 43 min, then the subway to Namba", umeda, Train, HANKYU),
                stay("Hotel in Namba", "Dotonbori, Kuromon and Ura-Namba on foot", namba, Train),
                food("Kitsune udon at Usami-tei Matsubaya", "Where udon with fried tofu began, 1893 · closed Sundays, holidays", matsubaya, Walk, B),
                place("Sennichimae Doguyasuji", "Kitchen-tool street: knives, plastic food, takoyaki pans", doguyasuji, Walk, B),
                food("Takoyaki and okonomiyaki in Dotonbori", "Street stands first, then a griddle place", dotonbori, Walk),
                food("Hozen-ji Yokocho", "Stone alley of small old restaurants", hozenji, Walk),
                food("Ura-Namba", "Tiny izakaya and standing bars behind Namba station", uraNamba, Walk, E),
            ),
            day(
                "Day trip to Kobe: sake and beef", "Osaka",
                place("Hakutsuru Sake Brewery Museum", "Free · old wooden brewery and a tasting, 5 min from Hanshin Sumiyoshi", hakutsuru, Train),
                place("Kiku-Masamune Sake Brewery Museum", "Free tasting · closed Tuesdays", kikumasamune, Walk, B),
                food("Kobe beef lunch", "Lunch sets cost far less than dinner · book the better places"),
                food("Nankinmachi", "Kobe's Chinatown: buns and dumplings from the stalls", nankinmachi, Train, B),
                place("Kobe Harborland at dusk", "Harbour walk before the train back", harborland, Walk, E),
                transport("Back to Osaka", "Hanshin trains run through to Namba", namba, Train),
            ),
            day(
                "Ikeda noodles, Umeda food halls", "Osaka",
                place("Cup Noodles Museum Osaka Ikeda", "Free · make your own cup, tickets on the day · closed Tuesdays", cupNoodles, Train),
                food("Hankyu Umeda food hall", "Basement floors of sweets, bento and gifts", hankyuUmeda, Train),
                food("Ikayaki at Hanshin Umeda", "Squid pancake, an Osaka snack, in the basement", hanshinUmeda, Walk, B),
                food("Shin-Umeda Shokudogai", "Food alleys under the tracks since 1950, standing bars too", shinUmeda, Walk, B),
                place("Umeda Sky Building", "Open-air rooftop at sunset", umedaSky, Walk, E),
                food("Izakaya in Fukushima", "Lanes of small izakaya one stop west of Osaka", fukushima, Walk, E),
            ),
            day(
                "Tenma to Shinsekai", "Osaka",
                food("Tenjinbashi-suji food crawl", "≈ 2.6 km of arcade: croquettes, sushi, standing bars", tenjinbashi, Train),
                place("Osaka Tenmangu", "Shrine by the arcade's south end", tenmangu, Walk, B),
                place("Osaka Castle", "The keep and the moats · 9:00–18:00", osakaCastle, Train, B),
                food("Kushikatsu in Shinsekai", "Fried skewers · dip once only in the shared sauce", janjan, Train),
                food("Yakiniku in Tsuruhashi", "Grill-your-own meat by the station, next to Korea Town", tsuruhashi, Train, E),
            ),
            day(
                "Last market, then home", "Osaka",
                place("Namba Yasaka Shrine", "The giant lion-head stage", nambaYasaka, Walk, E),
                food("Breakfast at Kuromon Market", "Grilled seafood and fruit · come early", kuromon, Walk),
                food("Gifts from Takashimaya Osaka", "Namba food hall: sweets, tea and pickles to take home", takashimayaOsaka, Walk),
                place("Sumiyoshi Taisha", "Old shrine on the airport line · Nankai local ≈ 10 min from Namba", sumiyoshi, Train, B),
                place("Sakai knife museum", "Sakai Traditional Crafts Museum: knives and a shop · closed 3rd Tuesday", denshokan, Train, B),
                transport("Fly home from Kansai", "Nankai rapi:t β trains stop at Sakai on the way to KIX", kix, Train, kix.url),
            ),
        ),
        ideas = listOf(
            idea("food", "Kibune riverside lunch", "May–September: meals on platforms over the stream · book ahead", kibuneShrine, url = null),
            idea("activity", "Chicken Ramen Factory", "Make the noodles by hand at the Ikeda museum · book 3 months ahead", cupNoodles),
            idea("food", "Kizu Market Saturday market", "Wholesale market open to all on the 2nd and last Saturday, 9:00–12:00", kizu),
            idea("food", "Yudofu near Nanzen-ji", "Simmered tofu in a garden · Okutan closes Thursdays", okutan),
            idea("food", "Kakinoha-zushi in Nara", "Mackerel or salmon sushi wrapped in persimmon leaves"),
        ),
        sources = listOf(
            "https://en.wikivoyage.org/wiki/Kyoto",
            "https://en.wikivoyage.org/wiki/Osaka",
            "https://kyoto.travel/en/destinations/kyoto-nishiki-food-market/",
            "https://kyoto-nishiki.or.jp/en/manner",
            "https://kyoto-nishiki.or.jp/en/stores/aritsugu",
            "https://kyoto.travel/en/season_festivals/noryoyuka_kawadoko.html",
            "https://www.kyoto-yuka.com/",
            "https://www.daimaru.co.jp/kyoto/hours.html",
            "https://www.takashimaya.co.jp/kyoto/departmentstore/businesshours/",
            "https://inari.jp/en/",
            "https://www.gekkeikan.com/museum/",
            "https://www.fushimi.or.jp/sake_guide/tasting",
            "https://ja.wikipedia.org/wiki/伏見十石舟",
            "https://www.byodoin.or.jp/en/guide/",
            "https://tokichi.jp/pages/honten-store-page",
            "https://www.kyoto-uji-kankou.or.jp/taihoan.html",
            "https://www.tenryuji.com/en/shigetsu/",
            "https://www.inoda-coffee.co.jp/shop/honten/",
            "https://eizandensha.co.jp/en/ramen/",
            "https://www.westjr.co.jp/travel-information/en/tickets-passes/oneway/haruka/",
            "https://hankyu.co.jp/global/en/characteristic/index.html",
            "https://osaka-info.jp/spot/sennichimae-doguyasuji-shopping-street/",
            "https://www.timeout.com/osaka/restaurants/usamitei-matsubaya",
            "https://www.hakutsuru.co.jp/community/shiryo/",
            "https://www.kikumasamune.co.jp/kinenkan/access.html",
            "https://kobe-niku.jp/en/contents/about/definition.html",
            "https://www.cupnoodles-museum.jp/en/osaka_ikeda/",
            "https://www.hankyu-dept.co.jp/honten/",
            "https://www.hanshin-dept.jp/hshonten/",
            "https://ja.wikipedia.org/wiki/新梅田食道街",
            "https://osaka-info.jp/en/spot/tenjimbashisuji-shopping-street/",
            "https://www.osakacastle.net/",
            "https://www.sumiyoshitaisha.net/access/",
            "https://www.city.sakai.lg.jp/yoyakuanai/bunrui/sangyo/dentosangyo.html",
            "https://en.wikipedia.org/wiki/Rapi:t",
        ),
    )

    // ---- Nature & views ----
    private val outdoors = Program(
        id = "$ID-nature",
        destinationId = ID,
        focus = Focus.Nature,
        title = "Hills, rivers and gardens",
        blurb = "Kyoto's hill trails, gorge and gardens with Nara's park, then Minoo, Mt Rokko and Himeji from Osaka.",
        days = listOf(
            day(
                "Arrive, the Kamo River", "Kyoto",
                landAtKix(),
                harukaToKyoto(),
                stay("Small hotel near Sanjo and the river", "Taxi from the station: big cases are hard on city buses", sanjo, Car),
                place("Maruyama Park", "Old park below the hills, with a famous weeping cherry", maruyama, Walk, B),
                place("Yasaka Shrine", "Down through the shrine to Gion", yasaka, Walk, E),
                place("Gion Shirakawa", "Willows and a stream by the old tea houses", shirakawa, Walk, B),
                activity("Kamo River at dusk", "Walk the bank north from Shijo; herons fish in the shallows", kamoRiver, Walk),
                food("Dinner in Pontocho", "Narrow lane above the river · river platforms May to mid-October", pontocho, Walk),
            ),
            day(
                "Fushimi Inari to the top", "Kyoto",
                activity("Fushimi Inari to the summit", "Open 24 h · ≈ 2–3 h up Mt Inari (233 m) and back; city view at Yotsutsuji", fushimiInari, Train, time = "07:00"),
                place("Tofuku-ji", "Maple valley under the Tsutenkyo bridge · peak colour mid-Nov to early Dec", tofukuji, Walk),
                place("Kiyomizu-dera", "The wooden stage looks out over the trees to the city", kiyomizudera, Car, B),
                place("Sannenzaka and Ninenzaka", "Stone lanes down from the temple", sannenzaka, Walk, E),
                place("Shogunzuka Seiryuden", "Big wooden deck high above the city · taxi up", shogunzuka, Car, B),
                place("Shoren-in", "Down the hill trail (≈ 30 min) to its giant camphor trees", shorenin, Walk, E),
            ),
            day(
                "Arashiyama by train and boat", "Kyoto",
                note("Book the train", "Romantic Train seats go on sale online a month ahead; it runs March to late December"),
                place("Arashiyama bamboo grove", "Go early, before the groups", bamboo, Train, time = "07:30"),
                place("Okochi Sanso", "Hillside garden with views over the city · matcha included", okochi, Walk, E),
                activity("Sagano Romantic Train", "Open cars up the Hozu gorge, ≈ 25 min · no trains most Wednesdays", torokkoArashiyama, Walk),
                activity("Hozugawa river boat", "≈ 2 h back down the gorge by pole and oar · stops in high water", hozuPier, Car),
                place("Iwatayama Monkey Park", "20–30 min climb · wild macaques and a view over the city", monkeyPark, Ferry, B),
                place("Togetsukyo Bridge", "The bridge by the boat landing, best near sunset", togetsukyo, Walk, B),
            ),
            day(
                "Kibune to Kurama", "Kyoto",
                transport("Eizan train to Kibuneguchi", "From Demachiyanagi ≈ 30 min, then bus 33 or a 30 min walk up", kibuneguchi, Train, EIZAN),
                place("Kibune Shrine", "Lantern-lined steps up to the shrine of water", kibuneShrine, Car),
                food("Lunch by the Kibune stream", "May–September on platforms over the water · book ahead", pace = B),
                place("Kurama-dera, over the ridge", "≈ 1 h trail over tree roots from Kibune · the temple closes 16:15", kuramadera, Walk),
                activity("Kurama Onsen", "Outdoor bath in the woods near Kurama station · reopened Nov 2024", kuramaOnsen, Walk, B),
                place("Shimogamo Shrine", "The old Tadasu forest, on the way back from Demachiyanagi", shimogamo, Train, E),
                place("Kamo River delta", "Turtle stepping stones where two rivers meet", kamoDelta, Walk, E),
            ),
            day(
                "Daimonji and the Philosopher's Path", "Kyoto",
                place("Ginkaku-ji", "Moss garden and a hill path above the pavilion · opens 8:30", ginkakuji, Car),
                activity("Climb Daimonji-yama", "Trail starts 5 min north of Ginkaku-ji · 30–60 min up to the fire bed view", daimonji, Walk),
                activity("Philosopher's Path", "Canal walk under cherry and maple, ≈ 2 km", philosophersPath, Walk),
                place("Honen-in", "Moss, a thatched gate and raked sand · grounds free", honenin, Walk, B),
                place("Nanzen-ji aqueduct", "Brick aqueduct in the woods; the Sanmon gate has a view", nanzenji, Walk, B),
                place("Keage Incline", "Old boat railway lined with cherry trees", keage, Walk, E),
            ),
            day(
                "Day trip to Nara: park and forest", "Kyoto",
                naraByKintetsu(),
                place("Isuien Garden", "Garden framing Todai-ji's gate and the hills · closed most Tuesdays", isuien, Walk, E),
                place("Nara Park", "Deer on the lawns: feed them only the crackers sold in the park", naraPark, Walk),
                place("Todai-ji", "The Great Buddha in its giant wooden hall", todaiji, Walk, B),
                place("Nigatsu-do", "Free hillside hall with a wide view over Nara", nigatsudo, Walk, B),
                activity("Mt Wakakusa", "Grassy hill, ≈ 35 min to the top · open mid-March to mid-December", wakakusa, Walk),
                activity("Kasugayama forest walk", "Old forest where logging has been banned since 841", kasugayama, Walk, E),
                place("Kasuga Taisha", "Lantern paths through the woods", kasuga, Walk),
                transport("Back to Kyoto", "", kyotoStation, Train),
            ),
            day(
                "Ohara, a slow day", "Kyoto",
                note("Slow morning", "The bus to Ohara takes about an hour from Kyoto Station"),
                place("Sanzen-in", "Moss garden under tall cedars", sanzenin, Car),
                place("Hosen-in", "Matcha and a sweet, the garden framed by the room's pillars", hosenin, Walk),
                food("Lunch with Ohara pickles", "Shibazuke, purple shiso pickles, are Ohara's own", pace = B),
                place("Jakko-in", "Quiet nunnery across the valley, ≈ 25 min on foot", jakkoin, Walk, B),
                place("Kyoto Botanical Gardens", "Big gardens by the river on the way back · last entry 16:00", botanical, Car, E),
            ),
            day(
                "To Osaka: castle park and sky", "Osaka",
                transport("Train to Osaka", "JR Special Rapid ≈ 29 min, or Shinkansen to Shin-Osaka ≈ 15 min", osakaStation, Train),
                stay("Hotel near Osaka Station", "Umeda: handy for the trains to Minoo, Kobe and Himeji", umeda, Walk),
                place("Osaka Castle Park", "Moats, high stone walls, plum and cherry groves", castlePark, Train),
                place("Nishinomaru Garden", "Big lawn and cherry trees facing the keep · closed Mondays", nishinomaru, Walk, B),
                place("Osaka Castle keep", "The top floor looks out over the park · 9:00–18:00", osakaCastle, Walk, B),
                place("Kema Sakuranomiya Park", "≈ 4 km of riverside cherry trees along the Okawa", kema, Walk, E),
                place("Umeda Sky Building", "Open-air rooftop at sunset · open to 22:30", umedaSky, Train),
            ),
            day(
                "Minoo Falls and the Expo park", "Osaka",
                food("Momiji tempura", "Maple leaves fried in batter, sold along the path"),
                activity("Walk up to Minoo Falls", "≈ 2.8 km up the river from Hankyu Minoo, ≈ 35 min each way", minooFalls, Train),
                place("Ryuan-ji", "Old temple halfway down the path", ryuanji, Walk, B),
                place("Expo '70 Commemorative Park", "Big gardens and woods · closed most Wednesdays", expoPark, Train, B),
                activity("Inside the Tower of the Sun", "Book at least a day ahead", towerOfSun, Walk, E),
            ),
            day(
                "Kobe: falls, Mt Rokko, Arima", "Osaka",
                place("Nunobiki Falls", "Four falls in a wooded ravine, 15 min up from Shin-Kobe station", nunobiki, Train, B),
                place("Nunobiki Herb Gardens", "Climb on to the gardens, then ride the ropeway down", herbGardens, Walk, E),
                place("Rokko Garden Terrace", "Cable car up Mt Rokko, then a bus · views over the bay", rokkoTerrace, Train),
                activity("Ropeway over to Arima", "≈ 12 min across the ridge · last car ≈ 17:10–17:30 on weekdays", arimaRopeway, Train),
                activity("Bath at Kin-no-yu", "Arima's iron-red hot spring · closed 2nd and 4th Tuesdays", kinnoyu, Walk),
                place("Arima Onsen lanes", "Old hot-spring town of steep lanes and small shops", arima, Walk, B),
                place("Kobe Harborland at night", "Lights on the water before the train back", harborland, Train, E),
                transport("Back to Osaka", "", umeda, Train),
            ),
            day(
                "Himeji and Mt Shosha", "Osaka",
                transport("Train to Himeji", "JR Special Rapid from Osaka ≈ 60 min, or Shinkansen ≈ 30 min", himejiStation, Train, SHINKANSEN),
                place("Himeji Castle", "The white castle · 9:00–17:00, last entry 16:00; shoes off inside", himejiCastle, Walk),
                place("Koko-en Garden", "Nine walled gardens with streams and ponds beside the castle", kokoen, Walk),
                food("Himeji oden", "Oden with a ginger-soy dip, the local way", pace = B),
                activity("Mt Shosha ropeway to Engyo-ji", "Forest temple on the mountain · bus to the ropeway, then a walk up", engyoji, Car, B),
                place("Maiko Park and the Akashi Kaikyo Bridge", "The long bridge to Awaji Island, by JR Maiko station", maiko, Train, E),
                transport("Back to Osaka", "", umeda, Train),
            ),
            day(
                "Rivers and roses, then home", "Osaka",
                activity("Aqua-Liner river loop", "≈ 1 h past the castle · boards at Yodoyabashi at 20 past the hour", yodoyabashi, Train, E),
                place("Nakanoshima rose garden", "Island park between two rivers · roses in May and October", nakanoshimaRoses, Walk),
                place("Utsubo Park", "Long park on an old airfield; roses in the east part", utsubo, Walk),
                food("Gifts from the Hankyu Umeda food hall", "Sweets and tea to take home", hankyuUmeda, Train, B),
                place("Rinku Park", "Seaside park facing the airport island, one stop before KIX", rinku, Train, B),
                transport("Fly home from Kansai", "One stop on from Rinku Town, by JR or Nankai", kix, Train, kix.url),
            ),
        ),
        ideas = listOf(
            idea("place", "Saiho-ji moss garden", "Book online 2 months ahead; sutra copying is part of the visit", saihoji),
            idea("place", "Takao and Jingo-ji", "Maple valley north-west of Kyoto · throw clay discs into the gorge", jingoji),
            idea("place", "Mt Hiei", "Cable car and ropeway up from Yase, late March to early December", enryakuji),
            idea("place", "Kikuseidai on Mt Maya", "Night view over Kobe · cable car and ropeway closed Tuesdays", kikuseidai),
            idea("place", "Koyasan", "Okunoin's cedar forest of old graves · ≈ 2 h each way from Namba", okunoin),
        ),
        sources = listOf(
            "https://en.wikivoyage.org/wiki/Kyoto",
            "https://en.wikivoyage.org/wiki/Osaka",
            "https://inari.jp/en/",
            "https://tofukuji.jp/guide/",
            "https://www.kiyomizudera.or.jp/en/visit",
            "https://www.shogunzuka.com/access.html",
            "https://www.sagano-kanko.co.jp/en/ticket/",
            "https://www.city.kameoka.kyoto.jp/uploaded/attachment/32825.pdf",
            "https://www.tenryuji.com/en/visit/",
            "https://eizandensha.co.jp/en/",
            "https://www.kuramadera.or.jp/",
            "https://www.kifunejinja.jp/en/info",
            "https://www.shokoku-ji.jp/en/ginkakuji/",
            "https://www.nanzenji.or.jp/",
            "https://www.todaiji.or.jp/en/information/haikan/",
            "https://www.pref.nara.lg.jp/site/park/2585.html",
            "https://www.visitnara.jp/venues/A00493/",
            "https://www.kasugataisha.or.jp/",
            "https://www.sanzenin.or.jp/en/",
            "https://kyoto.travel/en/getting-around/comfortable-access-to-ohara-including-yase/",
            "https://www.kyotobotanicalgardens.jp/english",
            "https://www.osakacastle.net/",
            "https://osaka-info.jp/en/spot/osaka-castle-nishinomaru-garden/",
            "https://osaka-info.jp/experience/en/osaka/spot/458",
            "https://www.skybldg.co.jp/en/observatory/",
            "https://minohkankou.net/english/",
            "https://www.expo70-park.jp/guide/check/",
            "https://taiyounotou-expo70.jp/en/",
            "https://www.feel-kobe.jp/en/attractions/detail_1014.html",
            "https://www.kobeherb.com/",
            "https://www.rokkocable.com/",
            "https://koberope.jp/rokko/price/",
            "https://arimaspa-kingin.jp/kin-01.htm",
            "https://www.himejicastle.jp/en/",
            "https://www.himeji-kanko.jp/spot/4/",
            "https://www.himeji-kanko.jp/spot/5/",
            "https://suijo-bus.osaka/",
            "https://www.city.osaka.lg.jp/hodoshiryo/kensetsu/0000686737.html",
            "https://intosaihoji.com/en/",
            "https://www.hieizan.gr.jp/timetable/",
            "https://koberope.jp/maya/price/",
        ),
    )

    val destination: Destination = Destination(
        id = ID,
        title = "Kyoto & Osaka",
        blurb = "Kyoto's temples and gardens, Osaka's food streets, and day trips to Nara, Uji, Kobe and Himeji.",
        countryCodes = listOf("JP"),
        zoneId = "Asia/Tokyo",
        bestTime = "Best in spring (late March–April) and autumn (November–early December); rainy June to mid-July, then hot",
        programs = listOf(eats, sights, outdoors),
    )
}

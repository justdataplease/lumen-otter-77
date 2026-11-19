package com.justdataplease.boringtravel.data

/**
 * The Argentina demo's real photos (sample version 8, owner 2026-11-14: "for images you find on web"), shipped in the
 * app's assets at `assets/demo/argentina/<name>.jpg`, 1280 px on the long side, without metadata. Freely licensed:
 * CC0 or public domain need no credit; the others carry their [Photo.credit] (author and licence), shown with the
 * photo credits of the demo trip ([CREDITS]). Where each came from: [Photo.source] and
 * `assets/licenses/demo-photos.txt`. Generated from the photo manifest by scratchpad scripts/gen_demo_photos.py.
 */
object DemoPhotos {
    const val FOLDER = "demo/argentina"

    data class Photo(val name: String, val width: Int, val height: Int, val credit: String? = null, val source: String = "") {
        /** The file in the app's assets. */
        val asset: String get() = "$FOLDER/$name.jpg"
    }

    val OBELISCO = Photo("obelisco-night", 853, 1280, credit = "Dennis G. Jarvis, CC BY-SA 2.0", source = "https://commons.wikimedia.org/wiki/File:Argentina-01811_-_Obelisk_of_Buenos_Aires_(49004693648).jpg")
    val CAFE = Photo("cafe-medialunas", 1280, 964, source = "https://commons.wikimedia.org/wiki/File:All_butter_croissant_and_latte_-_Blend_Coffee_Co._2026-01-02.jpg")
    val RECOLETA = Photo("recoleta-cemetery", 1280, 853, source = "https://commons.wikimedia.org/wiki/File:Cemetery_Argentina_Buenos_Aires_2.jpg")
    val ROSEDAL = Photo("rosedal", 1280, 960, source = "https://commons.wikimedia.org/wiki/File:RosedalBuenosAires.JPG")
    val SAN_TELMO = Photo("san-telmo", 1280, 853, source = "https://commons.wikimedia.org/wiki/File:2011.10.16.124619_Old_house_Defensa_San_Telmo_Buenos_Aires.jpg")
    val CAMINITO = Photo("caminito", 1280, 960, source = "https://commons.wikimedia.org/wiki/File:Caminito_apr2008.jpg")
    val UCO = Photo("uco-vineyard", 1280, 794, credit = "David, CC BY 2.0", source = "https://commons.wikimedia.org/wiki/File:Vineyard_in_Mendoza,_Argentina.jpg")
    val GRAPES = Photo("mendoza-grapes", 1280, 960, credit = "Micah MacAllen, CC BY-SA 2.0", source = "https://commons.wikimedia.org/wiki/File:Malbec_grapes_on_the_vine.jpg")
    val LLAO_LLAO = Photo("llao-llao", 1280, 960, credit = "Alexrebolledo, CC BY-SA 3.0", source = "https://commons.wikimedia.org/wiki/File:Llao_Llao_desde_punto_panor%C3%A1mico.JPG")
    val CAMPANARIO = Photo("campanario-view", 1280, 800, credit = "Hiroki Ogawa, CC BY 3.0", source = "https://commons.wikimedia.org/wiki/File:Cerro_Campanario_Bariloche_Argentina_-_panoramio_(2).jpg")
    val CENTRO = Photo("bariloche-centro", 1280, 960, credit = "Phil Whitehouse, CC BY 2.0", source = "https://commons.wikimedia.org/wiki/File:Bariloche_Centro_Civico.jpg")
    val BOAT = Photo("puerto-panuelo-boat", 1280, 914, credit = "Wolfgang Fricke, CC BY 3.0", source = "https://commons.wikimedia.org/wiki/File:Modesta_Victoria_P_Puerto_Panuelo_06-02-2018.jpg")
    val ARRAYANES = Photo("arrayanes", 960, 1280, source = "https://commons.wikimedia.org/wiki/File:ParqueNacionalArrayanes099.JPG")
    val CHALTEN = Photo("chalten-village", 1280, 960, credit = "Gagea, CC BY-SA 3.0", source = "https://commons.wikimedia.org/wiki/File:El_Chalt%C3%A9n_-_Fitz_Roy_massif_-_R%C3%ADo_Fitz_Roy.JPG")
    val SUNSET = Photo("condores-sunset", 1280, 746, credit = "Masa Sakano, CC BY-SA 2.0", source = "https://commons.wikimedia.org/wiki/File:Mount_Fitz_Roy_at_Sunset.jpg")
    val CAPRI = Photo("laguna-capri", 960, 1280, credit = "Gagea, CC BY-SA 3.0", source = "https://commons.wikimedia.org/wiki/File:Fitz_Roy_and_reflect_in_Laguna_Capri_(sunrise).JPG")
    val FITZ_ROY = Photo("fitz-roy-cover", 1280, 960, source = "https://commons.wikimedia.org/wiki/File:Fitz_Roy_2.jpg")

    val ALL: List<Photo> = listOf(OBELISCO, CAFE, RECOLETA, ROSEDAL, SAN_TELMO, CAMINITO, UCO, GRAPES, LLAO_LLAO, CAMPANARIO, CENTRO, BOAT, ARRAYANES, CHALTEN, SUNSET, CAPRI, FITZ_ROY)

    /** The credits the licences ask for, one line each ("Author, CC BY 2.0"); empty when every photo is free of them. */
    val CREDITS: List<String> = ALL.mapNotNull { it.credit }.distinct()

    /** Where a demo photo is stored once seeded: files/media/argentina-<name>-<id>.jpg (a real photo, never drawn). */
    fun localPath(photo: Photo, id: String): String = "media/argentina-${photo.name}-$id.jpg"
}

package com.justdataplease.boringtravel.clip

/**
 * Who walks the legs on foot in the trip video (owner: "customize avatars for video also add couple and be able to
 * change facial hair hair skin colour clothes same for woman so will go by hand"): the man, the woman, or both side
 * by side holding hands. Both people have every option; [Who] only picks who shows. Set in Me › Your traveller (the
 * default for every video) and per video in the editor's Traveller tool ([ClipEdits.traveller]). Drawn by
 * [PaperTraveller]; pure, so it runs on the JVM.
 */
data class Traveller(
    val who: Who = Who.Man,
    val man: Person = Person.MAN,
    val woman: Person = Person.WOMAN,
) {
    /** The people in the video, left to right as they walk (the man on the left of a couple). */
    val people: List<Person> get() = when (who) {
        Who.Man -> listOf(man)
        Who.Woman -> listOf(woman)
        Who.Couple -> listOf(man, woman)
    }

    /** [slot]'s person (the man or the woman). */
    fun person(slot: Who): Person = if (slot == Who.Woman) woman else man

    /** [slot]'s person changed by [change]; the couple's slots are the man and the woman. */
    fun change(slot: Who, change: (Person) -> Person): Traveller =
        if (slot == Who.Woman) copy(woman = change(woman)) else copy(man = change(man))

    /** "who=couple;man=skin:t3,hair:short,…;woman=…" — every value an id, so new options never break an old string. */
    fun encode(): String = "who=${who.id};man=${man.encode()};woman=${woman.encode()}"

    companion object {
        /** The friendly default: the man, walking on his own. */
        val DEFAULT = Traveller()

        /** Reads [encode]'s string; anything missing or unknown is the default (null and blank: [DEFAULT]). */
        fun decode(raw: String?): Traveller {
            if (raw.isNullOrBlank()) return DEFAULT
            val parts = parts(raw)
            return Traveller(
                who = Who.entries.firstOrNull { it.id == parts["who"] } ?: DEFAULT.who,
                man = Person.decode(parts["man"], Person.MAN),
                woman = Person.decode(parts["woman"], Person.WOMAN),
            )
        }

        /** [raw]'s "key=value;key=value" parts (the traveller's, and what a [com.justdataplease.boringtravel.community.ProfileAvatar] adds). */
        fun parts(raw: String): Map<String, String> = raw.split(';').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
        }.toMap()

        /** A stored per-video choice: null when there is none or it can't be read (the default then applies). */
        fun decodeOrNull(raw: String?): Traveller? =
            raw?.takeIf { it.isNotBlank() && it.length <= 2_000 && it.contains("who=") }?.let(::decode)
    }
}

/** Who walks: one of the two people, or both hand in hand. */
enum class Who(val id: String, val label: String) { Man("man", "Man"), Woman("woman", "Woman"), Couple("couple", "Couple") }

/** One person's look. Every option is open to both people (a beard, long hair, a dress). */
data class Person(
    val skin: SkinTone = SkinTone.T3,
    val hair: HairStyle = HairStyle.Short,
    val hairColour: HairColour = HairColour.DarkBrown,
    val facialHair: FacialHair = FacialHair.None,
    val top: TopStyle = TopStyle.Tee,
    val topColour: Cloth = Cloth.Teal,
    val bottom: BottomStyle = BottomStyle.Trousers,
    val bottomColour: Cloth = Cloth.Navy,
    val hat: Hat = Hat.None,
    val hatColour: Cloth = Cloth.Mustard,
    val glasses: Glasses = Glasses.None,
    val backpack: Boolean = true,
) {
    /** A dress takes the top's colour all the way down. */
    val dress: Boolean get() = bottom == BottomStyle.Dress

    fun encode(): String = listOf(
        "skin" to skin.id, "hair" to hair.id, "hairColour" to hairColour.id, "face" to facialHair.id,
        "top" to top.id, "topColour" to topColour.id, "bottom" to bottom.id, "bottomColour" to bottomColour.id,
        "hat" to hat.id, "hatColour" to hatColour.id, "glasses" to glasses.id, "bag" to if (backpack) "on" else "off",
    ).joinToString(",") { (k, v) -> "$k:$v" }

    companion object {
        /** The default man: short dark hair, stubble, a teal T-shirt, navy trousers and a backpack. */
        val MAN = Person(facialHair = FacialHair.Stubble)

        /** The default woman: long brown hair and a coral dress with short sleeves, no backpack. */
        val WOMAN = Person(
            skin = SkinTone.T5, hair = HairStyle.Long, hairColour = HairColour.Brown, top = TopStyle.Tee,
            topColour = Cloth.Coral, bottom = BottomStyle.Dress, bottomColour = Cloth.Denim, backpack = false,
        )

        /** Reads [encode]'s string over [fallback]: each missing or unknown value keeps the fallback's. */
        fun decode(raw: String?, fallback: Person): Person {
            if (raw.isNullOrBlank()) return fallback
            val m = raw.split(',').mapNotNull { part ->
                val i = part.indexOf(':')
                if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
            }.toMap()
            fun <T> pick(key: String, values: List<T>, id: (T) -> String, default: T): T =
                m[key]?.let { v -> values.firstOrNull { id(it) == v } } ?: default
            return Person(
                skin = pick("skin", SkinTone.entries, { it.id }, fallback.skin),
                hair = pick("hair", HairStyle.entries, { it.id }, fallback.hair),
                hairColour = pick("hairColour", HairColour.entries, { it.id }, fallback.hairColour),
                facialHair = pick("face", FacialHair.entries, { it.id }, fallback.facialHair),
                top = pick("top", TopStyle.entries, { it.id }, fallback.top),
                topColour = pick("topColour", Cloth.entries, { it.id }, fallback.topColour),
                bottom = pick("bottom", BottomStyle.entries, { it.id }, fallback.bottom),
                bottomColour = pick("bottomColour", Cloth.entries, { it.id }, fallback.bottomColour),
                hat = pick("hat", Hat.entries, { it.id }, fallback.hat),
                hatColour = pick("hatColour", Cloth.entries, { it.id }, fallback.hatColour),
                glasses = pick("glasses", Glasses.entries, { it.id }, fallback.glasses),
                backpack = when (m["bag"]) { "on" -> true; "off" -> false; else -> fallback.backpack },
            )
        }
    }
}

/** Eight skin tones, light to deep. */
enum class SkinTone(val id: String, val label: String, val argb: Int) {
    T1("t1", "Very light", 0xFFF6DCCB.toInt()),
    T2("t2", "Light", 0xFFEEC7A6.toInt()),
    T3("t3", "Light medium", 0xFFE0AE86.toInt()),
    T4("t4", "Medium", 0xFFC98F63.toInt()),
    T5("t5", "Tan", 0xFFAD744B.toInt()),
    T6("t6", "Brown", 0xFF8C5736.toInt()),
    T7("t7", "Dark brown", 0xFF6B3F27.toInt()),
    T8("t8", "Deep", 0xFF4A2C1D.toInt()),
}

enum class HairStyle(val id: String, val label: String) {
    Short("short", "Short"), Curly("curly", "Curly"), Long("long", "Long"), Bob("bob", "Bob"),
    Ponytail("ponytail", "Ponytail"), Bun("bun", "Bun"), Buzz("buzz", "Shaved"), Bald("bald", "Bald");

    /** Whether there is hair to colour. */
    val hasHair: Boolean get() = this != Bald
}

enum class HairColour(val id: String, val label: String, val argb: Int) {
    Black("black", "Black", 0xFF221D1B.toInt()),
    DarkBrown("darkbrown", "Dark brown", 0xFF402B1F.toInt()),
    Brown("brown", "Brown", 0xFF6E4529.toInt()),
    Blonde("blonde", "Blonde", 0xFFDDB66F.toInt()),
    Red("red", "Red", 0xFFA4462A.toInt()),
    Grey("grey", "Grey", 0xFFA5A29D.toInt()),
    Pink("pink", "Pink", 0xFFE77FAE.toInt()),
    Blue("blue", "Blue", 0xFF4B78D6.toInt()),
    Green("green", "Green", 0xFF3C9D74.toInt()),
    Purple("purple", "Purple", 0xFF7E57B8.toInt()),
}

enum class FacialHair(val id: String, val label: String) {
    None("none", "None"), Stubble("stubble", "Stubble"), Moustache("moustache", "Moustache"),
    Goatee("goatee", "Goatee"), Beard("beard", "Beard"),
}

enum class TopStyle(val id: String, val label: String) {
    Tee("tee", "T-shirt"), Long("long", "Long sleeves"), Hoodie("hoodie", "Hoodie"), Jacket("jacket", "Jacket"), Tank("tank", "Tank top"),
}

enum class BottomStyle(val id: String, val label: String) {
    Trousers("trousers", "Trousers"), Shorts("shorts", "Shorts"), Skirt("skirt", "Skirt"), Dress("dress", "Dress"),
}

/** The clothes' colours (tops, bottoms, hats). */
enum class Cloth(val id: String, val label: String, val argb: Int) {
    White("white", "White", 0xFFF3EFE6.toInt()),
    Black("black", "Black", 0xFF26272D.toInt()),
    Grey("grey", "Grey", 0xFF8D9096.toInt()),
    Navy("navy", "Navy", 0xFF27385C.toInt()),
    Denim("denim", "Denim", 0xFF4C70A6.toInt()),
    Teal("teal", "Teal", 0xFF2E8F8B.toInt()),
    Olive("olive", "Olive", 0xFF6E7B3B.toInt()),
    Khaki("khaki", "Khaki", 0xFFCBB68A.toInt()),
    Brown("brown", "Brown", 0xFF7B5135.toInt()),
    Mustard("mustard", "Mustard", 0xFFE2A92F.toInt()),
    Coral("coral", "Coral", 0xFFE96B52.toInt()),
    Red("red", "Red", 0xFFC6323D.toInt()),
    Pink("pink", "Pink", 0xFFEA92B6.toInt()),
    Purple("purple", "Purple", 0xFF7A5AA8.toInt()),
}

enum class Hat(val id: String, val label: String) { None("none", "None"), Cap("cap", "Cap"), Beanie("beanie", "Beanie"), SunHat("sunhat", "Sun hat") }

enum class Glasses(val id: String, val label: String) { None("none", "None"), Glasses("glasses", "Glasses"), Sunglasses("sunglasses", "Sunglasses") }

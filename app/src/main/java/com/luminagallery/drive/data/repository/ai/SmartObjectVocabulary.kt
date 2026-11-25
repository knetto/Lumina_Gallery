package com.luminagallery.drive.data.repository.ai

import java.util.Locale

/**
 * Curated object/scene filter + bilingual (English + Dutch) search synonym expansion
 * for Google ML Kit Image Labeling results so searching "hond" or "dog", "strand" or "beach",
 * "auto" or "car", "eten" or "food" works seamlessly offline.
 */
object SmartObjectVocabulary {

    /** High-level human-friendly categories surfaced as visual chips in Search. */
    enum class ObjectCategory(val displayName: String, val key: String) {
        PETS("Pets & Animals", "pets"),
        FOOD("Food & Drink", "food"),
        BEACH("Beach & Water", "beach"),
        NATURE("Nature", "nature"),
        VEHICLES("Vehicles", "vehicles"),
        ARCHITECTURE("City & Buildings", "architecture"),
        FLOWERS("Flowers & Plants", "flowers"),
        SKY("Sky & Sunset", "sky"),
        SPORTS("Sports", "sports"),
        MUSIC("Music", "music"),
        TRAVEL("Travel", "travel"),
        CELEBRATION("Celebration", "celebration"),
        DOCUMENTS("Documents", "documents"),
        TECH("Tech", "tech")
    }

    private val NOISY_GENERIC_LABELS: Set<String> = setOf(
        "fun", "event", "leisure", "cool", "good", "snapshot", "photography", "photo",
        "flesh", "skin", "eyelash", "eyebrow", "forehead", "jaw", "chin", "cheek", "neck",
        "thumb", "nail", "finger", "wrist", "elbow", "shoulder", "arm", "leg", "hand", "mouth",
        "nose", "lip", "hair", "head", "ear", "eye", "face", "person", "human", "people",
        "smile", "gesture", "interaction", "happy", "happiness", "youth", "adult", "man", "woman",
        "tints and shades", "electric blue", "magenta", "pattern", "rectangle", "circle", "line",
        "font", "material property", "wood", "plastic", "metal", "paper product", "flooring",
        "wall", "ceiling", "room", "interior design", "space", "world", "art", "design",
        "monochrome", "black and white", "screenshot", "product", "brand", "advertising",
        "fashion", "style", "clothing", "outerwear", "sleeve", "collar", "jeans", "shirt",
        "t-shirt", "jersey", "textile", "fabric", "activewear", "footwear", "eyewear"
    )

    private val SYNONYM_MAP: Map<String, List<String>> = mapOf(
        "dog" to listOf("dogs", "hond", "honden", "puppy", "pup", "pet", "huisdier", "animal", "dier"),
        "puppy" to listOf("dog", "hond", "puppy", "pet", "huisdier", "animal", "dier"),
        "cat" to listOf("cats", "kat", "katten", "poes", "kitten", "pet", "huisdier", "animal", "dier"),
        "pet" to listOf("huisdier", "huisdieren", "animal", "dier", "dieren"),
        "bird" to listOf("vogel", "vogels", "animal", "dier", "wildlife"),
        "horse" to listOf("paard", "paarden", "animal", "dier"),
        "fish" to listOf("vis", "vissen", "aquarium", "animal", "dier"),
        "animal" to listOf("dier", "dieren", "wildlife", "natuur"),
        "food" to listOf("eten", "meal", "maaltijd", "dish", "gerecht", "dinner", "diner", "lunch", "restaurant"),
        "cuisine" to listOf("food", "eten", "meal", "maaltijd", "dish", "gerecht", "restaurant"),
        "meal" to listOf("food", "eten", "maaltijd", "dinner", "lunch"),
        "pizza" to listOf("food", "eten", "italian", "pizza"),
        "cake" to listOf("taart", "gebak", "birthday", "verjaardag", "dessert", "toetje", "food", "eten"),
        "dessert" to listOf("toetje", "gebak", "taart", "sweet", "zoet", "food", "eten"),
        "coffee" to listOf("koffie", "espresso", "latte", "cappuccino", "cafe", "drink", "drankje"),
        "drink" to listOf("drank", "drankje", "beverage", "cocktail", "bier", "beer", "wine", "wijn"),
        "fruit" to listOf("fruit", "appel", "banaan", "food", "eten"),
        "baked goods" to listOf("brood", "bread", "bakery", "bakker", "gebak", "food", "eten"),
        "beach" to listOf("strand", "sea", "zee", "ocean", "oceaan", "sand", "zand", "coast", "kust", "vacation", "vakantie"),
        "sea" to listOf("zee", "ocean", "oceaan", "beach", "strand", "water"),
        "ocean" to listOf("oceaan", "zee", "sea", "beach", "strand", "water"),
        "water" to listOf("water", "lake", "meer", "river", "rivier", "pool", "zwembad"),
        "pool" to listOf("zwembad", "swimming", "zwemmen", "water", "summer", "zomer"),
        "boat" to listOf("boot", "ship", "schip", "sailing", "zeilen", "water"),
        "mountain" to listOf("bergen", "berg", "mountains", "hiking", "wandelen", "nature", "natuur", "alps"),
        "forest" to listOf("bos", "woods", "tree", "trees", "boom", "bomen", "nature", "natuur"),
        "tree" to listOf("boom", "bomen", "forest", "bos", "nature", "natuur", "park"),
        "plant" to listOf("plant", "planten", "nature", "natuur", "garden", "tuin", "flower", "bloem"),
        "flower" to listOf("bloem", "bloemen", "flowers", "plant", "garden", "tuin", "nature", "natuur"),
        "garden" to listOf("tuin", "park", "flowers", "bloemen", "nature", "natuur"),
        "grass" to listOf("gras", "park", "field", "veld", "nature", "natuur", "outdoor", "buiten"),
        "sky" to listOf("lucht", "hemel", "clouds", "wolken", "outdoor", "buiten"),
        "cloud" to listOf("wolk", "wolken", "sky", "lucht"),
        "sunset" to listOf("zonsondergang", "sunrise", "zonsopgang", "sky", "lucht", "evening", "avond"),
        "sunrise" to listOf("zonsopgang", "sunset", "zonsondergang", "morning", "ochtend", "sky", "lucht"),
        "snow" to listOf("sneeuw", "winter", "ice", "ijs", "cold", "koud", "ski"),
        "car" to listOf("auto", "autos", "cars", "vehicle", "voertuig", "road", "weg"),
        "vehicle" to listOf("voertuig", "car", "auto", "transport", "vervoer"),
        "bicycle" to listOf("fiets", "fietsen", "bike", "cycling", "wielrennen"),
        "motorcycle" to listOf("motor", "motorfiets", "bike", "vehicle", "voertuig"),
        "airplane" to listOf("vliegtuig", "plane", "flight", "vlucht", "airport", "luchthaven", "travel", "reizen"),
        "train" to listOf("trein", "station", "rail", "travel", "reizen"),
        "bus" to listOf("bus", "transport", "vervoer"),
        "building" to listOf("gebouw", "gebouwen", "architecture", "architectuur", "city", "stad", "house", "huis"),
        "house" to listOf("huis", "home", "woning", "building", "gebouw"),
        "skyscraper" to listOf("wolkenkrabber", "city", "stad", "building", "gebouw", "skyline"),
        "bridge" to listOf("brug", "architecture", "city", "stad"),
        "city" to listOf("stad", "urban", "street", "straat", "building", "gebouw"),
        "street" to listOf("straat", "road", "weg", "city", "stad"),
        "sport" to listOf("sports", "sporten", "fitness", "gym", "football", "voetbal", "running", "hardlopen"),
        "soccer" to listOf("voetbal", "football", "sport", "ball", "bal"),
        "basketball" to listOf("basketbal", "sport", "ball"),
        "music" to listOf("muziek", "concert", "band", "festival", "instrument"),
        "concert" to listOf("concert", "festival", "music", "muziek", "stage", "podium"),
        "guitar" to listOf("gitaar", "music", "muziek", "instrument"),
        "piano" to listOf("piano", "music", "muziek", "instrument"),
        "wedding" to listOf("bruiloft", "trouwen", "huwelijk", "celebration", "feest", "party"),
        "party" to listOf("feest", "feestje", "celebration", "birthday", "verjaardag"),
        "birthday" to listOf("verjaardag", "feest", "party", "cake", "taart"),
        "baby" to listOf("baby", "kind", "child", "kid", "toddler", "familie", "family"),
        "computer" to listOf("laptop", "pc", "desktop", "screen", "scherm", "tech", "work", "werk"),
        "laptop" to listOf("computer", "pc", "work", "werk", "tech"),
        "mobile phone" to listOf("phone", "telefoon", "smartphone", "tech"),
        "camera" to listOf("camera", "fotocamera", "lens", "tech"),
        "book" to listOf("boek", "boeken", "reading", "lezen", "library", "bibliotheek"),
        "document" to listOf("document", "paper", "papier", "receipt", "bon", "scan", "text", "tekst"),
        "paper" to listOf("papier", "document", "receipt", "bon", "note", "notitie"),
        "watch" to listOf("horloge", "clock", "klok"),
        "shoe" to listOf("schoen", "schoenen", "sneaker", "sneakers", "shoes"),
        "furniture" to listOf("meubel", "meubels", "chair", "stoel", "table", "tafel", "sofa", "bank"),
        "table" to listOf("tafel", "desk", "bureau", "furniture"),
        "chair" to listOf("stoel", "furniture")
    )

    private val LABEL_TO_CATEGORY: Map<String, ObjectCategory> = mapOf(
        "dog" to ObjectCategory.PETS,
        "puppy" to ObjectCategory.PETS,
        "cat" to ObjectCategory.PETS,
        "kitten" to ObjectCategory.PETS,
        "pet" to ObjectCategory.PETS,
        "bird" to ObjectCategory.PETS,
        "horse" to ObjectCategory.PETS,
        "fish" to ObjectCategory.PETS,
        "animal" to ObjectCategory.PETS,
        "wildlife" to ObjectCategory.PETS,
        "food" to ObjectCategory.FOOD,
        "cuisine" to ObjectCategory.FOOD,
        "meal" to ObjectCategory.FOOD,
        "dish" to ObjectCategory.FOOD,
        "pizza" to ObjectCategory.FOOD,
        "cake" to ObjectCategory.FOOD,
        "dessert" to ObjectCategory.FOOD,
        "coffee" to ObjectCategory.FOOD,
        "drink" to ObjectCategory.FOOD,
        "beverage" to ObjectCategory.FOOD,
        "fruit" to ObjectCategory.FOOD,
        "vegetable" to ObjectCategory.FOOD,
        "baked goods" to ObjectCategory.FOOD,
        "fast food" to ObjectCategory.FOOD,
        "restaurant" to ObjectCategory.FOOD,
        "beach" to ObjectCategory.BEACH,
        "sea" to ObjectCategory.BEACH,
        "ocean" to ObjectCategory.BEACH,
        "coast" to ObjectCategory.BEACH,
        "lake" to ObjectCategory.BEACH,
        "river" to ObjectCategory.BEACH,
        "pool" to ObjectCategory.BEACH,
        "water" to ObjectCategory.BEACH,
        "boat" to ObjectCategory.BEACH,
        "mountain" to ObjectCategory.NATURE,
        "forest" to ObjectCategory.NATURE,
        "tree" to ObjectCategory.NATURE,
        "grass" to ObjectCategory.NATURE,
        "park" to ObjectCategory.NATURE,
        "landscape" to ObjectCategory.NATURE,
        "snow" to ObjectCategory.NATURE,
        "nature" to ObjectCategory.NATURE,
        "flower" to ObjectCategory.FLOWERS,
        "plant" to ObjectCategory.FLOWERS,
        "garden" to ObjectCategory.FLOWERS,
        "petal" to ObjectCategory.FLOWERS,
        "bouquet" to ObjectCategory.FLOWERS,
        "sky" to ObjectCategory.SKY,
        "cloud" to ObjectCategory.SKY,
        "sunset" to ObjectCategory.SKY,
        "sunrise" to ObjectCategory.SKY,
        "night" to ObjectCategory.SKY,
        "car" to ObjectCategory.VEHICLES,
        "vehicle" to ObjectCategory.VEHICLES,
        "bicycle" to ObjectCategory.VEHICLES,
        "motorcycle" to ObjectCategory.VEHICLES,
        "bus" to ObjectCategory.VEHICLES,
        "truck" to ObjectCategory.VEHICLES,
        "train" to ObjectCategory.TRAVEL,
        "airplane" to ObjectCategory.TRAVEL,
        "airport" to ObjectCategory.TRAVEL,
        "luggage" to ObjectCategory.TRAVEL,
        "building" to ObjectCategory.ARCHITECTURE,
        "house" to ObjectCategory.ARCHITECTURE,
        "skyscraper" to ObjectCategory.ARCHITECTURE,
        "bridge" to ObjectCategory.ARCHITECTURE,
        "city" to ObjectCategory.ARCHITECTURE,
        "street" to ObjectCategory.ARCHITECTURE,
        "architecture" to ObjectCategory.ARCHITECTURE,
        "tower" to ObjectCategory.ARCHITECTURE,
        "sport" to ObjectCategory.SPORTS,
        "soccer" to ObjectCategory.SPORTS,
        "basketball" to ObjectCategory.SPORTS,
        "tennis" to ObjectCategory.SPORTS,
        "stadium" to ObjectCategory.SPORTS,
        "ball" to ObjectCategory.SPORTS,
        "music" to ObjectCategory.MUSIC,
        "concert" to ObjectCategory.MUSIC,
        "guitar" to ObjectCategory.MUSIC,
        "piano" to ObjectCategory.MUSIC,
        "musical instrument" to ObjectCategory.MUSIC,
        "wedding" to ObjectCategory.CELEBRATION,
        "party" to ObjectCategory.CELEBRATION,
        "birthday" to ObjectCategory.CELEBRATION,
        "fireworks" to ObjectCategory.CELEBRATION,
        "document" to ObjectCategory.DOCUMENTS,
        "paper" to ObjectCategory.DOCUMENTS,
        "book" to ObjectCategory.DOCUMENTS,
        "handwriting" to ObjectCategory.DOCUMENTS,
        "computer" to ObjectCategory.TECH,
        "laptop" to ObjectCategory.TECH,
        "mobile phone" to ObjectCategory.TECH,
        "camera" to ObjectCategory.TECH,
        "electronics" to ObjectCategory.TECH,
        "keyboard" to ObjectCategory.TECH
    )

    fun filterUsefulLabels(rawLabels: List<Pair<String, Float>>, minConfidence: Float = 0.62f): List<String> {
        val cleaned = LinkedHashSet<String>()
        for ((text, conf) in rawLabels.sortedByDescending { it.second }) {
            if (conf < minConfidence) continue
            val trimmed = text.trim()
            if (trimmed.length < 2) continue
            val lower = trimmed.lowercase(Locale.US)
            if (lower in NOISY_GENERIC_LABELS) continue
            cleaned.add(trimmed)
            if (cleaned.size >= 8) break
        }
        return cleaned.toList()
    }

    fun categoryForLabel(label: String): ObjectCategory? {
        val lower = label.trim().lowercase(Locale.US)
        LABEL_TO_CATEGORY[lower]?.let { return it }
        for ((key, cat) in LABEL_TO_CATEGORY) {
            if (lower.contains(key)) return cat
        }
        return null
    }

    /**
     * Builds a normalized lowercase token string containing every detected label plus English
     * and Dutch synonyms and category keywords for instant O(1) search matching.
     */
    fun buildSearchTokens(labels: List<String>): String {
        if (labels.isEmpty()) return ""
        val tokens = LinkedHashSet<String>()
        for (raw in labels) {
            val lower = raw.trim().lowercase(Locale.US)
            if (lower.isBlank()) continue
            tokens.add(lower)
            SYNONYM_MAP[lower]?.forEach { tokens.add(it) }
            // Also check sub-word synonym matches (e.g., "Golden retriever" or "Sports car")
            for (word in lower.split(' ')) {
                if (word.length >= 3) {
                    tokens.add(word)
                    SYNONYM_MAP[word]?.forEach { tokens.add(it) }
                }
            }
            categoryForLabel(raw)?.let { cat ->
                tokens.add(cat.displayName.lowercase(Locale.US))
                tokens.add(cat.key)
            }
        }
        return tokens.joinToString(" ")
    }
}

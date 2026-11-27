package com.luminagallery.drive.data.repository.ai

import java.util.Locale

/**
 * Unified object/scene vocabulary combining:
 *  - ML Kit Image Labeling (high-level scenes: Beach, Sunset, Wedding, Mountain, Food, Architecture)
 *  - EfficientNet-Lite2 ImageNet-1000 (specific animals & objects: Parrot/Macaw/Cockatoo/African Grey,
 *    120 dog breeds, cats, owls, flamingos, pizza, espresso, cars, guitars, laptops, sunglasses…)
 *  - Bilingual English + Dutch search synonym expansion (`parrot` / `papegaai`, `dog` / `hond`, etc.)
 *  - Strict non-human face-crop veto so parrots, pets, statues, plush toys, and pareidolia textures
 *    NEVER appear in the People section while remaining fully searchable as objects/animals!
 */
object SmartObjectVocabulary {

    /** High-level human-friendly categories surfaced with curated icons in Search & Details. */
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

    data class MergedObjectResult(
        val displayLabels: List<String>,
        val searchTokens: String
    )

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
        "t-shirt", "jersey", "textile", "fabric", "activewear", "footwear", "eyewear",
        "web site", "website", "envelope", "menu", "packet", "carton", "plastic bag", "wool",
        "velvet", "shower curtain", "window shade", "window screen", "screen", "monitor"
    )

    /** Keywords that definitively identify a non-human crop (animals, birds, toys, statues, art, objects). */
    private val NON_HUMAN_VETO_KEYWORDS: List<String> = listOf(
        // Birds & Parrots
        "parrot", "macaw", "cockatoo", "lorikeet", "african grey", "parakeet", "budgie",
        "bird", "beak", "feather", "owl", "eagle", "hawk", "falcon", "toucan", "flamingo",
        "peacock", "penguin", "swan", "duck", "goose", "hen", "rooster", "cock", "pigeon",
        "seagull", "pelican", "ostrich", "robin", "jay", "magpie", "vulture", "heron", "stork",
        "hummingbird", "albatross", "chickadee", "finch", "crane", "quail", "partridge",
        // Pets & Mammals
        "dog", "puppy", "retriever", "terrier", "spaniel", "poodle", "bulldog", "beagle",
        "chihuahua", "husky", "corgi", "shepherd", "collie", "hound", "pug", "boxer",
        "rottweiler", "dachshund", "dalmatian", "pomeranian", "malamute", "samoyed", "mastiff",
        "doberman", "schnauzer", "shih-tzu", "maltese", "whippet", "basenji", "chow", "dingo",
        "cat", "kitten", "tabby", "persian cat", "siamese cat", "egyptian cat", "feline", "canine",
        "pet", "animal", "wildlife", "mammal", "carnivore", "snout", "whiskers", "fur", "paw",
        "lion", "tiger", "leopard", "cheetah", "jaguar", "cougar", "lynx", "wolf", "fox",
        "bear", "panda", "koala", "kangaroo", "elephant", "zebra", "giraffe", "hippo", "rhino",
        "horse", "pony", "donkey", "cow", "ox", "bison", "sheep", "ram", "goat", "pig", "hog",
        "deer", "elk", "moose", "monkey", "ape", "gorilla", "chimpanzee", "orangutan", "baboon",
        "macaque", "gibbon", "lemur", "titi", "capuchin", "marmoset", "guenon", "patas", "langur",
        "colobus", "proboscis", "siamang", "indri", "madagascar cat", "howler", "primate",
        "squirrel", "rabbit", "hare", "hamster", "guinea pig",
        "mouse", "rat", "hedgehog", "otter", "ferret", "weasel", "badger", "skunk", "raccoon",
        "sloth", "bat", "camel", "llama",
        // Reptiles, Fish, Insects
        "reptile", "amphibian", "turtle", "tortoise", "frog", "toad", "lizard", "iguana",
        "chameleon", "gecko", "crocodile", "alligator", "snake", "cobra", "viper", "python",
        "fish", "shark", "whale", "dolphin", "seal", "sea lion", "goldfish", "jellyfish",
        "starfish", "crab", "lobster", "octopus", "insect", "butterfly", "moth", "bee", "wasp",
        "ant", "beetle", "ladybug", "dragonfly", "spider", "scorpion", "snail", "slug",
        // Toys, Statues, Cartoons & Masks
        "teddy", "plush", "stuffed", "toy", "doll", "puppet", "figurine", "statue", "sculpture",
        "bust", "totem", "mannequin", "mask", "gasmask", "ski mask", "comic", "cartoon", "anime",
        "illustration", "drawing", "painting", "sketch", "graffiti", "poster", "jack-o'-lantern"
    )

    /** Strong positive cues confirming a crop contains a real human face/portrait. */
    private val HUMAN_FACE_CONFIRM_KEYWORDS: Set<String> = setOf(
        "person", "human", "face", "smile", "hair", "forehead", "cheek", "chin", "eyebrow",
        "eyelash", "jaw", "beard", "mustache", "lip", "mouth", "nose", "skin", "flesh",
        "portrait", "selfie", "man", "woman", "boy", "girl", "child", "baby", "adult", "youth",
        "people", "eyewear", "glasses", "sunglasses", "sunglass", "wig", "neck", "collar",
        "hairstyle", "head", "ear", "eye", "lipstick", "suit", "bow tie", "windsor tie",
        "sombrero", "cowboy hat", "sunhat", "beanie", "bonnet", "groom", "bride"
    )

    /**
     * Checks ML Kit + EfficientNet-Lite2 classifications run directly on a `160x160` candidate
     * face crop and vetoes parrots, pets, animals, toys, statues, cartoons, and pareidolia noise.
     *
     * Returns `true` if the crop is non-human and MUST be excluded from People.
     */
    fun isNonHumanFaceCrop(
        cropMlKitLabels: List<Pair<String, Float>>,
        cropImageNetLabels: List<Pair<String, Float>>
    ): Boolean {
        var maxAnimalOrObjectVeto = 0f
        var maxHumanConfirm = 0f

        for ((raw, conf) in cropImageNetLabels) {
            val lower = raw.trim().lowercase(Locale.US)
            if (matchesAnyKeyword(lower, NON_HUMAN_VETO_KEYWORDS)) {
                if (conf > maxAnimalOrObjectVeto) maxAnimalOrObjectVeto = conf
            }
            if (lower in HUMAN_FACE_CONFIRM_KEYWORDS ||
                lower.contains("sunglass") ||
                lower.contains("lipstick") ||
                lower.contains("wig") ||
                lower.contains("groom") ||
                lower.contains("bride") ||
                lower.contains("bow tie") ||
                lower.contains("windsor tie") ||
                lower.contains("neck brace") ||
                lower.contains("jersey") ||
                lower.contains("sweatshirt") ||
                lower.contains("cardigan")
            ) {
                if (conf > maxHumanConfirm) maxHumanConfirm = conf
            }
        }

        var mlKitHasAnyHuman = false
        for ((raw, conf) in cropMlKitLabels) {
            val lower = raw.trim().lowercase(Locale.US)
            if (matchesAnyKeyword(lower, NON_HUMAN_VETO_KEYWORDS) && conf >= 0.45f) {
                if (conf > maxAnimalOrObjectVeto) maxAnimalOrObjectVeto = conf
            }
            if (lower in HUMAN_FACE_CONFIRM_KEYWORDS && conf >= 0.48f) {
                mlKitHasAnyHuman = true
                if (conf > maxHumanConfirm) maxHumanConfirm = conf
            }
        }

        // 1. Direct animal / parrot / pet / toy / statue / cartoon veto!
        if (maxAnimalOrObjectVeto >= 0.20f) {
            // Even if ML Kit weakly hallucinated "Eye" or "Face", a clear bird/pet/toy prediction vetoes it
            if (maxAnimalOrObjectVeto >= 0.35f || !mlKitHasAnyHuman || maxAnimalOrObjectVeto >= maxHumanConfirm * 0.55f) {
                return true
            }
        }

        // 2. If BOTH classifiers returned confident results and NEITHER saw any human cue, veto pareidolia noise
        val topImageNetConf = cropImageNetLabels.firstOrNull()?.second ?: 0f
        if (cropMlKitLabels.isNotEmpty() && !mlKitHasAnyHuman && maxHumanConfirm < 0.25f && topImageNetConf >= 0.30f) {
            return true
        }

        return false
    }

    private fun matchesAnyKeyword(lowerText: String, keywords: List<String>): Boolean {
        for (kw in keywords) {
            if (lowerText == kw) return true
            if (kw.length >= 4 && lowerText.contains(kw)) return true
            if (kw.length == 3 && (lowerText.startsWith("$kw ") || lowerText.endsWith(" $kw") || lowerText.contains(" $kw "))) {
                return true
            }
        }
        return false
    }

    private val PARROT_SPECIES = setOf("macaw", "african grey", "sulphur-crested cockatoo", "lorikeet")

    private val DOG_BREED_TOKENS = listOf(
        "retriever", "terrier", "spaniel", "poodle", "bulldog", "beagle", "chihuahua", "husky",
        "corgi", "shepherd", "collie", "hound", "pug", "rottweiler", "dachshund", "dalmatian",
        "pomeranian", "malamute", "samoyed", "mastiff", "doberman", "schnauzer", "shih-tzu",
        "maltese", "whippet", "basenji", "chow", "dingo", "vizsla", "weimaraner", "papillon",
        "borzoi", "kuvasz", "komondor", "keeshond", "kelpie", "schipperke", "groenendael",
        "malinois", "briard", "leonberg", "newfoundland", "great pyrenees", "saint bernard",
        "bernese mountain dog", "appenzeller", "entlebucher", "boxer", "pekinese", "lhasa",
        "blenheim", "affenpinscher", "saluki", "setter", "pointer", "akita", "eskimo dog",
        "great dane", "bull mastiff", "affenpinscher", "brabancon", "dhole", "african hunting dog"
    )

    private val CAT_SPECIES = setOf("tabby", "tiger cat", "persian cat", "siamese cat", "egyptian cat")

    private val BIRD_MAP: Map<String, String> = mapOf(
        "great grey owl" to "Owl",
        "bald eagle" to "Eagle",
        "flamingo" to "Flamingo",
        "peacock" to "Peacock",
        "toucan" to "Toucan",
        "king penguin" to "Penguin",
        "hummingbird" to "Hummingbird",
        "black swan" to "Swan",
        "drake" to "Duck",
        "goose" to "Goose",
        "pelican" to "Pelican",
        "ostrich" to "Ostrich",
        "robin" to "Bird",
        "jay" to "Bird",
        "magpie" to "Bird",
        "goldfinch" to "Bird",
        "indigo bunting" to "Bird",
        "vulture" to "Bird",
        "kite" to "Bird",
        "hen" to "Chicken",
        "cock" to "Rooster",
        "white stork" to "Stork",
        "black stork" to "Stork",
        "albatross" to "Bird",
        "little blue heron" to "Heron",
        "american egret" to "Bird",
        "bittern" to "Bird",
        "crane" to "Bird",
        "quail" to "Bird",
        "partridge" to "Bird",
        "hornbill" to "Bird",
        "bee eater" to "Bird",
        "jacamar" to "Bird",
        "coucal" to "Bird",
        "water ouzel" to "Bird",
        "bulbul" to "Bird",
        "brambling" to "Bird",
        "house finch" to "Bird",
        "junco" to "Bird",
        "chickadee" to "Bird"
    )

    private val SPECIFIC_OBJECT_MAP: Map<String, List<String>> = mapOf(
        // Vehicles
        "sports car" to listOf("Car"),
        "convertible" to listOf("Car"),
        "jeep" to listOf("Car"),
        "minivan" to listOf("Car"),
        "limousine" to listOf("Car"),
        "cab" to listOf("Car"),
        "pickup" to listOf("Car"),
        "beach wagon" to listOf("Car"),
        "racer" to listOf("Car"),
        "model t" to listOf("Car"),
        "mountain bike" to listOf("Bicycle"),
        "bicycle-built-for-two" to listOf("Bicycle"),
        "moped" to listOf("Motorcycle"),
        "motor scooter" to listOf("Motorcycle"),
        "airliner" to listOf("Airplane"),
        "warplane" to listOf("Airplane"),
        "steam locomotive" to listOf("Train"),
        "electric locomotive" to listOf("Train"),
        "bullet train" to listOf("Train"),
        "passenger car" to listOf("Train"),
        "school bus" to listOf("Bus"),
        "trolleybus" to listOf("Bus"),
        "minibus" to listOf("Bus"),
        "sailboat" to listOf("Boat"),
        "catamaran" to listOf("Boat"),
        "trimaran" to listOf("Boat"),
        "speedboat" to listOf("Boat"),
        "yawl" to listOf("Boat"),
        "gondola" to listOf("Boat"),
        "canoe" to listOf("Boat"),
        "liner" to listOf("Boat"),
        "lifeboat" to listOf("Boat"),
        "fireboat" to listOf("Boat"),
        // Food & Drink
        "pizza" to listOf("Pizza", "Food"),
        "cheeseburger" to listOf("Burger", "Food"),
        "hotdog" to listOf("Food"),
        "ice cream" to listOf("Ice Cream", "Dessert"),
        "ice lolly" to listOf("Ice Cream", "Dessert"),
        "espresso" to listOf("Coffee", "Drink"),
        "coffee mug" to listOf("Coffee", "Drink"),
        "cup" to listOf("Coffee", "Drink"),
        "red wine" to listOf("Wine", "Drink"),
        "wine bottle" to listOf("Wine", "Drink"),
        "beer glass" to listOf("Beer", "Drink"),
        "beer bottle" to listOf("Beer", "Drink"),
        "strawberry" to listOf("Fruit", "Food"),
        "banana" to listOf("Fruit", "Food"),
        "orange" to listOf("Fruit", "Food"),
        "lemon" to listOf("Fruit", "Food"),
        "pineapple" to listOf("Fruit", "Food"),
        "pomegranate" to listOf("Fruit", "Food"),
        "granny smith" to listOf("Fruit", "Food"),
        "pretzel" to listOf("Food"),
        "bagel" to listOf("Food"),
        "french loaf" to listOf("Food"),
        "burrito" to listOf("Food"),
        "carbonara" to listOf("Food"),
        "guacamole" to listOf("Food"),
        "chocolate sauce" to listOf("Dessert", "Food"),
        "trifle" to listOf("Dessert", "Cake"),
        // Music
        "acoustic guitar" to listOf("Guitar", "Music"),
        "electric guitar" to listOf("Guitar", "Music"),
        "grand piano" to listOf("Piano", "Music"),
        "upright" to listOf("Piano", "Music"),
        "violin" to listOf("Music"),
        "cello" to listOf("Music"),
        "drum" to listOf("Music"),
        "sax" to listOf("Music"),
        "flute" to listOf("Music"),
        // Tech & Everyday objects
        "laptop" to listOf("Laptop"),
        "notebook" to listOf("Laptop"),
        "desktop computer" to listOf("Computer"),
        "cellular telephone" to listOf("Phone"),
        "ipod" to listOf("Phone"),
        "reflex camera" to listOf("Camera"),
        "polaroid camera" to listOf("Camera"),
        "sunglasses" to listOf("Sunglasses"),
        "sunglass" to listOf("Sunglasses"),
        "digital watch" to listOf("Watch"),
        "analog clock" to listOf("Watch"),
        "backpack" to listOf("Backpack"),
        "running shoe" to listOf("Shoes"),
        "umbrella" to listOf("Umbrella"),
        "christmas stocking" to listOf("Christmas"),
        // Nature & Landscapes
        "seashore" to listOf("Beach"),
        "sandbar" to listOf("Beach"),
        "lakeside" to listOf("Lake", "Water"),
        "alp" to listOf("Mountain"),
        "volcano" to listOf("Mountain"),
        "valley" to listOf("Nature"),
        "cliff" to listOf("Nature"),
        "coral reef" to listOf("Ocean", "Water"),
        "geyser" to listOf("Nature"),
        "daisy" to listOf("Flower"),
        "yellow lady's slipper" to listOf("Flower"),
        "rapeseed" to listOf("Flower"),
        // Wildlife
        "african elephant" to listOf("Elephant", "Animal"),
        "indian elephant" to listOf("Elephant", "Animal"),
        "lion" to listOf("Lion", "Animal"),
        "tiger" to listOf("Tiger", "Animal"),
        "cheetah" to listOf("Animal"),
        "leopard" to listOf("Animal"),
        "snow leopard" to listOf("Animal"),
        "zebra" to listOf("Zebra", "Animal"),
        "giant panda" to listOf("Panda", "Animal"),
        "koala" to listOf("Animal"),
        "brown bear" to listOf("Bear", "Animal"),
        "ice bear" to listOf("Bear", "Animal"),
        "american black bear" to listOf("Bear", "Animal"),
        "red fox" to listOf("Fox", "Animal"),
        "grey fox" to listOf("Fox", "Animal"),
        "arctic fox" to listOf("Fox", "Animal"),
        "timber wolf" to listOf("Wolf", "Animal"),
        "white wolf" to listOf("Wolf", "Animal"),
        "fox squirrel" to listOf("Squirrel", "Animal"),
        "wood rabbit" to listOf("Rabbit", "Animal"),
        "cottontail" to listOf("Rabbit", "Animal"),
        "angora" to listOf("Rabbit", "Animal"),
        "hamster" to listOf("Hamster", "Pet"),
        "guinea pig" to listOf("Pet", "Animal"),
        "sorrel" to listOf("Horse", "Animal"),
        "goldfish" to listOf("Fish", "Animal"),
        "great white shark" to listOf("Shark", "Fish"),
        "killer whale" to listOf("Whale", "Ocean"),
        "grey whale" to listOf("Whale", "Ocean"),
        "monarch" to listOf("Butterfly", "Nature"),
        "admiral" to listOf("Butterfly", "Nature"),
        "ringlet" to listOf("Butterfly", "Nature"),
        "sulphur butterfly" to listOf("Butterfly", "Nature"),
        "lycaenid" to listOf("Butterfly", "Nature"),
        "dragonfly" to listOf("Nature"),
        "ladybug" to listOf("Nature"),
        "bee" to listOf("Nature"),
        "loggerhead" to listOf("Turtle", "Animal"),
        "box turtle" to listOf("Turtle", "Animal"),
        "tree frog" to listOf("Frog", "Animal"),
        "green lizard" to listOf("Lizard", "Animal"),
        "african chameleon" to listOf("Lizard", "Animal")
    )

    /**
     * Converts a raw ImageNet-1000 class name (e.g. `"macaw"`, `"African grey"`, `"golden retriever"`,
     * `"sports car"`) into clean human-friendly display tags while retaining the original species/object
     * name for deep search matching.
     */
    fun normalizeImageNetLabel(rawLabel: String): List<String> {
        val lower = rawLabel.trim().lowercase(Locale.US)
        if (lower.isBlank() || lower in NOISY_GENERIC_LABELS) return emptyList()
        if (lower in PARROT_SPECIES) return listOf("Parrot", "Bird")
        if (lower in CAT_SPECIES) return listOf("Cat")
        BIRD_MAP[lower]?.let { bird ->
            return if (bird == "Bird") listOf("Bird") else listOf(bird, "Bird")
        }
        SPECIFIC_OBJECT_MAP[lower]?.let { return it }
        if (DOG_BREED_TOKENS.any { lower.contains(it) }) return listOf("Dog")
        return emptyList()
    }

    /**
     * Merges high-level ML Kit scene labels with specific EfficientNet-Lite2 ImageNet-1000 labels
     * into clean display tags + bilingual (EN + NL) search tokens.
     */
    fun mergeSceneAndObjectLabels(
        mlKitLabels: List<Pair<String, Float>>,
        imageNetLabels: List<Pair<String, Float>>,
        minMlKitConf: Float = 0.62f,
        minImageNetTopConf: Float = 0.26f,
        minImageNetSecondaryConf: Float = 0.36f,
        animal: AnimalTaxonomy.Analysis? = null,
        animalSpecificLabel: String? = null
    ): MergedObjectResult {
        val display = LinkedHashSet<String>()
        val rawSearchExtras = LinkedHashSet<String>()

        // 0. Animals from whole-softmax group masses (never from a single look-alike breed name).
        if (animal != null) {
            AnimalTaxonomy.displayLabels(animal).forEach { display.add(it) }
            if (!animalSpecificLabel.isNullOrBlank() && AnimalTaxonomy.confidentGroup(animal) != null) {
                rawSearchExtras.add(animalSpecificLabel.trim().lowercase(Locale.US))
            }
        }

        // 1. Specific ImageNet-1000 objects/animals FIRST so specific labels like "Parrot", "Pizza", "Owl" lead!
        val sortedNet = imageNetLabels.sortedByDescending { it.second }
        sortedNet.forEachIndexed { idx, (raw, conf) ->
            val threshold = if (idx == 0) minImageNetTopConf else minImageNetSecondaryConf
            if (conf < threshold) return@forEachIndexed
            val normalized = normalizeImageNetLabel(raw)
            // When group masses are available, animal tags only come from step 0.
            if (animal != null && normalized.any { it in ANIMAL_DISPLAY_TAGS }) return@forEachIndexed
            if (normalized.isNotEmpty()) {
                normalized.forEach { display.add(it) }
                rawSearchExtras.add(raw.trim().lowercase(Locale.US))
            }
        }

        // 2. High-level ML Kit scene/context labels (Beach, Sunset, Mountain, Food, Architecture, Wedding…)
        for (label in filterUsefulLabels(mlKitLabels, minConfidence = minMlKitConf)) {
            if (display.size >= 8) break
            if (animal != null && !isMlKitAnimalLabelConsistent(label, animal)) continue
            display.add(label)
        }

        val displayList = display.take(8)
        val baseTokens = buildSearchTokens(displayList)
        val combinedTokens = buildString {
            append(baseTokens)
            for (extra in rawSearchExtras) {
                if (extra.isNotBlank() && !baseTokens.contains(extra)) {
                    if (isNotEmpty()) append(' ')
                    append(extra)
                }
            }
        }
        return MergedObjectResult(
            displayLabels = displayList,
            searchTokens = combinedTokens
        )
    }

    private val ANIMAL_DISPLAY_TAGS: Set<String> = buildSet {
        AnimalTaxonomy.Group.entries.forEach { add(it.displayLabel); addAll(it.extraLabels) }
        addAll(
            listOf(
                "Parrot", "Pet", "Animal", "Owl", "Eagle", "Flamingo", "Peacock", "Toucan", "Penguin",
                "Hummingbird", "Swan", "Duck", "Goose", "Pelican", "Ostrich", "Chicken", "Rooster", "Stork",
                "Heron", "Lion", "Tiger", "Elephant", "Zebra", "Panda", "Bear", "Fox", "Wolf", "Squirrel",
                "Rabbit", "Hamster", "Horse", "Shark", "Whale", "Butterfly", "Turtle", "Frog", "Lizard"
            )
        )
        remove("Nature")
    }

    /**
     * Cross-checks an ML Kit scene label against the ImageNet group masses: ML Kit is great at
     * scenes but happily calls a monkey a "Dog" or "Bird". Animal labels survive only when the
     * full-softmax evidence agrees.
     */
    internal fun isMlKitAnimalLabelConsistent(label: String, animal: AnimalTaxonomy.Analysis): Boolean {
        val lower = label.trim().lowercase(Locale.US)
        val group = AnimalTaxonomy.groupForMlKitLabel(lower)
        if (group != null) {
            val confident = AnimalTaxonomy.confidentGroup(animal, minMass = 0.30f, minLeadRatio = 1.5f)
            if (confident != null) return confident == group
            return animal.massOf(group) >= 0.25f && animal.massOf(group) >= animal.dominantMass * 0.75f
        }
        if (lower in AnimalTaxonomy.GENERIC_ANIMAL_LABELS) return animal.animalMass >= 0.35f
        return true
    }

    /**
     * Word-aware search matching for label tokens: exact word, or word prefix for queries of 4+
     * characters ("hond" -> "honden"). Prevents "dog" matching "hotdog" or "cat" matching "catamaran".
     */
    fun tokensMatch(haystack: String?, query: String): Boolean {
        if (haystack.isNullOrBlank()) return false
        val q = query.trim().lowercase(Locale.US)
        if (q.isEmpty()) return false
        val lower = haystack.lowercase(Locale.US)
        if (q.contains(' ')) {
            return (" " + lower.replace(',', ' ') + " ").contains(" $q")
        }
        var start = -1
        for (i in 0..lower.length) {
            val inWord = i < lower.length && (lower[i].isLetterOrDigit() || lower[i] == '-' || lower[i] == '\'')
            if (inWord) {
                if (start < 0) start = i
            } else if (start >= 0) {
                val word = lower.substring(start, i)
                if (word == q || (q.length >= 4 && word.startsWith(q))) return true
                start = -1
            }
        }
        return false
    }

    private val SYNONYM_MAP: Map<String, List<String>> = mapOf(
        "monkey" to listOf("monkeys", "aap", "apen", "aapje", "primate", "primaat", "ape", "animal", "dier", "zoo", "dierentuin", "wildlife"),
        "reptile" to listOf("reptiel", "reptielen", "animal", "dier"),
        "insect" to listOf("insecten", "bug", "beestje", "nature", "natuur"),
        "rodent" to listOf("knaagdier", "knaagdieren", "animal", "dier"),
        "wildcat" to listOf("roofkat", "lion", "leeuw", "tiger", "tijger", "leopard", "luipaard", "animal", "dier", "safari", "zoo", "dierentuin"),
        "sealife" to listOf("zeedier", "ocean", "zee", "animal", "dier"),
        "parrot" to listOf("parrots", "papegaai", "papegaaien", "macaw", "ara", "cockatoo", "kaketoe", "lorikeet", "parakeet", "parkiet", "bird", "vogel", "pet", "huisdier", "animal", "dier"),
        "owl" to listOf("uil", "uilen", "bird", "vogel", "animal", "dier", "wildlife"),
        "eagle" to listOf("arend", "adelaar", "bird", "vogel", "animal", "dier", "wildlife"),
        "flamingo" to listOf("flamingo", "bird", "vogel", "pink", "animal", "dier"),
        "penguin" to listOf("pinguin", "pinguïn", "bird", "vogel", "animal", "dier"),
        "swan" to listOf("zwaan", "zwanen", "bird", "vogel", "water", "animal", "dier"),
        "duck" to listOf("eend", "eenden", "bird", "vogel", "water", "animal", "dier"),
        "peacock" to listOf("pauw", "bird", "vogel", "animal", "dier"),
        "toucan" to listOf("toekan", "bird", "vogel", "tropical", "animal", "dier"),
        "hummingbird" to listOf("kolibrie", "bird", "vogel", "animal", "dier"),
        "chicken" to listOf("kip", "kippen", "bird", "vogel", "animal", "dier"),
        "rooster" to listOf("haan", "kip", "bird", "vogel", "animal", "dier"),
        "dog" to listOf("dogs", "hond", "honden", "puppy", "pup", "pet", "huisdier", "animal", "dier"),
        "puppy" to listOf("dog", "hond", "puppy", "pet", "huisdier", "animal", "dier"),
        "cat" to listOf("cats", "kat", "katten", "poes", "kitten", "pet", "huisdier", "animal", "dier"),
        "pet" to listOf("huisdier", "huisdieren", "animal", "dier", "dieren"),
        "bird" to listOf("vogel", "vogels", "animal", "dier", "wildlife"),
        "horse" to listOf("paard", "paarden", "animal", "dier"),
        "fish" to listOf("vis", "vissen", "aquarium", "animal", "dier"),
        "elephant" to listOf("olifant", "olifanten", "animal", "dier", "safari", "zoo", "dierentuin"),
        "lion" to listOf("leeuw", "animal", "dier", "safari", "zoo", "dierentuin"),
        "tiger" to listOf("tijger", "animal", "dier", "safari", "zoo", "dierentuin"),
        "zebra" to listOf("zebra", "animal", "dier", "safari", "zoo", "dierentuin"),
        "panda" to listOf("panda", "bear", "beer", "animal", "dier", "zoo", "dierentuin"),
        "bear" to listOf("beer", "beren", "animal", "dier", "wildlife"),
        "fox" to listOf("vos", "vossen", "animal", "dier", "wildlife"),
        "wolf" to listOf("wolf", "wolven", "animal", "dier", "wildlife"),
        "squirrel" to listOf("eekhoorn", "animal", "dier", "nature", "natuur"),
        "rabbit" to listOf("konijn", "konijnen", "bunny", "pet", "huisdier", "animal", "dier"),
        "butterfly" to listOf("vlinder", "vlinders", "insect", "nature", "natuur"),
        "turtle" to listOf("schildpad", "animal", "dier", "reptile"),
        "frog" to listOf("kikker", "animal", "dier", "nature"),
        "lizard" to listOf("hagedis", "gekko", "kameleon", "reptile", "animal", "dier"),
        "whale" to listOf("walvis", "ocean", "zee", "animal", "dier"),
        "shark" to listOf("haai", "fish", "vis", "ocean", "zee"),
        "animal" to listOf("dier", "dieren", "wildlife", "natuur"),
        "food" to listOf("eten", "meal", "maaltijd", "dish", "gerecht", "dinner", "diner", "lunch", "restaurant"),
        "cuisine" to listOf("food", "eten", "meal", "maaltijd", "dish", "gerecht", "restaurant"),
        "meal" to listOf("food", "eten", "maaltijd", "dinner", "lunch"),
        "pizza" to listOf("food", "eten", "italian", "pizza"),
        "burger" to listOf("hamburger", "cheeseburger", "food", "eten", "fast food"),
        "ice cream" to listOf("ijs", "ijsje", "gelato", "dessert", "toetje", "food", "eten"),
        "cake" to listOf("taart", "gebak", "birthday", "verjaardag", "dessert", "toetje", "food", "eten"),
        "dessert" to listOf("toetje", "gebak", "taart", "sweet", "zoet", "food", "eten"),
        "coffee" to listOf("koffie", "espresso", "latte", "cappuccino", "cafe", "drink", "drankje"),
        "wine" to listOf("wijn", "drink", "drankje", "dinner"),
        "beer" to listOf("bier", "biertje", "drink", "drankje"),
        "drink" to listOf("drank", "drankje", "beverage", "cocktail", "bier", "beer", "wine", "wijn"),
        "fruit" to listOf("fruit", "appel", "banaan", "aardbei", "food", "eten"),
        "baked goods" to listOf("brood", "bread", "bakery", "bakker", "gebak", "food", "eten"),
        "beach" to listOf("strand", "sea", "zee", "ocean", "oceaan", "sand", "zand", "coast", "kust", "vacation", "vakantie"),
        "sea" to listOf("zee", "ocean", "oceaan", "beach", "strand", "water"),
        "ocean" to listOf("oceaan", "zee", "sea", "beach", "strand", "water"),
        "lake" to listOf("meer", "water", "nature", "natuur"),
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
        "motorcycle" to listOf("motor", "motorfiets", "scooter", "bike", "vehicle", "voertuig"),
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
        "christmas" to listOf("kerst", "kerstmis", "holiday", "feest", "winter"),
        "baby" to listOf("baby", "kind", "child", "kid", "toddler", "familie", "family"),
        "computer" to listOf("laptop", "pc", "desktop", "screen", "scherm", "tech", "work", "werk"),
        "laptop" to listOf("computer", "pc", "work", "werk", "tech"),
        "phone" to listOf("telefoon", "smartphone", "mobile", "tech"),
        "mobile phone" to listOf("phone", "telefoon", "smartphone", "tech"),
        "camera" to listOf("camera", "fotocamera", "lens", "tech"),
        "sunglasses" to listOf("zonnebril", "glasses", "bril", "summer", "zomer"),
        "backpack" to listOf("rugzak", "tas", "bag", "travel", "reizen"),
        "umbrella" to listOf("paraplu", "rain", "regen"),
        "book" to listOf("boek", "boeken", "reading", "lezen", "library", "bibliotheek"),
        "document" to listOf("document", "paper", "papier", "receipt", "bon", "scan", "text", "tekst"),
        "paper" to listOf("papier", "document", "receipt", "bon", "note", "notitie"),
        "watch" to listOf("horloge", "clock", "klok"),
        "shoes" to listOf("schoen", "schoenen", "sneaker", "sneakers", "shoe"),
        "shoe" to listOf("schoen", "schoenen", "sneaker", "sneakers", "shoes"),
        "furniture" to listOf("meubel", "meubels", "chair", "stoel", "table", "tafel", "sofa", "bank"),
        "table" to listOf("tafel", "desk", "bureau", "furniture"),
        "chair" to listOf("stoel", "furniture")
    )

    private val LABEL_TO_CATEGORY: Map<String, ObjectCategory> = mapOf(
        "monkey" to ObjectCategory.PETS,
        "reptile" to ObjectCategory.PETS,
        "rodent" to ObjectCategory.PETS,
        "wildcat" to ObjectCategory.PETS,
        "sealife" to ObjectCategory.PETS,
        "insect" to ObjectCategory.NATURE,
        "parrot" to ObjectCategory.PETS,
        "owl" to ObjectCategory.PETS,
        "eagle" to ObjectCategory.PETS,
        "flamingo" to ObjectCategory.PETS,
        "penguin" to ObjectCategory.PETS,
        "swan" to ObjectCategory.PETS,
        "duck" to ObjectCategory.PETS,
        "peacock" to ObjectCategory.PETS,
        "toucan" to ObjectCategory.PETS,
        "hummingbird" to ObjectCategory.PETS,
        "chicken" to ObjectCategory.PETS,
        "rooster" to ObjectCategory.PETS,
        "dog" to ObjectCategory.PETS,
        "puppy" to ObjectCategory.PETS,
        "cat" to ObjectCategory.PETS,
        "kitten" to ObjectCategory.PETS,
        "pet" to ObjectCategory.PETS,
        "bird" to ObjectCategory.PETS,
        "horse" to ObjectCategory.PETS,
        "fish" to ObjectCategory.PETS,
        "elephant" to ObjectCategory.PETS,
        "lion" to ObjectCategory.PETS,
        "tiger" to ObjectCategory.PETS,
        "zebra" to ObjectCategory.PETS,
        "panda" to ObjectCategory.PETS,
        "bear" to ObjectCategory.PETS,
        "fox" to ObjectCategory.PETS,
        "wolf" to ObjectCategory.PETS,
        "squirrel" to ObjectCategory.PETS,
        "rabbit" to ObjectCategory.PETS,
        "hamster" to ObjectCategory.PETS,
        "turtle" to ObjectCategory.PETS,
        "frog" to ObjectCategory.PETS,
        "lizard" to ObjectCategory.PETS,
        "whale" to ObjectCategory.PETS,
        "shark" to ObjectCategory.PETS,
        "animal" to ObjectCategory.PETS,
        "wildlife" to ObjectCategory.PETS,
        "food" to ObjectCategory.FOOD,
        "cuisine" to ObjectCategory.FOOD,
        "meal" to ObjectCategory.FOOD,
        "dish" to ObjectCategory.FOOD,
        "pizza" to ObjectCategory.FOOD,
        "burger" to ObjectCategory.FOOD,
        "ice cream" to ObjectCategory.FOOD,
        "cake" to ObjectCategory.FOOD,
        "dessert" to ObjectCategory.FOOD,
        "coffee" to ObjectCategory.FOOD,
        "wine" to ObjectCategory.FOOD,
        "beer" to ObjectCategory.FOOD,
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
        "butterfly" to ObjectCategory.NATURE,
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
        "backpack" to ObjectCategory.TRAVEL,
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
        "christmas" to ObjectCategory.CELEBRATION,
        "fireworks" to ObjectCategory.CELEBRATION,
        "document" to ObjectCategory.DOCUMENTS,
        "paper" to ObjectCategory.DOCUMENTS,
        "book" to ObjectCategory.DOCUMENTS,
        "handwriting" to ObjectCategory.DOCUMENTS,
        "computer" to ObjectCategory.TECH,
        "laptop" to ObjectCategory.TECH,
        "phone" to ObjectCategory.TECH,
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
        if (lower.isEmpty()) return null
        LABEL_TO_CATEGORY[lower]?.let { return it }
        for ((key, cat) in LABEL_TO_CATEGORY) {
            if (tokensMatch(lower, key) || (key.length == 3 && tokensMatch(lower, "${key}s"))) {
                return cat
            }
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

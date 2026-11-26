package com.luminagallery.drive.data.repository.ai

/**
 * Probability-mass based animal understanding on top of the ImageNet-1000 softmax.
 *
 * Why: the old logic looked at single top-5 class names. A monkey face spreads its probability
 * over many look-alike classes ("affenpinscher" 0.24, "Brabancon griffon" 0.18, "macaque" 0.15,
 * "titi" 0.11 ...). Any one of those passing a threshold put monkeys under Dogs / Birds and, because
 * no single primate class was confident enough, let them through into People.
 *
 * Here every ImageNet class index belongs to one taxonomic group and we sum the whole softmax per
 * group. A label is only assigned when its group clearly dominates the others, and primates get
 * their own group (so they can be found as "monkey" / "aap" and vetoed from People).
 */
object AnimalTaxonomy {

    enum class Group(val displayLabel: String, val extraLabels: List<String> = emptyList()) {
        PRIMATE("Monkey"),
        DOG("Dog"),
        WILD_CANINE("Wolf", listOf("Animal")),
        CAT("Cat"),
        BIG_CAT("Wildcat", listOf("Animal")),
        BEAR("Bear", listOf("Animal")),
        PANDA("Panda", listOf("Animal")),
        BIRD("Bird"),
        FISH("Fish"),
        REPTILE("Reptile", listOf("Animal")),
        AMPHIBIAN("Frog", listOf("Animal")),
        INSECT("Insect", listOf("Nature")),
        BUTTERFLY("Butterfly", listOf("Nature")),
        SEA_LIFE("Sealife", listOf("Animal")),
        MARINE_MAMMAL("Whale", listOf("Animal")),
        RABBIT("Rabbit", listOf("Animal")),
        RODENT("Rodent", listOf("Animal")),
        HORSE("Horse", listOf("Animal")),
        ZEBRA("Zebra", listOf("Animal")),
        HOOFED("Animal"),
        ELEPHANT("Elephant", listOf("Animal")),
        SMALL_MAMMAL("Animal")
    }

    /** Inclusive ImageNet-1k index ranges (standard synset order) per group. */
    private val RANGES: List<Pair<IntRange, Group>> = listOf(
        0..6 to Group.FISH,
        7..24 to Group.BIRD,
        25..32 to Group.AMPHIBIAN,
        33..68 to Group.REPTILE,
        69..79 to Group.INSECT,
        80..100 to Group.BIRD,
        101..101 to Group.ELEPHANT,
        102..106 to Group.SMALL_MAMMAL,
        107..126 to Group.SEA_LIFE,
        127..146 to Group.BIRD,
        147..150 to Group.MARINE_MAMMAL,
        151..268 to Group.DOG,
        269..280 to Group.WILD_CANINE,
        281..285 to Group.CAT,
        286..293 to Group.BIG_CAT,
        294..297 to Group.BEAR,
        298..299 to Group.SMALL_MAMMAL,
        300..320 to Group.INSECT,
        321..326 to Group.BUTTERFLY,
        327..329 to Group.SEA_LIFE,
        330..332 to Group.RABBIT,
        333..338 to Group.RODENT,
        339..339 to Group.HORSE,
        340..340 to Group.ZEBRA,
        341..355 to Group.HOOFED,
        356..364 to Group.SMALL_MAMMAL,
        365..384 to Group.PRIMATE,
        385..386 to Group.ELEPHANT,
        387..387 to Group.SMALL_MAMMAL,
        388..388 to Group.PANDA,
        389..397 to Group.FISH
    )

    private val GROUP_BY_INDEX: Array<Group?> = arrayOfNulls<Group>(1000).also { table ->
        for ((range, group) in RANGES) for (i in range) table[i] = group
    }

    /** Parrots inside the BIRD group (African grey, macaw, cockatoo, lorikeet). */
    private val PARROT_INDICES = 87..90

    /**
     * ImageNet classes that only ever show up on real people: clothing, accessories, make-up,
     * headwear, masks worn by people. Used as the "human evidence" counterweight on face crops.
     */
    private val HUMAN_CUE_INDICES: IntArray = intArrayOf(
        399, 400, 411, 419, 445, 452, 457, 459, 474, 501, 515, 518, 552, 560, 568, 570, 578,
        584, 585, 608, 610, 614, 617, 629, 638, 639, 643, 652, 655, 667, 678, 691, 697, 735,
        796, 808, 824, 834, 836, 837, 841, 869, 887, 903, 906, 982, 983,
        433 /* bathing cap */, 443 /* bib */, 529 /* diaper */, 793 /* shower cap */
    )

    /** Toys / statues / dolls that produce human-looking "faces". */
    private val FAKE_FACE_INDICES: IntArray = intArrayOf(850 /* teddy */)

    fun groupOf(index: Int): Group? = GROUP_BY_INDEX.getOrNull(index)

    data class Analysis(
        val masses: Map<Group, Float>,
        val animalMass: Float,
        val humanCueMass: Float,
        val fakeFaceMass: Float,
        val dominant: Group?,
        val dominantMass: Float,
        val runnerUpMass: Float,
        val parrotShareOfBirds: Float,
        /** Most likely specific class index inside the dominant group (for search, e.g. "macaque"). */
        val dominantTopIndex: Int
    ) {
        fun massOf(group: Group): Float = masses[group] ?: 0f
        val primateMass: Float get() = massOf(Group.PRIMATE)
    }

    fun analyze(probs: FloatArray): Analysis {
        val masses = HashMap<Group, Float>()
        val bestIndex = HashMap<Group, Int>()
        var animal = 0f
        val limit = minOf(probs.size, GROUP_BY_INDEX.size)
        for (i in 0 until limit) {
            val p = probs[i]
            if (p <= 0f) continue
            val g = GROUP_BY_INDEX[i] ?: continue
            masses[g] = (masses[g] ?: 0f) + p
            animal += p
            val prevBest = bestIndex[g]
            if (prevBest == null || probs[prevBest] < p) bestIndex[g] = i
        }
        var human = 0f
        for (i in HUMAN_CUE_INDICES) if (i < probs.size) human += probs[i]
        var fake = 0f
        for (i in FAKE_FACE_INDICES) if (i < probs.size) fake += probs[i]

        val sorted = masses.entries.sortedByDescending { it.value }
        val top = sorted.getOrNull(0)
        val second = sorted.getOrNull(1)?.value ?: 0f
        val birdMass = masses[Group.BIRD] ?: 0f
        var parrot = 0f
        for (i in PARROT_INDICES) if (i < probs.size) parrot += probs[i]
        return Analysis(
            masses = masses,
            animalMass = animal,
            humanCueMass = human,
            fakeFaceMass = fake,
            dominant = top?.key,
            dominantMass = top?.value ?: 0f,
            runnerUpMass = second,
            parrotShareOfBirds = if (birdMass > 0f) parrot / birdMass else 0f,
            dominantTopIndex = top?.key?.let { bestIndex[it] } ?: -1
        )
    }

    /**
     * The confidently recognised animal group of a whole photo, or null.
     * Requires the group to hold real probability mass AND clearly beat every other animal group,
     * so look-alikes (monkey vs dog vs bird) never get a wrong label.
     */
    fun confidentGroup(a: Analysis, minMass: Float = 0.40f, minLeadRatio: Float = 1.5f): Group? {
        val g = a.dominant ?: return null
        if (a.dominantMass < minMass) return null
        if (a.runnerUpMass > 0f && a.dominantMass < a.runnerUpMass * minLeadRatio) return null
        return g
    }

    /** Display labels for a photo's animal content (empty when nothing is confidently an animal). */
    fun displayLabels(a: Analysis): List<String> {
        val group = confidentGroup(a)
        if (group == null) {
            // Clearly an animal but ambiguous species: only the safe generic tag.
            return if (a.animalMass >= 0.70f) listOf("Animal") else emptyList()
        }
        val out = ArrayList<String>(3)
        if (group == Group.BIRD && a.parrotShareOfBirds >= 0.55f) out.add("Parrot")
        out.add(group.displayLabel)
        group.extraLabels.forEach { if (it !in out) out.add(it) }
        if (group == Group.DOG || group == Group.CAT || group == Group.RABBIT || group == Group.RODENT) out.add("Pet")
        return out
    }

    /**
     * People-section veto for a candidate face crop (plus the whole photo for context).
     * Returns true when the "face" belongs to an animal, toy or statue rather than a person.
     *
     * Optional [mlKitHumanConfidence] (from ML Kit portrait cues like Person/Face/Smile/Forehead/Hair)
     * prevents close-up bare human faces (which have no clothing/accessories for ImageNet-1k's
     * limited human classes) from being falsely rejected by weak stray animal scores.
     */
    fun isNonHumanFace(
        crop: Analysis,
        wholePhoto: Analysis?,
        mlKitHumanConfidence: Float = 0f
    ): Boolean {
        val mlKitBoost = if (mlKitHumanConfidence >= 0.65f) mlKitHumanConfidence * 0.40f else 0f
        val humanEvidence = maxOf(crop.humanCueMass, mlKitBoost)
        // Monkeys / apes are by far the most common false positive for a human face detector.
        if (crop.primateMass >= 0.10f && crop.primateMass > humanEvidence * 0.8f) return true
        // Any clearly animal crop (dogs, cats, birds ... summed over all breeds / species).
        if (crop.animalMass >= 0.30f && crop.animalMass > humanEvidence * 1.5f) return true
        if (crop.fakeFaceMass >= 0.25f && crop.fakeFaceMass > humanEvidence) return true
        if (wholePhoto != null) {
            // A photo that is overwhelmingly a monkey: faces inside it are the monkey's.
            if (wholePhoto.primateMass >= 0.45f && crop.animalMass >= 0.05f && humanEvidence < 0.15f) return true
            // Pet close-up where the crop still leans animal.
            if (wholePhoto.animalMass >= 0.75f && crop.animalMass >= 0.15f && humanEvidence < 0.10f) return true
        }
        return false
    }

    /** ML Kit label text -> taxonomy group, used to cross-check ML Kit's scene labels. */
    fun groupForMlKitLabel(lower: String): Group? = when (lower) {
        "dog", "puppy", "dog breed" -> Group.DOG
        "cat", "kitten" -> Group.CAT
        "bird", "parrot", "duck", "chicken", "owl", "eagle", "penguin", "swan", "goose", "seabird" -> Group.BIRD
        "monkey", "primate", "ape" -> Group.PRIMATE
        "horse" -> Group.HORSE
        "fish" -> Group.FISH
        "butterfly" -> Group.BUTTERFLY
        "insect", "bee", "spider" -> Group.INSECT
        "bear" -> Group.BEAR
        "rabbit" -> Group.RABBIT
        "turtle", "reptile", "lizard", "snake" -> Group.REPTILE
        else -> null
    }

    /** Generic ML Kit animal labels that need *some* animal evidence from ImageNet. */
    val GENERIC_ANIMAL_LABELS: Set<String> = setOf("pet", "animal", "wildlife", "mammal", "zoo", "vertebrate")
}

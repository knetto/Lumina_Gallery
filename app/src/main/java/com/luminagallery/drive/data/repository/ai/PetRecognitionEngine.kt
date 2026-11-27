package com.luminagallery.drive.data.repository.ai

import com.luminagallery.drive.data.local.entity.PersonClusterEntity
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure-Kotlin domestic pet recognition & clustering engine built specifically for grouping
 * unmistakable household **cats** and **dogs** alongside People — while strictly excluding
 * wildlife (lions, tigers, cheetahs, wolves, foxes, monkeys, birds, zoo animals, etc.) and
 * hiding 1-photo stray encounters until a pet appears across multiple photos.
 */
object PetRecognitionEngine {

    /**
     * Pets are ONLY surfaced in People & Search once they appear in at least this many photos
     * (unless explicitly named or pinned by the user), keeping 1-shot street dogs/cats out.
     */
    const val MIN_PET_PHOTOS_TO_SHOW: Int = 2

    /** 118 ImageNet domestic dog breed slots + 42 center-weighted coat/fur appearance slots. */
    const val PET_BREED_SLOTS: Int = 118
    const val PET_COAT_SLOTS: Int = 42
    const val PET_EMBEDDING_DIM: Int = PET_BREED_SLOTS + PET_COAT_SLOTS // 160 (distinct from 192-dim MobileFaceNet)

    /** Strict cosine similarity thresholds for clustering individual dogs & cats. */
    const val PET_SINGLETON_MATCH_THRESHOLD: Float = 0.80f
    const val PET_CLUSTER_MATCH_THRESHOLD: Float = 0.77f
    const val PET_AUTO_CONSOLIDATE_THRESHOLD: Float = 0.84f
    const val PET_SUGGEST_MERGE_MIN_THRESHOLD: Float = 0.68f

    private const val DOG_INDEX_START = 151
    private const val DOG_INDEX_END = 268 // 118 domestic dog breeds
    private const val CAT_INDEX_START = 281
    private const val CAT_INDEX_END = 285 // 5 domestic house-cat classes

    /** Explicit wildlife / zoo ML Kit cues that veto pet grouping (protects wildlife photographers). */
    private val WILDLIFE_VETO_MLKIT_LABELS: Set<String> = setOf(
        "lion", "tiger", "leopard", "cheetah", "jaguar", "cougar", "puma", "panther", "lynx",
        "wildcat", "big cat", "wolf", "fox", "coyote", "hyena", "jackal", "dingo",
        "monkey", "primate", "ape", "gorilla", "chimpanzee", "orangutan", "baboon", "macaque", "lemur",
        "bear", "panda", "elephant", "giraffe", "zebra", "rhino", "rhinoceros", "hippo", "hippopotamus",
        "deer", "elk", "moose", "antelope", "gazelle", "bison", "buffalo", "camel", "kangaroo", "koala",
        "wildlife", "zoo", "safari", "fauna", "wild animal", "bird of prey", "raptor"
    )

    private val DOG_CONFIRM_MLKIT_LABELS: Set<String> = setOf("dog", "puppy", "dog breed", "pet", "hound", "terrier", "retriever")
    private val CAT_CONFIRM_MLKIT_LABELS: Set<String> = setOf("cat", "kitten", "pet", "feline")

    enum class PetSpecies(val entityKind: String, val displayLabel: String) {
        DOG(PersonClusterEntity.KIND_PET_DOG, "Dog"),
        CAT(PersonClusterEntity.KIND_PET_CAT, "Cat")
    }

    data class PetDetection(
        val species: PetSpecies,
        val confidence: Float
    )

    /**
     * Strict domestic-pet gate. Returns `DOG` or `CAT` ONLY when ImageNet-1k overwhelmingly
     * identifies a domestic dog or house cat with zero wild-species ambiguity (lions, cheetahs,
     * wolves, foxes, monkeys, bears, zoo/safari photos are all strictly rejected).
     */
    fun detectDomesticPet(
        probs: FloatArray,
        analysis: AnimalTaxonomy.Analysis,
        mlKitLabels: List<Pair<String, Float>> = emptyList()
    ): PetDetection? {
        if (probs.size <= CAT_INDEX_END) return null
        val dom = analysis.dominant ?: return null
        val species = when (dom) {
            AnimalTaxonomy.Group.DOG -> PetSpecies.DOG
            AnimalTaxonomy.Group.CAT -> PetSpecies.CAT
            else -> return null
        }

        // Veto if ML Kit explicitly detects wildlife / zoo / big cats / wild canines / primates
        val lowerMl = mlKitLabels.map { it.first.trim().lowercase(Locale.US) to it.second }
        if (lowerMl.any { (lbl, conf) -> conf >= 0.48f && lbl in WILDLIFE_VETO_MLKIT_LABELS }) {
            return null
        }

        // Strictly reject ambiguous wild look-alikes using ImageNet taxonomic group masses
        if (analysis.primateMass >= 0.05f) return null
        if (analysis.massOf(AnimalTaxonomy.Group.BEAR) >= 0.05f) return null
        if (analysis.massOf(AnimalTaxonomy.Group.BIRD) >= 0.08f) return null
        when (species) {
            PetSpecies.CAT -> {
                if (analysis.massOf(AnimalTaxonomy.Group.BIG_CAT) >= 0.08f) return null
                if (analysis.massOf(AnimalTaxonomy.Group.WILD_CANINE) >= 0.06f) return null
            }
            PetSpecies.DOG -> {
                if (analysis.massOf(AnimalTaxonomy.Group.WILD_CANINE) >= 0.08f) return null
                if (analysis.massOf(AnimalTaxonomy.Group.BIG_CAT) >= 0.06f) return null
            }
        }

        // Must clearly dominate every runner-up animal group
        if (analysis.runnerUpMass > 0f && analysis.dominantMass < analysis.runnerUpMass * 2.5f) {
            return null
        }

        val confirmSet = if (species == PetSpecies.DOG) DOG_CONFIRM_MLKIT_LABELS else CAT_CONFIRM_MLKIT_LABELS
        val mlConfirmed = lowerMl.any { (lbl, conf) -> conf >= 0.55f && lbl in confirmSet }
        val minRequiredMass = if (mlConfirmed) 0.52f else 0.66f
        if (analysis.dominantMass < minRequiredMass) return null

        return PetDetection(species = species, confidence = analysis.dominantMass)
    }

    /**
     * Checks whether a person/pet summary should be visible in People & Search strips:
     *  - Humans always appear.
     *  - Pets ONLY appear once they have at least [MIN_PET_PHOTOS_TO_SHOW] photos (or have been
     *    explicitly named or favorited by the user).
     */
    fun shouldDisplayCluster(
        entityKind: String,
        photoCount: Int,
        isNamed: Boolean,
        isFavorite: Boolean
    ): Boolean {
        if (entityKind == PersonClusterEntity.KIND_HUMAN) return true
        return isNamed || isFavorite || photoCount >= MIN_PET_PHOTOS_TO_SHOW
    }

    /**
     * Extracts a 42-dimensional center-weighted perceptual fur/coat descriptor from ARGB pixels
     * of a pet crop:
     *  - 24 hue × saturation bins (captures orange ginger vs cream vs brown vs blue-grey fur)
     *  - 12 lightness bins (captures black vs charcoal vs silver vs white coats)
     *  - 6 coat contrast & color-variance stats (distinguishes solid vs striped/tabby/spotted/tuxedo)
     *
     * Pixels are weighted by a 2D Gaussian centered on the crop so background walls/grass have
     * minimal influence on matching the same pet across different locations.
     */
    fun computeCoatColorDescriptor(argbPixels: IntArray, width: Int, height: Int): FloatArray {
        val out = FloatArray(PET_COAT_SLOTS)
        if (width <= 0 || height <= 0 || argbPixels.size < width * height) return out

        val halfW = (width - 1).coerceAtLeast(1) * 0.5f
        val halfH = (height - 1).coerceAtLeast(1) * 0.5f
        var weightSum = 0.0
        var lumSum = 0.0
        var lumSqSum = 0.0
        var satSum = 0.0
        var darkMass = 0.0
        var lightMass = 0.0
        var edgeSum = 0.0
        var edgeWeight = 0.0

        val step = max(1, min(width, height) / 48)
        for (y in 0 until height step step) {
            val ny = (y - halfH) / halfH
            val rowOffset = y * width
            for (x in 0 until width step step) {
                val nx = (x - halfW) / halfW
                val r2 = nx * nx + ny * ny
                if (r2 > 1.05f) continue
                val w = exp((-r2 * 2.2).coerceAtLeast(-10.0))

                val px = argbPixels[rowOffset + x]
                val r = ((px shr 16) and 0xFF) / 255f
                val g = ((px shr 8) and 0xFF) / 255f
                val b = (px and 0xFF) / 255f

                val maxC = maxOf(r, g, b)
                val minC = minOf(r, g, b)
                val delta = maxC - minC
                val sat = if (maxC > 1e-4f) delta / maxC else 0f
                val lum = 0.299f * r + 0.587f * g + 0.114f * b

                val hueDeg = when {
                    delta < 1e-4f -> 0f
                    maxC == r -> 60f * (((g - b) / delta) % 6f)
                    maxC == g -> 60f * (((b - r) / delta) + 2f)
                    else -> 60f * (((r - g) / delta) + 4f)
                }.let { if (it < 0f) it + 360f else it }

                // 12 hue buckets x 2 saturation tiers (muted warm/cool fur vs vivid fur) = 24 bins
                val hueBin = ((hueDeg / 30f).toInt()).coerceIn(0, 11)
                if (sat >= 0.14f) {
                    val satTier = if (sat >= 0.40f) 1 else 0
                    val bin = hueBin * 2 + satTier
                    out[bin] += (w * sat).toFloat()
                }

                // 12 luminance bins (slots 24..35)
                val lumBin = (lum * 12f).toInt().coerceIn(0, 11)
                out[24 + lumBin] += w.toFloat()

                weightSum += w
                lumSum += w * lum
                lumSqSum += w * lum * lum
                satSum += w * sat
                if (lum < 0.24f) darkMass += w
                if (lum > 0.74f) lightMass += w

                if (x + step < width) {
                    val pxNext = argbPixels[rowOffset + x + step]
                    val lumNext = 0.299f * (((pxNext shr 16) and 0xFF) / 255f) +
                        0.587f * (((pxNext shr 8) and 0xFF) / 255f) +
                        0.114f * ((pxNext and 0xFF) / 255f)
                    edgeSum += w * abs(lum - lumNext)
                    edgeWeight += w
                }
            }
        }

        if (weightSum > 1e-6) {
            val invW = (1.0 / weightSum).toFloat()
            for (i in 0 until 36) out[i] *= invW
            val meanLum = (lumSum / weightSum).toFloat()
            val varLum = ((lumSqSum / weightSum) - meanLum * meanLum).coerceAtLeast(0.0)
            out[36] = meanLum
            out[37] = sqrt(varLum).toFloat() * 2f
            out[38] = (satSum / weightSum).toFloat()
            out[39] = (darkMass / weightSum).toFloat()
            out[40] = (lightMass / weightSum).toFloat()
            out[41] = if (edgeWeight > 1e-6) ((edgeSum / edgeWeight) * 4.0).toFloat().coerceIn(0f, 1f) else 0f
        }

        // Hellinger (sqrt) on histogram bins reduces illumination sensitivity
        for (i in 0 until 36) {
            out[i] = sqrt(out[i].coerceAtLeast(0f))
        }
        return FaceClusteringEngine.l2Normalize(out)
    }

    /**
     * Builds an L2-normalized 160-dim appearance embedding for an individual dog or cat,
     * combining ImageNet fine-grained breed distribution + center-weighted coat color/pattern.
     */
    fun buildPetEmbedding(
        species: PetSpecies,
        probs: FloatArray,
        coatDescriptor: FloatArray
    ): FloatArray {
        val breedRaw = FloatArray(PET_BREED_SLOTS)
        when (species) {
            PetSpecies.DOG -> {
                val end = minOf(DOG_INDEX_END, probs.lastIndex)
                for (idx in DOG_INDEX_START..end) {
                    breedRaw[idx - DOG_INDEX_START] = sqrt(probs[idx].coerceAtLeast(0f))
                }
            }
            PetSpecies.CAT -> {
                val end = minOf(CAT_INDEX_END, probs.lastIndex)
                for (idx in CAT_INDEX_START..end) {
                    breedRaw[idx - CAT_INDEX_START] = sqrt(probs[idx].coerceAtLeast(0f))
                }
            }
        }
        val breedNorm = FaceClusteringEngine.l2Normalize(breedRaw)
        val coatNorm = if (coatDescriptor.size == PET_COAT_SLOTS) {
            FaceClusteringEngine.l2Normalize(coatDescriptor)
        } else {
            FloatArray(PET_COAT_SLOTS)
        }

        // Dogs have 118 fine-grained ImageNet breeds; cats have only 5 classes so coat color carries more weight.
        val breedWeight = if (species == PetSpecies.DOG) 0.72f else 0.42f
        val coatWeight = if (species == PetSpecies.DOG) 0.28f else 0.58f

        val combined = FloatArray(PET_EMBEDDING_DIM)
        for (i in 0 until PET_BREED_SLOTS) {
            combined[i] = breedNorm[i] * breedWeight
        }
        for (i in 0 until PET_COAT_SLOTS) {
            combined[PET_BREED_SLOTS + i] = coatNorm[i] * coatWeight
        }
        return FaceClusteringEngine.l2Normalize(combined)
    }

    /**
     * Finds the best matching existing pet cluster of the exact same [species].
     */
    fun findBestMatchingPetCluster(
        species: PetSpecies,
        embedding: FloatArray,
        candidates: List<FaceClusteringEngine.ClusterCandidate>,
        forbiddenPersonIds: Set<String> = emptySet()
    ): FaceClusteringEngine.MatchResult? {
        if (embedding.size != PET_EMBEDDING_DIM || candidates.isEmpty()) return null
        var bestId: String? = null
        var bestSim = -1f
        for (candidate in candidates) {
            if (candidate.entityKind != species.entityKind) continue
            if (candidate.personId in forbiddenPersonIds) continue
            if (candidate.centroid.size != embedding.size) continue
            val sim = FaceClusteringEngine.cosineSimilarity(embedding, candidate.centroid)
            val required = if (candidate.sampleCount <= 1) {
                PET_SINGLETON_MATCH_THRESHOLD
            } else {
                PET_CLUSTER_MATCH_THRESHOLD
            }
            if (sim >= required && sim > bestSim) {
                bestSim = sim
                bestId = candidate.personId
            }
        }
        return bestId?.let { FaceClusteringEngine.MatchResult(personId = it, similarity = bestSim) }
    }
}

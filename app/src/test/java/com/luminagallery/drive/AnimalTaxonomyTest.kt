package com.luminagallery.drive

import com.luminagallery.drive.data.repository.ai.AnimalTaxonomy
import com.luminagallery.drive.data.repository.ai.SmartObjectVocabulary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimalTaxonomyTest {

    private fun probs(vararg entries: Pair<Int, Float>): FloatArray =
        FloatArray(1000).also { arr -> entries.forEach { (i, p) -> arr[i] = p } }

    /** Monkey photo whose probability is spread over look-alike dog / primate classes. */
    private val spreadMonkey = probs(
        252 to 0.21f, // affenpinscher (dog)
        262 to 0.12f, // Brabancon griffon (dog)
        373 to 0.17f, // macaque
        380 to 0.14f, // titi
        378 to 0.12f, // capuchin
        382 to 0.08f  // squirrel monkey
    )

    @Test
    fun spreadMonkey_isLabelledMonkey_notDog() {
        val a = AnimalTaxonomy.analyze(spreadMonkey)
        assertEquals(AnimalTaxonomy.Group.PRIMATE, AnimalTaxonomy.confidentGroup(a, minMass = 0.40f, minLeadRatio = 1.5f))
        val labels = AnimalTaxonomy.displayLabels(a)
        assertFalse("Monkey must not be tagged Dog: $labels", "Dog" in labels)
        assertFalse("Bird" in labels)
    }

    @Test
    fun ambiguousAnimal_getsOnlyGenericOrNothing() {
        val a = AnimalTaxonomy.analyze(probs(207 to 0.30f, 373 to 0.28f, 88 to 0.20f))
        assertNull(AnimalTaxonomy.confidentGroup(a))
        val labels = AnimalTaxonomy.displayLabels(a)
        assertTrue(labels.none { it == "Dog" || it == "Monkey" || it == "Bird" })
    }

    @Test
    fun clearDogAndParrot_areStillRecognised() {
        val dog = AnimalTaxonomy.analyze(probs(207 to 0.62f, 208 to 0.20f))
        assertTrue("Dog" in AnimalTaxonomy.displayLabels(dog))
        val parrot = AnimalTaxonomy.analyze(probs(88 to 0.55f, 89 to 0.25f))
        val labels = AnimalTaxonomy.displayLabels(parrot)
        assertTrue("Parrot" in labels && "Bird" in labels)
    }

    @Test
    fun monkeyFaceCrop_isVetoedFromPeople() {
        val crop = AnimalTaxonomy.analyze(probs(373 to 0.08f, 380 to 0.06f, 252 to 0.10f, 903 to 0.04f))
        assertTrue(AnimalTaxonomy.isNonHumanFace(crop, wholePhoto = null))
    }

    @Test
    fun humanFaceCrop_withClothingCues_isKept() {
        val crop = AnimalTaxonomy.analyze(probs(903 to 0.30f, 837 to 0.20f, 457 to 0.10f, 254 to 0.05f))
        assertFalse(AnimalTaxonomy.isNonHumanFace(crop, wholePhoto = null))
    }

    @Test
    fun faceInsideMonkeyPhoto_isVetoed() {
        val whole = AnimalTaxonomy.analyze(probs(373 to 0.50f, 374 to 0.10f))
        val crop = AnimalTaxonomy.analyze(probs(373 to 0.04f, 252 to 0.03f, 600 to 0.20f))
        assertTrue(AnimalTaxonomy.isNonHumanFace(crop, whole))
    }

    @Test
    fun mlKitDogLabel_isDroppedWhenImageNetSaysMonkey() {
        val a = AnimalTaxonomy.analyze(spreadMonkey)
        assertFalse(SmartObjectVocabulary.isMlKitAnimalLabelConsistent("Dog", a))
        assertFalse(SmartObjectVocabulary.isMlKitAnimalLabelConsistent("Bird", a))
        assertTrue(SmartObjectVocabulary.isMlKitAnimalLabelConsistent("Beach", a))
    }

    @Test
    fun mergedLabels_forMonkeyPhoto_searchableAsAap_notHond() {
        val merged = SmartObjectVocabulary.mergeSceneAndObjectLabels(
            mlKitLabels = listOf("Dog" to 0.80f, "Tree" to 0.75f),
            imageNetLabels = emptyList(),
            animal = AnimalTaxonomy.analyze(spreadMonkey),
            animalSpecificLabel = null
        )
        assertTrue(merged.displayLabels.toString(), "Monkey" in merged.displayLabels)
        assertFalse("Dog" in merged.displayLabels)
        assertTrue(SmartObjectVocabulary.tokensMatch(merged.searchTokens, "aap"))
        assertFalse(SmartObjectVocabulary.tokensMatch(merged.searchTokens, "hond"))
        assertFalse(SmartObjectVocabulary.tokensMatch(merged.searchTokens, "dog"))
    }

    @Test
    fun tokensMatch_isWordAware() {
        assertFalse(SmartObjectVocabulary.tokensMatch("hotdog food eten", "dog"))
        assertFalse(SmartObjectVocabulary.tokensMatch("catamaran boat boot", "cat"))
        assertTrue(SmartObjectVocabulary.tokensMatch("dog hond honden pet", "dog"))
        assertTrue(SmartObjectVocabulary.tokensMatch("dog hond honden pet", "hond"))
        assertTrue(SmartObjectVocabulary.tokensMatch("Parrot,Bird", "parr"))
        assertTrue(SmartObjectVocabulary.tokensMatch("ice cream ijs dessert", "ice cream"))
    }
}

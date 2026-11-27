package com.luminagallery.drive.widget

import com.luminagallery.drive.data.local.dao.LightFaceRow
import com.luminagallery.drive.data.local.entity.MediaAnnotationEntity
import com.luminagallery.drive.data.local.entity.PersonClusterEntity
import com.luminagallery.drive.data.local.entity.PhotoSmartIndexEntity
import com.luminagallery.drive.data.repository.ai.PetRecognitionEngine
import com.luminagallery.drive.data.repository.ai.SmartObjectVocabulary
import java.util.Locale

internal data class WidgetPersonOption(
    val personId: String,
    val memoryId: String?,
    val name: String,
    val isPet: Boolean,
    val entityKind: String,
    val isFavorite: Boolean,
    val coverCropPath: String?,
    val photoIds: Set<String>
) {
    val photoCount: Int get() = photoIds.size

    val displayName: String
        get() = name.trim().takeIf { it.isNotEmpty() } ?: when (entityKind) {
            PersonClusterEntity.KIND_PET_DOG -> "Dog"
            PersonClusterEntity.KIND_PET_CAT -> "Cat"
            else -> "Person"
        }
}

internal enum class WidgetTagKind {
    PRESET,
    ANIMAL,
    AI_TAG,
    CUSTOM_TAG
}

internal data class WidgetTagOption(
    val key: String,
    val label: String,
    val photoCount: Int,
    val kind: WidgetTagKind,
    val category: SmartObjectVocabulary.ObjectCategory? = null
)

/**
 * Pure-Kotlin filter, catalog, and summary engine for People, Pets, Animals & Tag widgets.
 * Enforces privacy by intersecting every result with visible (unlocked, non-hidden) local photo IDs
 * and excluding hidden person/pet clusters.
 */
internal object WidgetSmartFilter {

    const val TAG_ALL_PETS = "__smart_all_pets__"
    const val TAG_ALL_ANIMALS = "__smart_all_animals__"

    private val DOMESTIC_PET_LABELS: Set<String> = setOf(
        "pet", "pets", "huisdier", "huisdieren",
        "dog", "dogs", "puppy", "hond", "honden",
        "cat", "cats", "kitten", "kat", "katten", "poes",
        "rabbit", "bunny", "konijn", "konijnen",
        "hamster", "guinea pig"
    )

    private val EXTRA_ANIMAL_LABELS: Set<String> = setOf(
        "animal", "animals", "wildlife", "dier", "dieren", "zoo", "safari",
        "insect", "butterfly", "bee", "spider", "dragonfly", "ladybug",
        "deer", "cow", "sheep", "goat", "pig", "giraffe", "hippo", "rhino",
        "koala", "kangaroo", "otter", "seal", "dolphin", "snake", "alligator", "crocodile"
    )

    /**
     * Builds the list of selectable people & pets that have at least one visible local photo,
     * applying [PetRecognitionEngine.shouldDisplayCluster] so 1-photo stray dogs/cats stay hidden.
     */
    fun buildPersonOptions(
        persons: List<PersonClusterEntity>,
        faces: List<LightFaceRow>,
        visibleLocalPhotoIds: Set<String>
    ): List<WidgetPersonOption> {
        if (persons.isEmpty() || visibleLocalPhotoIds.isEmpty()) return emptyList()
        val personById = persons.associateBy { it.personId }
        val facesByPerson = HashMap<String, MutableList<LightFaceRow>>()
        val totalPhotoIdsByPerson = HashMap<String, MutableSet<String>>()

        for (face in faces) {
            val pid = face.personId ?: continue
            val person = personById[pid] ?: continue
            if (person.isHidden) continue
            totalPhotoIdsByPerson.getOrPut(pid) { HashSet() }.add(face.itemId)
            if (face.itemId in visibleLocalPhotoIds) {
                facesByPerson.getOrPut(pid) { mutableListOf() }.add(face)
            }
        }

        return persons.mapNotNull { person ->
            if (person.isHidden) return@mapNotNull null
            val localFaces = facesByPerson[person.personId] ?: return@mapNotNull null
            if (localFaces.isEmpty()) return@mapNotNull null
            val localPhotoIds = localFaces.mapTo(LinkedHashSet(localFaces.size)) { it.itemId }
            val clusterTotal = maxOf(
                localPhotoIds.size,
                totalPhotoIdsByPerson[person.personId]?.size ?: 0
            )
            if (!PetRecognitionEngine.shouldDisplayCluster(
                    entityKind = person.entityKind,
                    photoCount = clusterTotal,
                    isNamed = person.name.isNotBlank(),
                    isFavorite = person.isFavorite
                )
            ) {
                return@mapNotNull null
            }
            val safeCover = localFaces.firstOrNull {
                it.faceId == person.coverFaceId && !it.cropPath.isNullOrBlank()
            } ?: localFaces.maxByOrNull { it.qualityScore }
            val coverPath = safeCover?.cropPath
                ?: person.coverCropPath?.takeIf { person.coverPhotoId in visibleLocalPhotoIds }
            WidgetPersonOption(
                personId = person.personId,
                memoryId = person.memoryId,
                name = person.name.trim(),
                isPet = person.isPet,
                entityKind = person.entityKind,
                isFavorite = person.isFavorite,
                coverCropPath = coverPath,
                photoIds = localPhotoIds
            )
        }.sortedWith(
            compareByDescending<WidgetPersonOption> { it.isFavorite }
                .thenBy { if (it.name.isNotBlank()) 0 else 1 }
                .thenByDescending { it.photoCount }
                .thenBy { it.displayName.lowercase(Locale.US) }
        )
    }

    /**
     * Resolves a selected person/pet ID to its live cluster, seamlessly falling back to its
     * durable [PersonClusterEntity.memoryId] if AI recognition was rebuilt.
     */
    fun resolveClusterForSelection(
        selectedId: String,
        selectedMemoryIds: Map<String, String>,
        visiblePersonsById: Map<String, PersonClusterEntity>,
        visiblePersonsByMemoryId: Map<String, PersonClusterEntity>
    ): PersonClusterEntity? {
        visiblePersonsById[selectedId]?.let { return it }
        selectedMemoryIds[selectedId]?.let { memId ->
            visiblePersonsByMemoryId[memId]?.let { return it }
        }
        return visiblePersonsByMemoryId[selectedId]
    }

    /**
     * Resolves matching visible local photo IDs for a [WidgetPhotoSource.PEOPLE_AND_PETS] widget:
     * - 1 person/pet -> all visible local photos of that person/pet
     * - 2+ with [WidgetPersonMatchMode.ANY] -> union (any of the selected people/pets)
     * - 2+ with [WidgetPersonMatchMode.TOGETHER] -> intersection (all selected people/pets together)
     */
    fun resolveMatchingPhotoIdsForPeople(
        selectedPersonIds: List<String>,
        selectedPersonMemoryIds: Map<String, String>,
        matchMode: WidgetPersonMatchMode,
        persons: List<PersonClusterEntity>,
        faces: List<LightFaceRow>,
        visibleLocalPhotoIds: Set<String>
    ): Set<String> {
        val distinctSelected = selectedPersonIds.distinct()
        if (distinctSelected.isEmpty() || visibleLocalPhotoIds.isEmpty()) return emptySet()

        val visiblePersons = persons.filterNot { it.isHidden }
        val byId = visiblePersons.associateBy { it.personId }
        val byMemoryId = visiblePersons.mapNotNull { p -> p.memoryId?.let { it to p } }.toMap()

        val photosByPersonId = HashMap<String, MutableSet<String>>()
        for (face in faces) {
            if (face.itemId !in visibleLocalPhotoIds) continue
            val pid = face.personId ?: continue
            if (pid !in byId) continue
            photosByPersonId.getOrPut(pid) { HashSet() }.add(face.itemId)
        }

        val resolvedSets = distinctSelected.map { selId ->
            val cluster = resolveClusterForSelection(selId, selectedPersonMemoryIds, byId, byMemoryId)
            cluster?.let { photosByPersonId[it.personId] }.orEmpty()
        }

        return when (matchMode) {
            WidgetPersonMatchMode.ANY -> {
                val out = LinkedHashSet<String>()
                for (set in resolvedSets) out.addAll(set)
                out
            }
            WidgetPersonMatchMode.TOGETHER -> {
                var acc: Set<String> = resolvedSets.first()
                for (idx in 1 until resolvedSets.size) {
                    if (acc.isEmpty()) return emptySet()
                    acc = acc.intersect(resolvedSets[idx])
                }
                acc
            }
        }
    }

    /**
     * Fast helper for live UI preview counts inside the picker when options are already built.
     */
    fun computeMatchingPhotoCountForSelectedOptions(
        selectedIds: List<String>,
        matchMode: WidgetPersonMatchMode,
        optionsById: Map<String, WidgetPersonOption>
    ): Int {
        val distinct = selectedIds.distinct()
        if (distinct.isEmpty()) return 0
        val sets = distinct.map { optionsById[it]?.photoIds.orEmpty() }
        return when (matchMode) {
            WidgetPersonMatchMode.ANY -> {
                val out = HashSet<String>()
                sets.forEach { out.addAll(it) }
                out.size
            }
            WidgetPersonMatchMode.TOGETHER -> {
                var acc = sets.first()
                for (i in 1 until sets.size) {
                    if (acc.isEmpty()) return 0
                    acc = acc.intersect(sets[i])
                }
                acc.size
            }
        }
    }

    /**
     * Builds the categorized tag catalog (Presets: All pets & All animals, specific Animals,
     * user custom #tags, and AI scene/object tags) with accurate visible local photo counts.
     */
    fun buildTagOptions(
        visibleLocalPhotoIds: Set<String>,
        smartEntries: List<PhotoSmartIndexEntity>,
        annotations: List<MediaAnnotationEntity>,
        persons: List<PersonClusterEntity>,
        faces: List<LightFaceRow>
    ): List<WidgetTagOption> {
        if (visibleLocalPhotoIds.isEmpty()) return emptyList()

        val petPhotos = collectPetClusterPhotos(persons, faces, visibleLocalPhotoIds)
        val smartByItem = smartEntries.asSequence()
            .filter { it.itemId in visibleLocalPhotoIds }
            .associateBy { it.itemId }
        val annByItem = annotations.asSequence()
            .filter { it.itemId in visibleLocalPhotoIds }
            .associateBy { it.itemId }

        val candidateItemIds = LinkedHashSet<String>()
        candidateItemIds.addAll(smartByItem.keys)
        candidateItemIds.addAll(annByItem.keys)
        candidateItemIds.addAll(petPhotos.allPetPhotoIds)

        var allPetsCount = 0
        var allAnimalsCount = 0

        // lowerKey -> Pair(displayLabel, matchingItemIds)
        val aiTagMap = LinkedHashMap<String, Pair<String, MutableSet<String>>>()
        val customTagMap = LinkedHashMap<String, Pair<String, MutableSet<String>>>()

        for (itemId in candidateItemIds) {
            val smart = smartByItem[itemId]
            val ann = annByItem[itemId]
            if (isPetPhoto(itemId, smart, ann, petPhotos.allPetPhotoIds)) allPetsCount++
            if (isAnimalPhoto(itemId, smart, ann, petPhotos.allPetPhotoIds)) allAnimalsCount++

            if (smart != null && smart.labelsCsv.isNotBlank()) {
                for (raw in smart.labelsCsv.split(",")) {
                    val clean = raw.trim()
                    if (clean.isEmpty()) continue
                    val lower = clean.lowercase(Locale.US)
                    val entry = aiTagMap.getOrPut(lower) { clean to HashSet() }
                    entry.second.add(itemId)
                }
            }
            if (itemId in petPhotos.dogPhotoIds) {
                aiTagMap.getOrPut("dog") { "Dog" to HashSet() }.second.add(itemId)
            }
            if (itemId in petPhotos.catPhotoIds) {
                aiTagMap.getOrPut("cat") { "Cat" to HashSet() }.second.add(itemId)
            }
            if (ann != null && ann.tagsCsv.isNotBlank()) {
                for (raw in ann.tagsCsv.split(",")) {
                    val clean = raw.removePrefix("#").trim()
                    if (clean.isEmpty()) continue
                    val lower = clean.lowercase(Locale.US)
                    val existingAi = aiTagMap[lower]
                    if (existingAi != null) {
                        // Merge into existing AI tag so we don't show duplicate "Dog" and "#dog" chips
                        existingAi.second.add(itemId)
                    } else {
                        val entry = customTagMap.getOrPut(lower) { clean to HashSet() }
                        entry.second.add(itemId)
                    }
                }
            }
        }

        // Second pass: if a custom tag matched an AI tag that was discovered later in the loop, merge cleanly
        val customIterator = customTagMap.entries.iterator()
        while (customIterator.hasNext()) {
            val (lower, pair) = customIterator.next()
            val existingAi = aiTagMap[lower]
            if (existingAi != null) {
                existingAi.second.addAll(pair.second)
                customIterator.remove()
            }
        }

        val out = mutableListOf<WidgetTagOption>()
        out += WidgetTagOption(
            key = TAG_ALL_PETS,
            label = "All pets",
            photoCount = allPetsCount,
            kind = WidgetTagKind.PRESET,
            category = SmartObjectVocabulary.ObjectCategory.PETS
        )
        out += WidgetTagOption(
            key = TAG_ALL_ANIMALS,
            label = "All animals",
            photoCount = allAnimalsCount,
            kind = WidgetTagKind.PRESET,
            category = SmartObjectVocabulary.ObjectCategory.PETS
        )

        val animalTags = mutableListOf<WidgetTagOption>()
        val otherAiTags = mutableListOf<WidgetTagOption>()
        for ((lower, pair) in aiTagMap) {
            val (display, ids) = pair
            if (ids.isEmpty()) continue
            val category = SmartObjectVocabulary.categoryForLabel(display)
            val isAnimal = category == SmartObjectVocabulary.ObjectCategory.PETS || lower in EXTRA_ANIMAL_LABELS
            val option = WidgetTagOption(
                key = display,
                label = display,
                photoCount = ids.size,
                kind = if (isAnimal) WidgetTagKind.ANIMAL else WidgetTagKind.AI_TAG,
                category = category
            )
            if (isAnimal) animalTags += option else otherAiTags += option
        }

        val customTags = customTagMap.values.mapNotNull { (display, ids) ->
            if (ids.isEmpty()) null else WidgetTagOption(
                key = "#$display",
                label = "#$display",
                photoCount = ids.size,
                kind = WidgetTagKind.CUSTOM_TAG,
                category = SmartObjectVocabulary.categoryForLabel(display)
            )
        }

        val countThenName = compareByDescending<WidgetTagOption> { it.photoCount }
            .thenBy { it.label.lowercase(Locale.US) }

        out += animalTags.sortedWith(countThenName)
        out += customTags.sortedWith(countThenName)
        out += otherAiTags.sortedWith(countThenName)
        return out
    }

    /**
     * Resolves matching visible local photo IDs for a [WidgetPhotoSource.TAGS_AND_ANIMALS] widget.
     * Supports presets ([TAG_ALL_PETS], [TAG_ALL_ANIMALS]), specific animal labels, AI object/scene
     * labels (with bilingual EN+NL matching), and custom user `#tags` from `media_annotations`.
     */
    fun resolveMatchingPhotoIdsForTags(
        selectedTags: List<String>,
        matchMode: WidgetTagMatchMode,
        visibleLocalPhotoIds: Set<String>,
        smartEntries: List<PhotoSmartIndexEntity>,
        annotations: List<MediaAnnotationEntity>,
        persons: List<PersonClusterEntity>,
        faces: List<LightFaceRow>
    ): Set<String> {
        val cleanSelected = selectedTags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cleanSelected.isEmpty() || visibleLocalPhotoIds.isEmpty()) return emptySet()

        val petPhotos = collectPetClusterPhotos(persons, faces, visibleLocalPhotoIds)
        val smartByItem = smartEntries.asSequence()
            .filter { it.itemId in visibleLocalPhotoIds }
            .associateBy { it.itemId }
        val annByItem = annotations.asSequence()
            .filter { it.itemId in visibleLocalPhotoIds }
            .associateBy { it.itemId }

        val candidateItemIds = LinkedHashSet<String>()
        candidateItemIds.addAll(smartByItem.keys)
        candidateItemIds.addAll(annByItem.keys)
        candidateItemIds.addAll(petPhotos.allPetPhotoIds)

        val matched = LinkedHashSet<String>()
        for (itemId in candidateItemIds) {
            val smart = smartByItem[itemId]
            val ann = annByItem[itemId]
            val ok = when (matchMode) {
                WidgetTagMatchMode.ANY -> cleanSelected.any { tag ->
                    matchesTagKey(itemId, tag, smart, ann, petPhotos)
                }
                WidgetTagMatchMode.ALL -> cleanSelected.all { tag ->
                    matchesTagKey(itemId, tag, smart, ann, petPhotos)
                }
            }
            if (ok) matched.add(itemId)
        }
        return matched
    }

    fun isPetPhoto(
        itemId: String,
        smart: PhotoSmartIndexEntity?,
        annotation: MediaAnnotationEntity?,
        petClusterPhotoIds: Set<String>
    ): Boolean {
        if (itemId in petClusterPhotoIds) return true
        if (smart != null && smart.labelsCsv.isNotBlank()) {
            for (raw in smart.labelsCsv.split(",")) {
                if (raw.trim().lowercase(Locale.US) in DOMESTIC_PET_LABELS) return true
            }
        }
        if (annotation != null && annotation.tagsCsv.isNotBlank()) {
            for (raw in annotation.tagsCsv.split(",")) {
                if (raw.removePrefix("#").trim().lowercase(Locale.US) in DOMESTIC_PET_LABELS) return true
            }
        }
        return false
    }

    fun isAnimalPhoto(
        itemId: String,
        smart: PhotoSmartIndexEntity?,
        annotation: MediaAnnotationEntity?,
        petClusterPhotoIds: Set<String>
    ): Boolean {
        if (isPetPhoto(itemId, smart, annotation, petClusterPhotoIds)) return true
        if (smart != null && smart.labelsCsv.isNotBlank()) {
            for (raw in smart.labelsCsv.split(",")) {
                val lower = raw.trim().lowercase(Locale.US)
                if (lower.isEmpty()) continue
                if (lower in EXTRA_ANIMAL_LABELS) return true
                if (SmartObjectVocabulary.categoryForLabel(lower) == SmartObjectVocabulary.ObjectCategory.PETS) {
                    return true
                }
            }
        }
        if (annotation != null && annotation.tagsCsv.isNotBlank()) {
            for (raw in annotation.tagsCsv.split(",")) {
                val lower = raw.removePrefix("#").trim().lowercase(Locale.US)
                if (lower.isEmpty()) continue
                if (lower in EXTRA_ANIMAL_LABELS) return true
                if (SmartObjectVocabulary.categoryForLabel(lower) == SmartObjectVocabulary.ObjectCategory.PETS) {
                    return true
                }
            }
        }
        return false
    }

    private fun matchesTagKey(
        itemId: String,
        tagKey: String,
        smart: PhotoSmartIndexEntity?,
        annotation: MediaAnnotationEntity?,
        petPhotos: PetClusterPhotoSets
    ): Boolean {
        if (tagKey == TAG_ALL_PETS) {
            return isPetPhoto(itemId, smart, annotation, petPhotos.allPetPhotoIds)
        }
        if (tagKey == TAG_ALL_ANIMALS) {
            return isAnimalPhoto(itemId, smart, annotation, petPhotos.allPetPhotoIds)
        }
        val clean = tagKey.removePrefix("#").trim()
        if (clean.isEmpty()) return false
        val lower = clean.lowercase(Locale.US)

        if (lower == "dog" && itemId in petPhotos.dogPhotoIds) return true
        if (lower == "cat" && itemId in petPhotos.catPhotoIds) return true
        if (lower == "pet" && itemId in petPhotos.allPetPhotoIds) return true

        if (annotation != null && annotation.tagsCsv.isNotBlank()) {
            for (raw in annotation.tagsCsv.split(",")) {
                if (raw.removePrefix("#").trim().equals(clean, ignoreCase = true)) return true
            }
        }
        if (smart != null) {
            if (SmartObjectVocabulary.tokensMatch(smart.labelsCsv, clean)) return true
            if (SmartObjectVocabulary.tokensMatch(smart.labelSearchTokens, clean)) return true
        }
        return false
    }

    private data class PetClusterPhotoSets(
        val dogPhotoIds: Set<String>,
        val catPhotoIds: Set<String>,
        val allPetPhotoIds: Set<String>
    )

    private fun collectPetClusterPhotos(
        persons: List<PersonClusterEntity>,
        faces: List<LightFaceRow>,
        visibleLocalPhotoIds: Set<String>
    ): PetClusterPhotoSets {
        val kindByPetId = HashMap<String, String>()
        for (p in persons) {
            if (!p.isHidden && p.isPet) {
                kindByPetId[p.personId] = p.entityKind
            }
        }
        if (kindByPetId.isEmpty()) {
            return PetClusterPhotoSets(emptySet(), emptySet(), emptySet())
        }
        val dogs = HashSet<String>()
        val cats = HashSet<String>()
        val all = HashSet<String>()
        for (face in faces) {
            if (face.itemId !in visibleLocalPhotoIds) continue
            val kind = face.personId?.let { kindByPetId[it] } ?: continue
            all.add(face.itemId)
            when (kind) {
                PersonClusterEntity.KIND_PET_DOG -> dogs.add(face.itemId)
                PersonClusterEntity.KIND_PET_CAT -> cats.add(face.itemId)
            }
        }
        return PetClusterPhotoSets(dogs, cats, all)
    }

    fun displayLabelForTagKey(tagKey: String): String = when (tagKey) {
        TAG_ALL_PETS -> "All pets"
        TAG_ALL_ANIMALS -> "All animals"
        else -> tagKey
    }

    fun captionTitleForTagKey(tagKey: String): String = when (tagKey) {
        TAG_ALL_PETS -> "Pets"
        TAG_ALL_ANIMALS -> "Animals"
        else -> tagKey
    }

    fun formatPeopleCaption(
        selectedPersonIds: List<String>,
        selectedPersonMemoryIds: Map<String, String>,
        matchMode: WidgetPersonMatchMode,
        persons: List<PersonClusterEntity>
    ): String {
        val visible = persons.filterNot { it.isHidden }
        val byId = visible.associateBy { it.personId }
        val byMem = visible.mapNotNull { p -> p.memoryId?.let { it to p } }.toMap()
        val names = selectedPersonIds.distinct().mapNotNull { selId ->
            val cluster = resolveClusterForSelection(selId, selectedPersonMemoryIds, byId, byMem)
                ?: return@mapNotNull null
            cluster.name.trim().takeIf { it.isNotEmpty() } ?: when (cluster.entityKind) {
                PersonClusterEntity.KIND_PET_DOG -> "Dog"
                PersonClusterEntity.KIND_PET_CAT -> "Cat"
                else -> "Person"
            }
        }
        return formatPeopleNames(names, matchMode, fallbackCount = selectedPersonIds.distinct().size)
    }

    fun formatPeopleNames(
        names: List<String>,
        matchMode: WidgetPersonMatchMode,
        fallbackCount: Int = names.size
    ): String {
        if (names.isEmpty()) {
            return when {
                fallbackCount <= 0 -> "People & pets"
                fallbackCount == 1 -> "1 person"
                matchMode == WidgetPersonMatchMode.TOGETHER -> "$fallbackCount together"
                else -> "$fallbackCount people"
            }
        }
        if (names.size == 1) return names.first()
        val sep = if (matchMode == WidgetPersonMatchMode.TOGETHER) " & " else " · "
        return when {
            names.size == 2 -> "${names[0]}$sep${names[1]}"
            else -> "${names[0]}, ${names[1]} +${names.size - 2}"
        }
    }

    fun formatTagsCaption(
        selectedTags: List<String>,
        matchMode: WidgetTagMatchMode
    ): String {
        val labels = selectedTags.distinct().map { captionTitleForTagKey(it) }
        if (labels.isEmpty()) return "Pets, animals & tags"
        if (labels.size == 1) return labels.first()
        val sep = if (matchMode == WidgetTagMatchMode.ALL) " + " else " · "
        return when {
            labels.size == 2 -> "${labels[0]}$sep${labels[1]}"
            else -> "${labels[0]}, ${labels[1]} +${labels.size - 2}"
        }
    }
}

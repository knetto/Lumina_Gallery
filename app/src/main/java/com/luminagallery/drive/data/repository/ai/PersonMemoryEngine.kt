package com.luminagallery.drive.data.repository.ai

import com.luminagallery.drive.data.local.entity.PersonClusterEntity
import com.luminagallery.drive.data.local.entity.SavedPersonAnchorEntity
import com.luminagallery.drive.data.local.entity.SavedPersonMemoryEntity
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Pure-Kotlin matching, balanced anchor retention, and post-rebuild reconciliation engine for
 * durable user-curated People memories (names, user-combined groups, favorites, hidden states,
 * custom cover faces, and per-person face exclusions).
 *
 * Completely independent of any specific face detector or embedding model: anchors match across
 * model upgrades by stable `(itemId, normalizedFaceBoundingBox)` geometry.
 */
object PersonMemoryEngine {

    const val MAX_POSITIVE_ANCHORS_PER_MEMORY: Int = 250
    const val MAX_EXCLUDED_ANCHORS_PER_MEMORY: Int = 400
    const val MAX_CLOUD_ANCHORS_PER_MEMORY: Int = 40
    const val LEGACY_ORIGIN_GROUP: String = "legacy_profile"

    data class FaceBox(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        val width: Float get() = (right - left).coerceAtLeast(0f)
        val height: Float get() = (bottom - top).coerceAtLeast(0f)
        val area: Float get() = width * height
        val centerX: Float get() = (left + right) * 0.5f
        val centerY: Float get() = (top + bottom) * 0.5f
        val isValidBox: Boolean get() = right > left && bottom > top
        val isLegacyWholePhotoPlaceholder: Boolean
            get() = !isValidBox || (left <= 0.001f && top <= 0.001f && right >= 0.999f && bottom >= 0.999f)
    }

    data class ActiveFaceSnapshot(
        val faceId: String,
        val itemId: String,
        val personId: String,
        val box: FaceBox,
        val qualityScore: Float,
        val cropPath: String?
    )

    data class ActiveClusterSnapshot(
        val personId: String,
        val memoryId: String?,
        val name: String,
        val isFavorite: Boolean,
        val isHidden: Boolean,
        val coverFaceId: String?,
        val coverPhotoId: String?,
        val sampleCount: Int,
        val photoIds: Set<String>,
        val entityKind: String = PersonClusterEntity.KIND_HUMAN
    )

    data class MemoryLinkAction(
        val personId: String,
        val memory: SavedPersonMemoryEntity
    )

    data class MemoryMergeAction(
        val targetPersonId: String,
        val sourcePersonId: String,
        val memoryId: String
    )

    data class MemoryCoverAction(
        val personId: String,
        val faceId: String,
        val itemId: String,
        val cropPath: String?
    )

    data class ReconciliationPlan(
        val links: List<MemoryLinkAction>,
        val merges: List<MemoryMergeAction>,
        val coverRestores: List<MemoryCoverAction>
    )

    fun SavedPersonAnchorEntity.toFaceBox(): FaceBox =
        FaceBox(left = bboxLeft, top = bboxTop, right = bboxRight, bottom = bboxBottom)

    fun SavedPersonMemoryEntity.coverBoxOrNull(): FaceBox? {
        val l = coverBboxLeft ?: return null
        val t = coverBboxTop ?: return null
        val r = coverBboxRight ?: return null
        val b = coverBboxBottom ?: return null
        return FaceBox(l, t, r, b).takeIf { it.isValidBox }
    }

    fun computeIoU(a: FaceBox, b: FaceBox): Float {
        if (!a.isValidBox || !b.isValidBox) return 0f
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        val interW = (interRight - interLeft).coerceAtLeast(0f)
        val interH = (interBottom - interTop).coerceAtLeast(0f)
        val interArea = interW * interH
        if (interArea <= 0f) return 0f
        val unionArea = a.area + b.area - interArea
        if (unionArea <= 1e-7f) return 0f
        return (interArea / unionArea).coerceIn(0f, 1f)
    }

    fun containsPoint(box: FaceBox, x: Float, y: Float): Boolean =
        box.isValidBox && x in box.left..box.right && y in box.top..box.bottom

    /**
     * Determines whether [detected] refers to the same face region inside the same photo as
     * [anchor], tolerating bounding-box framing differences between different face detectors
     * (e.g., tight chin-to-brow box vs looser head box) while separating distinct people in
     * group photos.
     */
    fun isSameFaceLocation(
        detected: FaceBox,
        anchor: FaceBox,
        singleFaceInPhoto: Boolean = false
    ): Boolean {
        if (!detected.isValidBox || anchor.isLegacyWholePhotoPlaceholder) return false
        val iou = computeIoU(detected, anchor)
        if (iou >= 0.22f) return true

        val areaRatio = if (anchor.area > 1e-6f) detected.area / anchor.area else 0f
        val centerDist = hypot(
            (detected.centerX - anchor.centerX).toDouble(),
            (detected.centerY - anchor.centerY).toDouble()
        ).toFloat()

        // Tight vs loose detector crop around the exact same face
        if (areaRatio in 0.15f..6.5f) {
            if (containsPoint(anchor, detected.centerX, detected.centerY) &&
                containsPoint(detected, anchor.centerX, anchor.centerY)
            ) {
                return true
            }
            val refSpan = maxOf(detected.width, detected.height, anchor.width, anchor.height)
            if (iou >= 0.10f && refSpan > 0f && centerDist <= refSpan * 0.42f) {
                return true
            }
        }

        // Single-person portrait tolerance if a radically different detector shifts the crop frame
        if (singleFaceInPhoto && areaRatio in 0.12f..8.0f && centerDist <= 0.22f) {
            return true
        }
        return false
    }

    fun scoreFaceLocationMatch(
        detected: FaceBox,
        anchor: FaceBox,
        isUserAssigned: Boolean,
        singleFaceInPhoto: Boolean = false
    ): Float? {
        if (!isSameFaceLocation(detected, anchor, singleFaceInPhoto)) return null
        val iou = computeIoU(detected, anchor)
        val centerDist = hypot(
            (detected.centerX - anchor.centerX).toDouble(),
            (detected.centerY - anchor.centerY).toDouble()
        ).toFloat().coerceIn(0f, 1f)
        return iou + (if (isUserAssigned) 0.50f else 0f) + (1f - centerDist) * 0.25f
    }

    /**
     * Returns every `memoryId` from which this detected face location was explicitly excluded by
     * the user (so a removed wrong face will never drift back into that person on future rescans).
     */
    fun findExcludedMemoryIds(
        detected: FaceBox,
        anchorsForItem: List<SavedPersonAnchorEntity>,
        singleFaceInPhoto: Boolean = false
    ): Set<String> {
        if (anchorsForItem.isEmpty() || !detected.isValidBox) return emptySet()
        val excluded = HashSet<String>()
        for (anchor in anchorsForItem) {
            if (!anchor.isExcluded) continue
            if (isSameFaceLocation(detected, anchor.toFaceBox(), singleFaceInPhoto)) {
                excluded.add(anchor.memoryId)
            }
        }
        return excluded
    }

    /**
     * Finds the strongest positive saved `memoryId` matching this face location inside a photo,
     * ignoring any `memoryId` present in [excludedMemoryIds] or missing from [validMemoryIds].
     */
    fun findBestPositiveMemoryId(
        detected: FaceBox,
        anchorsForItem: List<SavedPersonAnchorEntity>,
        validMemoryIds: Set<String>,
        excludedMemoryIds: Set<String> = emptySet(),
        singleFaceInPhoto: Boolean = false
    ): String? {
        if (anchorsForItem.isEmpty() || !detected.isValidBox) return null
        var bestMemoryId: String? = null
        var bestScore = -1f
        for (anchor in anchorsForItem) {
            if (anchor.isExcluded) continue
            if (anchor.memoryId !in validMemoryIds) continue
            if (anchor.memoryId in excludedMemoryIds) continue
            val score = scoreFaceLocationMatch(
                detected = detected,
                anchor = anchor.toFaceBox(),
                isUserAssigned = anchor.isUserAssigned,
                singleFaceInPhoto = singleFaceInPhoto
            ) ?: continue
            if (score > bestScore) {
                bestScore = score
                bestMemoryId = anchor.memoryId
            }
        }
        return bestMemoryId
    }

    /**
     * Checks whether a detected face matches the user's saved custom cover anchor for [memory].
     */
    fun matchesSavedCover(
        memory: SavedPersonMemoryEntity,
        itemId: String,
        detected: FaceBox,
        singleFaceInPhoto: Boolean = false
    ): Boolean {
        val coverItem = memory.coverItemId ?: return false
        if (coverItem != itemId) return false
        val coverBox = memory.coverBoxOrNull() ?: return singleFaceInPhoto
        return isSameFaceLocation(detected, coverBox, singleFaceInPhoto)
    }

    /**
     * Deduplicates overlapping anchors for the same `(memoryId, itemId)` and applies balanced
     * round-robin pruning across [SavedPersonAnchorEntity.originGroupId]s so every sub-group
     * combined by the user stays represented even if one group had hundreds of photos.
     */
    fun pruneAndDeduplicateAnchors(
        anchors: List<SavedPersonAnchorEntity>,
        maxPositiveCount: Int = MAX_POSITIVE_ANCHORS_PER_MEMORY,
        maxExcludedCount: Int = MAX_EXCLUDED_ANCHORS_PER_MEMORY,
        coverItemId: String? = null,
        coverBox: FaceBox? = null
    ): List<SavedPersonAnchorEntity> {
        if (anchors.isEmpty()) return emptyList()

        val rawExcluded = anchors.filter { it.isExcluded }
        val rawPositive = anchors.filterNot { it.isExcluded }

        val dedupExcluded = deduplicateAnchors(rawExcluded)
            .sortedByDescending { it.updatedAtEpochMs }
            .take(maxExcludedCount)

        // Never keep a positive anchor that overlaps an explicit exclusion for the same memory & photo
        val excludedByItem = dedupExcluded.groupBy { it.itemId }
        val filteredPositive = rawPositive.filter { pos ->
            val posBox = pos.toFaceBox()
            val exclList = excludedByItem[pos.itemId].orEmpty()
            exclList.none { excl ->
                !posBox.isLegacyWholePhotoPlaceholder &&
                    isSameFaceLocation(posBox, excl.toFaceBox(), singleFaceInPhoto = false)
            }
        }

        val dedupPositive = deduplicateAnchors(filteredPositive)
        val keptPositive = if (dedupPositive.size <= maxPositiveCount) {
            dedupPositive
        } else {
            selectBalancedPositiveAnchors(
                anchors = dedupPositive,
                limit = maxPositiveCount,
                coverItemId = coverItemId,
                coverBox = coverBox
            )
        }

        return keptPositive + dedupExcluded
    }

    /**
     * Selects up to [limit] positive anchors while guaranteeing representation from every distinct
     * `originGroupId` (i.e. every cluster the user combined), always prioritizing the saved cover
     * anchor and manually reassigned faces.
     */
    fun selectBalancedPositiveAnchors(
        anchors: List<SavedPersonAnchorEntity>,
        limit: Int,
        coverItemId: String? = null,
        coverBox: FaceBox? = null
    ): List<SavedPersonAnchorEntity> {
        if (limit <= 0 || anchors.isEmpty()) return emptyList()
        if (anchors.size <= limit) return anchors

        val selected = LinkedHashMap<String, SavedPersonAnchorEntity>(limit)

        // 1. Pin saved cover face anchor first
        if (!coverItemId.isNullOrBlank()) {
            val coverMatch = anchors.firstOrNull { a ->
                a.itemId == coverItemId && (coverBox == null || isSameFaceLocation(a.toFaceBox(), coverBox))
            }
            if (coverMatch != null) {
                selected[coverMatch.anchorId] = coverMatch
            }
        }

        // 2. Pin user-reassigned faces next
        val userAssigned = anchors
            .filter { it.isUserAssigned && it.anchorId !in selected }
            .sortedByDescending { it.updatedAtEpochMs }
        for (a in userAssigned) {
            if (selected.size >= limit) break
            selected[a.anchorId] = a
        }

        if (selected.size >= limit) return selected.values.toList()

        // 3. Round-robin across every distinct originGroupId (preferring exact-box high-quality anchors)
        val remaining = anchors.filter { it.anchorId !in selected }
        val queues = remaining
            .groupBy { it.originGroupId }
            .entries
            .sortedByDescending { (_, list) -> list.maxOfOrNull { it.updatedAtEpochMs } ?: 0L }
            .map { (_, list) ->
                ArrayDeque(
                    list.sortedWith(
                        compareBy<SavedPersonAnchorEntity> { it.toFaceBox().isLegacyWholePhotoPlaceholder }
                            .thenByDescending { it.qualityScore }
                            .thenByDescending { it.updatedAtEpochMs }
                    )
                )
            }

        var progress = true
        while (selected.size < limit && progress) {
            progress = false
            for (queue in queues) {
                if (selected.size >= limit) break
                val next = queue.removeFirstOrNull() ?: continue
                selected[next.anchorId] = next
                progress = true
            }
        }
        return selected.values.toList()
    }

    private fun deduplicateAnchors(anchors: List<SavedPersonAnchorEntity>): List<SavedPersonAnchorEntity> {
        if (anchors.size <= 1) return anchors
        val result = ArrayList<SavedPersonAnchorEntity>(anchors.size)
        val byItem = anchors.groupBy { it.itemId }
        for ((_, list) in byItem) {
            val sorted = list.sortedWith(
                compareByDescending<SavedPersonAnchorEntity> { it.isUserAssigned }
                    .thenBy { it.toFaceBox().isLegacyWholePhotoPlaceholder }
                    .thenByDescending { it.qualityScore }
                    .thenByDescending { it.updatedAtEpochMs }
            )
            val keptForItem = ArrayList<SavedPersonAnchorEntity>(sorted.size)
            for (candidate in sorted) {
                val candBox = candidate.toFaceBox()
                val duplicate = keptForItem.any { existing ->
                    val existingBox = existing.toFaceBox()
                    when {
                        candBox.isLegacyWholePhotoPlaceholder && existingBox.isLegacyWholePhotoPlaceholder -> true
                        candBox.isLegacyWholePhotoPlaceholder && !existingBox.isLegacyWholePhotoPlaceholder -> true
                        !candBox.isLegacyWholePhotoPlaceholder && existingBox.isLegacyWholePhotoPlaceholder -> false
                        else -> isSameFaceLocation(candBox, existingBox, singleFaceInPhoto = false)
                    }
                }
                if (!duplicate) {
                    keptForItem.add(candidate)
                }
            }
            result.addAll(keptForItem)
        }
        return result
    }

    /**
     * Plans durable reconciliation of active clusters against saved [memories] & [anchors]:
     *  - Links clusters to their remembered `memoryId` (restoring name, favorite, hidden state).
     *  - Automatically re-combines split clusters that both match the SAME `memoryId` (e.g., two
     *    groups the user combined previously that a new detector/model scanned separately), while
     *    never merging two clusters that co-occur in the same photo.
     *  - Falls back to cluster-level photo-ID overlap for legacy box-less preserved profiles.
     *  - Restores the user's chosen cover face once that photo has been scanned.
     */
    fun planReconciliation(
        memories: List<SavedPersonMemoryEntity>,
        anchors: List<SavedPersonAnchorEntity>,
        clusters: List<ActiveClusterSnapshot>,
        faces: List<ActiveFaceSnapshot>
    ): ReconciliationPlan {
        if (memories.isEmpty() || clusters.isEmpty()) {
            return ReconciliationPlan(emptyList(), emptyList(), emptyList())
        }

        val memoryById = memories.associateBy { it.memoryId }
        val validMemoryIds = memoryById.keys
        val anchorsByItem = anchors.groupBy { it.itemId }
        val facesByItemCount = faces.groupingBy { it.itemId }.eachCount()
        val facesByPerson = faces.groupBy { it.personId }

        // Step A: Determine each active cluster's effective memoryId (existing link OR exact-box anchor vote)
        val clusterToMemory = LinkedHashMap<String, String>()
        for (cluster in clusters) {
            val existingMem = cluster.memoryId?.takeIf { it in validMemoryIds }
            if (existingMem != null) {
                clusterToMemory[cluster.personId] = existingMem
                continue
            }
            val clusterFaces = facesByPerson[cluster.personId].orEmpty()
            if (clusterFaces.isEmpty()) continue
            val validIdsForKind = memoryById.values
                .filter { it.entityKind == cluster.entityKind }
                .mapTo(HashSet()) { it.memoryId }
            if (validIdsForKind.isEmpty()) continue
            val voteCounts = HashMap<String, Int>()
            var totalAnchored = 0
            for (face in clusterFaces) {
                val itemAnchors = anchorsByItem[face.itemId].orEmpty()
                if (itemAnchors.isEmpty()) continue
                val singleFace = (facesByItemCount[face.itemId] ?: 1) == 1
                val excluded = findExcludedMemoryIds(face.box, itemAnchors, singleFaceInPhoto = singleFace)
                val bestMem = findBestPositiveMemoryId(
                    detected = face.box,
                    anchorsForItem = itemAnchors,
                    validMemoryIds = validIdsForKind,
                    excludedMemoryIds = excluded,
                    singleFaceInPhoto = singleFace
                ) ?: continue
                voteCounts[bestMem] = (voteCounts[bestMem] ?: 0) + 1
                totalAnchored++
            }
            if (totalAnchored > 0) {
                val winner = voteCounts.maxByOrNull { it.value }
                if (winner != null && winner.value.toFloat() / totalAnchored.toFloat() >= 0.60f) {
                    clusterToMemory[cluster.personId] = winner.key
                }
            }
        }

        // Step B: Legacy whole-photo fallback for memories without any matched cluster yet
        val matchedMemories = clusterToMemory.values.toHashSet()
        val legacyMemories = memories.filter { mem ->
            mem.memoryId !in matchedMemories && (mem.name.isNotBlank() || mem.isFavorite || mem.isHidden)
        }
        if (legacyMemories.isNotEmpty()) {
            val legacyAnchorsByMemory = anchors
                .filter { !it.isExcluded && it.toFaceBox().isLegacyWholePhotoPlaceholder }
                .groupBy { it.memoryId }
            val preservedProfiles = mutableListOf<FaceClusteringEngine.PreservedPersonProfile>()
            val profileIndexToMemoryId = mutableListOf<String>()
            for (mem in legacyMemories) {
                val photoIds = legacyAnchorsByMemory[mem.memoryId]
                    .orEmpty()
                    .mapTo(HashSet()) { it.itemId }
                if (photoIds.isNotEmpty()) {
                    preservedProfiles.add(
                        FaceClusteringEngine.PreservedPersonProfile(
                            name = mem.name,
                            isFavorite = mem.isFavorite,
                            photoIds = photoIds
                        )
                    )
                    profileIndexToMemoryId.add(mem.memoryId)
                }
            }
            if (preservedProfiles.isNotEmpty()) {
                val unlinkedCandidates = clusters
                    .filter { it.personId !in clusterToMemory && it.name.isBlank() }
                    .map { c ->
                        FaceClusteringEngine.ClusterCandidate(
                            personId = c.personId,
                            centroid = FloatArray(0),
                            sampleCount = c.sampleCount,
                            isNamed = false,
                            photoIds = c.photoIds
                        )
                    }
                val legacyMatches = FaceClusteringEngine.matchPreservedProfiles(preservedProfiles, unlinkedCandidates)
                for (m in legacyMatches) {
                    val idx = preservedProfiles.indexOf(m.profile)
                    val memId = profileIndexToMemoryId.getOrNull(idx) ?: continue
                    if (m.personId !in clusterToMemory) {
                        clusterToMemory[m.personId] = memId
                    }
                }
            }
        }

        // Step C: Link primary cluster per memoryId & plan auto-recombines for split clusters
        val clusterById = clusters.associateBy { it.personId }
        val clustersGroupedByMemory = LinkedHashMap<String, MutableList<ActiveClusterSnapshot>>()
        for ((personId, memId) in clusterToMemory) {
            val c = clusterById[personId] ?: continue
            clustersGroupedByMemory.getOrPut(memId) { mutableListOf() }.add(c)
        }

        val links = mutableListOf<MemoryLinkAction>()
        val merges = mutableListOf<MemoryMergeAction>()
        val coverRestores = mutableListOf<MemoryCoverAction>()

        for ((memId, group) in clustersGroupedByMemory) {
            val memory = memoryById[memId] ?: continue
            val sorted = group.sortedWith(
                compareByDescending<ActiveClusterSnapshot> { it.memoryId == memId }
                    .thenByDescending { it.name.isNotBlank() }
                    .thenByDescending { it.sampleCount }
                    .thenBy { it.personId }
            )
            val primary = sorted.first()
            val needsLinkOrMetadata = primary.memoryId != memId ||
                (memory.name.isNotBlank() && primary.name != memory.name) ||
                (memory.isFavorite && !primary.isFavorite) ||
                (memory.isHidden && !primary.isHidden)
            if (needsLinkOrMetadata) {
                links.add(MemoryLinkAction(personId = primary.personId, memory = memory))
            }

            val accumulatedPhotoIds = HashSet(primary.photoIds)
            val allPrimaryAndMergedFaces = ArrayList(facesByPerson[primary.personId].orEmpty())
            for (i in 1 until sorted.size) {
                val secondary = sorted[i]
                // Never merge different entity kinds (e.g. human vs pet) or clusters co-occurring in the same photo
                if (secondary.entityKind != primary.entityKind) continue
                if (secondary.photoIds.any { it in accumulatedPhotoIds }) continue
                merges.add(
                    MemoryMergeAction(
                        targetPersonId = primary.personId,
                        sourcePersonId = secondary.personId,
                        memoryId = memId
                    )
                )
                accumulatedPhotoIds.addAll(secondary.photoIds)
                allPrimaryAndMergedFaces.addAll(facesByPerson[secondary.personId].orEmpty())
            }

            // Step D: Restore custom cover face if present in this person's active faces
            val desiredCoverItem = memory.coverItemId
            if (!desiredCoverItem.isNullOrBlank()) {
                val matchingCover = allPrimaryAndMergedFaces.firstOrNull { face ->
                    val singleFace = (facesByItemCount[face.itemId] ?: 1) == 1
                    matchesSavedCover(memory, face.itemId, face.box, singleFaceInPhoto = singleFace)
                }
                if (matchingCover != null &&
                    (primary.coverFaceId != matchingCover.faceId || primary.coverPhotoId != matchingCover.itemId)
                ) {
                    coverRestores.add(
                        MemoryCoverAction(
                            personId = primary.personId,
                            faceId = matchingCover.faceId,
                            itemId = matchingCover.itemId,
                            cropPath = matchingCover.cropPath
                        )
                    )
                }
            }
        }

        return ReconciliationPlan(
            links = links,
            merges = merges,
            coverRestores = coverRestores
        )
    }

    /**
     * Checks whether a [SavedPersonMemoryEntity] still carries any meaningful user preference
     * (name, favorite, hidden, explicit combine, custom cover, excluded face, or rejected merge).
     */
    fun hasMeaningfulUserState(
        memory: SavedPersonMemoryEntity,
        hasExcludedAnchors: Boolean,
        hasRejectedMerges: Boolean
    ): Boolean = memory.name.isNotBlank() ||
        memory.isFavorite ||
        memory.isHidden ||
        memory.isUserMerged ||
        !memory.coverItemId.isNullOrBlank() ||
        hasExcludedAnchors ||
        hasRejectedMerges
}

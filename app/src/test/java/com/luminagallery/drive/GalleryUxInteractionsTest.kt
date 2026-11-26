package com.luminagallery.drive

import com.luminagallery.drive.data.repository.LocalFileOperationsManager
import com.luminagallery.drive.data.repository.ai.PersonSummary
import com.luminagallery.drive.domain.model.UserPreferences
import com.luminagallery.drive.ui.components.computeSteppedPinchColumns
import com.luminagallery.drive.ui.screens.people.PeopleViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryUxInteractionsTest {

    @Test
    fun pinchZoom_photoGrid_stepsAndClampsWithinOneToSix() {
        // Zoom in (spread fingers) -> fewer, larger columns down to 1
        assertEquals(3, computeSteppedPinchColumns(currentColumns = 4, cumulativeZoom = 1.20f, minColumns = 1, maxColumns = 6))
        assertEquals(1, computeSteppedPinchColumns(currentColumns = 1, cumulativeZoom = 1.35f, minColumns = 1, maxColumns = 6))

        // Zoom out (pinch fingers together) -> more, denser columns up to 6
        assertEquals(5, computeSteppedPinchColumns(currentColumns = 4, cumulativeZoom = 0.80f, minColumns = 1, maxColumns = 6))
        assertEquals(6, computeSteppedPinchColumns(currentColumns = 6, cumulativeZoom = 0.72f, minColumns = 1, maxColumns = 6))

        // Inside dead-zone -> no step triggered yet
        assertNull(computeSteppedPinchColumns(currentColumns = 4, cumulativeZoom = 1.08f, minColumns = 1, maxColumns = 6))
        assertNull(computeSteppedPinchColumns(currentColumns = 4, cumulativeZoom = 0.92f, minColumns = 1, maxColumns = 6))
    }

    @Test
    fun pinchZoom_albumGrid_stepsAndClampsWithinTwoToFive() {
        assertEquals(2, computeSteppedPinchColumns(currentColumns = 3, cumulativeZoom = 1.22f, minColumns = 2, maxColumns = 5))
        assertEquals(2, computeSteppedPinchColumns(currentColumns = 2, cumulativeZoom = 1.40f, minColumns = 2, maxColumns = 5))
        assertEquals(4, computeSteppedPinchColumns(currentColumns = 3, cumulativeZoom = 0.80f, minColumns = 2, maxColumns = 5))
        assertEquals(5, computeSteppedPinchColumns(currentColumns = 5, cumulativeZoom = 0.70f, minColumns = 2, maxColumns = 5))
    }

    @Test
    fun resolveParentLocalFolder_nestedSubfolderReturnsMotherFolder() {
        val dcimChild = LocalFileOperationsManager.resolveParentLocalFolder("DCIM/Vakantie/Dag1/")
        assertEquals("Vakantie", dcimChild?.displayName)
        assertEquals("DCIM/Vakantie", dcimChild?.relativePath)
        assertTrue(dcimChild?.useDcimParent == true)

        val picturesChild = LocalFileOperationsManager.resolveParentLocalFolder("Pictures\\Trips")
        assertEquals("Pictures", picturesChild?.displayName)
        assertEquals("Pictures", picturesChild?.relativePath)
        assertFalse(picturesChild?.useDcimParent == true)

        val cameraRoll = LocalFileOperationsManager.resolveParentLocalFolder("DCIM/Camera")
        assertEquals("DCIM", cameraRoll?.displayName)
        assertEquals("DCIM", cameraRoll?.relativePath)
        assertTrue(cameraRoll?.useDcimParent == true)
    }

    @Test
    fun resolveParentLocalFolder_rootOrBlankReturnsNull() {
        assertNull(LocalFileOperationsManager.resolveParentLocalFolder(null))
        assertNull(LocalFileOperationsManager.resolveParentLocalFolder(""))
        assertNull(LocalFileOperationsManager.resolveParentLocalFolder("   "))
        assertNull(LocalFileOperationsManager.resolveParentLocalFolder("DCIM"))
        assertNull(LocalFileOperationsManager.resolveParentLocalFolder("/Pictures/"))
    }

    @Test
    fun userPreferences_hasSensibleGridDefaults() {
        val prefs = UserPreferences()
        assertEquals(4, prefs.gridColumns)
        assertEquals(3, prefs.albumGridColumns)
        assertTrue(prefs.showAlbumSidebarDefault)
    }

    @Test
    fun resolveCombineOrder_prefersNamedTargetAndLargerClusterWhenBothUnnamed() {
        fun fakePerson(id: String, name: String, count: Int) = PersonSummary(
            personId = id,
            name = name,
            isHidden = false,
            isFavorite = false,
            coverCropPath = null,
            coverPhotoId = null,
            photoCount = count,
            photoIds = emptySet()
        )

        val unnamedSmall = fakePerson("u1", "", 2)
        val unnamedLarge = fakePerson("u2", "", 9)
        val namedTarget = fakePerson("n1", "Mom", 4)
        val namedDragged = fakePerson("n2", "Alex", 12)

        // Unnamed dragged onto named -> named stays primary
        val r1 = PeopleViewModel.resolveCombineOrder(dragged = unnamedLarge, target = namedTarget)
        assertEquals("n1", r1.first.personId)
        assertEquals("u2", r1.second.personId)

        // Named dragged onto unnamed -> named stays primary
        val r2 = PeopleViewModel.resolveCombineOrder(dragged = namedDragged, target = unnamedLarge)
        assertEquals("n2", r2.first.personId)
        assertEquals("u2", r2.second.personId)

        // Both named -> the circle dropped onto (target) stays primary
        val r3 = PeopleViewModel.resolveCombineOrder(dragged = namedDragged, target = namedTarget)
        assertEquals("n1", r3.first.personId)
        assertEquals("n2", r3.second.personId)

        // Both unnamed -> larger cluster stays primary
        val r4 = PeopleViewModel.resolveCombineOrder(dragged = unnamedLarge, target = unnamedSmall)
        assertEquals("u2", r4.first.personId)
        assertEquals("u1", r4.second.personId)
    }
}

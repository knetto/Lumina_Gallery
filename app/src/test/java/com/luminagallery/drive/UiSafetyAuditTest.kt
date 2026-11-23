package com.luminagallery.drive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Automated regression guardrails preventing viewer crashes, negative-padding spring overshoots,
 * and SurfaceView black-box glitches from ever re-entering the codebase.
 */
class UiSafetyAuditTest {

    private fun projectSrcRoot(): File {
        val candidates = listOf(
            File("src/main"),
            File("app/src/main")
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Could not locate app/src/main from ${File(".").absolutePath}")
    }

    @Test
    fun viewerFiles_containNoUnsafeNotNullAssertions() {
        val mainDir = projectSrcRoot()
        val viewerDir = File(mainDir, "java/com/luminagallery/drive/ui/screens/viewer")
        assertTrue("viewer directory must exist", viewerDir.exists())

        val filesToCheck = listOf("ViewerScreen.kt", "VideoPlayerPage.kt", "ViewerViewModel.kt")
        for (fileName in filesToCheck) {
            val file = File(viewerDir, fileName)
            assertTrue("$fileName must exist", file.exists())
            val lines = file.readLines()
            val unsafeLines = lines.mapIndexedNotNull { idx, line ->
                val trimmed = line.trim()
                if (!trimmed.startsWith("//") && !trimmed.startsWith("*") && line.contains("!!")) {
                    "$fileName:${idx + 1}: $trimmed"
                } else {
                    null
                }
            }
            assertTrue(
                "Unsafe '!!' found in $fileName:\n${unsafeLines.joinToString("\n")}",
                unsafeLines.isEmpty()
            )
        }
    }

    @Test
    fun filmstripMargin_usesNonBouncySpringAndNonNegativeClamp() {
        val viewerFile = File(
            projectSrcRoot(),
            "java/com/luminagallery/drive/ui/screens/viewer/ViewerScreen.kt"
        )
        val text = viewerFile.readText()
        assertTrue(
            "Filmstrip sideMargin must clamp with coerceAtLeast(0.dp) to prevent negative-padding crash",
            text.contains("sideMargin.coerceAtLeast(0.dp)")
        )
        assertTrue(
            "Viewer bottomDockClearance must clamp with coerceAtLeast(0.dp)",
            text.contains("bottomDockClearance.coerceAtLeast(0.dp)")
        )
    }

    @Test
    fun videoPlayers_useTextureViewLayoutToPreventBlackBoxGlitches() {
        val mainDir = projectSrcRoot()
        val layoutFile = File(mainDir, "res/layout/view_exo_texture_player.xml")
        assertTrue("view_exo_texture_player.xml must exist", layoutFile.exists())
        assertTrue(
            "view_exo_texture_player.xml must configure texture_view",
            layoutFile.readText().contains("texture_view")
        )

        val videoPlayer = File(mainDir, "java/com/luminagallery/drive/ui/screens/viewer/VideoPlayerPage.kt").readText()
        assertFalse(
            "VideoPlayerPage must inflate TextureView PlayerView instead of raw SurfaceView PlayerView(ctx)",
            videoPlayer.contains("PlayerView(ctx)")
        )
    }

    @Test
    fun motionPhotoDetector_neverUsesUnboundedReadBytes() {
        val detector = File(
            projectSrcRoot(),
            "java/com/luminagallery/drive/data/repository/MotionPhotoDetector.kt"
        ).readText()
        assertFalse(
            "MotionPhotoDetector must stream with bounded buffers instead of stream.readBytes()",
            detector.contains("readBytes()")
        )
    }

    @Test
    fun navGraph_hasNoDelayedAlbumPopFadeOut() {
        val navGraph = File(
            projectSrcRoot(),
            "java/com/luminagallery/drive/ui/navigation/LuminaNavGraph.kt"
        ).readText()
        assertFalse(
            "Delayed fadeOut on popExitTransition causes floating black blocks during predictive back",
            navGraph.contains("delayMillis = 55")
        )
    }

    @Test
    fun navGraph_morphParentRoutesOverridePopEnterTransitionSoChildCloseNeverZoomsPage() {
        val navGraph = File(
            projectSrcRoot(),
            "java/com/luminagallery/drive/ui/navigation/LuminaNavGraph.kt"
        ).readText()
        val folderBlock = navGraph.substringAfter("composable<FolderRoute>(").substringBefore(") { backStackEntry ->")
        val collectionBlock = navGraph.substringAfter("composable<CollectionRoute>(").substringBefore(") { backStackEntry ->")
        assertTrue(
            "FolderRoute must explicitly set popEnterTransition = null so closing Viewer doesn't re-run album enter morph",
            folderBlock.contains("popEnterTransition = null")
        )
        assertTrue(
            "CollectionRoute must explicitly set popEnterTransition = null so closing a child album doesn't re-run collection enter morph",
            collectionBlock.contains("popEnterTransition = null")
        )
    }
}

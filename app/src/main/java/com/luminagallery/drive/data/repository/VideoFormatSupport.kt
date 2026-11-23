package com.luminagallery.drive.data.repository

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ts.PsExtractor
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import java.util.Locale

/**
 * Unified video container & MIME detection plus Media3 ExoPlayer configuration with full
 * MPEG-2 Program Stream (`video/mp2p` / `.mp2p`, `.m2p`, `.mpg`, `.mpeg`, `.vob`, `.mod`, `.vro`, `.ps`)
 * demuxing (`PsExtractor`) and FFmpeg software codec fallback (`NextRenderersFactory`).
 */
object VideoFormatSupport {

    const val MIME_MP2P: String = MimeTypes.VIDEO_PS // "video/mp2p"

    val MP2P_MIME_TYPES: Set<String> = setOf(
        "video/mp2p",
        "video/mpeg",
        "video/x-mpeg",
        "video/mpeg2",
        "video/x-mpeg2",
        "video/x-mpeg-system",
        "video/x-ms-vob",
        "video/dvd"
    )

    val MP2P_EXTENSIONS: Set<String> = setOf(
        "mp2p", "m2p", "mpg", "mpeg", "mpe", "vob", "vro", "mod", "ps"
    )

    /**
     * Video extensions that Android MediaStore sometimes indexes as `MEDIA_TYPE_NONE` (0)
     * instead of `MEDIA_TYPE_VIDEO`, so we explicitly include them in local gallery scans.
     */
    val MEDIASTORE_FALLBACK_VIDEO_EXTS: Set<String> = MP2P_EXTENSIONS + setOf(
        "m2ts", "mts", "ts", "flv", "asf"
    )

    val ALL_VIDEO_EXTENSIONS: Set<String> = setOf(
        "mp4", "mov", "mkv", "webm", "3gp", "3g2", "m4v", "avi", "wmv", "ogv"
    ) + MEDIASTORE_FALLBACK_VIDEO_EXTS

    fun extractExtension(fileNameOrUri: String?): String {
        if (fileNameOrUri.isNullOrBlank()) return ""
        val clean = fileNameOrUri.substringBefore('?').substringBefore('#')
        val lastSegment = clean.substringAfterLast('/')
        val dot = lastSegment.lastIndexOf('.')
        if (dot <= 0 || dot >= lastSegment.length - 1) return ""
        return lastSegment.substring(dot + 1).lowercase(Locale.US)
    }

    fun isMp2pVideo(fileNameOrUri: String?, mimeType: String?): Boolean {
        val lowerMime = mimeType?.trim()?.lowercase(Locale.US).orEmpty()
        if (lowerMime in MP2P_MIME_TYPES) return true
        return extractExtension(fileNameOrUri) in MP2P_EXTENSIONS
    }

    fun isVideoFile(fileNameOrUri: String?, mimeType: String?): Boolean {
        val lowerMime = mimeType?.trim()?.lowercase(Locale.US).orEmpty()
        if (lowerMime.startsWith("video/")) return true
        return extractExtension(fileNameOrUri) in ALL_VIDEO_EXTENSIONS
    }

    /**
     * Normalizes missing or `application/octet-stream` MIME types on known video extensions
     * (especially `.mp2p`, `.m2p`, `.mpg`, `.mpeg`, `.vob`, `.mod`, `.vro`) so MediaStore,
     * Drive indexing, Share, and ExoPlayer consistently see a concrete video MIME type.
     */
    fun normalizeVideoMimeType(fileNameOrUri: String?, rawMimeType: String?): String {
        val trimmed = rawMimeType?.trim().orEmpty()
        val lower = trimmed.lowercase(Locale.US)
        if (isMp2pVideo(fileNameOrUri, trimmed)) {
            return if (lower == MIME_MP2P || lower == "video/mpeg") lower else MIME_MP2P
        }
        if (lower.startsWith("video/") && lower != "video/*") {
            return trimmed
        }
        return when (extractExtension(fileNameOrUri)) {
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "3gp" -> "video/3gpp"
            "avi" -> "video/x-msvideo"
            "ts", "mts", "m2ts" -> MimeTypes.VIDEO_MP2T
            "flv" -> "video/x-flv"
            "wmv" -> "video/x-ms-wmv"
            "ogv" -> "video/ogg"
            else -> "video/mp4"
        }
    }

    /**
     * Returns an explicit ExoPlayer container MIME hint when needed (always [MimeTypes.VIDEO_PS]
     * for MP2P/MPEG-PS files so extensionless Drive URLs and `content://` URIs prioritize [PsExtractor]).
     */
    fun resolveExoPlayerMimeType(fileNameOrUri: String?, mimeType: String?): String? {
        if (isMp2pVideo(fileNameOrUri, mimeType)) {
            return MIME_MP2P
        }
        val lower = mimeType?.trim()?.lowercase(Locale.US).orEmpty()
        return when {
            lower == MimeTypes.VIDEO_MP2T -> MimeTypes.VIDEO_MP2T
            lower.startsWith("video/") && lower != "video/*" -> lower
            else -> null
        }
    }

    /**
     * Builds an [ExtractorsFactory] with constant-bitrate seeking enabled (essential for
     * seeking inside MPEG-2 Program Streams / MP2P) and prioritizes [PsExtractor] first
     * for MP2P files while preserving all standard fallback extractors.
     */
    @OptIn(UnstableApi::class)
    fun buildExtractorsFactory(preferProgramStream: Boolean): ExtractorsFactory {
        val defaults = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setConstantBitrateSeekingAlwaysEnabled(true)
        if (!preferProgramStream) return defaults

        return object : ExtractorsFactory {
            override fun createExtractors(): Array<Extractor> =
                prependPsExtractor(defaults.createExtractors())

            override fun createExtractors(
                uri: Uri,
                responseHeaders: Map<String, List<String>>
            ): Array<Extractor> =
                prependPsExtractor(defaults.createExtractors(uri, responseHeaders))

            private fun prependPsExtractor(base: Array<Extractor>): Array<Extractor> {
                val ordered = ArrayList<Extractor>(base.size + 1)
                ordered.add(PsExtractor())
                for (ext in base) {
                    if (ext !is PsExtractor) {
                        ordered.add(ext)
                    }
                }
                return ordered.toTypedArray()
            }
        }
    }

    /**
     * Builds a [DefaultRenderersFactory] backed by NextLib's FFmpeg extension renderers
     * (`EXTENSION_RENDERER_MODE_ON`) so hardware decoders run when available and software
     * FFmpeg decoders seamlessly take over for codecs commonly embedded in MP2P/legacy videos
     * (AC-3, E-AC-3, DTS, MPEG audio, software H.264/HEVC, etc.).
     */
    @OptIn(UnstableApi::class)
    fun buildRenderersFactory(context: Context): DefaultRenderersFactory =
        NextRenderersFactory(context.applicationContext)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
}

package com.luminagallery.drive.data.remote

import com.luminagallery.drive.data.remote.dto.DriveAboutResponse
import com.luminagallery.drive.data.remote.dto.DriveChangesResponse
import com.luminagallery.drive.data.remote.dto.DriveFileDto
import com.luminagallery.drive.data.remote.dto.DriveFileListResponse
import com.luminagallery.drive.data.remote.dto.StartPageTokenResponse
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

interface DriveApiService {

    @GET("drive/v3/files")
    suspend fun listFiles(
        @Query("q") query: String,
        @Query("fields") fields: String = DriveConstants.FILES_LIST_FIELDS,
        @Query("pageSize") pageSize: Int = DriveConstants.DEFAULT_PAGE_SIZE,
        @Query("pageToken") pageToken: String? = null,
        @Query("orderBy") orderBy: String? = "folder,createdTime desc",
        @Query("spaces") spaces: String = "drive",
        @Query("supportsAllDrives") supportsAllDrives: Boolean = true,
        @Query("includeItemsFromAllDrives") includeItemsFromAllDrives: Boolean = true
    ): DriveFileListResponse

    @GET("drive/v3/files/{fileId}")
    suspend fun getFile(
        @Path("fileId") fileId: String,
        @Query("fields") fields: String = DriveConstants.SINGLE_FILE_FIELDS,
        @Query("supportsAllDrives") supportsAllDrives: Boolean = true
    ): DriveFileDto

    @GET("drive/v3/changes/startPageToken")
    suspend fun getStartPageToken(
        @Query("supportsAllDrives") supportsAllDrives: Boolean = true
    ): StartPageTokenResponse

    @GET("drive/v3/changes")
    suspend fun listChanges(
        @Query("pageToken") pageToken: String,
        @Query("fields") fields: String = DriveConstants.CHANGES_LIST_FIELDS,
        @Query("pageSize") pageSize: Int = DriveConstants.DEFAULT_PAGE_SIZE,
        @Query("includeRemoved") includeRemoved: Boolean = true,
        @Query("spaces") spaces: String = "drive",
        @Query("supportsAllDrives") supportsAllDrives: Boolean = true,
        @Query("includeItemsFromAllDrives") includeItemsFromAllDrives: Boolean = true
    ): DriveChangesResponse

    @Streaming
    @GET("drive/v3/files/{fileId}?alt=media")
    suspend fun downloadFile(
        @Path("fileId") fileId: String,
        @Query("supportsAllDrives") supportsAllDrives: Boolean = true
    ): ResponseBody

    @GET("drive/v3/about")
    suspend fun getAbout(
        @Query("fields") fields: String = "user(displayName,emailAddress)"
    ): DriveAboutResponse
}

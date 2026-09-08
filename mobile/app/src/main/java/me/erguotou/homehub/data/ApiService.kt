package me.erguotou.homehub.data

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

interface ApiService {

    // ── health ──
    @GET("api/health")
    suspend fun health(): Health

    // ── album views ──
    @GET("api/photos/timeline")
    suspend fun timeline(
        @Query("group") group: String = "month",
        @Query("kind") kind: String? = null,
        @Query("per_group") perGroup: Int? = null
    ): TimelineResponse

    @GET("api/photos/tree")
    suspend fun tree(@Query("dir_id") dirId: Long? = null): TreeResponse

    @GET("api/photos/tags")
    suspend fun tags(): TagsResponse

    @GET("api/photos/people")
    suspend fun people(): PeopleResponse

    @GET("api/photos/geo")
    suspend fun geo(@Query("precision") precision: Double? = null): GeoResponse

    @GET("api/photos/list")
    suspend fun list(
        @Query("dir_id") dirId: Long? = null,
        @Query("tag") tag: String? = null,
        @Query("person_id") personId: Long? = null,
        @Query("from") from: Long? = null,
        @Query("to") to: Long? = null,
        @Query("has_gps") hasGps: Boolean? = null,
        @Query("kind") kind: String? = null,
        @Query("ids") ids: String? = null,
        @Query("limit") limit: Int = 300,
        @Query("offset") offset: Int = 0
    ): PhotoListResponse

    @GET("api/photos/{id}")
    suspend fun photoDetail(@Path("id") id: Long): PhotoDetail

    @POST("api/photos/{id}/rotate")
    suspend fun rotate(@Path("id") id: Long, @Body body: RotateRequest): RotateResponse

    @POST("api/photos/people/{id}/rename")
    suspend fun renamePerson(@Path("id") id: Long, @Body body: Map<String, String>): SimpleResponse

    // ── search ──
    @GET("api/search")
    suspend fun search(
        @Query("q") q: String,
        @Query("type") type: String? = null,
        @Query("limit") limit: Int = 100
    ): SearchResponse

    // ── duplicates ──
    @GET("api/duplicates")
    suspend fun duplicates(): DuplicatesResponse

    // ── NAS files ──
    @GET("api/dirs")
    suspend fun dirs(): ListDirsResponse

    @GET("api/files/{dir}")
    suspend fun listRoot(
        @Path("dir") dir: String,
        @Query("sort") sort: String? = null,
        @Query("desc") desc: Boolean? = null
    ): ListFilesResponse

    @GET("api/files/{dir}/{path}")
    suspend fun listPath(
        @Path("dir") dir: String,
        @Path("path", encoded = true) path: String,
        @Query("sort") sort: String? = null,
        @Query("desc") desc: Boolean? = null
    ): ListFilesResponse

    @Streaming
    @GET("api/files/{dir}/{path}")
    suspend fun download(
        @Path("dir") dir: String,
        @Path("path", encoded = true) path: String
    ): Response<ResponseBody>

    @Multipart
    @POST("api/files/{dir}")
    suspend fun uploadRoot(
        @Path("dir") dir: String,
        @Part file: MultipartBody.Part
    ): UploadResponse

    @Multipart
    @POST("api/files/{dir}/{path}")
    suspend fun uploadPath(
        @Path("dir") dir: String,
        @Path("path", encoded = true) path: String,
        @Part file: MultipartBody.Part
    ): UploadResponse

    @POST("api/mkdir/{dir}")
    suspend fun mkdirRoot(@Path("dir") dir: String, @Body body: RenameRequest): SimpleResponse

    @POST("api/mkdir/{dir}/{path}")
    suspend fun mkdir(
        @Path("dir") dir: String,
        @Path("path", encoded = true) path: String,
        @Body body: RenameRequest
    ): SimpleResponse

    @PATCH("api/files/{dir}/{path}")
    suspend fun rename(
        @Path("dir") dir: String,
        @Path("path", encoded = true) path: String,
        @Body body: RenameRequest
    ): SimpleResponse

    @PATCH("api/files/{dir}/{path}")
    suspend fun copyOrMove(
        @Path("dir") dir: String,
        @Path("path", encoded = true) path: String,
        @Body body: CopyMoveRequest
    ): SimpleResponse

    @DELETE("api/files/{dir}/{path}")
    suspend fun delete(
        @Path("dir") dir: String,
        @Path("path", encoded = true) path: String
    ): SimpleResponse

    @GET("api/documents/{dir}/{path}")
    suspend fun readDocument(
        @Path("dir") dir: String,
        @Path("path", encoded = true) path: String
    ): DocumentResponse

    @PUT("api/documents/{dir}/{path}")
    suspend fun writeDocument(
        @Path("dir") dir: String,
        @Path("path", encoded = true) path: String,
        @Body body: Map<String, String>
    ): SimpleResponse

    // ── resumable chunked upload ──
    @GET("api/upload/offset")
    suspend fun uploadOffset(
        @Query("dir") dir: String,
        @Query("path") path: String,
        @Query("name") name: String
    ): UploadOffsetResponse

    @POST("api/upload/chunk")
    suspend fun uploadChunk(
        @Query("dir") dir: String,
        @Query("path") path: String,
        @Query("name") name: String,
        @Query("offset") offset: Long,
        @Query("total") total: Long,
        @Body body: RequestBody
    ): SimpleResponse

    @POST("api/upload/complete")
    suspend fun uploadComplete(@Body body: UploadCompleteRequest): UploadResponse

    // ── trash ──
    @GET("api/trash")
    suspend fun trash(): TrashResponse

    @GET("api/trash/stats")
    suspend fun trashStats(): TrashStats

    @POST("api/trash/{id}/restore")
    suspend fun restoreTrash(@Path("id") id: Long): SimpleResponse

    @DELETE("api/trash/{id}")
    suspend fun purgeTrash(@Path("id") id: Long): SimpleResponse

    @DELETE("api/trash/empty")
    suspend fun emptyTrash(): SimpleResponse

    // ── monitoring placeholder ──
    @GET("api/monitor/config")
    suspend fun monitorConfig(): MonitorConfig

    @PUT("api/monitor/config")
    suspend fun saveMonitorConfig(@Body body: Map<String, String>): SimpleResponse

    companion object {
        /** Build a multipart part for a whole file (small uploads / resumable chunks). */
        fun filePart(name: String, fileName: String, body: RequestBody): MultipartBody.Part =
            MultipartBody.Part.createFormData(name, fileName, body)
    }
}

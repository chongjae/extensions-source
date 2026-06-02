package eu.kanade.tachiyomi.extension.ko.kakaowebtoon

import android.webkit.CookieManager
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.UUID

class KakaoWebtoon : HttpSource() {

    override val name = "Kakao Webtoon"
    override val lang = "ko"
    override val baseUrl = "https://webtoon.kakao.com"
    override val supportsLatest = true

    private val apiUrl = "https://gateway-kw.kakao.com"
    private val authUrl = "$apiUrl/auth/v1"

    // Kakao app ID for webtoon (production), derived from decrypted encryptedVariables
    private val webAppId = "48432be89b3a9cc4b1984569f22ed2cb"

    // Cached Kakao user ID (Long as string) for image decryption
    @Volatile
    private var cachedUserId: String? = null

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor(ImageInterceptor)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)

    private fun apiHeaders() = headersBuilder()
        .set("Accept", "application/json")
        .build()

    // ─── Popular ─────────────────────────────────────────────────────────────

    override fun popularMangaRequest(page: Int): Request = GET("$apiUrl/section/v4/sections?placement=rank_all", apiHeaders())

    override fun popularMangaParse(response: Response): MangasPage {
        val result = response.parseAs<SectionResponse>()
        val mangas = result.data
            .flatMap { it.cardGroups }
            .flatMap { it.cards }
            .mapNotNull { it.content?.toSManga() }
        return MangasPage(mangas, false)
    }

    // ─── Latest ──────────────────────────────────────────────────────────────

    override fun latestUpdatesRequest(page: Int): Request = GET("$apiUrl/section/v4/sections?placement=timetable_new", apiHeaders())

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ─── Search ──────────────────────────────────────────────────────────────

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val offset = (page - 1) * PAGE_SIZE
        val url = "$apiUrl/search/v2/content".toHttpUrl().newBuilder()
            .addQueryParameter("word", query.ifBlank { "웹툰" })
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("offset", offset.toString())
            .build()
        return GET(url.toString(), apiHeaders())
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val result = response.parseAs<SearchResponse>()
        val mangas = result.data?.content?.map { it.toSManga() } ?: emptyList()
        val hasMore = result.meta?.pagination?.last?.not() ?: false
        return MangasPage(mangas, hasMore)
    }

    // ─── Manga Detail ─────────────────────────────────────────────────────────

    override fun mangaDetailsRequest(manga: SManga): Request {
        val id = manga.url.trimStart('/')
        return GET("$apiUrl/decorator/v2/decorator/contents/$id", apiHeaders())
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val result = response.parseAs<ContentDetailResponse>()
        val detail = result.data ?: return SManga.create()
        return detail.toSManga().apply {
            url = "/${response.request.url.pathSegments.last()}"
        }
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/content/webtoon/${manga.url.trimStart('/')}"

    // ─── Chapter List ─────────────────────────────────────────────────────────

    override fun chapterListRequest(manga: SManga): Request {
        val id = manga.url.trimStart('/')
        return GET(
            "$apiUrl/episode/v2/views/content-home/contents/$id/episodes?sort=NO",
            apiHeaders(),
        )
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val result = response.parseAs<EpisodeListResponse>()
        val episodes = result.data?.episodes ?: return emptyList()
        val contentId = response.request.url.pathSegments
            .dropWhile { it != "contents" }.drop(1)
            .firstOrNull()?.toIntOrNull() ?: 0

        return episodes
            .filter { it.readable || it.isWaitForFree }
            .map { it.toSChapter(contentId) }
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val contentId = chapter.url.trimStart('/').substringBefore('/')
        return "$baseUrl/viewer/webtoon/$contentId"
    }

    // ─── Pages (images) ──────────────────────────────────────────────────────

    override fun pageListRequest(chapter: SChapter): Request {
        val urlParts = chapter.url.trimStart('/').split('/')
        val episodeId = urlParts[1]
        val isLockedWaitForFree = urlParts.getOrNull(2) == "w"

        if (isLockedWaitForFree) {
            useWaitForFreeTicket(episodeId.toLong())
        }

        val nonce = UUID.randomUUID().toString().replace("-", "").take(16)
        val timestamp = System.currentTimeMillis().toString()

        val requestBody =
            """{"id":"$episodeId","type":"AES_CBC_WEBP","nonce":"$nonce","timestamp":"$timestamp","download":false,"webAppId":"$webAppId"}"""
                .toRequestBody("application/json".toMediaType())

        // Embed nonce and timestamp in URL query so pageListParse can retrieve them
        val url = "$apiUrl/episode/v1/views/viewer/episodes/$episodeId/media-resources" +
            "?_nonce=$nonce&_ts=$timestamp"

        return POST(url, apiHeaders(), requestBody)
    }

    /**
     * Calls the Kakao Webtoon "pass" API to consume a 기다무 (wait-for-free) ticket for the
     * given episode. Only called when the user actively opens a locked waitForFree chapter.
     *
     * The API is idempotent: if the episode is already accessible (e.g., wait period elapsed or
     * ticket already used), the server returns alreadyRented=true and no ticket is consumed.
     *
     * Endpoint: POST /episode/v3/episodes/{id}/pass
     * ticketType value is the lodash snakeCase of "waitForFree" → "wait_for_free"
     */
    private fun useWaitForFreeTicket(episodeId: Long) {
        runCatching {
            val body = """{"readAgain":false,"ticketType":"wait_for_free"}"""
                .toRequestBody("application/json".toMediaType())
            client.newCall(
                POST("$apiUrl/episode/v3/episodes/$episodeId/pass", apiHeaders(), body),
            ).execute().close()
        }
    }

    override fun pageListParse(response: Response): List<Page> {
        val requestUrl = response.request.url
        val nonce = requestUrl.queryParameter("_nonce") ?: ""
        val timestamp = requestUrl.queryParameter("_ts") ?: ""
        val episodeId = requestUrl.pathSegments
            .dropWhile { it != "episodes" }.drop(1)
            .firstOrNull()?.toLongOrNull() ?: 0L

        val result = response.parseAs<MediaResourcesResponse>()
        val media = result.data?.media ?: return emptyList()

        // Fetch the Kakao user ID from the atn session cookie.
        // The atn cookie is set at .kakao.com after login and is needed to derive the correct
        // AES master key: masterKey = SHA-256(userId + episodeId + timestamp).
        val userId = getOrFetchUserId()

        val keys = ImageInterceptor.deriveImageKeys(
            userId = userId,
            episodeId = episodeId,
            nonce = nonce,
            timestamp = timestamp,
            aid = media.aid,
            zid = media.zid,
        )

        return media.files.mapIndexed { index, file ->
            if (keys != null) {
                val (imageKey, imageIV) = keys
                val keyHex = imageKey.joinToString("") { "%02x".format(it) }
                val ivHex = imageIV.joinToString("") { "%02x".format(it) }
                // Append decryption params as URL fragment; ImageInterceptor reads them
                Page(index, imageUrl = "${file.url}#key=$keyHex&iv=$ivHex")
            } else {
                Page(index, imageUrl = file.url)
            }
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun getFilterList() = FilterList()

    // ─── Auth helpers ─────────────────────────────────────────────────────────

    /**
     * Reads the `atn` access token from the Android CookieManager (set during WebView login),
     * calls the Kakao auth user API to obtain the numeric user ID, and caches it.
     *
     * Returns null if the user is not logged in or the call fails.
     */
    private fun getOrFetchUserId(): String? {
        cachedUserId?.let { return it }

        val atn = readAtnCookie() ?: return null

        return runCatching {
            val userResponse = client.newCall(
                GET("$authUrl/auth/user?access_token=$atn", apiHeaders()),
            ).execute()
            val userId = userResponse.parseAs<UserResponse>().data?.id?.toString()
            cachedUserId = userId
            userId
        }.getOrNull()
    }

    /**
     * Reads the `atn` access-token cookie from the Android system CookieManager.
     * The cookie is named "atn" and is stored at domain ".kakao.com" after login.
     */
    private fun readAtnCookie(): String? {
        val cookies = runCatching {
            CookieManager.getInstance().getCookie("https://webtoon.kakao.com")
        }.getOrNull() ?: return null

        return cookies.split(";")
            .map { it.trim() }
            .find { it.startsWith("atn=") }
            ?.substringAfter("atn=")
            ?.takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val PAGE_SIZE = 20
    }
}

package eu.kanade.tachiyomi.extension.ko.manatoki

import android.content.SharedPreferences
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.tryParse
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

class Manatoki :
    HttpSource(),
    ConfigurableSource {

    override val name = "뉴토끼"
    override val baseUrl by lazy { getPrefBaseUrl() }
    override val lang = "ko"
    override val supportsLatest = true
    override val versionId = 3

    private val preferences: SharedPreferences by getPreferencesLazy()

    private fun getPrefBaseUrl(): String = preferences.getString(PREF_BASE_URL, DEFAULT_BASE_URL)!!

    private val flareSolverrUrl: String
        get() = preferences.getString(PREF_FLARESOLVERR_URL, "")!!.trim().trimEnd('/')

    // When FlareSolverr URL is configured, requests are proxied through it to bypass Cloudflare.
    // Falls back to the built-in cloudflareClient bypass otherwise.
    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimit(2)
        .addInterceptor(::flareSolverrInterceptor)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    private val chapterDateFormat by lazy { SimpleDateFormat("yy.MM.dd", Locale.ROOT) }

    private fun flareSolverrInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val fsUrl = flareSolverrUrl
        if (fsUrl.isBlank()) return chain.proceed(request)

        val body = """{"cmd":"request.get","url":"${request.url}","maxTimeout":60000}"""
        val fsRequest = Request.Builder()
            .url("$fsUrl/v1")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            val fsRaw = network.client.newCall(fsRequest).execute()
            val json = org.json.JSONObject(fsRaw.body.string())
            if (json.optString("status") == "ok") {
                val solution = json.getJSONObject("solution")
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(solution.optInt("status", 200))
                    .message("OK")
                    .body(
                        solution.getString("response")
                            .toResponseBody("text/html; charset=utf-8".toMediaType()),
                    )
                    .build()
            } else {
                chain.proceed(request)
            }
        } catch (e: Exception) {
            chain.proceed(request)
        }
    }

    // ============================== Popular ==============================
    // Curated manhwa landing. URL pagination doesn't work (JS-based) → single page only.
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/manhwa", headers)

    override fun popularMangaParse(response: Response): MangasPage = MangasPage(parseCards(response.asJsoup()), hasNextPage = false)

    // ============================== Latest ===============================
    // Ongoing webtoon feed. Same single-page constraint.
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/ing", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = MangasPage(parseCards(response.asJsoup()), hasNextPage = false)

    // ============================== Search ===============================
    // /search?q={query}&page=N — confirmed paginated.
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val q = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/search?q=$q&page=$page", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val mangas = parseCards(response.asJsoup())
        return MangasPage(mangas, hasNextPage = mangas.isNotEmpty())
    }

    // ============================== Details ==============================

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        return SManga.create().apply {
            title = doc.selectFirst("h1.hero-v2-title")?.text()
                ?: throw Exception("Title not found")
            thumbnail_url = doc.selectFirst("div.hero-v2-thumb img:not(.platform-icon)")
                ?.attr("abs:src")
            author = doc.select("div.hero-v2-author a")
                .joinToString(", ") { it.text() }
                .ifBlank { doc.selectFirst("div.hero-v2-author")?.text() }
            genre = doc.select("a.hero-v2-tag")
                .joinToString(", ") { it.text().removePrefix("#") }
                .ifBlank { null }
            description = doc.selectFirst("p.hero-v2-desc")?.text()
            status = parseStatus(doc.selectFirst("span.pill-status"))
            initialized = true
        }
    }

    private fun parseStatus(el: Element?): Int = when {
        el == null -> SManga.UNKNOWN
        el.hasClass("ongoing") -> SManga.ONGOING
        el.hasClass("end") -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    // ============================= Chapters ==============================

    override fun chapterListParse(response: Response): List<SChapter> {
        val elements = response.asJsoup().select("ul.ep-list-v2 li.ep-row-v2")
        val total = elements.size
        return elements.mapIndexed { index, el ->
            SChapter.create().apply {
                val a = el.selectFirst("a.ep-row-v2-link")
                    ?: throw Exception("Chapter URL not found")
                setUrlWithoutDomain(a.attr("abs:href"))
                val num = el.selectFirst("span.ep-row-v2-no")?.text()?.toIntOrNull()
                val rawTitle = el.selectFirst(".ep-row-v2-title strong")?.text()
                name = when {
                    !rawTitle.isNullOrBlank() -> rawTitle
                    num != null -> "Chapter $num"
                    else -> "Chapter ${total - index}"
                }
                chapter_number = num?.toFloat() ?: (total - index).toFloat()
                date_upload = chapterDateFormat.tryParse(
                    el.selectFirst("span.ep-row-v2-date")?.text(),
                )
            }
        }
    }

    // =============================== Pages ===============================
    // sbxh1 viewer: direct <img src> inside .vw-imgs — no base64 encoding.

    override fun pageListParse(response: Response): List<Page> = response.asJsoup().select("div.vw-imgs img").mapIndexed { i, img ->
        val src = img.attr("abs:src").ifBlank { img.attr("abs:data-src") }
        Page(i, imageUrl = src)
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        androidx.preference.EditTextPreference(screen.context).apply {
            key = PREF_BASE_URL
            title = "사이트 URL"
            summary = "도메인이 바뀌었을 때 수정 (앱 재시작 필요). 기본값: $DEFAULT_BASE_URL"
            setDefaultValue(DEFAULT_BASE_URL)
            dialogTitle = "사이트 URL 변경"
            dialogMessage = "기본값: $DEFAULT_BASE_URL"
        }.also { screen.addPreference(it) }

        androidx.preference.EditTextPreference(screen.context).apply {
            key = PREF_FLARESOLVERR_URL
            title = "FlareSolverr URL"
            summary = "Cloudflare 우회용 FlareSolverr 주소 (예: https://flaresolver.example.com). 비워두면 앱 내장 우회 방식 사용"
            setDefaultValue("")
            dialogTitle = "FlareSolverr URL"
        }.also { screen.addPreference(it) }
    }

    // ============================== Helpers ==============================

    private fun parseCards(doc: Document): List<SManga> = doc.select("div.card-grid a.card").map { card ->
        SManga.create().apply {
            setUrlWithoutDomain(card.attr("abs:href"))
            title = card.selectFirst("p.subject")?.text()
                ?: card.select(".thumb img").last()?.attr("alt")
                ?: throw Exception("Manga title not found")
            thumbnail_url = card.selectFirst(".thumb img:not(.platform-icon)")
                ?.attr("abs:src")
        }
    }

    companion object {
        private const val DEFAULT_BASE_URL = "https://sbxh1.com"
        private const val PREF_BASE_URL = "pref_base_url"
        private const val PREF_FLARESOLVERR_URL = "pref_flaresolverr_url"
    }
}

package eu.kanade.tachiyomi.extension.ko.kakaowebtoon

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─── Section / Ranking / Timetable ───────────────────────────────────────────

@Serializable
data class SectionResponse(val data: List<SectionItem> = emptyList())

@Serializable
data class SectionItem(
    val cardGroups: List<CardGroup> = emptyList(),
)

@Serializable
data class CardGroup(val cards: List<Card> = emptyList())

@Serializable
data class Card(val content: ContentSummary? = null)

@Serializable
data class ContentSummary(
    val id: Int = 0,
    val title: String = "",
    val seoId: String = "",
    val genre: String? = null,
    val adult: Boolean = false,
    val featuredCharacterImageA: String? = null,
    val backgroundImage: String? = null,
    val authors: List<AuthorItem> = emptyList(),
) {
    fun toSManga(): SManga = SManga.create().apply {
        url = "/$id"
        title = this@ContentSummary.title
        thumbnail_url = featuredCharacterImageA ?: backgroundImage
        author = authors.filter { it.type == "AUTHOR" }.joinToString { it.name }
        genre = this@ContentSummary.genre
        status = SManga.UNKNOWN
    }
}

// ─── Search ──────────────────────────────────────────────────────────────────

@Serializable
data class SearchResponse(
    val data: SearchData? = null,
    val meta: SearchMeta? = null,
)

@Serializable
data class SearchData(val content: List<ContentSummary> = emptyList())

@Serializable
data class SearchMeta(val pagination: Pagination? = null)

@Serializable
data class Pagination(
    val offset: Int = 0,
    val limit: Int = 20,
    val totalCount: Int = 0,
    val last: Boolean = true,
)

// ─── Content Detail ───────────────────────────────────────────────────────────

@Serializable
data class ContentDetailResponse(val data: ContentDetail? = null)

@Serializable
data class ContentDetail(
    val id: Int = 0,
    val title: String = "",
    val synopsis: String? = null,
    val genre: String? = null,
    val adult: Boolean = false,
    val status: String? = null,
    val thumbnailImage: String? = null,
    val featuredCharacterImageA: String? = null,
    val authors: List<AuthorItem> = emptyList(),
    val badges: List<BadgeItem> = emptyList(),
    val webtoonType: String? = null,
) {
    fun toSManga(): SManga = SManga.create().apply {
        title = this@ContentDetail.title
        description = synopsis
        thumbnail_url = thumbnailImage ?: featuredCharacterImageA
        author = authors.filter { it.type == "AUTHOR" }.joinToString { it.name }
        artist = authors.filter { it.type == "ILLUSTRATOR" }.joinToString { it.name }
        genre = this@ContentDetail.genre
        status = when (this@ContentDetail.status) {
            "SELLING" -> {
                if (badges.any { it.title == "EPISODES_PUBLISHING" }) {
                    SManga.ONGOING
                } else {
                    SManga.UNKNOWN
                }
            }
            "COMPLETED" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }
}

@Serializable
data class AuthorItem(
    val name: String = "",
    val type: String = "",
    val order: Int = 0,
)

@Serializable
data class BadgeItem(
    val title: String = "",
    val type: String = "",
)

// ─── Episode List ─────────────────────────────────────────────────────────────

@Serializable
data class EpisodeListResponse(val data: EpisodeListData? = null)

@Serializable
data class EpisodeListData(
    val episodes: List<EpisodeItem> = emptyList(),
    val totalCount: Int? = null,
    @SerialName("responseDateTime") val responseDateTime: String? = null,
)

@Serializable
data class EpisodeItem(
    val id: Long = 0,
    @SerialName("no") val episodeNo: Int = 0,
    val title: String = "",
    val readable: Boolean = false,
    @SerialName("seoId") val seoId: String? = null,
    val asset: EpisodeAsset? = null,
    val contentId: Int = 0,
    @SerialName("useType") val useType: String? = null,
    val readDateTime: String? = null,
) {
    fun toSChapter(contentId: Int): SChapter = SChapter.create().apply {
        url = "/$contentId/${this@EpisodeItem.id}"
        name = if (title.isNotBlank()) title else "화 $episodeNo"
        chapter_number = episodeNo.toFloat()
        scanlator = when (useType) {
            "rental", "pay" -> "유료"
            "waitForFree" -> "기다무"
            "freePublishing" -> "연재무료"
            else -> null
        }
    }
}

@Serializable
data class EpisodeAsset(
    val thumbnailImage: String? = null,
)

// ─── Viewer (image resources) ─────────────────────────────────────────────────

@Serializable
data class MediaResourcesResponse(val data: MediaResourcesData? = null)

@Serializable
data class MediaResourcesData(val media: MediaInfo? = null)

@Serializable
data class MediaInfo(
    val files: List<MediaFile> = emptyList(),
    @SerialName("aid") val aid: String = "",
    @SerialName("zid") val zid: String = "",
    val codec: String? = null,
    val container: String? = null,
)

@Serializable
data class MediaFile(
    val url: String = "",
    val width: Int = 0,
    val height: Int = 0,
)

// ─── User Profile ─────────────────────────────────────────────────────────────

@Serializable
data class UserResponse(val data: UserData? = null)

@Serializable
data class UserData(val id: Long? = null)

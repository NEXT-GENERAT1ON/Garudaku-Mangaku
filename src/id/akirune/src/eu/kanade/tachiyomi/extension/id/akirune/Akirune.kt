package eu.kanade.tachiyomi.extension.id.akirune

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Akirune (akirune.my.id) runs on Blogger with the "ZeistManga" template.
 *
 * IMPORTANT — read before opening an issue if this misbehaves:
 * The site's own browse/popular grid is filled in by client-side JavaScript
 * that (based on how this template works elsewhere) calls Blogger's own
 * public JSON post feed. This source calls that same feed API directly,
 * since a Jsoup-based source doesn't run JavaScript. That part (Blogger's
 * feed shape) is stable, documented platform behaviour, not a guess.
 *
 * What IS still a best-effort guess, not confirmed against Akirune itself,
 * because its individual pages aren't reachable from here (not indexed
 * anywhere search could find, and the site returned an empty grid on a
 * plain HTTP fetch with no way to inspect a real chapter/manga page):
 *   - chapterListRequest(): assumes each manga's chapters are tagged with a
 *     Blogger label equal to the manga's own title. If chapterList comes
 *     back empty, this is the first thing to check.
 *   - pageListParse(): assumes chapter images sit inside Blogger's standard
 *     ".post-body" block. If pages come back empty, this is the next thing
 *     to check — open a chapter in a browser, view source, and adjust the
 *     selector to match.
 *   - mangaDetailsParse()'s selectors (h1 / .post-body p / label links) were
 *     confirmed on a different site using the same template family, not on
 *     Akirune's exact current theme version, so they may need small tweaks.
 *
 * Dropped from the old Active.kt: the locked/VIP-chapter regex logic. That
 * was specific to active.biz.id's own paywall markup and has no reason to
 * carry over to a different site.
 */
@Source
abstract class Akirune : HttpSource() {

    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true }

    // ---------------------------------------------------------------------
    // Popular / Latest
    //
    // Blogger's feed API has no native "sort by popularity", so both of
    // these hit the same "most recent posts" feed — there's no reliable way
    // to get a true popularity ranking without running the site's own JS.
    // ---------------------------------------------------------------------

    override fun popularMangaRequest(page: Int): Request = feedRequest(page)

    override fun latestUpdatesRequest(page: Int): Request = feedRequest(page)

    private fun feedRequest(page: Int): Request {
        val startIndex = (page - 1) * 20 + 1
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("feeds")
            .addPathSegment("posts")
            .addPathSegment("default")
            .addQueryParameter("alt", "json")
            .addQueryParameter("max-results", "20")
            .addQueryParameter("start-index", startIndex.toString())
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage = parseFeed(response)

    override fun latestUpdatesParse(response: Response): MangasPage = parseFeed(response)

    private fun parseFeed(response: Response): MangasPage {
        val feed = json.decodeFromString<BloggerFeed>(response.body.string())
        val entries = feed.feed.entry.orEmpty()

        // Manga-info posts and individual chapter posts are both just
        // "posts" in Blogger's flat model. Chapter titles reliably contain
        // the word "chapter" on this site (e.g. "Houseki no Kuni Chapter
        // 90"), so use that to keep only series entries in this listing.
        val mangas = entries
            .filterNot { it.title.t.contains("chapter", ignoreCase = true) }
            .map { it.toSManga() }

        return MangasPage(mangas, entries.size >= 20)
    }

    // ---------------------------------------------------------------------
    // Search
    // ---------------------------------------------------------------------

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val startIndex = (page - 1) * 20 + 1
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("feeds")
            .addPathSegment("posts")
            .addPathSegment("default")
            .addQueryParameter("alt", "json")
            .addQueryParameter("max-results", "20")
            .addQueryParameter("start-index", startIndex.toString())
            .addQueryParameter("q", query)
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseFeed(response)

    // ---------------------------------------------------------------------
    // Manga details — parsed from the post's own HTML page (plain
    // server-rendered content, unlike the JS-driven homepage grid).
    // ---------------------------------------------------------------------

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val labelTexts = document.select("a[href*=/search/label/]").map { it.text() }
        val metaLabels = setOf(
            "Manga", "Manhwa", "Manhua", "Novel",
            "Ongoing", "Completed", "Dropped", "Upcoming",
        )

        return SManga.create().apply {
            title = document.selectFirst("h1")?.text().orEmpty()
            thumbnail_url = document.selectFirst(".post-body img, article img")
                ?.attr("abs:src")
            description = document.select(".post-body p")
                .map { it.text() }
                .filterNot { it.isBlank() }
                .joinToString("\n\n")
            genre = labelTexts.filterNot { it in metaLabels }.distinct().joinToString(", ")
            status = when {
                "Ongoing" in labelTexts -> SManga.ONGOING
                "Completed" in labelTexts -> SManga.COMPLETED
                "Dropped" in labelTexts -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
        }
    }

    // ---------------------------------------------------------------------
    // Chapter list — see the class-level note: this queries the feed API
    // for posts labeled with the manga's own title, on the theory that
    // that's how this template links chapters back to their series.
    // ---------------------------------------------------------------------

    override fun chapterListRequest(manga: SManga): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("feeds")
            .addPathSegment("posts")
            .addPathSegment("default")
            .addPathSegment("-")
            .addPathSegment(manga.title)
            .addQueryParameter("alt", "json")
            .addQueryParameter("max-results", "200")
            .build()
        return GET(url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val feed = json.decodeFromString<BloggerFeed>(response.body.string())
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZZZZZ", Locale.US)

        return feed.feed.entry.orEmpty().map { entry ->
            SChapter.create().apply {
                name = entry.title.t
                url = entry.link.firstOrNull { it.rel == "alternate" }?.href.orEmpty()
                date_upload = runCatching { dateFormat.parse(entry.published.t)?.time }
                    .getOrNull() ?: 0L
            }
        }.sortedByDescending { it.date_upload }
    }

    // ---------------------------------------------------------------------
    // Pages — least-verified part of this source, see class-level note.
    // ---------------------------------------------------------------------

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        return document.select(".post-body img").mapIndexed { index, element ->
            val src = element.attr("abs:data-src").ifBlank { element.attr("abs:src") }
            Page(index, imageUrl = src)
        }
    }

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException("Not used — image URLs come from pageListParse directly")

    // ---------------------------------------------------------------------
    // Blogger's own JSON feed shape (GData-style). This part is documented,
    // stable platform behaviour, independent of the ZeistManga template.
    // ---------------------------------------------------------------------

    @Serializable
    private data class BloggerFeed(val feed: FeedBody)

    @Serializable
    private data class FeedBody(val entry: List<FeedEntry>? = null)

    @Serializable
    private data class FeedEntry(
        val title: TextField,
        val published: TextField,
        val link: List<FeedLink> = emptyList(),
        @SerialName("media\$thumbnail") val thumbnail: Thumbnail? = null,
    ) {
        fun toSManga(): SManga = SManga.create().apply {
            title = this@FeedEntry.title.t
            url = this@FeedEntry.link.firstOrNull { it.rel == "alternate" }?.href.orEmpty()
            thumbnail_url = this@FeedEntry.thumbnail?.url
        }
    }

    @Serializable
    private data class TextField(@SerialName("\$t") val t: String)

    @Serializable
    private data class FeedLink(val rel: String, val href: String)

    @Serializable
    private data class Thumbnail(val url: String)
}

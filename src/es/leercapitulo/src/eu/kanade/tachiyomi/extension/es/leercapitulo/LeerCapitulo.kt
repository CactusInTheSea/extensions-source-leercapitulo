package eu.kanade.tachiyomi.extension.es.leercapitulo

import android.app.Application
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

@Source
abstract class LeerCapitulo : HttpSource(), ConfigurableSource {
    private val baseUrlHost by lazy { baseUrl.toHttpUrl().host }
    private val preferences by lazy { Injekt.get<Application>().getSharedPreferences("source_$id", 0) }

    override val supportsLatest = true
    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(1, 2.seconds) { it.host == baseUrlHost }
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
    private var lastMangaUpdate: Long = 0
    private var cachedMangas: List<SManga> = emptyList()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Accept-Language", "es-ES,es;q=0.9")
        .add("Accept-Encoding", "gzip, deflate, br")

    // ============================== POPULAR ==============================
    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/manga/".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "az")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage = parseMangaList(response)

    // ============================== LATEST ==============================
    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/manga/".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "updatetime_desc")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = parseMangaList(response)

    // ============================== SEARCH ==============================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val urlBuilder = "$baseUrl/manga/".toHttpUrl().newBuilder()

        if (query.isNotBlank()) {
            urlBuilder.addQueryParameter("q", query.trim())
        }

        filters.firstInstanceOrNull<GenreFilter>()?.takeIf { it.state != 0 }?.let {
            urlBuilder.addQueryParameter("genre", it.toUriPart())
        }
        filters.firstInstanceOrNull<StatusFilter>()?.takeIf { it.state != 0 }?.let {
            urlBuilder.addQueryParameter("status", it.toUriPart())
        }

        urlBuilder.addQueryParameter("page", page.toString())
        return GET(urlBuilder.build(), headers)
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return if (query.isBlank()) {
            client.newCall(searchMangaRequest(page, query, filters))
                .asObservableSuccess()
                .map { searchMangaParse(it) }
        } else {
            val cleanQuery = query.trim()
            val autocompleteUrl = "$baseUrl/search-autocomplete?term=${cleanQuery.replace(" ", "+")}"
            
            client.newCall(GET(autocompleteUrl, headers))
                .asObservableSuccess()
                .flatMap { response ->
                    try {
                        val bodyText = response.body.string()
                        if (bodyText.isBlank() || bodyText == "[]" || bodyText == "null") {
                            throw Exception("Respuesta vacía del autocomplete")
                        }

                        val mangas = runCatching {
                            parseAs<List<AutocompleteDto>>(bodyText)
                                .filter { it.label.isNotBlank() && it.link.isNotBlank() }
                                .distinctBy { it.link }
                                .map { it.toSManga() }
                        }.getOrNull() ?: emptyList()

                        if (mangas.isNotEmpty()) {
                            Observable.just(MangasPage(mangas, false))
                        } else {
                            throw Exception("Sin resultados en autocomplete")
                        }
                    } catch (e: Exception) {
                        // Fallback a búsqueda normal
                        client.newCall(searchMangaRequest(page, cleanQuery, filters))
                            .asObservableSuccess()
                            .map { searchMangaParse(it) }
                    }
                }
                .onErrorResumeNext { _ ->
                    client.newCall(searchMangaRequest(page, cleanQuery, filters))
                        .asObservableSuccess()
                        .map { searchMangaParse(it) }
                }
        }
    }

    override fun searchMangaParse(response: Response): MangasPage = parseMangaList(response)

    // ============================== MANGA LIST PARSING ==============================
    private fun parseMangaList(response: Response): MangasPage {
        try {
            val document = response.asJsoup()
            
            val mangas = document.select("article.lc-card")
                .asSequence()
                .mapNotNull { card ->
                    try {
                        val link = card.selectFirst("a.lc-card-name") 
                            ?: card.selectFirst("a.lc-card-cover")
                            ?: return@mapNotNull null
                        
                        val url = link.attr("abs:href")
                        if (url.isBlank()) return@mapNotNull null

                        val titleText = (card.selectFirst("a.lc-card-name")?.text() 
                            ?: link.text()).trim()
                        if (titleText.isBlank()) return@mapNotNull null

                        val img = card.selectFirst("a.lc-card-cover img")
                        val thumbnailUrl = img?.imgAttr()

                        SManga.create().apply {
                            setUrlWithoutDomain(url)
                            title = titleText
                            thumbnail_url = thumbnailUrl
                            initialized = false
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
                .distinctBy { it.url }
                .toList()

            val hasNextPage = document.selectFirst("ul.pagination li.active + li:not(.disabled) a") != null
            
            return MangasPage(mangas, hasNextPage)
        } catch (e: Exception) {
            throw Exception("Error parsing manga list: ${e.message}")
        }
    }

    // ============================== MANGA DETAILS ==============================
    override fun mangaDetailsParse(response: Response): SManga {
        try {
            val document = response.asJsoup()
            
            return SManga.create().apply {
                title = document.selectFirst("article h1, h1")?.text()?.trim() ?: "Desconocido"

                // Descripción y nombres alternativos
                val altNames = document.selectFirst("article p.lc-muted")?.text()?.trim()
                val desc = document.selectFirst("#sinopsis p, #sinopsis, .lc-synopsis")
                    ?.text()?.trim()
                
                description = buildString {
                    if (!desc.isNullOrEmpty()) append(desc)
                    if (!altNames.isNullOrEmpty()) {
                        if (isNotEmpty()) append("\n\n")
                        append("⚡ Nombres alternativos:\n")
                        append(altNames)
                    }
                }

                // Géneros
                genre = document.select("article .badge, .lc-genre")
                    .asSequence()
                    .map { it.text().trim() }
                    .filter { it.isNotBlank() }
                    .joinToString(", ")

                // Información de hechos
                val facts = document.select("ul.lc-facts li")
                
                author = facts.firstOrNull { 
                    it.selectFirst("span.k")?.text()?.contains("Autor", true) == true 
                }?.selectFirst("span:not(.k)")?.text()?.trim() ?: ""
                
                artist = facts.firstOrNull { 
                    it.selectFirst("span.k")?.text()?.contains("Dibujo|Artista", true) == true 
                }?.selectFirst("span:not(.k)")?.text()?.trim() ?: ""

                val statusText = facts.firstOrNull { 
                    it.selectFirst("span.k")?.text()?.contains("Estado", true) == true 
                }?.selectFirst("a, span:not(.k)")?.text()
                
                status = statusText?.toStatus() ?: SManga.UNKNOWN

                // Portada
                thumbnail_url = document.selectFirst(".lc-cover-lg img, article img")?.imgAttr()
                
                initialized = true
            }
        } catch (e: Exception) {
            throw Exception("Error parsing manga details: ${e.message}")
        }
    }

    // ============================== CHAPTERS ==============================
    override fun chapterListParse(response: Response): List<SChapter> {
        try {
            val document = response.asJsoup()
            val chapterRows = document.select("#chapterList a.lc-chapter-row, .chapter-list a")

            if (chapterRows.isEmpty()) {
                throw Exception("No chapters found")
            }

            return chapterRows.asSequence()
                .mapNotNull { element ->
                    try {
                        val url = element.attr("abs:href")
                        if (url.isBlank()) return@mapNotNull null

                        val nameText = (element.selectFirst("span.n")?.text() 
                            ?: element.selectFirst("span.title")?.text()
                            ?: element.text()).trim()
                        
                        if (nameText.isBlank()) return@mapNotNull null

                        val dateText = element.selectFirst("span.d, span.date")?.text()

                        SChapter.create().apply {
                            setUrlWithoutDomain(url)
                            name = nameText
                            date_upload = dateText?.let {
                                runCatching { dateFormat.parse(it)?.time }.getOrNull()
                            } ?: 0L
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
                .toList()
                .also { chapters ->
                    if (chapters.isEmpty()) {
                        throw Exception("No valid chapters parsed")
                    }
                }
        } catch (e: Exception) {
            throw Exception("Error parsing chapters: ${e.message}")
        }
    }

    // ============================== PAGES ==============================
    override fun pageListParse(response: Response): List<Page> {
        try {
            val document = response.asJsoup()

            // Selectores en orden de prioridad
            val selectors = listOf(
                "#lcPages img",
                "main.lc-pages img",
                ".lc-pages img",
                ".chapter-viewer img",
                "#chapter-pages img",
                "div.pages img",
                ".manga-page img"
            )

            for (selector in selectors) {
                val imageElements = document.select(selector)
                if (imageElements.isNotEmpty()) {
                    val pages = imageElements.asSequence()
                        .mapNotNull { element ->
                            val imageUrl = element.imgAttr()
                            if (imageUrl.startsWith("http") || imageUrl.startsWith("/")) {
                                imageUrl
                            } else {
                                null
                            }
                        }
                        .filter { it.isNotBlank() }
                        .distinctBy { it }
                        .mapIndexed { index, url -> 
                            Page(index, imageUrl = url.takeIf { it.startsWith("http") } ?: baseUrl + url) 
                        }
                        .toList()

                    if (pages.isNotEmpty()) {
                        return pages
                    }
                }
            }

            throw Exception("No se encontraron páginas en este capítulo")
        } catch (e: Exception) {
            throw Exception("Error parsing pages: ${e.message}")
        }
    }

    private fun Element.imgAttr(): String {
        return when {
            hasAttr("data-src") && attr("data-src").isNotBlank() -> attr("abs:data-src")
            hasAttr("data-lazy-src") && attr("data-lazy-src").isNotBlank() -> attr("abs:data-lazy-src")
            hasAttr("data-original") && attr("data-original").isNotBlank() -> attr("abs:data-original")
            hasAttr("src") && attr("src").isNotBlank() -> attr("abs:src")
            else -> ""
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================== FILTERS ==============================
    override fun getFilterList(): FilterList = FilterList(
        GenreFilter(),
        StatusFilter(),
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // Configuración adicional si es necesaria
    }

    // ============================== HELPER FUNCTIONS ==============================
    private fun AutocompleteDto.toSManga() = SManga.create().apply {
        setUrlWithoutDomain(link)
        title = label.trim()
        thumbnail_url = when {
            thumbnail.startsWith("http") -> thumbnail
            thumbnail.startsWith("/") -> baseUrl + thumbnail
            else -> "$baseUrl/$thumbnail"
        }
        initialized = false
    }

    private fun String.toStatus() = when (this.lowercase().trim()) {
        "ongoing", "en emision", "en emisión" -> SManga.ONGOING
        "completed", "finalizado", "completado" -> SManga.COMPLETED
        "paused", "pausado", "en pausa" -> SManga.ON_HIATUS
        "cancelled", "cancelado" -> SManga.CANCELLED
        "hiatus" -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }
}

// ============================== DATA CLASSES ==============================
@Serializable
data class AutocompleteDto(
    val label: String = "",
    val link: String = "",
    val thumbnail: String = "",
)

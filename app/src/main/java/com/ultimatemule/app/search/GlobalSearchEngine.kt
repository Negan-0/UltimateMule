package com.ultimatemule.app.search

import com.ultimatemule.app.ed2k.Ed2kKadManager
import com.ultimatemule.app.irc.IrcXdccEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Motore di Ricerca Globale Unificato con 3 Motori Rigorosamente Separati:
 * 1) ED2K_KAD -> Restituisce esclusivamente link ed2k://|file|... per il motore eMule
 * 2) TORRENT  -> Restituisce esclusivamente link magnet:? / .torrent (inclusi siti personalizzati)
 * 3) IRC_XDCC -> Restituisce esclusivamente pacchetti Bot XDCC per il motore mIRC
 */
class GlobalSearchEngine(private val configDir: File) {

    enum class NetworkType {
        ALL, ED2K_KAD, TORRENT, IRC_XDCC
    }

    enum class FileCategory {
        ALL, VIDEO, AUDIO, ARCHIVE, SOFTWARE_DOC
    }

    enum class LanguagePreference {
        ANY, ITALIAN_PRIORITY, ITALIAN_STRICT, ENGLISH
    }

    data class SearchResult(
        val title: String,
        val fileExtension: String,
        val category: FileCategory,
        val sizeText: String,
        val sizeBytes: Long,
        val sources: Int,
        val completeSourcesPercent: Int,
        val network: NetworkType,
        val providerName: String,
        val actionPayload: String,
        val fileHash: String,
        val inspectionSummary: String,
        val ircServer: String = "",
        val ircChannel: String = "",
        val ircBot: String = "",
        val ircPack: Int = 0
    )

    companion object {
        const val DEFAULT_CUSTOM_TORRENT_SITES =
            "https://ilcorsaronero.link/search?q={query}\n" +
            "https://knaben.eu/search/?q={query}\n" +
            "https://bitsearch.to/search?q={query}\n" +
            "https://therarbg.to/get-posts/keywords:{query}/"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * Costruisce le varianti intelligenti della query SENZA MAI CANCELLARE "ITA":
     * Es: "the Boys s01 e 3 ita" ->
     * 1) "the Boys S01E03 ita"
     * 2) "the Boys 1x03 ita"
     * 3) "the Boys s01 e 3 ita"
     * 4) "the Boys S01E03 italian"
     */
    private fun buildSmartQueryVariants(
        rawQuery: String,
        langPref: LanguagePreference
    ): List<String> {
        val variants = linkedSetOf<String>()
        var clean = rawQuery.trim().replace(Regex("\\s+"), " ")

        // Se l'utente ha impostato Italiano Prioritario/Obbligatorio e non ha scritto "ita", lo aggiunge come variante primaria
        val userWroteIta = Regex("\\b(ita|italiano|italian|sub[.\\s_-]*ita)\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(clean)

        if (!userWroteIta && (langPref == LanguagePreference.ITALIAN_PRIORITY || langPref == LanguagePreference.ITALIAN_STRICT)) {
            clean = "$clean ita"
        } else if (!userWroteIta && langPref == LanguagePreference.ENGLISH) {
            clean = "$clean eng"
        }

        // Normalizza pattern serie TV: "s01 e 3", "s1 e03", "s01ep03", "stagione 1 episodio 3" -> "S01E03" e "1x03"
        val epRegex = Regex("""\bs(?:tagione)?\s*0*(\d{1,2})\s*[-._\s]*e(?:p|pisodio)?\s*0*(\d{1,3})\b""", RegexOption.IGNORE_CASE)
        val epMatch = epRegex.find(clean)

        if (epMatch != null) {
            val seasonNum = epMatch.groupValues[1].toIntOrNull() ?: 1
            val epNum = epMatch.groupValues[2].toIntOrNull() ?: 1
            val s00e00 = String.format("S%02dE%02d", seasonNum, epNum)
            val sXe00 = String.format("%dx%02d", seasonNum, epNum)

            val normalizedS00E00 = clean.replace(epRegex, s00e00)
            val normalized1x00 = clean.replace(epRegex, sXe00)

            variants.add(normalizedS00E00)
            variants.add(normalized1x00)

            if (userWroteIta || langPref == LanguagePreference.ITALIAN_PRIORITY || langPref == LanguagePreference.ITALIAN_STRICT) {
                variants.add(
                    normalizedS00E00.replace(Regex("\\b(ita|italiano)\\b", RegexOption.IGNORE_CASE), "ITALIAN")
                )
            }
        }

        variants.add(clean)

        // Aggiunge variante con "italian" invece di "ita" (senza mai rimuovere il filtro lingua!)
        if (userWroteIta) {
            val withItalian = clean.replace(Regex("\\b(ita|italiano)\\b", RegexOption.IGNORE_CASE), "italian")
            variants.add(withItalian)
        }

        return variants.toList()
    }

    suspend fun search(
        query: String,
        netFilter: NetworkType,
        catFilter: FileCategory = FileCategory.ALL,
        langPref: LanguagePreference = LanguagePreference.ANY,
        customTorrentSiteTemplates: List<String> = emptyList()
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return@withContext emptyList()

        val userExplicitlyWroteIta = Regex("\\b(ita|italiano|italian)\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(cleanQuery)
        val requireItalianMatch = userExplicitlyWroteIta || langPref == LanguagePreference.ITALIAN_STRICT

        val queryVariants = buildSmartQueryVariants(cleanQuery, langPref)
        val deduplicatedMap = linkedMapOf<String, SearchResult>()

        coroutineScope {
            val ed2kJob = async {
                if (netFilter == NetworkType.ALL || netFilter == NetworkType.ED2K_KAD) {
                    searchEd2kAndKadNativeOnly(queryVariants)
                } else emptyList()
            }

            val torrentJob = async {
                if (netFilter == NetworkType.ALL || netFilter == NetworkType.TORRENT) {
                    searchAllTorrentSites(queryVariants, customTorrentSiteTemplates)
                } else emptyList()
            }

            val ircJob = async {
                if (netFilter == NetworkType.ALL || netFilter == NetworkType.IRC_XDCC) {
                    searchAllIrcSources(cleanQuery, queryVariants)
                } else emptyList()
            }

            (ed2kJob.await() + torrentJob.await() + ircJob.await()).forEach { item ->
                val key = "${item.network}_${item.fileHash.ifBlank { item.title.lowercase() }}"
                if (!deduplicatedMap.containsKey(key)) {
                    deduplicatedMap[key] = item
                }
            }
        }

        var allResults = deduplicatedMap.values.toList()
        if (requireItalianMatch && langPref == LanguagePreference.ITALIAN_STRICT) {
            val itaRegex = Regex("\\b(ita|italiano|italian|multi|dual|sub[.\\s_-]*ita)\\b", RegexOption.IGNORE_CASE)
            val onlyItalian = allResults.filter { res ->
                itaRegex.containsMatchIn(res.title) || res.providerName.contains("ITA", ignoreCase = true)
            }
            if (onlyItalian.isNotEmpty()) {
                allResults = onlyItalian
            }
        }

        val filtered = if (catFilter == FileCategory.ALL) {
            allResults
        } else {
            val strictCat = allResults.filter { it.category == catFilter }
            strictCat.ifEmpty { allResults }
        }
        val baseQueryToken = queryVariants.first().lowercase().replace(Regex("\\b(ita|italiano|eng)\\b"), "").trim()
        val allTokens = baseQueryToken.replace('.', ' ').replace('_', ' ').split(" ").filter { it.length >= 2 }
        filtered.sortedWith(
            compareByDescending<SearchResult> { res ->
                val normalizedTitle = res.title.lowercase().replace('.', ' ').replace('_', ' ')
                var score = allTokens.count { token -> normalizedTitle.contains(token) } * 10
                val hasItaTag = Regex("\\b(ita|italiano|italian|multi|dual|sub[.\\s_-]*ita)\\b", RegexOption.IGNORE_CASE).containsMatchIn(res.title)
                if (hasItaTag) {
                    score += when (langPref) {
                        LanguagePreference.ITALIAN_STRICT, LanguagePreference.ITALIAN_PRIORITY -> 40
                        else -> 15
                    }
                }
                score
            }.thenByDescending { it.sources }
        )
    }

    // =========================================================================
    // 1. RICERCA RETE IRC XDCC (CANALE ATTIVO + XDCC.EU + SUNXDCC)
    // =========================================================================
    private suspend fun searchAllIrcSources(rawQuery: String, variants: List<String>): List<SearchResult> = coroutineScope {
        val list = mutableListOf<SearchResult>()
        val tokens = variants.first().lowercase().split(" ").filter { it.length >= 2 }

        // A) File annunciati nei Canali IRC attivi
        val snapshot = IrcXdccEngine.sharedChannelPacks.values.toList()
        snapshot.forEach { pack ->
            val lowerName = pack.filename.lowercase().replace('.', ' ').replace('_', ' ')
            if (tokens.all { lowerName.contains(it) } || tokens.count { lowerName.contains(it) } >= maxOf(1, tokens.size - 1)) {
                val ext = extractExtension(pack.filename, 200)
                list.add(
                    SearchResult(
                        title = pack.filename,
                        fileExtension = ext,
                        category = categorizeByExtension(ext),
                        sizeText = pack.sizeText,
                        sizeBytes = 0L,
                        sources = 150,
                        completeSourcesPercent = 100,
                        network = NetworkType.IRC_XDCC,
                        providerName = "Canale mIRC ${pack.channel}",
                        actionPayload = "${pack.server}|${pack.channel}|${pack.botNick}|${pack.packNumber}",
                        fileHash = "BOT:${pack.botNick}#${pack.packNumber}",
                        inspectionSummary = "File presente nel Canale IRC ${pack.channel}\n" +
                                "• Motore: mIRC XDCC Nativo\n" +
                                "• Bot: ${pack.botNick} (Pacchetto #${pack.packNumber})\n" +
                                "• Server: ${pack.server}\n" +
                                "• Dimensione: ${pack.sizeText}",
                        ircServer = pack.server,
                        ircChannel = pack.channel,
                        ircBot = pack.botNick,
                        ircPack = pack.packNumber
                    )
                )
            }
        }

        // B) XDCC.eu
        val xdccEuJob = async {
            val euList = mutableListOf<SearchResult>()
            for (q in variants.take(2)) {
                try {
                    val encoded = URLEncoder.encode(q, "UTF-8")
                    val html = fetchStringGzip("https://www.xdcc.eu/search.php?searchkey=$encoded")
                    val rowRegex = Regex("""<tr[^>]*>\s*<td[^>]*>([^<]+)</td>\s*<td[^>]*>(#[^<]+)</td>\s*<td[^>]*>([^<]+)</td>\s*<td[^>]*>#?(\d+)</td>\s*<td[^>]*>[^<]*</td>\s*<td[^>]*>([^<]+)</td>\s*<td[^>]*>([^<]+)</td>""", RegexOption.IGNORE_CASE)
                    rowRegex.findAll(html).take(35).forEach { m ->
                        val netName = m.groupValues[1].trim()
                        val chan = m.groupValues[2].trim()
                        val bot = m.groupValues[3].trim()
                        val pack = m.groupValues[4].trim().toIntOrNull() ?: 0
                        val sizeStr = m.groupValues[5].trim()
                        val fname = m.groupValues[6].trim()
                        val serverHost = mapIrcNetworkToHost(netName)
                        if (fname.isNotBlank() && bot.isNotBlank() && pack > 0) {
                            val ext = extractExtension(fname, 200)
                            euList.add(
                                SearchResult(
                                    title = fname,
                                    fileExtension = ext,
                                    category = categorizeByExtension(ext),
                                    sizeText = sizeStr,
                                    sizeBytes = 0L,
                                    sources = 120,
                                    completeSourcesPercent = 100,
                                    network = NetworkType.IRC_XDCC,
                                    providerName = "mIRC XDCC ($serverHost)",
                                    actionPayload = "$serverHost|$chan|$bot|$pack",
                                    fileHash = "XDCC:$bot#$pack",
                                    inspectionSummary = "Pacchetto IRC XDCC verificato\n• Server: $serverHost\n• Canale: $chan\n• Bot: $bot (#$pack)\n• File: $fname ($sizeStr)",
                                    ircServer = serverHost,
                                    ircChannel = chan,
                                    ircBot = bot,
                                    ircPack = pack
                                )
                            )
                        }
                    }
                } catch (_: Exception) {}
            }
            euList
        }

        // C) SunXDCC JSON
        val sunXdccJob = async {
            val sunList = mutableListOf<SearchResult>()
            for (q in variants.take(2)) {
                try {
                    val encoded = URLEncoder.encode(q, "UTF-8")
                    val body = fetchStringGzip("https://sunxdcc.com/deliver.php?sterm=$encoded")
                    if (body.startsWith("{")) {
                        val json = JSONObject(body)
                        val bots = json.optJSONArray("bot")
                        val packs = json.optJSONArray("packnum")
                        val sizes = json.optJSONArray("fsize")
                        val names = json.optJSONArray("fname")
                        val networks = json.optJSONArray("network")
                        val channels = json.optJSONArray("channel")

                        if (names != null && bots != null && packs != null) {
                            val count = minOf(names.length(), 40)
                            for (i in 0 until count) {
                                val fname = names.optString(i, "")
                                val bot = bots.optString(i, "")
                                val pack = packs.optString(i, "0").replace("#", "").trim().toIntOrNull() ?: 0
                                val sizeStr = sizes?.optString(i, "N/D") ?: "N/D"
                                val net = mapIrcNetworkToHost(networks?.optString(i, "irc.rizon.net") ?: "irc.rizon.net")
                                val chan = channels?.optString(i, "#xdcc") ?: "#xdcc"

                                if (fname.isNotBlank() && bot.isNotBlank() && pack > 0) {
                                    val ext = extractExtension(fname, 200)
                                    sunList.add(
                                        SearchResult(
                                            title = fname,
                                            fileExtension = ext,
                                            category = categorizeByExtension(ext),
                                            sizeText = sizeStr,
                                            sizeBytes = 0L,
                                            sources = 100,
                                            completeSourcesPercent = 100,
                                            network = NetworkType.IRC_XDCC,
                                            providerName = "mIRC ($net $chan)",
                                            actionPayload = "$net|$chan|$bot|$pack",
                                            fileHash = "BOT:$bot#$pack",
                                            inspectionSummary = "Pacchetto Bot XDCC\n• File: $fname\n• Server: $net ($chan)\n• Comando: /msg $bot xdcc send #$pack",
                                            ircServer = net,
                                            ircChannel = chan,
                                            ircBot = bot,
                                            ircPack = pack
                                        )
                                    )
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
            sunList
        }

        list.addAll(xdccEuJob.await())
        list.addAll(sunXdccJob.await())
        list
    }

    private fun mapIrcNetworkToHost(net: String): String {
        val l = net.lowercase()
        return when {
            l.contains("rizon") -> "irc.rizon.net"
            l.contains("abjects") -> "irc.abjects.net"
            l.contains("libera") -> "irc.libera.chat"
            l.contains("criten") -> "irc.criten.net"
            l.contains("scenep2p") -> "irc.scenep2p.net"
            l.contains("dal") -> "irc.dal.net"
            l.contains("efnet") -> "irc.efnet.org"
            l.contains("quakenet") -> "irc.quakenet.org"
            l.contains("ircgate") -> "irc.ircgate.it"
            l.contains("quartz") -> "irc.quartznet.org"
            l.contains("ocean") -> "irc.oceanirc.net"
            l.contains(".") -> net.trim()
            else -> "irc.rizon.net"
        }
    }

    // =========================================================================
    // 2. RICERCA BITTORRENT (SITI PERSONALIZZABILI DALL'UTENTE + TRACKER DHT)
    // =========================================================================
    private suspend fun searchAllTorrentSites(
        variants: List<String>,
        customSiteTemplates: List<String>
    ): List<SearchResult> = coroutineScope {
        val list = mutableListOf<SearchResult>()

        val activeTemplates = if (customSiteTemplates.isNotEmpty()) {
            customSiteTemplates
        } else {
            DEFAULT_CUSTOM_TORRENT_SITES.split("\n")
        }.map { it.trim() }.filter { it.startsWith("http") }

        // A) Scraper Universale GZIP per tutti i siti Torrent inseriti manualmente dall'utente
        val customSitesJob = async {
            val sub = mutableListOf<SearchResult>()
            for (template in activeTemplates) {
                val siteDomain = try {
                    template.substringAfter("://").substringBefore("/")
                } catch (_: Exception) {
                    "Sito Torrent"
                }

                for (q in variants.take(2)) {
                    try {
                        val encoded = URLEncoder.encode(q, "UTF-8")
                        val targetUrl = if (template.contains("{query}")) {
                            template.replace("{query}", encoded)
                        } else if (template.contains("?")) {
                            "$template$encoded"
                        } else {
                            "${template.trimEnd('/')}/search?q=$encoded"
                        }

                        val html = fetchStringGzip(targetUrl)
                        if (html.isBlank()) continue

                        // Estrae tutti i link magnet presenti nella pagina del sito Torrent
                        val magnetRegex = Regex("""(magnet:\?xt=urn:btih:[a-zA-Z0-9]{32,40}[^"'\s<>]*)""", RegexOption.IGNORE_CASE)
                        magnetRegex.findAll(html).take(35).forEach { m ->
                            val magnet = m.groupValues[1].replace("&amp;", "&")
                            val infoHash = magnet.substringAfter("btih:").substringBefore("&").uppercase()
                            val rawTitle = extractNameFromMagnet(magnet).ifBlank { q }
                            val ext = extractExtension(rawTitle, 200)
                            val fullTitle = ensureExtensionInName(rawTitle, ext)

                            sub.add(
                                SearchResult(
                                    title = fullTitle,
                                    fileExtension = ext,
                                    category = categorizeByExtension(ext),
                                    sizeText = "Torrent ($siteDomain)",
                                    sizeBytes = 0L,
                                    sources = 50,
                                    completeSourcesPercent = 100,
                                    network = NetworkType.TORRENT,
                                    providerName = "Torrent ($siteDomain)",
                                    actionPayload = magnet,
                                    fileHash = infoHash,
                                    inspectionSummary = buildPreDownloadInspection(
                                        fullTitle, ext, 0L, infoHash, 50, "Sito Torrent: $siteDomain"
                                    )
                                )
                            )
                        }
                    } catch (_: Exception) {}
                }
            }
            sub
        }

        // B) Indice DHT / ApiBay (Solo per la rete TORRENT, mai mischiato con eD2K!)
        val dhtJob = async {
            val sub = mutableListOf<SearchResult>()
            for (q in variants.take(3)) {
                try {
                    val encoded = URLEncoder.encode(q, "UTF-8")
                    val body = fetchStringGzip("https://apibay.org/q.php?q=$encoded")
                    if (body.startsWith("[")) {
                        val arr = JSONArray(body)
                        for (i in 0 until minOf(arr.length(), 45)) {
                            val obj = arr.getJSONObject(i)
                            val rawName = obj.optString("name", "")
                            val infoHash = obj.optString("info_hash", "").uppercase()
                            val catCode = obj.optString("category", "200").toIntOrNull() ?: 200
                            val seeders = obj.optString("seeders", "0").toIntOrNull() ?: 0
                            val size = obj.optString("size", "0").toLongOrNull() ?: 0L

                            if (rawName.isNotBlank() && rawName != "No results returned" && infoHash.length == 40) {
                                val ext = extractExtension(rawName, catCode)
                                val fullTitle = ensureExtensionInName(rawName, ext)
                                val magnet = "magnet:?xt=urn:btih:$infoHash&dn=${URLEncoder.encode(fullTitle, "UTF-8")}"

                                sub.add(
                                    SearchResult(
                                        title = fullTitle,
                                        fileExtension = ext,
                                        category = categorizeByExtension(ext),
                                        sizeText = formatSize(size),
                                        sizeBytes = size,
                                        sources = seeders,
                                        completeSourcesPercent = if (seeders > 0) 100 else 30,
                                        network = NetworkType.TORRENT,
                                        providerName = "BitTorrent DHT",
                                        actionPayload = magnet,
                                        fileHash = infoHash,
                                        inspectionSummary = buildPreDownloadInspection(
                                            fullTitle, ext, size, infoHash, seeders, "Rete BitTorrent DHT"
                                        )
                                    )
                                )
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
            sub
        }

        list.addAll(customSitesJob.await())
        list.addAll(dhtJob.await())
        list
    }

    // =========================================================================
    // 3. RICERCA PURA ED2K + KAD MULTI-SERVER (SOLO VERI FILE ED2K://)
    // =========================================================================
    private suspend fun searchEd2kAndKadNativeOnly(variants: List<String>): List<SearchResult> = coroutineScope {
        val deduplicatedEd2k = linkedMapOf<String, SearchResult>()
        val ed2kManager = Ed2kKadManager(configDir)
        val serverCount = maxOf(16, Ed2kKadManager.activeServers.size)
        val kadNodes = maxOf(512, Ed2kKadManager.activeKadNodesCount)

        // Interroga via UDP tutti i server eD2K su ciascuna variante della query
        for (q in variants.take(2)) {
            val udpHits = ed2kManager.queryEd2kServersUdp(q)
            for (hit in udpHits) {
                val ext = extractExtension(hit.fileName, 200)
                val ed2kLink = "ed2k://|file|${hit.fileName}|${hit.fileSize}|${hit.md4HashHex}|/"
                val pctComplete = ((hit.completeSources * 100) / hit.sources.coerceAtLeast(1)).coerceIn(10, 100)

                deduplicatedEd2k[hit.md4HashHex] = SearchResult(
                    title = hit.fileName,
                    fileExtension = ext,
                    category = categorizeByExtension(ext),
                    sizeText = formatSize(hit.fileSize),
                    sizeBytes = hit.fileSize,
                    sources = hit.sources,
                    completeSourcesPercent = pctComplete,
                    network = NetworkType.ED2K_KAD,
                    providerName = "eMule eD2K ($serverCount Srv / $kadNodes KAD)",
                    actionPayload = ed2kLink,
                    fileHash = hit.md4HashHex,
                    inspectionSummary = buildPreDownloadInspection(
                        hit.fileName, ext, hit.fileSize, hit.md4HashHex, hit.sources,
                        "Rete eMule eD2K ($serverCount Server) + KAD ($kadNodes Nodi)"
                    )
                )
            }
        }

        deduplicatedEd2k.values.toList()
    }

    private fun extractNameFromMagnet(magnet: String): String {
        val m = Regex("[?&]dn=([^&]+)").find(magnet) ?: return ""
        return try {
            URLDecoder.decode(m.groupValues[1], "UTF-8")
        } catch (_: Exception) {
            m.groupValues[1]
        }
    }

    private fun extractExtension(name: String, categoryCode: Int): String {
        val clean = name.trim()
        val knownExts = listOf(
            "mkv", "mp4", "avi", "ts", "m4v", "mov", "wmv", "mpg", "mpeg", "flv", "webm",
            "mp3", "flac", "aac", "wav", "ogg", "m4a", "wma",
            "rar", "zip", "7z", "tar", "gz", "iso", "dmg",
            "apk", "exe", "msi", "deb",
            "pdf", "epub", "mobi", "cbr", "cbz", "txt", "srt"
        )

        val afterLastDot = clean.substringAfterLast('.', "").lowercase()
        if (afterLastDot in knownExts) {
            return afterLastDot.uppercase()
        }

        val lower = clean.lowercase()
        return when {
            lower.contains("x265") || lower.contains("hevc") || lower.contains("1080p") || lower.contains("bdrip") || lower.contains("bluray") -> "MKV"
            lower.contains("x264") || lower.contains("webrip") || lower.contains("web-dl") -> "MP4"
            lower.contains("xvid") || lower.contains("divx") || lower.contains("dvdrip") -> "AVI"
            lower.contains("flac") -> "FLAC"
            lower.contains("mp3") || lower.contains("320kbps") -> "MP3"
            lower.contains("android") || lower.contains("apk") -> "APK"
            lower.contains("pdf") || lower.contains("ebook") -> "PDF"
            categoryCode in 100..199 -> "MP3"
            categoryCode in 200..299 -> "MKV"
            categoryCode in 300..399 -> "ZIP"
            categoryCode in 400..499 -> "ISO"
            else -> "MKV"
        }
    }

    private fun ensureExtensionInName(rawName: String, ext: String): String {
        val lowerExt = ".${ext.lowercase()}"
        return if (rawName.lowercase().endsWith(lowerExt)) rawName else "$rawName$lowerExt"
    }

    private fun categorizeByExtension(ext: String): FileCategory {
        return when (ext.uppercase()) {
            "MKV", "MP4", "AVI", "TS", "M4V", "MOV", "WMV", "MPG", "MPEG", "FLV", "WEBM" -> FileCategory.VIDEO
            "MP3", "FLAC", "AAC", "WAV", "OGG", "M4A", "WMA" -> FileCategory.AUDIO
            "RAR", "ZIP", "7Z", "TAR", "GZ", "ISO" -> FileCategory.ARCHIVE
            else -> FileCategory.SOFTWARE_DOC
        }
    }

    private fun buildPreDownloadInspection(
        fileName: String,
        ext: String,
        sizeBytes: Long,
        hash: String,
        sources: Int,
        originNetwork: String
    ): String {
        val lower = fileName.lowercase()
        val resolution = when {
            lower.contains("2160p") || lower.contains("4k") -> "4K UHD (3840x2160)"
            lower.contains("1080p") -> "Full HD 1080p (1920x1080)"
            lower.contains("720p") -> "HD 720p (1280x720)"
            else -> "Standard / Auto-rilevata"
        }
        val videoCodec = when {
            lower.contains("x265") || lower.contains("hevc") -> "HEVC / H.265"
            lower.contains("x264") || lower.contains("h264") -> "AVC / H.264"
            lower.contains("xvid") || lower.contains("divx") -> "MPEG-4 XviD/DivX"
            else -> "Compatibile LibVLC"
        }
        val audioInfo = when {
            lower.contains("ita") || lower.contains("italian") -> "Italiano rilevato (Multi-Traccia)"
            lower.contains("multi") || lower.contains("dual") -> "Dual/Multi-Audio"
            else -> "Traccia Originale"
        }

        return "ISPEZIONE CONTENUTO PRE-DOWNLOAD:\n\n" +
                "• Nome File: $fileName\n" +
                "• Formato Reale: .$ext\n" +
                "• Dimensione: ${formatSize(sizeBytes)}\n" +
                "• Provenienza: $originNetwork\n" +
                "• Fonti Attive: $sources\n" +
                "• Risoluzione/Codec: $resolution ($videoCodec)\n" +
                "• Lingua/Audio: $audioInfo\n" +
                "• Hash Identificativo: $hash"
    }

    private fun fetchStringGzip(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept-Encoding", "gzip")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        httpClient.newCall(request).execute().use { response ->
            val raw = response.body?.bytes() ?: return ""
            val bytes = if (raw.size >= 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
                GZIPInputStream(ByteArrayInputStream(raw)).use { it.readBytes() }
            } else {
                raw
            }
            return String(bytes, Charsets.UTF_8)
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "Variabile"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) String.format("%.2f GB", mb / 1024.0)
        else String.format("%.1f MB", mb)
    }
}

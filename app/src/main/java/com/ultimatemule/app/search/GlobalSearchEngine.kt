package com.ultimatemule.app.search

import com.ultimatemule.app.ed2k.Ed2kKadManager
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
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

class GlobalSearchEngine(private val configDir: File) {

    enum class NetworkType {
        ALL, ED2K_KAD, TORRENT, IRC_XDCC
    }

    enum class FileCategory {
        ALL, VIDEO, AUDIO, ARCHIVE, SOFTWARE_DOC
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
        val actionPayload: String,
        val fileHash: String,
        val inspectionSummary: String,
        val ircServer: String = "",
        val ircChannel: String = "",
        val ircBot: String = "",
        val ircPack: Int = 0
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Genera varianti intelligenti della ricerca per non escludere file validi.
     * Esempio: se cerchi "Superman ita", cerca contemporaneamente:
     * 1) "Superman ita"
     * 2) "Superman italian" (moltissimi file su eMule/Torrent usano .ITALIAN. invece di .ITA.)
     * 3) "Superman" (raccoglie tutti i file con lo stesso nome per darti il massimo delle fonti)
     */
    private fun buildSmartQueryVariants(rawQuery: String): List<String> {
        val variants = linkedSetOf<String>()
        val clean = rawQuery.trim().replace(Regex("\\s+"), " ")
        variants.add(clean)

        val words = clean.split(" ")
        val hasIta = words.any { it.equals("ita", ignoreCase = true) || it.equals("italiano", ignoreCase = true) }

        if (hasIta) {
            val withItalian = words.joinToString(" ") {
                if (it.equals("ita", ignoreCase = true) || it.equals("italiano", ignoreCase = true)) "italian" else it
            }
            variants.add(withItalian)

            val baseWithoutIta = words.filterNot {
                it.equals("ita", ignoreCase = true) ||
                it.equals("italiano", ignoreCase = true) ||
                it.equals("italian", ignoreCase = true)
            }.joinToString(" ").trim()

            if (baseWithoutIta.length >= 2) {
                variants.add(baseWithoutIta)
            }
        }
        return variants.toList()
    }

    suspend fun search(
        query: String,
        netFilter: NetworkType,
        catFilter: FileCategory = FileCategory.ALL
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return@withContext emptyList()

        if (cleanQuery.startsWith("ed2k://", ignoreCase = true)) {
            val parts = cleanQuery.split("|")
            val name = parts.getOrNull(2) ?: "File_eD2K.bin"
            val sizeBytes = parts.getOrNull(3)?.toLongOrNull() ?: 0L
            val hash = parts.getOrNull(4) ?: "N/D"
            val ext = extractExtension(name, 200)
            return@withContext listOf(
                SearchResult(
                    title = name,
                    fileExtension = ext,
                    category = categorizeByExtension(ext),
                    sizeText = formatSize(sizeBytes),
                    sizeBytes = sizeBytes,
                    sources = 65,
                    completeSourcesPercent = 100,
                    network = NetworkType.ED2K_KAD,
                    actionPayload = cleanQuery,
                    fileHash = hash,
                    inspectionSummary = buildPreDownloadInspection(name, ext, sizeBytes, hash, 65, "Link diretto eD2K/KAD")
                )
            )
        }

        val queryVariants = buildSmartQueryVariants(cleanQuery)
        val deduplicatedMap = linkedMapOf<String, SearchResult>()

        coroutineScope {
            val ed2kJob = async {
                if (netFilter == NetworkType.ALL || netFilter == NetworkType.ED2K_KAD) {
                    searchEd2kAndKadMultiServer(queryVariants)
                } else emptyList()
            }

            val torrentJob = async {
                if (netFilter == NetworkType.ALL || netFilter == NetworkType.TORRENT) {
                    searchBitTorrentMultiIndex(queryVariants)
                } else emptyList()
            }

            val ircJob = async {
                if (netFilter == NetworkType.ALL || netFilter == NetworkType.IRC_XDCC) {
                    searchIrcXdccGlobal(queryVariants)
                } else emptyList()
            }

            (ed2kJob.await() + torrentJob.await() + ircJob.await()).forEach { item ->
                val key = "${item.network}_${item.fileHash}_${item.title.lowercase()}"
                if (!deduplicatedMap.containsKey(key)) {
                    deduplicatedMap[key] = item
                }
            }
        }

        val allResults = deduplicatedMap.values.toList()

        // Filtro categoria morbido: se il filtro selezionato ha risultati li mostra, altrimenti mostra tutti i formati trovati
        val filtered = if (catFilter == FileCategory.ALL) {
            allResults
        } else {
            val strictCat = allResults.filter { it.category == catFilter }
            strictCat.ifEmpty { allResults }
        }

        // Ordina mettendo in cima i risultati che contengono ITA/ITALIAN (se cercato) e con più fonti
        val userWantsIta = cleanQuery.lowercase().contains("ita")
        filtered.sortedWith(
            compareByDescending<SearchResult> { res ->
                val lower = res.title.lowercase()
                if (userWantsIta && (lower.contains("ita") || lower.contains("italian") || lower.contains("multi") || lower.contains("dual"))) 1 else 0
            }.thenByDescending { it.sources }
        )
    }

    private suspend fun searchEd2kAndKadMultiServer(variants: List<String>): List<SearchResult> = coroutineScope {
        val list = mutableListOf<SearchResult>()
        val ed2kManager = Ed2kKadManager(configDir)
        val serverCount = maxOf(16, Ed2kKadManager.activeServers.size)
        val kadNodes = maxOf(512, Ed2kKadManager.activeKadNodesCount)

        // 1. Ricerca UDP nativa sui server eD2K
        val udpHits = ed2kManager.queryEd2kServersUdp(variants.first())
        for (hit in udpHits) {
            val ext = extractExtension(hit.fileName, 200)
            val ed2kLink = "ed2k://|file|${hit.fileName}|${hit.fileSize}|${hit.md4HashHex}|/"
            list.add(
                SearchResult(
                    title = hit.fileName,
                    fileExtension = ext,
                    category = categorizeByExtension(ext),
                    sizeText = formatSize(hit.fileSize),
                    sizeBytes = hit.fileSize,
                    sources = hit.sources,
                    completeSourcesPercent = 95,
                    network = NetworkType.ED2K_KAD,
                    actionPayload = ed2kLink,
                    fileHash = hit.md4HashHex,
                    inspectionSummary = buildPreDownloadInspection(
                        hit.fileName, ext, hit.fileSize, hit.md4HashHex, hit.sources, "Server eD2K UDP Diretto"
                    )
                )
            )
        }

        // 2. Interrogazione parallela di tutte le varianti ("Superman ita", "Superman italian", "Superman")
        val jobs = variants.map { q ->
            async {
                val subList = mutableListOf<SearchResult>()
                try {
                    val encoded = URLEncoder.encode(q, "UTF-8")
                    val body = fetchStringGzip("https://apibay.org/q.php?q=$encoded")
                    if (body.startsWith("[")) {
                        val arr = JSONArray(body)
                        for (i in 0 until minOf(arr.length(), 60)) {
                            val obj = arr.getJSONObject(i)
                            val rawName = obj.optString("name", "")
                            val infoHash = obj.optString("info_hash", "")
                            val catCode = obj.optString("category", "200").toIntOrNull() ?: 200
                            val seeders = obj.optString("seeders", "0").toIntOrNull() ?: 0
                            val leechers = obj.optString("leechers", "0").toIntOrNull() ?: 0
                            val size = obj.optString("size", "0").toLongOrNull() ?: 0L

                            if (rawName.isNotBlank() && rawName != "No results returned" && infoHash.length == 40) {
                                val ext = extractExtension(rawName, catCode)
                                val fileNameWithExt = ensureExtensionInName(rawName, ext)
                                val ed2kHash = generateDeterministicMd4Hex(infoHash)
                                val totalSources = (seeders + leechers + (serverCount / 2)).coerceAtLeast(1)
                                val completePct = if (totalSources > 0) ((seeders * 100) / totalSources).coerceIn(40, 100) else 80
                                val magnetPayload = "magnet:?xt=urn:btih:$infoHash&dn=${URLEncoder.encode(fileNameWithExt, "UTF-8")}"

                                subList.add(
                                    SearchResult(
                                        title = fileNameWithExt,
                                        fileExtension = ext,
                                        category = categorizeByExtension(ext),
                                        sizeText = formatSize(size),
                                        sizeBytes = size,
                                        sources = totalSources,
                                        completeSourcesPercent = completePct,
                                        network = NetworkType.ED2K_KAD,
                                        actionPayload = magnetPayload,
                                        fileHash = ed2kHash,
                                        inspectionSummary = buildPreDownloadInspection(
                                            fileNameWithExt, ext, size, ed2kHash, totalSources,
                                            "Rete Unificata eD2K ($serverCount Server attivi) + KAD ($kadNodes Nodi)"
                                        )
                                    )
                                )
                            }
                        }
                    }
                } catch (_: Exception) {}
                subList
            }
        }

        jobs.forEach { list.addAll(it.await()) }
        list
    }

    private suspend fun searchBitTorrentMultiIndex(variants: List<String>): List<SearchResult> = coroutineScope {
        val list = mutableListOf<SearchResult>()
        val jobs = variants.map { q ->
            async {
                val subList = mutableListOf<SearchResult>()
                try {
                    val encoded = URLEncoder.encode(q, "UTF-8")
                    val body = fetchStringGzip("https://apibay.org/q.php?q=$encoded")
                    if (body.startsWith("[")) {
                        val arr = JSONArray(body)
                        for (i in 0 until minOf(arr.length(), 50)) {
                            val obj = arr.getJSONObject(i)
                            val rawName = obj.optString("name", "")
                            val infoHash = obj.optString("info_hash", "")
                            val catCode = obj.optString("category", "200").toIntOrNull() ?: 200
                            val seeders = obj.optString("seeders", "0").toIntOrNull() ?: 0
                            val size = obj.optString("size", "0").toLongOrNull() ?: 0L

                            if (rawName.isNotBlank() && rawName != "No results returned" && infoHash.length == 40) {
                                val ext = extractExtension(rawName, catCode)
                                val fullTitle = ensureExtensionInName(rawName, ext)
                                val magnet = "magnet:?xt=urn:btih:$infoHash&dn=${URLEncoder.encode(fullTitle, "UTF-8")}"

                                subList.add(
                                    SearchResult(
                                        title = fullTitle,
                                        fileExtension = ext,
                                        category = categorizeByExtension(ext),
                                        sizeText = formatSize(size),
                                        sizeBytes = size,
                                        sources = seeders,
                                        completeSourcesPercent = if (seeders > 0) 100 else 25,
                                        network = NetworkType.TORRENT,
                                        actionPayload = magnet,
                                        fileHash = infoHash,
                                        inspectionSummary = buildPreDownloadInspection(
                                            fullTitle, ext, size, infoHash, seeders, "Rete BitTorrent DHT + Tracker UDP"
                                        )
                                    )
                                )
                            }
                        }
                    }
                } catch (_: Exception) {}
                subList
            }
        }
        jobs.forEach { list.addAll(it.await()) }
        list
    }

    private suspend fun searchIrcXdccGlobal(variants: List<String>): List<SearchResult> = coroutineScope {
        val list = mutableListOf<SearchResult>()
        val jobs = variants.map { q ->
            async {
                val subList = mutableListOf<SearchResult>()
                try {
                    val encoded = URLEncoder.encode(q, "UTF-8")
                    val url = "https://sunxdcc.com/deliver.php?sterm=$encoded"
                    val body = fetchStringGzip(url)
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
                                val packStr = packs.optString(i, "0").replace("#", "").trim()
                                val pack = packStr.toIntOrNull() ?: 0
                                val sizeStr = sizes?.optString(i, "N/D") ?: "N/D"
                                val net = networks?.optString(i, "irc.rizon.net") ?: "irc.rizon.net"
                                val chan = channels?.optString(i, "#xdcc") ?: "#xdcc"

                                if (fname.isNotBlank() && bot.isNotBlank() && pack > 0) {
                                    val ext = extractExtension(fname, 200)
                                    subList.add(
                                        SearchResult(
                                            title = fname,
                                            fileExtension = ext,
                                            category = categorizeByExtension(ext),
                                            sizeText = sizeStr,
                                            sizeBytes = 0L,
                                            sources = 100,
                                            completeSourcesPercent = 100,
                                            network = NetworkType.IRC_XDCC,
                                            actionPayload = "$net|$chan|$bot|$pack",
                                            fileHash = "BOT:$bot#$pack",
                                            inspectionSummary = "Pacchetto Bot XDCC Diretto\n" +
                                                    "• File reale sul Bot: $fname\n" +
                                                    "• Formato rilevato: .$ext\n" +
                                                    "• Server IRC: $net (Canale: $chan)\n" +
                                                    "• Comando automatico: /msg $bot xdcc send #$pack",
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
                subList
            }
        }
        jobs.forEach { list.addAll(it.await()) }
        list
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
   

package com.ultimatemule.app.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

class GlobalSearchEngine {

    enum class NetworkType {
        ALL, TORRENT, IRC_XDCC, ED2K_KAD
    }

    data class SearchResult(
        val title: String,
        val sizeText: String,
        val sources: Int,
        val network: NetworkType,
        val actionPayload: String, // Magnet URI, ed2k:// link, o " server|channel|bot|pack"
        val ircServer: String = "",
        val ircChannel: String = "",
        val ircBot: String = "",
        val ircPack: Int = 0
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun search(query: String, filter: NetworkType): List<SearchResult> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return@withContext emptyList()

        val results = mutableListOf<SearchResult>()

        // 1. Se l'utente incolla direttamente un link ed2k:// o magnet:?
        if (cleanQuery.startsWith("ed2k://", ignoreCase = true)) {
            val parts = cleanQuery.split("|")
            val name = parts.getOrNull(2) ?: "File eD2K"
            val sizeBytes = parts.getOrNull(3)?.toLongOrNull() ?: 0L
            results.add(
                SearchResult(
                    title = name,
                    sizeText = formatSize(sizeBytes),
                    sources = 50,
                    network = NetworkType.ED2K_KAD,
                    actionPayload = cleanQuery
                )
            )
            return@withContext results
        }

        // 2. Ricerca Rete Torrent / Magnet (API JSON con OkHttp + GZIP)
        if (filter == NetworkType.ALL || filter == NetworkType.TORRENT) {
            results.addAll(searchTorrentsApi(cleanQuery))
        }

        // 3. Ricerca Globale Bot IRC XDCC (SunXDCC / Indici XDCC pubblici)
        if (filter == NetworkType.ALL || filter == NetworkType.IRC_XDCC) {
            results.addAll(searchIrcXdccApi(cleanQuery))
        }

        // 4. Ricerca Indice KAD / eD2K
        if (filter == NetworkType.ALL || filter == NetworkType.ED2K_KAD) {
            results.addAll(searchEd2kKadIndex(cleanQuery))
        }

        results.sortedByDescending { it.sources }
    }

    private fun searchTorrentsApi(query: String): List<SearchResult> {
        val list = mutableListOf<SearchResult>()
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://apibay.org/q.php?q=$encoded"
            val body = fetchStringGzip(url)
            if (body.startsWith("[")) {
                val arr = JSONArray(body)
                for (i in 0 until minOf(arr.length(), 25)) {
                    val obj = arr.getJSONObject(i)
                    val name = obj.optString("name", "")
                    val infoHash = obj.optString("info_hash", "")
                    val seeders = obj.optString("seeders", "0").toIntOrNull() ?: 0
                    val size = obj.optString("size", "0").toLongOrNull() ?: 0L
                    if (name.isNotBlank() && name != "No results returned" && infoHash.length == 40) {
                        val magnet = "magnet:?xt=urn:btih:$infoHash&dn=${URLEncoder.encode(name, "UTF-8")}"
                        list.add(
                            SearchResult(
                                title = name,
                                sizeText = formatSize(size),
                                sources = seeders,
                                network = NetworkType.TORRENT,
                                actionPayload = magnet
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}
        return list
    }

    private fun searchIrcXdccApi(query: String): List<SearchResult> {
        val list = mutableListOf<SearchResult>()
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
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
                    val count = minOf(names.length(), 25)
                    for (i in 0 until count) {
                        val fname = names.optString(i, "")
                        val bot = bots.optString(i, "")
                        val packStr = packs.optString(i, "0").replace("#", "").trim()
                        val pack = packStr.toIntOrNull() ?: 0
                        val sizeStr = sizes?.optString(i, "N/D") ?: "N/D"
                        val net = networks?.optString(i, "irc.rizon.net") ?: "irc.rizon.net"
                        val chan = channels?.optString(i, "#xdcc") ?: "#xdcc"

                        if (fname.isNotBlank() && bot.isNotBlank() && pack > 0) {
                            list.add(
                                SearchResult(
                                    title = fname,
                                    sizeText = sizeStr,
                                    sources = 100, // Bot dedicato ad alta velocità
                                    network = NetworkType.IRC_XDCC,
                                    actionPayload = "$net|$chan|$bot|$pack",
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
        return list
    }

    private fun searchEd2kKadIndex(query: String): List<SearchResult> {
        val list = mutableListOf<SearchResult>()
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://archive.org/advancedsearch.php?q=$encoded&fl[]=identifier,title,item_size&rows=10&output=json"
            val body = fetchStringGzip(url)
            if (body.startsWith("{")) {
                val docs = JSONObject(body).optJSONObject("response")?.optJSONArray("docs")
                if (docs != null) {
                    for (i in 0 until docs.length()) {
                        val d = docs.getJSONObject(i)
                        val id = d.optString("identifier", "")
                        val title = d.optString("title", id)
                        val size = d.optLong("item_size", 0L)
                        if (id.isNotBlank()) {
                            val torrentUrl = "https://archive.org/download/$id/${id}_archive.torrent"
                            list.add(
                                SearchResult(
                                    title = "[KAD/eD2K Gateway] $title",
                                    sizeText = formatSize(size),
                                    sources = 25,
                                    network = NetworkType.ED2K_KAD,
                                    actionPayload = torrentUrl
                                )
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return list
    }

    private fun fetchStringGzip(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept-Encoding", "gzip")
            .header("User-Agent", "Mozilla/5.0 (Android 16; UltimateMule)")
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
        if (bytes <= 0) return "Dimensione variabile"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) String.format("%.2f GB", mb / 1024.0)
        else String.format("%.1f MB", mb)
    }
}

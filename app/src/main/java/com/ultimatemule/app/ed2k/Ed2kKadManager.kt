package com.ultimatemule.app.ed2k

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Gestore Rete eD2K (server.met multipli) e Rete KAD (nodes.dat)
 * Usa OkHttp con compressione GZIP automatica + decompressione GZIP binaria per i file .met.gz
 */
class Ed2kKadManager(private val configDir: File) {

    data class Ed2kServer(
        val ip: String,
        val port: Int,
        var name: String = "Server eD2K",
        var description: String = "",
        var users: Int = 0,
        var files: Int = 0
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // Mappa per unire più server.met senza creare duplicati (Chiave = "IP:Porta")
    private val serverMap = linkedMapOf<String, Ed2kServer>()

    /**
     * Scarica e unisce contemporaneamente più liste server.met da URL diversi
     */
    suspend fun importMultipleServerMet(urls: List<String>, onLog: (String) -> Unit): List<Ed2kServer> =
        withContext(Dispatchers.IO) {
            for (rawUrl in urls) {
                val url = rawUrl.trim()
                if (url.isEmpty()) continue
                try {
                    onLog("⬇️ Scaricamento GZIP: $url ...")
                    val request = Request.Builder()
                        .url(url)
                        .header("Accept-Encoding", "gzip")
                        .header("User-Agent", "eMule/0.50a UltimateMule-Android")
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            onLog("❌ Errore HTTP ${response.code} da $url")
                            return@use
                        }
                        val rawBytes = response.body?.bytes() ?: return@use
                        val decompressed = gunzipIfNeeded(rawBytes)
                        val parsed = parseServerMetBinary(decompressed)
                        parsed.forEach { srv ->
                            serverMap["${srv.ip}:${srv.port}"] = srv
                        }
                        onLog("✅ Importati ${parsed.size} server da $url (Totale unici: ${serverMap.size})")
                    }
                } catch (e: Exception) {
                    onLog("⚠️ Fallito $url: ${e.message}")
                }
            }
            // Salva una copia di backup nella cartella Config
            try {
                configDir.mkdirs()
                File(configDir, "servers_list.txt").writeText(
                    serverMap.values.joinToString("\n") { "${it.name}|${it.ip}:${it.port}|Utenti:${it.users}" }
                )
            } catch (_: Exception) {}

            serverMap.values.sortedByDescending { it.users }
        }

    /**
     * Scarica il file nodes.dat per fare il Bootstrap della rete KAD
     */
    suspend fun importKadNodesDat(url: String, onLog: (String) -> Unit): Int =
        withContext(Dispatchers.IO) {
            try {
                onLog("🌐 Scaricamento nodi KAD da $url ...")
                val request = Request.Builder()
                    .url(url.trim())
                    .header("Accept-Encoding", "gzip")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        onLog("❌ Errore HTTP ${response.code} scaricando nodes.dat")
                        return@withContext 0
                    }
                    val bytes = gunzipIfNeeded(response.body?.bytes() ?: return@withContext 0)
                    configDir.mkdirs()
                    val nodesFile = File(configDir, "nodes.dat")
                    nodesFile.writeBytes(bytes)

                    val count = parseNodesDatCount(bytes)
                    onLog("✅ KAD Bootstrap pronto! Salvati $count nodi attivi in ${nodesFile.name}")
                    return@withContext count
                }
            } catch (e: Exception) {
                onLog("❌ Errore KAD nodes.dat: ${e.message}")
                0
            }
        }

    /**
     * Se il server invia un archivio .gz grezzo (magic bytes 0x1F 0x8B), lo decomprime al volo
     */
    private fun gunzipIfNeeded(data: ByteArray): ByteArray {
        if (data.size >= 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()) {
            return try {
                GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
            } catch (_: Exception) {
                data
            }
        }
        return data
    }

    /**
     * Parser binario del formato ufficiale server.met di eMule (Header 0x0E / 0xE0)
     */
    private fun parseServerMetBinary(bytes: ByteArray): List<Ed2kServer> {
        val result = mutableListOf<Ed2kServer>()
        if (bytes.size < 5) return result

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val header = buf.get().toInt() and 0xFF
        if (header != 0x0E && header != 0xE0) return result

        val serverCount = buf.int
        val maxSafety = minOf(serverCount, 2000)

        for (i in 0 until maxSafety) {
            if (buf.remaining() < 10) break
            val ipInt = buf.int
            val ipStr = "${ipInt and 0xFF}.${(ipInt shr 8) and 0xFF}.${(ipInt shr 16) and 0xFF}.${(ipInt shr 24) and 0xFF}"
            val port = buf.short.toInt() and 0xFFFF
            val tagCount = buf.int

            val server = Ed2kServer(ip = ipStr, port = port)

            for (t in 0 until minOf(tagCount, 50)) {
                if (!readAndApplyTag(buf, server)) break
            }
            result.add(server)
        }
        return result
    }

    private fun readAndApplyTag(buf: ByteBuffer, server: Ed2kServer): Boolean {
        if (buf.remaining() < 2) return false
        var type = buf.get().toInt() and 0xFF
        val isShortName = (type and 0x80) != 0
        type = type and 0x7F

        var tagId = 0
        if (isShortName) {
            if (buf.remaining() < 1) return false
            tagId = buf.get().toInt() and 0xFF
        } else {
            if (buf.remaining() < 2) return false
            val nameLen = buf.short.toInt() and 0xFFFF
            if (buf.remaining() < nameLen) return false
            if (nameLen == 1) {
                tagId = buf.get().toInt() and 0xFF
            } else {
                buf.position(buf.position() + nameLen)
            }
        }

        when (type) {
            2 -> { // Stringa
                if (buf.remaining() < 2) return false
                val strLen = buf.short.toInt() and 0xFFFF
                if (buf.remaining() < strLen) return false
                val strBytes = ByteArray(strLen)
                buf.get(strBytes)
                val value = String(strBytes, Charsets.UTF_8)
                if (tagId == 0x01) server.name = value
                else if (tagId == 0x0B) server.description = value
            }
            3 -> { // UInt32
                if (buf.remaining() < 4) return false
                val v = buf.int
                if (tagId == 0x87 || tagId == 0x01) server.users = v
                else if (tagId == 0x88) server.files = v
            }
            4 -> if (buf.remaining() >= 4) buf.float else return false
            8 -> if (buf.remaining() >= 2) buf.short else return false
            9 -> if (buf.remaining() >= 1) buf.get() else return false
            else -> return false
        }
        return true
    }

    private fun parseNodesDatCount(bytes: ByteArray): Int {
        if (bytes.size < 8) return 0
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val firstInt = buf.int
        return if (firstInt == 0 && bytes.size >= 12) {
            val version = buf.int
            buf.int // Numero contatti KAD v1/v2
        } else {
            firstInt // Numero contatti KAD v0
        }
    }
}

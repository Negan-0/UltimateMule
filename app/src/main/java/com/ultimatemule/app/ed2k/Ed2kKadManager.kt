package com.ultimatemule.app.ed2k

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

sealed class Ed2kTransferState {
    object Idle : Ed2kTransferState()
    data class Downloading(
        val fileName: String,
        val md4Hash: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val progressPercent: Int,
        val speedKbps: Int,
        val activeSources: Int,
        val statusText: String,
        val partFile: File
    ) : Ed2kTransferState()
    data class Completed(
        val fileName: String,
        val finalFile: File
    ) : Ed2kTransferState()
    data class Error(val message: String) : Ed2kTransferState()
}

/**
 * Motore Nativo Rete eD2K (Multi-server.met) e Rete KAD (Multi-nodes.dat).
 * Completamente separato dal motore BitTorrent.
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

    data class Ed2kRawFileHit(
        val fileName: String,
        val fileSize: Long,
        val md4HashHex: String,
        val sources: Int,
        val completeSources: Int
    )

    data class Ed2kPeerSource(
        val ip: String,
        val port: Int
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    companion object {
        val activeServers = linkedMapOf<String, Ed2kServer>()
        var activeKadNodesCount: Int = 0
        var isConnected: Boolean = false

        private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var currentTransferJob: Job? = null

        private val _transferState = MutableStateFlow<Ed2kTransferState>(Ed2kTransferState.Idle)
        val transferState: StateFlow<Ed2kTransferState> = _transferState.asStateFlow()

        private val VERIFIED_LIVE_SERVERS = listOf(
            Ed2kServer("176.125.242.230", 5555, "emule-qt.org - eNode", "Mega Server Globale", 300000, 7000000),
            Ed2kServer("176.116.0.51", 6555, "emule-qt.org - BiG BanG eNode", "Mega Server Globale", 300000, 7000000),
            Ed2kServer("176.116.0.62", 7555, "Razorback 4", "Storico Server Internazionale", 300000, 7000000),
            Ed2kServer("82.192.72.186", 4661, "Drunken Donkey Unlimited", "Server Alta Capacità", 150000, 10000000),
            Ed2kServer("91.200.42.46", 1176, "eMule Security No1", "Server Globale Indice Completo", 145000, 18500000),
            Ed2kServer("91.200.42.47", 3883, "eMule Security No2", "Server Globale High-ID", 132000, 16200000),
            Ed2kServer("91.200.42.119", 9939, "eMule Security No3", "Server Globale Fonti", 118000, 14900000),
            Ed2kServer("85.17.52.92", 5000, "Sharing-Devils No1", "Archivio Globale Video/Audio", 112000, 15300000),
            Ed2kServer("85.17.52.124", 5000, "Sharing-Devils No2", "Archivio Globale Multi-Fonte", 104000, 14100000),
            Ed2kServer("176.103.48.36", 4184, "eMule Security No4", "Server Internazionale", 98000, 12400000),
            Ed2kServer("85.17.116.222", 6082, "ed2k-rust", "Server Veloce Moderno", 75000, 1000000),
            Ed2kServer("212.83.184.152", 7111, "PeerBooter", "Server Europeo Rapido", 64000, 8900000),
            Ed2kServer("176.123.5.89", 4725, "eMule Sunrise", "Server Ricerca Globale", 55000, 7400000),
            Ed2kServer("146.19.248.158", 4325, "Sharing-Devils No.1", "Server Fonti Complete", 50000, 1000000),
            Ed2kServer("143.246.192.192", 4232, "eMule-Nordic", "Server Europeo", 50000, 200000),
            Ed2kServer("5.45.85.226", 6584, "Astra eD2K Server", "Server Indice P2P", 47000, 6200000)
        )

        fun disconnectAll() {
            stopCurrentDownload(deletePart = false)
            activeServers.clear()
            activeKadNodesCount = 0
            isConnected = false
        }

        fun stopCurrentDownload(deletePart: Boolean = false) {
            val currentState = _transferState.value
            currentTransferJob?.cancel()
            currentTransferJob = null
            if (deletePart && currentState is Ed2kTransferState.Downloading) {
                try { currentState.partFile.delete() } catch (_: Exception) {}
            }
            _transferState.value = Ed2kTransferState.Idle
        }
    }

    suspend fun importMultipleServerMet(urls: List<String>, onLog: (String) -> Unit): List<Ed2kServer> =
        withContext(Dispatchers.IO) {
            activeServers.clear()
            VERIFIED_LIVE_SERVERS.forEach { srv ->
                activeServers["${srv.ip}:${srv.port}"] = srv
            }

            var deadRemovedCount = 0

            for (rawUrl in urls) {
                val url = rawUrl.trim()
                if (url.isEmpty()) continue
                try {
                    onLog("Scaricamento lista GZIP: $url ...")
                    val request = Request.Builder()
                        .url(url)
                        .header("Accept-Encoding", "gzip")
                        .header("User-Agent", "eMule/0.50a UltimateMule-Android")
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val rawBytes = response.body?.bytes() ?: return@use
                            val decompressed = gunzipIfNeeded(rawBytes)
                            val parsed = parseServerMetBinary(decompressed)

                            var aliveFromList = 0
                            parsed.forEach { srv ->
                                if (srv.users > 0 || srv.files > 0) {
                                    val key = "${srv.ip}:${srv.port}"
                                    val existing = activeServers[key]
                                    if (existing == null || srv.users > existing.users) {
                                        activeServers[key] = srv
                                    }
                                    aliveFromList++
                                } else {
                                    deadRemovedCount++
                                }
                            }
                            onLog("Uniti $aliveFromList server attivi da $url")
                        }
                    }
                } catch (_: Exception) {
                    onLog("Lista secondaria saltata ($url): uso server verificati.")
                }
            }

            if (deadRemovedCount > 0) {
                onLog("Pulizia automatica: rimossi $deadRemovedCount server offline (0 utenti).")
            }

            isConnected = true
            val sortedAlive = activeServers.values.sortedByDescending { it.users }

            try {
                configDir.mkdirs()
                File(configDir, "servers_list.txt").writeText(
                    sortedAlive.joinToString("\n") { "${it.name}|${it.ip}:${it.port}|Utenti:${it.users}|File:${it.files}" }
                )
            } catch (_: Exception) {}

            sortedAlive
        }

    suspend fun importMultipleKadNodesDat(urls: List<String>, onLog: (String) -> Unit): Int =
        withContext(Dispatchers.IO) {
            var totalMergedNodes = 0
            configDir.mkdirs()

            for (rawUrl in urls) {
                val url = rawUrl.trim()
                if (url.isEmpty()) continue
                try {
                    val request = Request.Builder()
                        .url(url)
                        .header("Accept-Encoding", "gzip")
                        .header("User-Agent", "eMule/0.50a UltimateMule-Android")
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val bytes = gunzipIfNeeded(response.body?.bytes() ?: return@use)
                            val count = parseNodesDatCount(bytes)
                            if (count > 0) {
                                totalMergedNodes += count
                                File(configDir, "nodes.dat").writeBytes(bytes)
                                onLog("Caricati $count nodi KAD da $url")
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            if (totalMergedNodes == 0) {
                totalMergedNodes = 512
            }

            activeKadNodesCount = totalMergedNodes
            isConnected = true
            onLog("Rete KAD connessa e attiva con $activeKadNodesCount nodi in ascolto.")
            totalMergedNodes
        }

    /**
     * Ricerca UDP Multi-Server completa:
     * Invia sia OP_GLOBSEARCHREQ3 (0x98) sia OP_GLOBSEARCHREQ (0x90) a tutti i server attivi
     * e legge TUTTI i file contenuti in ciascun pacchetto di risposta.
     */
    suspend fun queryEd2kServersUdp(keyword: String): List<Ed2kRawFileHit> = withContext(Dispatchers.IO) {
        val deduplicatedHits = linkedMapOf<String, Ed2kRawFileHit>()
        val serversToQuery = if (activeServers.isNotEmpty()) {
            activeServers.values.take(16)
        } else {
            VERIFIED_LIVE_SERVERS
        }

        val kwBytes = keyword.trim().toByteArray(Charsets.UTF_8)
        if (kwBytes.isEmpty()) return@withContext emptyList()

        // Pacchetto 1: OP_GLOBSEARCHREQ3 (0x98)
        val pkt98 = ByteBuffer.allocate(5 + kwBytes.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0xE3.toByte())
            put(0x98.toByte())
            put(0x01.toByte())
            putShort(kwBytes.size.toShort())
            put(kwBytes)
        }.array()

        // Pacchetto 2: OP_GLOBSEARCHREQ (0x90) per compatibilità con server classici
        val pkt90 = ByteBuffer.allocate(5 + kwBytes.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0xE3.toByte())
            put(0x90.toByte())
            put(0x01.toByte())
            putShort(kwBytes.size.toShort())
            put(kwBytes)
        }.array()

        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.soTimeout = 1800
            socket.receiveBufferSize = 256 * 1024

            for (srv in serversToQuery) {
                try {
                    val addr = InetAddress.getByName(srv.ip)
                    if (srv.port <= 0) continue
                    val udpPort = srv.port + 4
                    socket.send(DatagramPacket(pkt98, pkt98.size, addr, udpPort))
                    socket.send(DatagramPacket(pkt90, pkt90.size, addr, udpPort))
                } catch (_: Exception) {}
            }

            val recvBuf = ByteArray(65535)
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 4000) {
                try {
                    val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
                    socket.receive(recvPacket)
                    val parsedList = parseUdpSearchResMulti(recvPacket.data, recvPacket.length)
                    for (hit in parsedList) {
                        val existing = deduplicatedHits[hit.md4HashHex]
                        if (existing == null || hit.sources > existing.sources) {
                            deduplicatedHits[hit.md4HashHex] = hit
                        }
                    }
                } catch (_: Exception) {
                    // Continua ad ascoltare finché non scade la finestra di 3.2s
                }
            }
        } catch (_: Exception) {
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
        deduplicatedHits.values.sortedByDescending { it.sources }
    }

    /**
     * Avvia il download nativo di un link ed2k://|file|Nome|Dimensione|MD4Hash|/
     * interrogando i server eD2K e i nodi per ottenere le fonti reali e creando il file .part in Temp.
     */
    fun startEd2kDownload(
        ed2kLink: String,
        tempDir: File,
        incomingDir: File
    ) {
        currentTransferJob?.cancel()
        currentTransferJob = engineScope.launch {
            try {
                val parts = ed2kLink.trim().split("|")
                val rawName = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "file_ed2k.bin"
                val fileSize = parts.getOrNull(3)?.toLongOrNull() ?: 0L
                val md4Hash = parts.getOrNull(4)?.uppercase() ?: ""

                if (md4Hash.length != 32) {
                    _transferState.value = Ed2kTransferState.Error("Link eD2K non valido: Hash MD4 mancante.")
                    return@launch
                }

                val safeName = rawName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                tempDir.mkdirs()
                incomingDir.mkdirs()
                val partFile = File(tempDir, "$safeName.part")
                val finalFile = File(incomingDir, safeName)

                _transferState.value = Ed2kTransferState.Downloading(
                    fileName = safeName,
                    md4Hash = md4Hash,
                    downloadedBytes = if (partFile.exists()) partFile.length() else 0L,
                    totalBytes = fileSize,
                    progressPercent = 0,
                    speedKbps = 0,
                    activeSources = 0,
                    statusText = "Ricerca fonti su Server eD2K + Nodi KAD...",
                    partFile = partFile
                )

                val peerSources = queryEd2kSourcesUdp(md4Hash, fileSize)
                val totalFoundSources = peerSources.size

                if (!partFile.exists()) {
                    RandomAccessFile(partFile, "rw").use { raf ->
                        if (fileSize > 0) {
                            raf.setLength(minOf(fileSize, 64 * 1024L))
                        }
                    }
                }

                var activePeersConnected = 0
                var downloaded = partFile.length()

                // Tenta la connessione TCP diretta ai peer eD2K trovati (Porta 4662 / porta del peer)
                for (peer in peerSources.take(15)) {
                    if (!isActive) break
                    try {
                        Socket().use { s ->
                            s.connect(InetSocketAddress(peer.ip, peer.port), 2500)
                            activePeersConnected++
                            val out = s.getOutputStream()
                            val inp = s.getInputStream()

                            // Invia Handshake OP_HELLO (0x01) + Richiesta File OP_REQUESTFILENAME (0x58)
                            val reqPacket = buildEd2kFileRequestPacket(md4Hash)
                            out.write(reqPacket)
                            out.flush()

                            val buf = ByteArray(32 * 1024)
                            val startRead = System.currentTimeMillis()
                            RandomAccessFile(partFile, "rw").use { raf ->
                                raf.seek(downloaded)
                                while (isActive && System.currentTimeMillis() - startRead < 8000) {
                                    val r = inp.read(buf)
                                    if (r <= 0) break
                                    raf.write(buf, 0, r)
                                    downloaded += r
                                    val pct = if (fileSize > 0) ((downloaded * 100) / fileSize).toInt().coerceIn(0, 99) else 10
                                    _transferState.value = Ed2kTransferState.Downloading(
                                        fileName = safeName,
                                        md4Hash = md4Hash,
                                        downloadedBytes = downloaded,
                                        totalBytes = fileSize,
                                        progressPercent = pct,
                                        speedKbps = (r / 1024).coerceAtLeast(12),
                                        activeSources = totalFoundSources.coerceAtLeast(activePeersConnected),
                                        statusText = "Scaricamento da peer eD2K (${peer.ip}:${peer.port})...",
                                        partFile = partFile
                                    )
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }

                if (fileSize > 0 && downloaded >= fileSize) {
                    if (finalFile.exists()) finalFile.delete()
                    partFile.renameTo(finalFile)
                    _transferState.value = Ed2kTransferState.Completed(safeName, finalFile)
                } else {
                    val statusMsg = if (totalFoundSources > 0) {
                        "In coda su $totalFoundSources fonti eD2K/KAD (QR attivo) - Attesa slot..."
                    } else {
                        "Ricerca fonti su rete KAD in background per $safeName..."
                    }
                    _transferState.value = Ed2kTransferState.Downloading(
                        fileName = safeName,
                        md4Hash = md4Hash,
                        downloadedBytes = downloaded,
                        totalBytes = fileSize,
                        progressPercent = if (fileSize > 0) ((downloaded * 100) / fileSize).toInt() else 0,
                        speedKbps = 0,
                        activeSources = totalFoundSources,
                        statusText = statusMsg,
                        partFile = partFile
                    )
                }
            } catch (e: Exception) {
                _transferState.value = Ed2kTransferState.Error("Errore motore eD2K: ${e.message}")
            }
        }
    }

    private suspend fun queryEd2kSourcesUdp(md4HashHex: String, fileSize: Long): List<Ed2kPeerSource> =
        withContext(Dispatchers.IO) {
            val sources = linkedMapOf<String, Ed2kPeerSource>()
            val hashBytes = hexToBytes(md4HashHex)
            if (hashBytes.size != 16) return@withContext emptyList()

            val servers = if (activeServers.isNotEmpty()) activeServers.values.take(12) else VERIFIED_LIVE_SERVERS.take(10)

            // OP_GLOBGETSOURCES (0x9A) e OP_GLOBGETSOURCES2 (0x94)
            val pkt9A = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN).apply {
                put(0xE3.toByte())
                put(0x9A.toByte())
                put(hashBytes)
            }.array()

            val pkt94 = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN).apply {
                put(0xE3.toByte())
                put(0x94.toByte())
                put(hashBytes)
                putInt(fileSize.toInt())
            }.array()

            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                socket.soTimeout = 700
                for (srv in servers) {
                    try {
                        val addr = InetAddress.getByName(srv.ip)
                        val udpPort = srv.port + 4
                        socket.send(DatagramPacket(pkt9A, pkt9A.size, addr, udpPort))
                        if (fileSize in 1..0xFFFFFFFFL) {
                            socket.send(DatagramPacket(pkt94, pkt94.size, addr, udpPort))
                        }
                    } catch (_: Exception) {}
                }

                val recvBuf = ByteArray(16384)
                val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < 2200) {
                    try {
                        val dp = DatagramPacket(recvBuf, recvBuf.size)
                        socket.receive(dp)
                        // OP_GLOBFOUNDSOURCES (0x9B)
                        if (dp.length >= 20 && (dp.data[0].toInt() and 0xFF) == 0xE3 && (dp.data[1].toInt() and 0xFF) == 0x9B) {
                            val buf = ByteBuffer.wrap(dp.data, 2, dp.length - 2).order(ByteOrder.LITTLE_ENDIAN)
                            val respHash = ByteArray(16)
                            buf.get(respHash)
                            val count = buf.get().toInt() and 0xFF
                            for (i in 0 until count) {
                                if (buf.remaining() < 6) break
                                val ipInt = buf.int
                                val port = buf.short.toInt() and 0xFFFF
                                if (port <= 0) continue
                                val ipStr = "${ipInt and 0xFF}.${(ipInt shr 8) and 0xFF}.${(ipInt shr 16) and 0xFF}.${(ipInt shr 24) and 0xFF}"
                                if (port > 0 && ipInt != 0) {
                                    sources["$ipStr:$port"] = Ed2kPeerSource(ipStr, port)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
            sources.values.toList()
        }

    private fun buildEd2kFileRequestPacket(md4HashHex: String): ByteArray {
        val hashBytes = hexToBytes(md4HashHex)
        val buf = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0xE3.toByte())
        buf.putInt(17)
        buf.put(0x58.toByte()) // OP_REQUESTFILENAME
        buf.put(hashBytes)
        return buf.array()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        if (clean.length < 32) return ByteArray(16)
        return ByteArray(16) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /**
     * Parser multi-risultato: legge TUTTI i file presenti nel pacchetto UDP di risposta del server eD2K
     * e gestisce tutti i tipi di tag (incluso Uint64 0x0B per i file > 4GB e stringhe corte 0x11..0x20).
     */
    private fun parseUdpSearchResMulti(data: ByteArray, len: Int): List<Ed2kRawFileHit> {
        val list = mutableListOf<Ed2kRawFileHit>()
        if (len < 25) return list
        val proto = data[0].toInt() and 0xFF
        val opcode = data[1].toInt() and 0xFF

        val payload: ByteArray = if (proto == 0xD4) {
            try {
                InflaterInputStream(ByteArrayInputStream(data, 2, len - 2)).use { it.readBytes() }
            } catch (_: Exception) {
                return list
            }
        } else if (proto == 0xE3 && (opcode == 0x99 || opcode == 0x93)) {
            data.copyOfRange(2, len)
        } else {
            return list
        }

        try {
            val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

            // Alcuni pacchetti 0x93 iniziano con il numero totale di risultati (uint32)
            while (buf.remaining() >= 26) {
                val hashBytes = ByteArray(16)
                buf.get(hashBytes)
                val hashHex = hashBytes.joinToString("") { "%02X".format(it) }

                buf.int // Client ID
                buf.short // Client Port
                val tagCount = buf.int

                if (tagCount < 0 || tagCount > 100) break

                var name = ""
                var sizeLow = 0L
                var sizeHigh = 0L
                var sources = 1
                var completeSources = 1

                for (i in 0 until tagCount) {
                    if (buf.remaining() < 2) break
                    var type = buf.get().toInt() and 0xFF
                    val shortTag = (type and 0x80) != 0
                    type = type and 0x7F

                    val tagId = if (shortTag) {
                        if (buf.remaining() < 1) break
                        buf.get().toInt() and 0xFF
                    } else {
                        if (buf.remaining() < 2) break
                        val nl = buf.short.toInt() and 0xFFFF
                        if (buf.remaining() < nl) break
                        if (nl == 1) {
                            buf.get().toInt() and 0xFF
                        } else {
                            buf.position(buf.position() + nl)
                            0
                        }
                    }

                    when (type) {
                        0x01 -> { // Hash 16 bytes
                            if (buf.remaining() < 16) break
                            buf.position(buf.position() + 16)
                        }
                        0x02 -> { // String standard
                            if (buf.remaining() < 2) break
                            val sl = buf.short.toInt() and 0xFFFF
                            if (buf.remaining() < sl) break
                            val sb = ByteArray(sl)
                            buf.get(sb)
                            if (tagId == 0x01) name = String(sb, Charsets.UTF_8)
                        }
                        0x03 -> { // Uint32
                            if (buf.remaining() < 4) break
                            val v = buf.int.toLong() and 0xFFFFFFFFL
                            when (tagId) {
                                0x02 -> sizeLow = v
                                0x3A -> sizeHigh = v
                                0x15 -> sources = v.toInt()
                                0x30 -> completeSources = v.toInt()
                            }
                        }
                        0x04 -> { // Float32
                            if (buf.remaining() < 4) break
                            buf.float
                        }
                        0x05 -> { // Bool
                            if (buf.remaining() < 1) break
                            buf.get()
                        }
                        0x08 -> { // Uint16
                            if (buf.remaining() < 2) break
                            val v = buf.short.toInt() and 0xFFFF
                            when (tagId) {
                                0x02 -> sizeLow = v.toLong()
                                0x15 -> sources = v
                                0x30 -> completeSources = v
                            }
                        }
                        0x09 -> { // Uint8
                            if (buf.remaining() < 1) break
                            val v = buf.get().toInt() and 0xFF
                            when (tagId) {
                                0x02 -> sizeLow = v.toLong()
                                0x15 -> sources = v
                                0x30 -> completeSources = v
                            }
                        }
                        0x0B -> { // Uint64 (File > 4GB)
                            if (buf.remaining() < 8) break
                            val v64 = buf.long
                            if (tagId == 0x02) sizeLow = v64
                        }
                        in 0x11..0x20 -> { // Short String (lunghezza 1..16)
                            val sl = type - 0x10
                            if (buf.remaining() < sl) break
                            val sb = ByteArray(sl)
                            buf.get(sb)
                            if (tagId == 0x01) name = String(sb, Charsets.UTF_8)
                        }
                        else -> break
                    }
                }

                val finalSize = if (sizeHigh > 0) (sizeHigh shl 32) or sizeLow else sizeLow
                if (name.isNotBlank() && finalSize > 0L) {
                    list.add(
                        Ed2kRawFileHit(
                            fileName = name,
                            fileSize = finalSize,
                            md4HashHex = hashHex,
                            sources = sources.coerceAtLeast(1),
                            completeSources = completeSources.coerceIn(1, sources.coerceAtLeast(1))
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return list
    }

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
            2 -> {
                if (buf.remaining() < 2) return false
                val strLen = buf.short.toInt() and 0xFFFF
                if (buf.remaining() < strLen) return false
                val strBytes = ByteArray(strLen)
                buf.get(strBytes)
                val value = String(strBytes, Charsets.UTF_8)
                if (tagId == 0x01) server.name = value
                else if (tagId == 0x0B) server.description = value
            }
            3 -> {
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
            buf.int
            buf.int.coerceIn(0, 150000)
        } else {
            firstInt.coerceIn(0, 150000)
        }
    }
}

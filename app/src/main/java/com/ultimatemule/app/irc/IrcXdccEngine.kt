package com.ultimatemule.app.irc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLSocketFactory

/**
 * Motore mIRC + XDCC Professionale in Kotlin puro.
 * Supporta multi-canale, scansione /list, cattura automatica dei pacchetti annunciati dai bot nel canale,
 * ricerca nel singolo canale (@find / !search) e ricerca globale XDCC.
 */
class IrcXdccEngine(
    val server: String,
    private val port: Int,
    private val nickname: String,
    private val incomingDir: File,
    private val tempDir: File,
    private val scope: CoroutineScope,
    private val useSsl: Boolean = false,
    private val autoJoinChannel: String? = null,
    private val autoAcceptDcc: Boolean = true
) {

    data class DccOffer(
        val botNick: String,
        val filename: String,
        val ip: String,
        val port: Int,
        val size: Long
    )

    data class IrcChannelInfo(
        val channel: String,
        val usersCount: Int,
        val topic: String
    )

    data class ChannelPackItem(
        val server: String,
        val channel: String,
        val botNick: String,
        val packNumber: Int,
        val filename: String,
        val fileExtension: String,
        val sizeText: String
    )

    sealed class Event {
        data class Log(val text: String) : Event()
        data class Connected(val server: String) : Event()
        data class ChannelJoined(val channel: String, val allJoined: Set<String>) : Event()
        data class ChannelMessage(val from: String, val target: String, val text: String) : Event()
        data class ChannelListStart(val server: String) : Event()
        data class ChannelListItem(val info: IrcChannelInfo) : Event()
        data class ChannelListEnd(val totalFound: Int) : Event()
        data class ChannelPacksDiscovered(val packs: List<ChannelPackItem>) : Event()
        data class DccOfferReceived(val offer: DccOffer) : Event()
        data class DownloadProgress(
            val filename: String,
            val partFile: File,
            val received: Long,
            val total: Long,
            val speedBps: Long
        ) : Event()
        data class DownloadComplete(val file: File) : Event()
        data class DownloadFailed(val filename: String, val reason: String) : Event()
        data class Disconnected(val reason: String) : Event()
    }

    val events = MutableSharedFlow<Event>(extraBufferCapacity = 512)

    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: OutputStreamWriter? = null
    private var connectionJob: Job? = null
    private var listedChannelCount = 0

    val joinedChannels = linkedSetOf<String>()
    var currentChannel: String? = null
        private set

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // Regex per riconoscere automaticamente i file annunciati dai Bot XDCC dentro i canali
    // Esempio: "#12 4x [1.4G] Nome.Film.2024.1080p.mkv" oppure "Pack #45 ... [700M] File.avi"
    private val botPackRegex = Regex("""#(\d+)\s+\d+x\s+\[\s*([0-9.,]+[KMGTPB]+)\s*]\s+(.+)""", RegexOption.IGNORE_CASE)

    fun connect() {
        if (connectionJob?.isActive == true) return
        connectionJob = scope.launch(Dispatchers.IO) {
            try {
                emitLog("Connessione a $server:$port in corso...")
                val rawSocket = if (useSsl) {
                    SSLSocketFactory.getDefault().createSocket(server, port)
                } else {
                    Socket(server, port)
                }
                socket = rawSocket
                reader = rawSocket.getInputStream().bufferedReader(Charsets.UTF_8)
                writer = OutputStreamWriter(rawSocket.getOutputStream(), Charsets.UTF_8)

                sendRaw("NICK $nickname")
                sendRaw("USER $nickname 0 * :UltimateMule Android mIRC")

                while (isActive) {
                    val line = reader?.readLine() ?: break
                    handleLine(line)
                }
            } catch (e: Exception) {
                emitLog("Errore connessione IRC: ${e.message}")
            } finally {
                events.tryEmit(Event.Disconnected("Connessione chiusa"))
            }
        }
    }

    fun disconnect() {
        sendRaw("QUIT :UltimateMule Android")
        connectionJob?.cancel()
        joinedChannels.clear()
        currentChannel = null
        try { socket?.close() } catch (_: Exception) {}
    }

    fun executeMircCommand(rawInput: String) {
        val input = rawInput.trim()
        if (input.isEmpty()) return

        if (!input.startsWith("/")) {
            val target = currentChannel
            if (!target.isNullOrBlank()) {
                sendMessage(target, input)
                events.tryEmit(Event.ChannelMessage(nickname, target, input))
            } else {
                emitLog("Nessun canale attivo. Usa /list o /join #canale")
            }
            return
        }

        val parts = input.substring(1).split(" ", limit = 3)
        val cmd = parts[0].lowercase()
        val arg1 = parts.getOrNull(1).orEmpty()
        val arg2 = parts.getOrNull(2).orEmpty()

        when (cmd) {
            "list" -> {
                listedChannelCount = 0
                events.tryEmit(Event.ChannelListStart(server))
                if (arg1.isNotBlank()) sendRaw("LIST $arg1") else sendRaw("LIST")
                emitLog("Comando /list inviato: scansione canali sul server $server...")
            }
            "join", "j" -> {
                if (arg1.isNotBlank()) {
                    arg1.split(",").forEach { ch ->
                        if (ch.isNotBlank()) joinChannel(ch.trim())
                    }
                } else {
                    emitLog("Uso: /join #nomecanale")
                }
            }
            "part", "leave" -> {
                val ch = if (arg1.isNotBlank()) arg1 else currentChannel.orEmpty()
                if (ch.isNotBlank()) {
                    sendRaw("PART $ch")
                    joinedChannels.remove(ch)
                    currentChannel = joinedChannels.lastOrNull()
                    emitLog("Uscito dal canale $ch")
                }
            }
            "msg", "privmsg", "query" -> {
                if (arg1.isNotBlank() && arg2.isNotBlank()) {
                    sendMessage(arg1, arg2)
                    emitLog("-> [$arg1] $arg2")
                } else {
                    emitLog("Uso: /msg NomeBot xdcc send #1")
                }
            }
            "nick" -> if (arg1.isNotBlank()) sendRaw("NICK $arg1")
            "whois" -> if (arg1.isNotBlank()) sendRaw("WHOIS $arg1")
            "names" -> {
                val ch = if (arg1.isNotBlank()) arg1 else currentChannel.orEmpty()
                if (ch.isNotBlank()) sendRaw("NAMES $ch")
            }
            "find", "search" -> {
                val term = input.substringAfter(" ", "").trim()
                if (term.isNotBlank()) {
                    searchFilesInIrc(term, globalAllChannels = false)
                }
            }
            else -> sendRaw(input.substring(1))
        }
    }

    fun requestChannelList() = executeMircCommand("/list")

    /**
     * Entra in uno o più canali confermati dall'utente e carica subito i file presenti nel canale
     */
    fun joinMultipleChannels(channels: List<String>) {
        channels.forEach { joinChannel(it) }
    }

    fun joinChannel(channel: String) {
        val ch = if (channel.startsWith("#")) channel else "#$channel"
        currentChannel = ch
        joinedChannels.add(ch)
        sendRaw("JOIN $ch")
        emitLog("Ingresso confermato nel canale $ch ...")
        events.tryEmit(Event.ChannelJoined(ch, joinedChannels.toSet()))

        // Appena entra nel canale, carica i file indicizzati per quel canale/server
        scope.launch(Dispatchers.IO) {
            val cleanCh = ch.removePrefix("#")
            val discovered = queryXdccDatabase(cleanCh, filterChannel = ch)
            if (discovered.isNotEmpty()) {
                events.tryEmit(Event.ChannelPacksDiscovered(discovered))
                emitLog("Trovati ${discovered.size} file attivi nel canale $ch.")
            } else {
                // Se il nome del canale non è nel database esterno, mostra i pacchetti attivi sul server
                val serverShort = server.substringAfter("irc.").substringBefore(".")
                val serverPacks = queryXdccDatabase(serverShort, filterChannel = null)
                if (serverPacks.isNotEmpty()) {
                    events.tryEmit(Event.ChannelPacksDiscovered(serverPacks))
                    emitLog("Caricati ${serverPacks.size} file dai bot del server $server.")
                }
            }
        }
    }

    /**
     * Cerca file o solo nel canale attivo (inviando anche @find e !search ai bot della stanza)
     * oppure globalmente su tutti i canali/server IRC.
     */
    fun searchFilesInIrc(query: String, globalAllChannels: Boolean) {
        val clean = query.trim()
        if (clean.isEmpty()) return

        // 1. Invia il comando di ricerca direttamente ai Bot presenti nei canali in cui siamo entrati!
        val targets = if (globalAllChannels) {
            joinedChannels.toList()
        } else {
            listOfNotNull(currentChannel)
        }

        if (targets.isNotEmpty()) {
            targets.forEach { ch ->
                sendMessage(ch, "@find $clean")
                sendMessage(ch, "!search $clean")
                emitLog("Inviata richiesta ricerca '@find $clean' ai bot del canale $ch")
            }
        }

        // 2. Interroga in parallelo il motore XDCC (filtrato per canale/server o globale)
        scope.launch(Dispatchers.IO) {
            val chFilter = if (globalAllChannels) null else currentChannel
            val results = queryXdccDatabase(clean, filterChannel = chFilter)
            events.tryEmit(Event.ChannelPacksDiscovered(results))
            if (results.isEmpty() && !globalAllChannels && currentChannel != null) {
                // Se il filtro ristretto al singolo canale non ha corrispondenze esterne, cerca su tutto il server
                val fallback = queryXdccDatabase(clean, filterChannel = null)
                events.tryEmit(Event.ChannelPacksDiscovered(fallback))
                emitLog("Ricerca completata: trovati ${fallback.size} file su rete IRC.")
            } else {
                emitLog("Ricerca IRC completata: ${results.size} file trovati per '$clean'.")
            }
        }
    }

    private fun queryXdccDatabase(searchTerm: String, filterChannel: String?): List<ChannelPackItem> {
        val list = mutableListOf<ChannelPackItem>()
        try {
            val encoded = URLEncoder.encode(searchTerm, "UTF-8")
            val url = "https://sunxdcc.com/deliver.php?sterm=$encoded"
            val request = Request.Builder()
                .url(url)
                .header("Accept-Encoding", "gzip")
                .header("User-Agent", "Mozilla/5.0 (Android 16; UltimateMule)")
                .build()

            httpClient.newCall(request).execute().use { response ->
                val raw = response.body?.bytes() ?: return emptyList()
                val bytes = if (raw.size >= 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
                    GZIPInputStream(ByteArrayInputStream(raw)).use { it.readBytes() }
                } else {
                    raw
                }
                val body = String(bytes, Charsets.UTF_8)
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
                            val fname = names.optString(i, "").trim()
                            val bot = bots.optString(i, "").trim()
                            val pack = packs.optString(i, "0").replace("#", "").trim().toIntOrNull() ?: 0
                            val sizeStr = sizes?.optString(i, "N/D")?.trim() ?: "N/D"
                            val net = networks?.optString(i, server)?.trim() ?: server
                            val chan = channels?.optString(i, currentChannel ?: "#xdcc")?.trim() ?: "#xdcc"

                            if (fname.isNotBlank() && bot.isNotBlank() && pack > 0) {
                                if (filterChannel == null || chan.equals(filterChannel, ignoreCase = true)) {
                                    val ext = extractFileExt(fname)
                                    list.add(
                                        ChannelPackItem(
                                            server = net,
                                            channel = chan,
                                            botNick = bot,
                                            packNumber = pack,
                                            filename = fname,
                                            fileExtension = ext,
                                            sizeText = sizeStr
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return list
    }

    private fun extractFileExt(filename: String): String {
        val ext = filename.substringAfterLast('.', "").uppercase()
        return if (ext.length in 2..4) ext else "FILE"
    }

    fun sendMessage(target: String, text: String) = sendRaw("PRIVMSG $target :$text")

    fun requestPack(botNick: String, packNumber: Int) {
        sendRaw("PRIVMSG $botNick :xdcc send #$packNumber")
        emitLog("Richiesta inviata a $botNick: xdcc send #$packNumber")
    }

    @Synchronized
    private fun sendRaw(line: String) {
        scope.launch(Dispatchers.IO) {
            try {
                writer?.let {
                    it.write(line + "\r\n")
                    it.flush()
                }
            } catch (e: Exception) {
                events.tryEmit(Event.Log("Errore invio: ${e.message}"))
            }
        }
    }

    private fun emitLog(text: String) = events.tryEmit(Event.Log(text))

    private fun handleLine(line: String) {
        val msg = parseLine(line)
        when (msg.command.uppercase()) {
            "PING" -> sendRaw("PONG :${msg.trailing ?: msg.params.firstOrNull().orEmpty()}")
            "001" -> {
                events.tryEmit(Event.Connected(server))
                emitLog("Connesso con successo a $server")
                autoJoinChannel?.takeIf { it.isNotBlank() }?.let { joinChannel(it) }
            }
            "JOIN" -> {
                val who = msg.prefix?.substringBefore('!').orEmpty()
                val ch = msg.trailing ?: msg.params.firstOrNull().orEmpty()
                if (who.equals(nickname, ignoreCase = true) && ch.startsWith("#")) {
                    joinedChannels.add(ch)
                    currentChannel = ch
                    events.tryEmit(Event.ChannelJoined(ch, joinedChannels.toSet()))
                }
            }
            "321" -> {
                listedChannelCount = 0
                events.tryEmit(Event.ChannelListStart(server))
            }
            "322" -> {
                val chName = msg.params.getOrNull(1) ?: return
                val count = msg.params.getOrNull(2)?.toIntOrNull() ?: 0
                val topic = msg.trailing.orEmpty()
                if (chName.startsWith("#")) {
                    listedChannelCount++
                    events.tryEmit(Event.ChannelListItem(IrcChannelInfo(chName, count, topic)))
                }
            }
            "323" -> {
                events.tryEmit(Event.ChannelListEnd(listedChannelCount))
                emitLog("Scansione /list completata: trovati $listedChannelCount canali.")
            }
            "332" -> {
                val ch = msg.params.getOrNull(1).orEmpty()
                emitLog("Topic $ch: ${msg.trailing.orEmpty()}")
            }
            "353" -> {
                val ch = msg.params.lastOrNull().orEmpty()
                emitLog("Utenti/Bot presenti in $ch: ${msg.trailing.orEmpty()}")
            }
            "433" -> {
                val newNick = nickname + (100..999).random()
                emitLog("Nickname occupato, cambio in $newNick")
                sendRaw("NICK $newNick")
            }
            "NOTICE" -> {
                val from = msg.prefix?.substringBefore('!') ?: "Server"
                val text = msg.trailing.orEmpty()
                emitLog("[$from] $text")
                checkAndCaptureBotAnnouncement(from, currentChannel ?: "#irc", text)
            }
            "PRIVMSG" -> handlePrivmsg(msg)
            "ERROR" -> emitLog("Errore Server: ${msg.trailing}")
        }
    }

    private fun handlePrivmsg(msg: IrcMessage) {
        val from = msg.prefix?.substringBefore('!') ?: "?"
        val target = msg.params.firstOrNull() ?: return
        val text = msg.trailing ?: return

        if (text.startsWith('\u0001')) {
            handleCtcp(from, text.trim('\u0001'))
        } else {
            events.tryEmit(Event.ChannelMessage(from, target, text))
            checkAndCaptureBotAnnouncement(from, target, text)
        }
    }

    /**
     * Se un Bot nel canale scrive o risponde con un pacchetto (es. "#14 3x [1.2G] Film.mkv"),
     * lo aggiunge automaticamente alla lista cliccabile "File Presenti nel Canale"!
     */
    private fun checkAndCaptureBotAnnouncement(botNick: String, channel: String, text: String) {
        val cleanText = text.replace(Regex("\\x03\\d{0,2}(,\\d{1,2})?|[\\x02\\x0F\\x16\\x1D\\x1F]"), "").trim()
        val match = botPackRegex.find(cleanText) ?: return
        val packNum = match.groupValues[1].toIntOrNull() ?: return
        val sizeStr = match.groupValues[2].trim()
        val fileName = match.groupValues[3].trim()

        val item = ChannelPackItem(
            server = server,
            channel = if (channel.startsWith("#")) channel else (currentChannel ?: "#xdcc"),
            botNick = botNick,
            packNumber = packNum,
            filename = fileName,
            fileExtension = extractFileExt(fileName),
            sizeText = sizeStr
        )
        events.tryEmit(Event.ChannelPacksDiscovered(listOf(item)))
    }

    private fun handleCtcp(from: String, payload: String) {
        if (!payload.startsWith("DCC ")) return
        val offer = parseDccSend(from, payload) ?: return
        events.tryEmit(Event.DccOfferReceived(offer))
        if (autoAcceptDcc) {
            scope.launch(Dispatchers.IO) { startDccDownload(offer) }
        }
    }

    private fun parseDccSend(from: String, payload: String): DccOffer? {
       val args = payload.removePrefix("DCC ").trim()
        if (!args.startsWith("SEND ")) return null
        var rest = args.removePrefix("SEND ").trim()

        val filename: String
        if (rest.startsWith("\"")) {
            val endQuote = rest.indexOf('"', 1)
            if (endQuote < 0) return null
            filename = rest.substring(1, endQuote)
            rest = rest.substring(endQuote + 1).trim()
        } else {
            val sp = rest.indexOf(' ')
            if (sp < 0) return null
            filename = rest.substring(0, sp)
            rest = rest.substring(sp + 1).trim()
        }

        val parts = rest.split(' ').filter { it.isNotBlank() }
        val ipLong = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val port = parts.getOrNull(1)?.toIntOrNull() ?: return null
        val size = parts.getOrNull(2)?.toLongOrNull() ?: return null

        return DccOffer(from, filename, longToIp(ipLong), port, size)
    }

    private fun longToIp(ipLong: Long): String {
        return "${(ipLong shr 24) and 0xFF}.${(ipLong shr 16) and 0xFF}.${(ipLong shr 8) and 0xFF}.${ipLong and 0xFF}"
    }

    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    private suspend fun startDccDownload(offer: DccOffer) = withContext(Dispatchers.IO) {
        val safeName = sanitizeFilename(offer.filename)
        tempDir.mkdirs()
        incomingDir.mkdirs()

        val partFile = File(tempDir, "$safeName.part")
        val finalFile = File(incomingDir, safeName)

        emitLog("Avvio scaricamento DCC da ${offer.botNick}: $safeName (${offer.size / 1024} KB)")

        var dccSocket: Socket? = null
        try {
            val s = Socket()
            s.connect(InetSocketAddress(offer.ip, offer.port), 30_000)
            dccSocket = s
            val input = s.getInputStream()
            val output = s.getOutputStream()

            var received = 0L
            var lastTime = System.currentTimeMillis()
            var lastBytes = 0L
            val buffer = ByteArray(64 * 1024)

            FileOutputStream(partFile).use { fos ->
                while (received < offer.size) {
                    val toRead = minOf(buffer.size.toLong(), offer.size - received).toInt()
                    val read = input.read(buffer, 0, toRead)
                    if (read == -1) break
                    fos.write(buffer, 0, read)
                    received += read

                    output.write(ByteBuffer.allocate(4).putInt((received and 0xFFFFFFFF).toInt()).array())
                    output.flush()

                    val now = System.currentTimeMillis()
                    if (now - lastTime >= 1000) {
                        fos.fd.sync()
                        val speed = (received - lastBytes) * 1000 / (now - lastTime)
                        events.tryEmit(Event.DownloadProgress(safeName, partFile, received, offer.size, speed))
                        lastTime = now
                        lastBytes = received
                    }
                }
            }

            if (received >= offer.size) {
                if (finalFile.exists()) finalFile.delete()
                val moved = partFile.renameTo(finalFile)
                val targetFile = if (moved) finalFile else partFile
                events.tryEmit(Event.DownloadComplete(targetFile))
            } else {
                events.tryEmit(Event.DownloadFailed(safeName, "Interrotto a $received/${offer.size} byte"))
            }
        } catch (e: Exception) {
            events.tryEmit(Event.DownloadFailed(safeName, e.message ?: "Errore socket DCC"))
        } finally {
            try { dccSocket?.close() } catch (_: Exception) {}
        }
    }

    private data class IrcMessage(
        val prefix: String?,
        val command: String,
        val params: List<String>,
        val trailing: String?
    )

    private fun parseLine(line: String): IrcMessage {
        var rest = line
        var prefix: String? = null
        if (rest.startsWith(":")) {
            val idx = rest.indexOf(' ')
            if (idx > 0) {
                prefix = rest.substring(1, idx)
                rest = rest.substring(idx + 1)
            }
        }
        var trailing: String? = null
        val trailIdx = rest.indexOf(" :")
        if (trailIdx >= 0) {
            trailing = rest.substring(trailIdx + 2)
            rest = rest.substring(0, trailIdx)
        } else if (rest.startsWith(":")) {
            trailing = rest.substring(1)
            rest = ""
        }
        val tokens = rest.split(' ').filter { it.isNotEmpty() }
        return IrcMessage(prefix, tokens.firstOrNull() ?: "", tokens.drop(1), trailing)
    }
}

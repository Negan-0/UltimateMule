package com.ultimatemule.app.irc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Collections
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Motore mIRC + XDCC con Protezione/Offuscamento IP (+x/+i Cloaking, Anti-CTCP Leak, Proxy SOCKS5 e SSL).
 * Coda di scrittura rigorosamente sequenziale (FIFO) per evitare il rifiuto dell'handshake dai server IRC.
 */
class IrcXdccEngine(
    val server: String,
    private val port: Int,
    private val nickname: String,
    private val incomingDir: File,
    private val tempDir: File,
    private val scope: CoroutineScope,
    private val useSsl: Boolean = false,
    private val ipCloakMode: Boolean = true,
    private val blockCtcpInfo: Boolean = true,
    private val socksProxyHost: String = "",
    private val socksProxyPort: Int = 0,
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

    companion object {
        val sharedChannelPacks: MutableMap<String, ChannelPackItem> =
            Collections.synchronizedMap(linkedMapOf())
    }

    sealed class Event {
        data class Log(val text: String) : Event()
        data class Connected(val server: String, val cloaked: Boolean) : Event()
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
    private var writerJob: Job? = null
    private var outgoingQueue = Channel<String>(Channel.UNLIMITED)

    private var activeNick: String = nickname
    private var isRegisteredOnServer: Boolean = false
    private var listedChannelCount = 0

    val joinedChannels = linkedSetOf<String>()
    var currentChannel: String? = null
        private set

    private val botPackRegex = Regex("""#(\d+)\s+\d*x?\s*\[\s*([0-9.,]+\s*[KMGTPBi]+)\s*]\s+(.+)""", RegexOption.IGNORE_CASE)

    fun connect() {
        if (connectionJob?.isActive == true) return
        isRegisteredOnServer = false
        activeNick = sanitizeNickname(nickname)
        outgoingQueue = Channel(Channel.UNLIMITED)

        connectionJob = scope.launch(Dispatchers.IO) {
            try {
                val proxyInfo = if (socksProxyHost.isNotBlank() && socksProxyPort > 0) {
                    " tramite Proxy SOCKS5 ($socksProxyHost:$socksProxyPort)"
                } else {
                    " (Scudo IP +ix pronto)"
                }
                emitLog("Connessione a $server:$port$proxyInfo...")

                val rawSocket = createProtectedSocket()
                socket = rawSocket
                reader = rawSocket.getInputStream().bufferedReader(Charsets.UTF_8)
                val outWriter = OutputStreamWriter(rawSocket.getOutputStream(), Charsets.UTF_8)
                writer = outWriter

                // Avvia il singolo consumer FIFO per garantire l'ordine rigoroso dei comandi RFC 2812
                writerJob = launch(Dispatchers.IO) {
                    for (cmdLine in outgoingQueue) {
                        try {
                            outWriter.write(cmdLine + "\r\n")
                            outWriter.flush()
                        } catch (e: Exception) {
                            break
                        }
                    }
                }

                // Handshake RFC 2812 in ordine garantito: prima NICK, poi USER (bitmask 8 = +i invisibile)
                val anonIdent = "u" + (1000..9999).random()
                sendRaw("NICK $activeNick")
                sendRaw("USER $anonIdent 8 * :mIRC Client")

                while (isActive) {
                    val line = reader?.readLine() ?: break
                    handleLine(line)
                }
            } catch (e: Exception) {
                emitLog("Errore connessione IRC ($server:$port): ${e.message}")
            } finally {
                writerJob?.cancel()
                events.tryEmit(Event.Disconnected("Connessione terminata"))
            }
        }
    }

    private fun sanitizeNickname(raw: String): String {
        val clean = raw.replace(Regex("[^a-zA-Z0-9_\\-\\[\\]]"), "")
        return if (clean.length < 3 || clean[0].isDigit()) {
            "Mule" + (1000..9999).random()
        } else {
            clean.take(15)
        }
    }

    private fun createProtectedSocket(): Socket {
        val useProxy = socksProxyHost.isNotBlank() && socksProxyPort in 1..65535
        val baseSocket = if (useProxy) {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksProxyHost.trim(), socksProxyPort))
            Socket(proxy).apply {
                soTimeout = 0
                keepAlive = true
                connect(InetSocketAddress(server, port), 20_000)
            }
        } else {
            Socket().apply {
                soTimeout = 0
                keepAlive = true
                connect(InetSocketAddress(server, port), 15_000)
            }
        }

        return if (useSsl) {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, SecureRandom())
            val sslSocket = sslContext.socketFactory.createSocket(baseSocket, server, port, true)
            sslSocket
        } else {
            baseSocket
        }
    }

    fun disconnect() {
        sendRaw("QUIT :Client closed")
        writerJob?.cancel()
        connectionJob?.cancel()
        joinedChannels.clear()
        currentChannel = null
        isRegisteredOnServer = false
        try { socket?.close() } catch (_: Exception) {}
    }

    fun executeMircCommand(rawInput: String) {
        val input = rawInput.trim()
        if (input.isEmpty()) return

        if (!input.startsWith("/")) {
            val target = currentChannel
            if (!target.isNullOrBlank()) {
                sendMessage(target, input)
                events.tryEmit(Event.ChannelMessage(activeNick, target, input))
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
                emitLog("Scansione canali (/list) avviata...")
            }
            "join", "j" -> {
                if (arg1.isNotBlank()) {
                    arg1.split(",").forEach { ch ->
                        if (ch.isNotBlank()) joinChannel(ch.trim())
                    }
                }
            }
            "part", "leave" -> {
                val ch = if (arg1.isNotBlank()) arg1 else currentChannel.orEmpty()
                if (ch.isNotBlank()) {
                    sendRaw("PART $ch")
                    joinedChannels.remove(ch)
                    currentChannel = joinedChannels.lastOrNull()
                    emitLog("Uscito da $ch")
                }
            }
            "msg", "privmsg", "query" -> {
                if (arg1.isNotBlank() && arg2.isNotBlank()) {
                    sendMessage(arg1, arg2)
                    emitLog("-> [$arg1] $arg2")
                }
            }
            "nick" -> if (arg1.isNotBlank()) {
                activeNick = sanitizeNickname(arg1)
                sendRaw("NICK $activeNick")
            }
            "whois" -> if (arg1.isNotBlank()) sendRaw("WHOIS $arg1")
            "names" -> {
                val ch = if (arg1.isNotBlank()) arg1 else currentChannel.orEmpty()
                if (ch.isNotBlank()) sendRaw("NAMES $ch")
            }
            else -> sendRaw(input.substring(1))
        }
    }

    fun requestChannelList() = executeMircCommand("/list")

    fun joinMultipleChannels(channels: List<String>) {
        channels.forEach { joinChannel(it) }
    }

    fun joinChannel(channel: String) {
        val ch = if (channel.startsWith("#")) channel else "#$channel"
        currentChannel = ch
        joinedChannels.add(ch)
        sendRaw("JOIN $ch")
        emitLog("Ingresso nel canale $ch...")
        events.tryEmit(Event.ChannelJoined(ch, joinedChannels.toSet()))
    }

    fun sendSearchCommandToActiveChannels(query: String) {
        val clean = query.trim()
        if (clean.isEmpty()) return
        joinedChannels.forEach { ch ->
            sendMessage(ch, "@find $clean")
            sendMessage(ch, "!search $clean")
        }
    }

    private fun extractFileExt(filename: String): String {
        val ext = filename.substringAfterLast('.', "").uppercase()
        return if (ext.length in 2..4) ext else "FILE"
    }

    fun sendMessage(target: String, text: String) = sendRaw("PRIVMSG $target :$text")

    fun requestPack(botNick: String, packNumber: Int) {
        sendRaw("PRIVMSG $botNick :xdcc send #$packNumber")
        emitLog("Richiesto pacchetto #$packNumber al bot $botNick")
    }

    private fun sendRaw(line: String) {
        outgoingQueue.trySend(line)
    }

    private fun emitLog(text: String) = events.tryEmit(Event.Log(text))

    private fun handleLine(line: String) {
        // Risposta immediata ai PING anti-spoof (sia "PING :token" che ":srv PING :token")
        if (line.startsWith("PING ", ignoreCase = true)) {
            val token = line.substring(5).trim()
            sendRaw("PONG $token")
            return
        }

        val msg = parseLine(line)
        when (msg.command.uppercase()) {
            "PING" -> {
                val token = msg.trailing ?: msg.params.firstOrNull().orEmpty()
                sendRaw("PONG :$token")
            }
            "001" -> {
                isRegisteredOnServer = true
                msg.params.firstOrNull()?.takeIf { it.isNotBlank() }?.let { confirmedNick ->
                    activeNick = confirmedNick
                }
                // Applica il cloaking IP una sola volta dopo la conferma 001 del server
                if (ipCloakMode) {
                    sendRaw("MODE $activeNick +ix")
                    emitLog("Scudo Privacy IRC attivato: MODE $activeNick +ix")
                }
                events.tryEmit(Event.Connected(server, ipCloakMode))
                autoJoinChannel?.takeIf { it.isNotBlank() }?.let { joinChannel(it) }
            }
            "396" -> {
                val maskedHost = msg.params.getOrNull(1) ?: "vhost.cloaked"
                emitLog("IP Offuscato dal Server: ora appari come '$maskedHost'")
            }
            "JOIN" -> {
                val who = msg.prefix?.substringBefore('!').orEmpty()
                val ch = msg.trailing ?: msg.params.firstOrNull().orEmpty()
                if (who.equals(activeNick, ignoreCase = true) && ch.startsWith("#")) {
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
                emitLog("Fine /list: trovati $listedChannelCount canali.")
            }
            "433", "432" -> { // Nickname in uso o non valido
                activeNick = "Mule" + (10000..99999).random()
                emitLog("Cambio nick automatico in $activeNick...")
                sendRaw("NICK $activeNick")
            }
            "451" -> { // ERR_NOTREGISTERED: reinvia registrazione se richiesto
                if (!isRegisteredOnServer) {
                    val anonIdent = "u" + (1000..9999).random()
                    sendRaw("NICK $activeNick")
                    sendRaw("USER $anonIdent 8 * :mIRC Client")
                }
            }
            "NOTICE" -> {
                val from = msg.prefix?.substringBefore('!') ?: "Server"
                val text = msg.trailing.orEmpty()
                if (text.startsWith('\u0001')) {
                    handleCtcp(from, text.trim('\u0001'))
                } else {
                    emitLog("[$from] $text")
                    checkAndCaptureBotAnnouncement(from, currentChannel ?: "#irc", text)
                }
            }
            "PRIVMSG" -> handlePrivmsg(msg)
            "ERROR" -> emitLog("Server: ${msg.trailing}")
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
        sharedChannelPacks["${botNick}#${packNum}"] = item
        events.tryEmit(Event.ChannelPacksDiscovered(listOf(item)))
    }

    private fun handleCtcp(from: String, payload: String) {
        val upper = payload.uppercase()
        if (upper.startsWith("DCC ")) {
            val offer = parseDccSend(from, payload) ?: return
            events.tryEmit(Event.DccOfferReceived(offer))
            if (autoAcceptDcc) {
                scope.launch(Dispatchers.IO) { startDccDownload(offer) }
            }
            return
        }

        if (upper.startsWith("PING")) {
            val arg = payload.substringAfter("PING", "").trim()
            sendRaw("NOTICE $from :\u0001PING $arg\u0001")
            return
        }

        if (blockCtcpInfo) {
            if (upper.startsWith("VERSION")) {
                sendRaw("NOTICE $from :\u0001VERSION mIRC v7.72\u0001")
                emitLog("Protetto da scansione CTCP VERSION di $from (Risposto: mIRC PC)")
            } else {
                emitLog("Bloccata richiesta di tracciamento CTCP ($upper) da $from")
            }
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

        emitLog("Download DCC da ${offer.botNick}: $safeName (${offer.size / 1024} KB)")

        var dccSocket: Socket? = null
        try {
            val s = if (socksProxyHost.isNotBlank() && socksProxyPort in 1..65535) {
                val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksProxyHost.trim(), socksProxyPort))
                Socket(proxy)
            } else {
                Socket()
            }
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
        

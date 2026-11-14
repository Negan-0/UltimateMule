package com.ultimatemule.app.irc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import javax.net.ssl.SSLSocketFactory

/**
 * Motore mIRC + XDCC completo in Kotlin puro.
 * Supporta comandi /list, /join, /part, /msg, /nick, /whois, /raw e anteprime .part in tempo reale.
 */
class IrcXdccEngine(
    private val server: String,
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

    sealed class Event {
        data class Log(val text: String) : Event()
        data class Connected(val server: String) : Event()
        data class ChannelMessage(val from: String, val target: String, val text: String) : Event()
        data class ChannelListStart(val server: String) : Event()
        data class ChannelListItem(val info: IrcChannelInfo) : Event()
        data class ChannelListEnd(val totalFound: Int) : Event()
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

    var currentChannel: String? = autoJoinChannel
        private set

    fun connect() {
        if (connectionJob?.isActive == true) return
        connectionJob = scope.launch(Dispatchers.IO) {
            try {
                emitLog("Connessione a $server:$port ...")
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
                events.tryEmit(Event.Disconnected("Connessione terminata"))
            }
        }
    }

    fun disconnect() {
        sendRaw("QUIT :UltimateMule Android")
        connectionJob?.cancel()
        try { socket?.close() } catch (_: Exception) {}
    }

    /**
     * Esegue qualsiasi comando stile mIRC (/list, /join, /msg, /nick, /part, /whois, /xdcc)
     */
    fun executeMircCommand(rawInput: String) {
        val input = rawInput.trim()
        if (input.isEmpty()) return

        if (!input.startsWith("/")) {
            val target = currentChannel
            if (!target.isNullOrBlank()) {
                sendMessage(target, input)
                events.tryEmit(Event.ChannelMessage(nickname, target, input))
            } else {
                emitLog("Nessun canale attivo. Usa /join #canale o /list")
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
                emitLog("Scansione canali del server in corso (/list)...")
            }
            "join", "j" -> {
                if (arg1.isNotBlank()) joinChannel(arg1)
            }
            "part", "leave" -> {
                val ch = if (arg1.isNotBlank()) arg1 else currentChannel.orEmpty()
                if (ch.isNotBlank()) {
                    sendRaw("PART $ch")
                    emitLog("Uscito da $ch")
                }
            }
            "msg", "privmsg", "query" -> {
                if (arg1.isNotBlank() && arg2.isNotBlank()) {
                    sendMessage(arg1, arg2)
                    emitLog("-> [$arg1] $arg2")
                } else {
                    emitLog("Uso corretto: /msg <NickBot> xdcc send #1")
                }
            }
            "nick" -> {
                if (arg1.isNotBlank()) sendRaw("NICK $arg1")
            }
            "whois" -> {
                if (arg1.isNotBlank()) sendRaw("WHOIS $arg1")
            }
            "names" -> {
                val ch = if (arg1.isNotBlank()) arg1 else currentChannel.orEmpty()
                if (ch.isNotBlank()) sendRaw("NAMES $ch")
            }
            "raw", "quote" -> {
                val rawCmd = input.substringAfter(" ", "")
                if (rawCmd.isNotBlank()) sendRaw(rawCmd)
            }
            else -> {
                // Invia direttamente al server il comando IRC
                sendRaw(input.substring(1))
            }
        }
    }

    fun requestChannelList() = executeMircCommand("/list")

    fun joinChannel(channel: String) {
        val ch = if (channel.startsWith("#")) channel else "#$channel"
        currentChannel = ch
        sendRaw("JOIN $ch")
        emitLog("Ingresso nel canale $ch ...")
    }

    fun sendMessage(target: String, text: String) = sendRaw("PRIVMSG $target :$text")

    fun requestPack(botNick: String, packNumber: Int) {
        sendRaw("PRIVMSG $botNick :xdcc send #$packNumber")
        emitLog("Inviato a $botNick: xdcc send #$packNumber")
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
                events.tryEmit(Event.Log("Errore invio comando: ${e.message}"))
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
                emitLog("Connesso e registrato su $server")
                autoJoinChannel?.takeIf { it.isNotBlank() }?.let { joinChannel(it) }
            }
            "321" -> { // RPL_LISTSTART
                listedChannelCount = 0
                events.tryEmit(Event.ChannelListStart(server))
            }
            "322" -> { // RPL_LIST: <client> <channel> <# visible> :<topic>
                val chName = msg.params.getOrNull(1) ?: return
                val count = msg.params.getOrNull(2)?.toIntOrNull() ?: 0
                val topic = msg.trailing.orEmpty()
                if (chName.startsWith("#")) {
                    listedChannelCount++
                    events.tryEmit(Event.ChannelListItem(IrcChannelInfo(chName, count, topic)))
                }
            }
            "323" -> { // RPL_LISTEND
                events.tryEmit(Event.ChannelListEnd(listedChannelCount))
                emitLog("Fine /list: trovati $listedChannelCount canali.")
            }
            "332" -> { // RPL_TOPIC
                val ch = msg.params.getOrNull(1).orEmpty()
                emitLog("Topic $ch: ${msg.trailing.orEmpty()}")
            }
            "353" -> { // RPL_NAMREPLY (Elenco utenti/bot nel canale)
                val ch = msg.params.lastOrNull().orEmpty()
                emitLog("Utenti/Bot in $ch: ${msg.trailing.orEmpty()}")
            }
            "433" -> { // Nickname occupato
                val newNick = nickname + (100..999).random()
                emitLog("Nickname in uso, cambio in $newNick")
                sendRaw("NICK $newNick")
            }
            "NOTICE" -> {
                val from = msg.prefix?.substringBefore('!') ?: "Server"
                emitLog("[$from] ${msg.trailing.orEmpty()}")
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
        }
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

    /**
     * Scarica il file in Temp/<nome>.part (così LibVLC può farne l'anteprima in diretta!)
     * e una volta completato lo sposta in Incoming/<nome>.
     */
    private suspend fun startDccDownload(offer: DccOffer) = withContext(Dispatchers.IO) {
        val safeName = sanitizeFilename(offer.filename)
        tempDir.mkdirs()
        incomingDir.mkdirs()

        val partFile = File(tempDir, "$safeName.part")
        val finalFile = File(incomingDir, safeName)

        emitLog("Avvio DCC da ${offer.botNick}: $safeName (${offer.size / 1024} KB)")

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
                        fos.fd.sync() // Sincronizza su disco per l'anteprima VLC in tempo reale
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

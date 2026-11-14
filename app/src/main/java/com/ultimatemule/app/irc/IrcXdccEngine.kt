package com.ultimatemule.app.irc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import javax.net.ssl.SSLSocketFactory

/**
 * Motore IRC + XDCC scritto da zero in Kotlin puro.
 * Zero dipendenze esterne: parla il protocollo IRC (RFC 1459) e DCC SEND nativo.
 */
class IrcXdccEngine(
    private val server: String,
    private val port: Int,
    private val nickname: String,
    private val downloadDir: File,
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

    sealed class Event {
        data class Log(val text: String) : Event()
        data class Connected(val server: String) : Event()
        data class ChannelMessage(val from: String, val target: String, val text: String) : Event()
        data class DccOfferReceived(val offer: DccOffer) : Event()
        data class DownloadProgress(val filename: String, val received: Long, val total: Long, val speedBps: Long) : Event()
        data class DownloadComplete(val file: File) : Event()
        data class DownloadFailed(val filename: String, val reason: String) : Event()
        data class Disconnected(val reason: String) : Event()
    }

    val events = MutableSharedFlow<Event>(extraBufferCapacity = 256)

    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: OutputStreamWriter? = null
    private var connectionJob: Job? = null

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
                sendRaw("USER $nickname 0 * :UltimateMule Android")

                while (isActive) {
                    val line = reader?.readLine() ?: break
                    handleLine(line)
                }
            } catch (e: Exception) {
                emitLog("Errore IRC: ${e.message}")
            } finally {
                events.tryEmit(Event.Disconnected("Connessione chiusa"))
            }
        }
    }

    fun disconnect() {
        sendRaw("QUIT :UltimateMule")
        connectionJob?.cancel()
        try { socket?.close() } catch (_: Exception) {}
    }

    fun joinChannel(channel: String) {
        val ch = if (channel.startsWith("#")) channel else "#$channel"
        sendRaw("JOIN $ch")
    }

    fun sendMessage(target: String, text: String) = sendRaw("PRIVMSG $target :$text")

    /** Richiede un pacchetto a un bot XDCC: equivalente a "/msg NomeBot xdcc send #12" */
    fun requestPack(botNick: String, packNumber: Int) {
        sendRaw("PRIVMSG $botNick :xdcc send #$packNumber")
        emitLog("Richiesto pacchetto #$packNumber a $botNick")
    }

    fun cancelPack(botNick: String) = sendRaw("PRIVMSG $botNick :xdcc cancel")

    @Synchronized
    private fun sendRaw(line: String) {
        try {
            writer?.let {
                it.write(line + "\r\n")
                it.flush()
            }
        } catch (e: Exception) {
            events.tryEmit(Event.Log("Errore invio: ${e.message}"))
        }
    }

    private fun emitLog(text: String) = events.tryEmit(Event.Log(text))

    private fun handleLine(line: String) {
        val msg = parseLine(line)
        when (msg.command.uppercase()) {
            "PING" -> sendRaw("PONG :${msg.trailing ?: msg.params.firstOrNull().orEmpty()}")
            "001" -> {
                events.tryEmit(Event.Connected(server))
                emitLog("Registrato su $server")
                autoJoinChannel?.let { joinChannel(it) }
            }
            "433" -> { // Nickname già in uso
                val newNick = nickname + (100..999).random()
                emitLog("Nickname occupato, riprovo con $newNick")
                sendRaw("NICK $newNick")
            }
            "PRIVMSG" -> handlePrivmsg(msg)
            "ERROR" -> emitLog("Server ERROR: ${msg.trailing}")
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
            // IMPORTANTE: il download parte su una coroutine SEPARATA,
            // così il loop di lettura continua a rispondere ai PING del server
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

    /** Il DCC invia l'IP come numero intero a 32 bit: lo riconvertiamo in formato classico */
    private fun longToIp(ipLong: Long): String {
        return "${(ipLong shr 24) and 0xFF}.${(ipLong shr 16) and 0xFF}.${(ipLong shr 8) and 0xFF}.${ipLong and 0xFF}"
    }

    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    private suspend fun startDccDownload(offer: DccOffer) = withContext(Dispatchers.IO) {
        val safeName = sanitizeFilename(offer.filename)
        val outFile = File(downloadDir, safeName)
        emitLog("DCC SEND da ${offer.botNick}: $safeName (${offer.size} byte) da ${offer.ip}:${offer.port}")

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

            FileOutputStream(outFile).use { fos ->
                while (received < offer.size) {
                    val toRead = minOf(buffer.size.toLong(), offer.size - received).toInt()
                    val read = input.read(buffer, 0, toRead)
                    if (read == -1) break
                    fos.write(buffer, 0, read)
                    received += read

                    // Protocollo DCC: dopo ogni blocco rispondiamo con 4 byte (totale ricevuto)
                    output.write(ByteBuffer.allocate(4).putInt((received and 0xFFFFFFFF).toInt()).array())
                    output.flush()

                    val now = System.currentTimeMillis()
                    if (now - lastTime >= 1000) {
                        val speed = (received - lastBytes) * 1000 / (now - lastTime)
                        events.tryEmit(Event.DownloadProgress(safeName, received, offer.size, speed))
                        lastTime = now
                        lastBytes = received
                    }
                }
            }

            if (received >= offer.size) {
                events.tryEmit(Event.DownloadComplete(outFile))
            } else {
                events.tryEmit(Event.DownloadFailed(safeName, "Interrotto a $received/${offer.size} byte"))
            }
        } catch (e: Exception) {
            events.tryEmit(Event.DownloadFailed(safeName, e.message ?: "Errore sconosciuto"))
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

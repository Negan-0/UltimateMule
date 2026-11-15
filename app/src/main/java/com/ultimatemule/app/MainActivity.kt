package com.ultimatemule.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.StrictMode
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.ultimatemule.app.ed2k.Ed2kKadManager
import com.ultimatemule.app.ed2k.Ed2kTransferState
import com.ultimatemule.app.irc.IrcXdccEngine
import com.ultimatemule.app.player.VlcPlayerManager
import com.ultimatemule.app.search.GlobalSearchEngine
import com.ultimatemule.app.service.P2PEngineService
import com.ultimatemule.app.streaming.TorrentStreamEngine
import com.ultimatemule.app.streaming.TorrentStreamState
import com.ultimatemule.app.ui.OnboardingWizard
import com.ultimatemule.app.ui.SettingsScreenBuilder
import com.ultimatemule.app.ui.UiComponents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : Activity() {

    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Le 6 Sezioni UI
    private lateinit var screenNetworks: LinearLayout
    private lateinit var screenSearch: LinearLayout
    private lateinit var screenTorrent: LinearLayout
    private lateinit var screenIrc: LinearLayout
    private lateinit var screenFilesVlc: LinearLayout
    private lateinit var screenSettings: LinearLayout

    private val navItems = mutableListOf<LinearLayout>()

    // Riferimenti di stato globali condivisi con le schermate
    lateinit var topStatusBanner: TextView
    lateinit var btnGlobalConnectToggle: Button
    lateinit var serverListLog: TextView
    var selectedEd2kConnectionMode = 0

    // Motori P2P + Player + Wizard
    lateinit var ed2kManager: Ed2kKadManager
    lateinit var torrentEngine: TorrentStreamEngine
    var ircEngine: IrcXdccEngine? = null
    lateinit var vlcManager: VlcPlayerManager
    lateinit var searchEngine: GlobalSearchEngine
    private lateinit var onboardingWizard: OnboardingWizard

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())

        val configDir = File(UiComponents.getBaseMulePath(this), "Config")
        ed2kManager = Ed2kKadManager(configDir)
        torrentEngine = TorrentStreamEngine(this)
        vlcManager = VlcPlayerManager(this)
        searchEngine = GlobalSearchEngine(configDir)
        onboardingWizard = OnboardingWizard(this)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111318"))
        }

        // --- BARRA SUPERIORE ---
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1E2430"))
            setPadding(20, 14, 20, 14)
        }

        topStatusBanner = TextView(this).apply {
            text = "UltimateMule  |  Pronto"
            textSize = 11f
            setTextColor(Color.parseColor("#E5E7EB"))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnQuickDisconnect = UiComponents.compactRowButton(this, "SCOLLEGA RETI", "#991B1B", 0f) {
            disconnectAllNetworks()
        }

        topBar.addView(topStatusBanner)
        topBar.addView(btnQuickDisconnect)
        rootLayout.addView(topBar)

        // --- CONTENITORE SCHERMATE ---
        val contentScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val contentHolder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 16, 22, 28)
        }

        // Istanziamo le schermate richiamando i moduli dedicati (che scriveremo separatamente)
        screenNetworks = com.ultimatemule.app.ui.NetworksScreenBuilder.build(this)
        screenSearch = com.ultimatemule.app.ui.SearchScreenBuilder.build(this)
        screenTorrent = com.ultimatemule.app.ui.TorrentScreenBuilder.build(this)
        screenIrc = com.ultimatemule.app.ui.IrcScreenBuilder.build(this)
        screenFilesVlc = com.ultimatemule.app.ui.FilesVlcScreenBuilder.build(this)
        screenSettings = SettingsScreenBuilder.build(this)

        contentHolder.addView(screenNetworks)
        contentHolder.addView(screenSearch)
        contentHolder.addView(screenTorrent)
        contentHolder.addView(screenIrc)
        contentHolder.addView(screenFilesVlc)
        contentHolder.addView(screenSettings)

        contentScroll.addView(contentHolder)
        rootLayout.addView(contentScroll)

        // --- BARRA NAV INFERIORE ---
        val bottomNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#181C24"))
        }

        val tabs = listOf(
            R.drawable.ic_networks to "Reti",
            R.drawable.ic_search to "Cerca",
            R.drawable.ic_torrent to "Torrent",
            R.drawable.ic_irc to "mIRC",
            R.drawable.ic_transfers to "File & VLC",
            R.drawable.ic_settings to "Opzioni"
        )
        tabs.forEachIndexed { idx, pair ->
            val navItem = UiComponents.createBottomNavItem(this, pair.first, pair.second, idx) {
                selectScreen(it)
            }
            navItems.add(navItem)
            bottomNav.addView(navItem)
        }

        rootLayout.addView(bottomNav)
        setContentView(rootLayout)

        observeEd2kEngineState()
        observeTorrentEngineState()
        P2PEngineService.updateNotificationStatus(this, "Servizio P2P pronto")
        selectScreen(0)
    }

    override fun onResume() {
        super.onResume()
        onboardingWizard.checkAndRunNextStep()
    }

    private fun selectScreen(index: Int) {
        screenNetworks.visibility = if (index == 0) View.VISIBLE else View.GONE
        screenSearch.visibility = if (index == 1) View.VISIBLE else View.GONE
        screenTorrent.visibility = if (index == 2) View.VISIBLE else View.GONE
        screenIrc.visibility = if (index == 3) View.VISIBLE else View.GONE
        screenFilesVlc.visibility = if (index == 4) View.VISIBLE else View.GONE
        screenSettings.visibility = if (index == 5) View.VISIBLE else View.GONE

        navItems.forEachIndexed { idx, layout ->
            val icon = layout.getChildAt(0) as ImageView
            val label = layout.getChildAt(1) as TextView
            if (idx == index) {
                layout.setBackgroundColor(Color.parseColor("#252B38"))
                icon.setColorFilter(Color.parseColor("#38BDF8"))
                label.setTextColor(Color.parseColor("#38BDF8"))
                label.setTypeface(null, Typeface.BOLD)
            } else {
                layout.setBackgroundColor(Color.TRANSPARENT)
                icon.setColorFilter(Color.parseColor("#9CA3AF"))
                label.setTextColor(Color.parseColor("#9CA3AF"))
                label.setTypeface(null, Typeface.NORMAL)
            }
        }

        if (index == 4) {
            com.ultimatemule.app.ui.FilesVlcScreenBuilder.refreshFiles(this)
        }
    }

    private fun disconnectAllNetworks() {
        Ed2kKadManager.disconnectAll()
        com.ultimatemule.app.ui.IrcScreenBuilder.disconnectIrc(this)
        torrentEngine.stopKeepFiles()
        vlcManager.stopAndDetach()
        stopService(Intent(this, P2PEngineService::class.java))

        topStatusBanner.text = "UltimateMule  |  DISCONNESSO"
        Toast.makeText(this, "Scollegato da tutte le reti P2P.", Toast.LENGTH_SHORT).show()
    }

    private fun observeEd2kEngineState() {
        mainScope.launch {
            Ed2kKadManager.transferState.collect { state ->
                com.ultimatemule.app.ui.FilesVlcScreenBuilder.updateEd2kState(this@MainActivity, state)
            }
        }
    }

    private fun observeTorrentEngineState() {
        mainScope.launch {
            torrentEngine.state.collect { state ->
                com.ultimatemule.app.ui.FilesVlcScreenBuilder.updateTorrentState(this@MainActivity, state)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        vlcManager.release()
    }
}

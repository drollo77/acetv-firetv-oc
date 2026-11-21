package local.acetv

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

private class ListRows(ctx: Context, items: List<String>) :
    ArrayAdapter<String>(ctx, android.R.layout.simple_list_item_activated_1, items) {
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = super.getView(position, convertView, parent)
        (v as TextView).textSize = 22f
        v.setPadding(24, 14, 24, 14)
        return v
    }
}

class MainActivity : Activity() {

    private val tag = "AceTV"
    private val match = ViewGroup.LayoutParams.MATCH_PARENT

    // Resolucion maxima que decodifica bien el Fire TV Stick 2.a gen: las fuentes superiores se prueban las ultimas
    private val maxResolution = 1080

    private lateinit var prefs: SharedPreferences
    private var player: ExoPlayer? = null
    private lateinit var surface: SurfaceView
    private lateinit var info: TextView
    private lateinit var statsView: TextView
    private lateinit var panel: View
    private lateinit var groupList: ListView
    private lateinit var channelList: ListView
    private lateinit var qrPanel: LinearLayout
    private lateinit var qrImage: ImageView
    private lateinit var qrText: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val ioStats = Executors.newSingleThreadExecutor()
    private val clearInfo = Runnable { info.visibility = View.GONE }
    private lateinit var logos: LogoLoader
    private var logoRefreshQueued = false
    private val logoRefresh = Runnable {
        logoRefreshQueued = false
        (channelList.adapter as? ChannelRows)?.notifyDataSetChanged()
    }

    // Lista: entradas por hash y filas fusionadas (un canal con varias fuentes)
    @Volatile private var channels: List<Channel> = emptyList()
    @Volatile private var rows: List<Row> = emptyList()
    @Volatile private var rowByHash: Map<String, Row> = emptyMap()
    private var groups: List<String> = emptyList()
    private var groupNames: List<String> = emptyList()
    private var shownGroup = ""
    private var shownRows: List<Row> = emptyList()
    private var zapRows: List<Row> = emptyList()

    // Reproduccion: fila actual, plan de fuentes y la fuente en uso (nombre del canal + hash de la fuente)
    @Volatile private var currentRow: Row? = null
    @Volatile private var current: Channel? = null
    private var plan: SourcePlan? = null
    @Volatile private var playing = false

    @Volatile private var commandUrl: String? = null
    @Volatile private var session = 0
    @Volatile private var statUrl: String? = null

    private var statsOn = false
    private var tickCount = 0
    @Volatile private var engineStats = "-"
    private val stall = StallDetector(15000)
    private var everReady = false
    private var startedAt = 0L
    private val tick = object : Runnable {
        override fun run() {
            onTick()
            ui.postDelayed(this, 1000)
        }
    }

    private var zapTarget: Row? = null
    private val zapRunnable = Runnable {
        val t = zapTarget
        zapTarget = null
        if (t != null && playing) startRow(t)
    }

    // ---------------------------------------------------------------- ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(tag, "onCreate")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("acetv", Context.MODE_PRIVATE)

        // Opciones por intent (se guardan): host del motor y modo hls|ts
        val ed = prefs.edit()
        intent.getStringExtra("host")?.let { ed.putString("host", it) }
        intent.getStringExtra("mode")?.let { ed.putString("mode", it) }
        ed.apply()

        logos = LogoLoader(java.io.File(cacheDir, "logos")) {
            ui.post {
                if (!logoRefreshQueued) {
                    logoRefreshQueued = true
                    ui.postDelayed(logoRefresh, 250)
                }
            }
        }
        loadData()
        buildUi()

        // Estado inicial: hash de depuracion, o volver al ultimo canal visto
        val dbg = intent.getStringExtra("hash")
        if (dbg != null) {
            prepareRow(ChannelMerge.manualRow(dbg, "prueba"))
            playing = true
        } else {
            val last = prefs.getString("last", null)
            val row = if (last != null) rowByHash[last] else null
            if (row != null) {
                zapRows = rows.filter { it.group == row.group }
                prepareRow(row)
                playing = true
            }
        }
        panel.visibility = if (playing) View.GONE else View.VISIBLE
        selectInitialGroup()
        if (channels.isEmpty()) say("No se pudo cargar lista.json de assets")
        channelList.requestFocus()
        if (intent.getBooleanExtra("mando", false)) startRemote()
    }

    override fun onStart() {
        super.onStart()
        Log.i(tag, "onStart")
        ui.removeCallbacks(tick)
        ui.postDelayed(tick, 1000)
        val c = current
        if (playing && c != null) {
            say("Cargando...")
            openSession(c.hash)
        }
    }

    override fun onResume() {
        super.onResume()
        Log.i(tag, "onResume")
    }

    override fun onPause() {
        super.onPause()
        Log.i(tag, "onPause")
    }

    override fun onStop() {
        Log.i(tag, "onStop")
        ui.removeCallbacks(tick)
        super.onStop()
        cancelZap()
        releasePlayer()
        stopSession()
    }

    override fun onDestroy() {
        Log.i(tag, "onDestroy")
        super.onDestroy()
        io.shutdown() // deja terminar las tareas ya encoladas (cierre de sesion)
        stopRemote()
        logos.shutdown()
        ioStats.shutdown()
    }

    // ---------------------------------------------------------------- datos

    // Carga las listas y las fusiona en filas (un canal con varias fuentes)
    private fun loadData() {
        channels = try {
            ChannelLogic.dedupe(loadChannels())
        } catch (e: Exception) {
            Log.e(tag, "lista: ${e.message}")
            emptyList()
        }
        val merged = ChannelMerge.merge(channels)
        rows = merged
        rowByHash = ChannelMerge.byHash(merged)
        groups = ChannelMerge.groupsOf(merged)
        groupNames = ChannelLogic.groupLabels(groups)
        Log.i(tag, "fusion: ${channels.size} entradas -> ${merged.size} canales en ${groups.size} grupos")
    }

    private fun reloadChannels() {
        loadData()
        groupList.adapter = ListRows(this, groupNames)
        selectInitialGroup()
    }

    private fun recentRows(): List<Row> =
        ChannelMerge.recentRows(rows, ChannelLogic.parseRecents(prefs.getString("recents", "")))

    private fun rowTitle(r: Row): String =
        if (r.sources.size > 1) r.name + "   (" + r.sources.size + ")" else r.name

    // ---------------------------------------------------------------- interfaz

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        surface = SurfaceView(this)
        root.addView(surface, FrameLayout.LayoutParams(match, match))

        val p = LinearLayout(this)
        p.orientation = LinearLayout.HORIZONTAL
        p.setBackgroundColor(0xFF101820.toInt())
        groupList = makeList()
        channelList = makeList()
        p.addView(groupList, LinearLayout.LayoutParams(0, match, 1f))
        p.addView(channelList, LinearLayout.LayoutParams(0, match, 2f))
        qrPanel = LinearLayout(this)
        qrPanel.orientation = LinearLayout.VERTICAL
        qrPanel.gravity = Gravity.CENTER_HORIZONTAL
        qrPanel.setPadding(24, 24, 24, 24)
        qrImage = ImageView(this)
        qrImage.scaleType = ImageView.ScaleType.FIT_XY
        qrPanel.addView(qrImage, LinearLayout.LayoutParams(520, 520))
        qrText = TextView(this)
        qrText.setTextColor(Color.WHITE)
        qrText.textSize = 20f
        qrText.setPadding(0, 24, 0, 0)
        qrPanel.addView(qrText, LinearLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT))
        qrPanel.visibility = View.GONE
        p.addView(qrPanel, LinearLayout.LayoutParams(0, match, 2f))
        panel = p
        root.addView(p, FrameLayout.LayoutParams(match, match))

        info = TextView(this)
        info.setTextColor(Color.WHITE)
        info.setBackgroundColor(0x99000000.toInt())
        info.textSize = 20f
        info.setPadding(24, 12, 24, 12)
        info.visibility = View.GONE
        root.addView(
            info,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START
            )
        )

        statsView = TextView(this)
        statsView.setTextColor(Color.WHITE)
        statsView.setBackgroundColor(0xCC000000.toInt())
        statsView.textSize = 18f
        statsView.setPadding(24, 12, 24, 12)
        statsView.visibility = View.GONE
        root.addView(
            statsView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            )
        )
        setContentView(root)

        groupList.adapter = ListRows(this, groupNames)
        groupList.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                groupList.setItemChecked(position, true)
                showChannels(groupNames[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        channelList.onItemClickListener =
            AdapterView.OnItemClickListener { _, _, position, _ -> playFromList(position) }
        groupList.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            if (groupNames[position] == ChannelLogic.QR_LABEL) showQrPanel(true) else channelList.requestFocus()
        }
    }

    private fun makeList(): ListView {
        val l = ListView(this)
        l.choiceMode = ListView.CHOICE_MODE_SINGLE
        l.divider = null
        l.isVerticalScrollBarEnabled = false
        return l
    }

    private fun selectInitialGroup() {
        val cur = currentRow
        val hasRecents = recentRows().isNotEmpty()
        val gi = when {
            cur != null && groups.contains(cur.group) -> groups.indexOf(cur.group) + 1
            hasRecents -> 0
            groups.isNotEmpty() -> 1
            else -> 0
        }
        groupList.setItemChecked(gi, true)
        groupList.setSelection(gi)
        showChannels(groupNames[gi])
    }

    private fun showChannels(g: String) {
        shownGroup = g
        if (g == ChannelLogic.QR_LABEL) {
            showQrPanel(false)
            return
        }
        hideQrPanel()
        shownRows = if (g == ChannelLogic.RECENT_LABEL) recentRows() else rows.filter { it.group == g }
        channelList.adapter = ChannelRows(this, shownRows.map { rowTitle(it) }, shownRows.map { it.logo }, logos)
        val cur = currentRow
        if (cur != null) {
            val i = shownRows.indexOfFirst { it.key == cur.key }
            if (i >= 0) {
                channelList.setItemChecked(i, true)
                channelList.setSelection(i)
            }
        }
    }

    // Nombre del canal y, si tiene varias fuentes, cual esta en uso: "M+ Deportes   [2/4 1080p *]"
    private fun titleLine(): String {
        val r = currentRow
        val p = plan
        if (r == null || p == null) return current?.title ?: ""
        return if (p.size > 1) r.name + "   [" + (p.index + 1) + "/" + p.size + " " + p.current().label + "]" else r.name
    }

    private fun say(msg: String) {
        Log.i(tag, msg)
        ui.post {
            if (zapTarget != null) return@post
            ui.removeCallbacks(clearInfo)
            val t = titleLine()
            info.text = if (t.isNotEmpty()) "$t\n$msg" else msg
            info.visibility = View.VISIBLE
        }
    }

    // ---------------------------------------------------------------- mando

    // Con la lista oculta, el mando actua siempre sobre la reproduccion, tenga el foco quien lo tenga
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            Log.i(
                tag,
                "tecla ${KeyEvent.keyCodeToString(event.keyCode)} playing=$playing " +
                    "lista=${panel.visibility == View.VISIBLE} foco=${currentFocus?.javaClass?.simpleName}"
            )
        }
        if (playing && panel.visibility != View.VISIBLE) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP,
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_MENU -> {
                    if (event.action == KeyEvent.ACTION_DOWN) onKeyDown(event.keyCode, event)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (playing) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    backToList()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    zap(-1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    zap(1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (event.repeatCount == 0) switchSource(-1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (event.repeatCount == 0) switchSource(1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    toggleStats()
                    return true
                }
                KeyEvent.KEYCODE_MENU -> {
                    showSources()
                    return true
                }
            }
        } else if (keyCode == KeyEvent.KEYCODE_MENU && panel.visibility == View.VISIBLE) {
            deleteSelectedRecent()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // Menu mientras se reproduce: lista las fuentes del canal y marca la que esta en uso
    private fun showSources() {
        val p = plan ?: return
        val r = currentRow ?: return
        val sb = StringBuilder(r.name)
        for ((i, l) in p.labels().withIndex()) {
            sb.append("\n").append(if (i == p.index) "> " else "   ").append(i + 1).append("  ").append(l)
        }
        sb.append("\n(izquierda/derecha cambian de fuente)")
        ui.removeCallbacks(clearInfo)
        info.text = sb.toString()
        info.visibility = View.VISIBLE
        ui.postDelayed(clearInfo, 6000)
    }

    // Menu en la lista "Recientes": quita el canal marcado de los recientes
    private fun deleteSelectedRecent() {
        if (shownGroup != ChannelLogic.RECENT_LABEL) return
        val pos = channelList.selectedItemPosition
        if (pos < 0 || pos >= shownRows.size) return
        val keep = ChannelMerge.removeRecent(
            rows,
            ChannelLogic.parseRecents(prefs.getString("recents", "")),
            shownRows[pos].key
        )
        prefs.edit().putString("recents", ChannelLogic.joinRecents(keep)).apply()
        showChannels(shownGroup)
        if (shownRows.isNotEmpty()) channelList.setSelection(minOf(pos, shownRows.size - 1))
    }

    private fun playFromList(pos: Int) {
        if (pos < 0 || pos >= shownRows.size) return
        zapRows = shownRows
        startRow(shownRows[pos])
    }

    private fun cancelZap() {
        ui.removeCallbacks(zapRunnable)
        zapTarget = null
    }

    // Cambia de canal solo cuando pasan 400 ms sin otra pulsacion
    private fun zap(delta: Int) {
        if (zapRows.isEmpty()) return
        val from = zapTarget ?: currentRow
        val idx = zapRows.indexOfFirst { it.key == from?.key }
        val n = ChannelLogic.zapIndex(zapRows.size, idx, delta)
        if (n < 0) return
        val t = zapRows[n]
        zapTarget = t
        ui.removeCallbacks(clearInfo)
        ui.removeCallbacks(zapRunnable)
        info.text = t.name
        info.visibility = View.VISIBLE
        ui.postDelayed(zapRunnable, 400)
    }

    // Cambio manual de fuente del mismo canal (izquierda/derecha)
    private fun switchSource(delta: Int) {
        val p = plan ?: return
        if (p.size < 2) {
            say("Este canal solo tiene una fuente")
            ui.postDelayed(clearInfo, 2500)
            return
        }
        p.jump(delta)
        syncCurrent()
        say("Cambiando de fuente...")
        reopen()
    }

    private fun backToList() {
        playing = false
        cancelZap()
        releasePlayer()
        stopSession()
        ui.removeCallbacks(clearInfo)
        info.visibility = View.GONE
        statsOn = false
        statsView.visibility = View.GONE
        panel.visibility = View.VISIBLE
        showChannels(shownGroup)
        channelList.requestFocus()
    }

    // ---------------------------------------------------------------- canales y fuentes

    private fun prepareRow(row: Row) {
        currentRow = row
        plan = SourcePlan(row.sources, prefs.getString("pref:" + row.key, null), maxResolution)
        syncCurrent()
    }

    private fun syncCurrent() {
        val r = currentRow
        val p = plan
        if (r != null && p != null) current = Channel(r.name, p.current().hash, r.group)
    }

    private fun startRow(row: Row) {
        prepareRow(row)
        playing = true
        panel.visibility = View.GONE
        val hash = current?.hash ?: return
        val recents = ChannelLogic.pushRecent(ChannelLogic.parseRecents(prefs.getString("recents", "")), hash)
        prefs.edit()
            .putString("last", hash)
            .putString("recents", ChannelLogic.joinRecents(recents))
            .apply()
        Log.i(tag, "canal: ${titleLine()}")
        say("Cargando...")
        openSession(hash)
    }

    private fun reopen() {
        val c = current ?: return
        openSession(c.hash)
    }

    // La fuente actual ya reproduce: se recuerda como preferida de este canal
    private fun onSourceWorks() {
        everReady = true
        stall.reset()
        plan?.onSuccess()
        val r = currentRow
        val c = current
        if (r != null && c != null) {
            val k = "pref:" + r.key
            if (prefs.getString(k, null) != c.hash) prefs.edit().putString(k, c.hash).apply()
        }
    }

    // Fallo de la fuente actual: reintento, siguiente fuente o rendirse (ver SourcePlan)
    private fun sourceFailed(reason: String) {
        val p = plan ?: return
        if (!playing) return
        Log.w(tag, "fallo: $reason en ${titleLine()}")
        logStat()
        when (p.onFailure()) {
            SourcePlan.Decision.RETRY -> {
                say("Sin senal, reintentando...")
                reopen()
            }
            SourcePlan.Decision.NEXT -> {
                syncCurrent()
                say("Probando la fuente " + (p.index + 1) + "/" + p.size + "...")
                reopen()
            }
            SourcePlan.Decision.GIVE_UP -> {
                releasePlayer()
                stopSession()
                say("Sin senal: $reason")
            }
        }
    }

    // ---------------------------------------------------------------- vigilante y estadisticas

    // Se ejecuta cada segundo: QR, vigilante de parada y refresco del panel de estadisticas
    private fun onTick() {
        if (panel.visibility == View.VISIBLE && qrPanel.visibility == View.VISIBLE &&
            remote != null && remoteSession.status() != RemoteSession.Result.OK
        ) {
            showQrPanel(true)
        }
        val p = player
        if (!playing || p == null) return
        val now = SystemClock.elapsedRealtime()
        val st = p.playbackState
        if (st == Player.STATE_READY || (everReady && st == Player.STATE_BUFFERING)) {
            if (stall.update(p.currentPosition, now)) {
                stall.reset()
                sourceFailed("sin avanzar 15 s")
                return
            }
        } else if (!everReady) {
            val limit = plan?.loadTimeoutMs() ?: 45000L
            if (now - startedAt > limit) {
                sourceFailed("no arranca en " + (limit / 1000) + " s")
                return
            }
        }
        if (statsOn) {
            tickCount++
            if (tickCount % 2 == 0) fetchStats()
            renderStats()
        }
    }

    private fun toggleStats() {
        statsOn = !statsOn
        if (statsOn) {
            tickCount = 0
            fetchStats()
            renderStats()
            statsView.visibility = View.VISIBLE
        } else {
            statsView.visibility = View.GONE
        }
    }

    private fun fetchStats() {
        val u = statUrl
        if (u.isNullOrEmpty()) return
        ioStats.execute {
            try {
                val r = JSONObject(httpGet(u, 3000)).optJSONObject("response")
                if (r != null) {
                    engineStats = "${r.optString("status", "?")}  pares ${r.optInt("peers")}  " +
                        "bajada ${r.optInt("speed_down")} KB/s  subida ${r.optInt("speed_up")} KB/s"
                }
            } catch (e: Exception) {
                engineStats = "sin datos (${e.message})"
            }
        }
    }

    private fun renderStats() {
        val p = player
        val sb = StringBuilder()
        sb.append(titleLine()).append("\n")
        sb.append("Motor: ").append(engineStats).append("\n")
        if (p != null) {
            val v = p.videoSize
            sb.append("Video: ").append(v.width).append("x").append(v.height)
            sb.append("   Buffer: ").append(p.totalBufferedDuration / 1000).append(" s")
        }
        statsView.text = sb.toString()
    }

    private fun logStat() {
        val u = statUrl
        if (u.isNullOrEmpty()) return
        ioStats.execute {
            try {
                Log.e(tag, "stat: " + httpGet(u, 5000).take(400))
            } catch (e: Exception) {
                Log.e(tag, "stat: ${e.message}")
            }
        }
    }

    // ---------------------------------------------------------------- motor y reproductor

    private fun openSession(hash: String) {
        releasePlayer()
        stopSession()
        val host = prefs.getString("host", "192.168.1.34") ?: "192.168.1.34"
        val mode = prefs.getString("mode", "ts") ?: "ts"
        val endpoint = if (mode == "ts") "getstream" else "manifest.m3u8"
        val my = ++session
        io.execute {
            try {
                val body = httpGet("http://$host:6878/ace/$endpoint?id=$hash&format=json", 45000)
                Log.i(tag, "motor: " + body.take(200))
                val r = JSONObject(body)
                val resp = r.optJSONObject("response")
                    ?: throw RuntimeException("Motor: " + r.opt("error"))
                val playback = resp.getString("playback_url")
                val cmd = resp.optString("command_url", "")
                if (my != session) {
                    // Respuesta tardia: cerrar su sesion y no reproducir
                    Log.i(tag, "respuesta tardia, se cierra su sesion")
                    if (cmd.isNotEmpty()) stopRemoteSession(cmd)
                    return@execute
                }
                commandUrl = if (cmd.isEmpty()) null else cmd
                statUrl = resp.optString("stat_url", "")
                ui.post {
                    if (my == session) play(playback, mode == "ts")
                }
            } catch (e: Exception) {
                ui.post {
                    if (my == session) sourceFailed("motor: ${e.message}")
                }
            }
        }
    }

    private fun play(url: String, forceTs: Boolean) {
        releasePlayer()
        everReady = false
        startedAt = SystemClock.elapsedRealtime()
        stall.reset()
        val lc = DefaultLoadControl.Builder()
            .setBufferDurationsMs(10000, 20000, 2500, 5000)
            .setTargetBufferBytes(8 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(45000)
            .setReadTimeoutMs(45000)
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            .setLoadControl(lc)
            .build()
        p.setVideoSurfaceView(surface)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> say("Cargando...")
                    Player.STATE_READY -> {
                        onSourceWorks()
                        say("Reproduciendo")
                        ui.postDelayed(clearInfo, 3000)
                    }
                    Player.STATE_ENDED -> say("Terminado")
                    else -> {}
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(
                    tag,
                    "error de reproduccion en ${titleLine()}: ${error.errorCodeName} buffer=${p.totalBufferedDuration} ms",
                    error
                )
                sourceFailed("error ${error.errorCodeName}")
            }
        })
        val mime = if (forceTs || !url.contains(".m3u8")) MimeTypes.VIDEO_MP2T else MimeTypes.APPLICATION_M3U8
        p.setMediaItem(MediaItem.Builder().setUri(url).setMimeType(mime).build())
        p.playWhenReady = true
        p.prepare()
        player = p
    }

    private fun releasePlayer() {
        player?.release()
        player = null
    }

    private fun stopSession() {
        val c = commandUrl
        commandUrl = null
        session++
        if (c != null) stopRemoteSession(c)
    }

    private fun stopRemoteSession(cmd: String) {
        io.execute {
            try {
                httpGet("$cmd?method=stop", 5000)
            } catch (e: Exception) {
                Log.w(tag, "stop: ${e.message}")
            }
        }
    }

    private fun httpGet(u: String, timeoutMs: Int): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    // ---------------------------------------------------------------- mando por movil

    private var remote: MiniHttpServer? = null
    private val remoteSession = RemoteSession()

    private val controller = object : RemoteController {
        override fun channels(): List<Channel> = rows.map { Channel(it.name, it.hash, it.group) }
        override fun playing(): Channel? = if (playing) current else null
        override fun engineHost(): String = prefs.getString("host", "192.168.1.34") ?: "192.168.1.34"
        override fun engineMode(): String = prefs.getString("mode", "ts") ?: "ts"
        override fun listSource(): String = if (customFile().exists()) "subida" else "incluida"
        override fun play(hash: String) {
            ui.post { playHash(hash) }
        }

        override fun zap(delta: Int) {
            ui.post { remoteZap(delta) }
        }

        override fun setEngineHost(host: String) {
            ui.post { changeHost(host) }
        }

        override fun applyList(list: List<Channel>?) {
            ui.post { applyListNow(list) }
        }

        override fun httpGet(url: String): String = httpGetAny(url, 3000)
        override fun page(): String = readAsset("remote.html") ?: "<h1>Falta remote.html</h1>"
    }
    private val api = RemoteApi(remoteSession, controller)

    private fun hideQrPanel() {
        qrPanel.visibility = View.GONE
        channelList.visibility = View.VISIBLE
    }

    private fun showQrPanel(renew: Boolean) {
        channelList.visibility = View.GONE
        qrPanel.visibility = View.VISIBLE
        startRemote()
        if (remote == null) {
            qrImage.setImageBitmap(null)
            qrText.text = "No se pudo abrir el puerto 8765 (mira el log, etiqueta AceTV)"
            return
        }
        if (renew || remoteSession.status() != RemoteSession.Result.OK) remoteSession.renew()
        val ip = localIp()
        if (ip == null) {
            qrImage.setImageBitmap(null)
            qrText.text = "No se pudo obtener la IP de la TV. Comprueba la conexion de red."
            return
        }
        val url = "http://$ip:8765/s/${remoteSession.code}/"
        val m = QrRender.modules(url)
        val scale = maxOf(1, 520 / m.size)
        val (size, px) = QrRender.pixels(m, scale)
        qrImage.layoutParams = LinearLayout.LayoutParams(size, size)
        qrImage.setImageBitmap(Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888))
        qrText.text = "Escanea el codigo con el movil (misma wifi)\n$url\n" +
            "Caduca a los 5 min sin usar. OK en \"+ Nuevo QR\" genera uno nuevo.\n" +
            "Motor: ${controller.engineHost()} (${controller.engineMode().uppercase()})   " +
            "Lista: ${controller.listSource()} (${rows.size} canales)"
    }

    private fun startRemote() {
        if (remote != null) return
        try {
            val srv = MiniHttpServer(8765, 8 * 1024 * 1024) { req -> api.handle(req) }
            srv.start()
            remote = srv
            Log.i(tag, "mando: http://${localIp() ?: "IP_DE_LA_TV"}:8765/s/${remoteSession.code}/")
        } catch (e: Exception) {
            Log.e(tag, "mando: no se pudo abrir el puerto 8765: ${e.message}")
        }
    }

    private fun stopRemote() {
        remote?.stop()
        remote = null
    }

    private fun localIp(): String? {
        try {
            for (ni in java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in java.util.Collections.list(ni.inetAddresses)) {
                    if (a is java.net.Inet4Address && !a.isLoopbackAddress) return a.hostAddress
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "ip: ${e.message}")
        }
        return null
    }

    private fun httpGetAny(u: String, timeoutMs: Int): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        try {
            val st = c.responseCode
            val stream = if (st >= 400) c.errorStream else c.inputStream
            return stream?.bufferedReader()?.use { it.readText() } ?: ""
        } finally {
            c.disconnect()
        }
    }

    // Reproduce un canal por el hash de cualquiera de sus fuentes (o un ID manual que no esta en la lista)
    private fun playHash(hash: String) {
        val row = rowByHash[hash] ?: ChannelMerge.manualRow(hash)
        zapRows = if (row.group.isEmpty()) listOf(row) else rows.filter { it.group == row.group }
        startRow(row)
    }

    private fun remoteZap(delta: Int) {
        if (playing) {
            zap(delta)
        } else {
            val r = currentRow
            if (r != null) startRow(r)
        }
    }

    private fun changeHost(host: String) {
        prefs.edit().putString("host", host).apply()
        val c = current
        if (playing && c != null) openSession(c.hash)
    }

    private fun customFile() = java.io.File(filesDir, "custom_list.json")

    private fun readCustom(): List<Channel>? {
        val f = customFile()
        if (!f.exists()) return null
        return try {
            val l = ChannelLogic.dedupe(ListParsers.parseJson(f.readText(Charsets.UTF_8)))
            if (l.isEmpty()) null else l
        } catch (e: Exception) {
            Log.e(tag, "custom_list: ${e.message}")
            null
        }
    }

    private fun applyListNow(list: List<Channel>?) {
        if (list == null) {
            customFile().delete()
        } else {
            customFile().writeText(ListParsers.toJson(list), Charsets.UTF_8)
        }
        reloadChannels()
    }

    // ---------------------------------------------------------------- lista

    private fun readAsset(name: String): String? =
        try {
            assets.open(name).bufferedReader().use { it.readText() }
        } catch (e: java.io.IOException) {
            null
        }

    // Lista subida desde el movil, o las incluidas en la compilacion: lista.json y lista.m3u del proyecto
    private fun loadChannels(): List<Channel> {
        val custom = readCustom()
        if (custom != null) {
            Log.i(tag, "lista subida: ${custom.size} canales")
            return custom
        }
        var json: List<Channel> = emptyList()
        var m3u: List<Channel> = emptyList()
        try {
            readAsset("lista.json")?.let { json = ListParsers.parseJson(it) }
        } catch (e: Exception) {
            Log.e(tag, "lista.json: ${e.message}")
        }
        try {
            readAsset("lista.m3u")?.let { m3u = ListParsers.parseM3u(it) }
        } catch (e: Exception) {
            Log.e(tag, "lista.m3u: ${e.message}")
        }
        val merged = ListParsers.mergeUnique(json, m3u)
        Log.i(tag, "listas: json=${json.size} m3u=${m3u.size} unicos=${merged.size} grupos=${ChannelLogic.groupsOf(merged).size}")
        return merged
    }
}

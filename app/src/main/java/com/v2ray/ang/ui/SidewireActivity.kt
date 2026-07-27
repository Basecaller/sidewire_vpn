package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import com.v2ray.ang.service.AppTriggerService
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.Utils
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sidewire shell: hosts the custom HTML UI in a WebView and bridges its
 * buttons to the real v2rayNG / xray backend.
 */
class SidewireActivity : ComponentActivity() {

    private lateinit var web: WebView
    @Volatile private var running = false

    private val requestVpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) doConnect()
        }

    /** Receives running-state broadcasts from the (separate-process) core service. */
    private val msgReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING, AppConfig.MSG_STATE_START_SUCCESS -> setRunning(true)
                AppConfig.MSG_STATE_NOT_RUNNING, AppConfig.MSG_STATE_STOP_SUCCESS -> setRunning(false)
                AppConfig.MSG_MEASURE_CONFIG_SUCCESS, AppConfig.MSG_MEASURE_CONFIG_NOTIFY -> pushPing(false)
                AppConfig.MSG_MEASURE_CONFIG_FINISH -> pushPing(true)
            }
        }
    }

    private fun pushPing(finished: Boolean) {
        runOnUiThread {
            if (::web.isInitialized) web.evaluateJavascript("window.onPing&&window.onPing($finished)", null)
        }
    }

    private fun setRunning(r: Boolean) {
        running = r
        runOnUiThread {
            if (::web.isInitialized) web.evaluateJavascript("window.onNativeState&&window.onNativeState($r)", null)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        // Perf: keep assets cached, drop the overscroll glow repaint, and render on
        // a hardware layer so tab switches and scrolling stay smooth.
        web.settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        web.overScrollMode = WebView.OVER_SCROLL_NEVER
        web.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        web.setBackgroundColor(0xFF0F0F10.toInt())
        web.addJavascriptInterface(Bridge(), "Native")
        setContentView(web)
        web.loadUrl("file:///android_asset/sidewire.html")

        ContextCompat.registerReceiver(
            this, msgReceiver, IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        MessageUtil.sendMsg2Service(this, AppConfig.MSG_REGISTER_CLIENT, "")
    }

    override fun onResume() {
        super.onResume()
        MessageUtil.sendMsg2Service(this, AppConfig.MSG_REGISTER_CLIENT, "")
        syncWatcher()
    }

    /**
     * Keeps the app-trigger watcher running whenever the user has selected trigger
     * apps and granted Usage Access — independent of whether the VPN is on, so it
     * can auto-connect the VPN when a selected app is opened.
     */
    private fun syncWatcher() {
        val hasOn = MmkvManager.decodeSettingsStringSet(AppTriggerService.KEY_TRIGGERS)?.isNotEmpty() == true
        val hasOff = MmkvManager.decodeSettingsStringSet(AppTriggerService.KEY_TRIGGERS_OFF)?.isNotEmpty() == true
        val hasAny = hasOn || hasOff
        if (hasAny && hasUsageAccess()) AppTriggerService.start(this)
        else if (!hasAny) AppTriggerService.stop(this)
    }

    override fun onDestroy() {
        try {
            MessageUtil.sendMsg2Service(this, AppConfig.MSG_UNREGISTER_CLIENT, "")
            unregisterReceiver(msgReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun connectRequest() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            web.evaluateJavascript("window.onToast&&window.onToast('Сначала выберите сервер')", null)
            return
        }
        val intent = VpnService.prepare(this)
        if (intent == null) doConnect() else requestVpnPermission.launch(intent)
    }

    private fun doConnect() {
        CoreServiceManager.startVService(this)
        // keep the app-trigger watcher in sync (it runs whenever either list is set)
        syncWatcher()
    }

    private fun hasUsageAccess(): Boolean {
        return try {
            val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = ops.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    /** Stable per-device HWID required by some subscription panels (Happ-style). */
    private fun hwid(): String {
        val k = "sidewire_hwid"
        var id = MmkvManager.decodeSettingsString(k)
        if (id.isNullOrEmpty()) {
            id = java.util.UUID.randomUUID().toString()
            MmkvManager.encodeSettings(k, id)
        }
        return id
    }

    private fun hwidUserAgent(): String = "Happ/2.0.0 (Android ${android.os.Build.VERSION.RELEASE})"

    private fun hwidHeaders(): String {
        val id = hwid()
        return JSONObject()
            .put("x-hwid", id)
            .put("hwid", id)
            .put("x-device-os", "Android")
            .put("x-ver-os", android.os.Build.VERSION.RELEASE)
            .put("x-device-model", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            .toString()
    }

    private fun iconB64(pkg: String): String {
        return try {
            val d = packageManager.getApplicationIcon(pkg)
            val size = 48
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            d.setBounds(0, 0, size, size)
            d.draw(c)
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 90, bos)
            bmp.recycle()
            "data:image/png;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) {
            ""
        }
    }

    private fun decodeTitle(t: String): String = try {
        if (t.startsWith("base64:")) String(Base64.decode(t.substring(7), Base64.DEFAULT)) else t
    } catch (_: Exception) { t }

    /** Fetches subscription-userinfo / profile-title headers (traffic, expiry, name) for display. */
    private fun fetchAndStoreMeta(subId: String, url: String) {
        try {
            val conn = URL(url.trim()).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 12000
            conn.readTimeout = 12000
            conn.setRequestProperty("User-Agent", hwidUserAgent())
            val id = hwid()
            conn.setRequestProperty("x-hwid", id)
            conn.setRequestProperty("hwid", id)
            conn.setRequestProperty("x-device-os", "Android")
            conn.setRequestProperty("x-ver-os", android.os.Build.VERSION.RELEASE)
            conn.setRequestProperty("x-device-model", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            conn.connect()
            val info = conn.getHeaderField("subscription-userinfo")
            val title = conn.getHeaderField("profile-title")
            android.util.Log.i("SidewireMeta", "code=${conn.responseCode} userinfo=$info title=$title headers=${conn.headerFields.keys}")
            try { conn.inputStream.close() } catch (_: Exception) {}
            val o = JSONObject()
            if (!info.isNullOrBlank()) {
                var up = 0L; var down = 0L; var total = 0L; var expire = 0L
                info.split(";").forEach { part ->
                    val kv = part.trim().split("=")
                    if (kv.size == 2) {
                        val v = kv[1].trim().toLongOrNull() ?: 0L
                        when (kv[0].trim()) {
                            "upload" -> up = v; "download" -> down = v; "total" -> total = v; "expire" -> expire = v
                        }
                    }
                }
                o.put("used", up + down); o.put("total", total); o.put("expire", expire)
            }
            if (!title.isNullOrBlank()) o.put("title", decodeTitle(title))
            if (o.length() > 0) MmkvManager.encodeSettings("sw_meta_$subId", o.toString())
            if (o.has("title")) {
                MmkvManager.decodeSubscription(subId)?.let { it.remarks = o.getString("title"); MmkvManager.encodeSubscription(subId, it) }
            }
        } catch (_: Exception) {}
    }

    private fun tcpPing(host: String, port: Int): Long {
        return try {
            val s = java.net.Socket()
            val t0 = System.currentTimeMillis()
            s.connect(java.net.InetSocketAddress(host, port), 3000)
            s.close()
            System.currentTimeMillis() - t0
        } catch (_: Exception) { -1L }
    }

    inner class Bridge {

        @JavascriptInterface
        fun status(): String {
            val o = JSONObject()
            o.put("running", running)
            val sel = MmkvManager.getSelectServer().orEmpty()
            o.put("selected", sel)
            val cfg = if (sel.isNotEmpty()) MmkvManager.decodeServerConfig(sel) else null
            o.put("serverName", cfg?.remarks.orEmpty())
            return o.toString()
        }

        @JavascriptInterface
        fun servers(): String {
            val arr = JSONArray()
            for (guid in MmkvManager.decodeAllServerList()) {
                val cfg = MmkvManager.decodeServerConfig(guid) ?: continue
                val o = JSONObject()
                o.put("guid", guid)
                o.put("name", cfg.remarks)
                o.put("type", cfg.configType.name)
                arr.put(o)
            }
            return arr.toString()
        }

        @JavascriptInterface
        fun select(guid: String) {
            MmkvManager.setSelectServer(guid)
        }

        /** Subscriptions grouped with their servers (for the V2RayTun-style blocks). */
        @JavascriptInterface
        fun subs(): String {
            val arr = JSONArray()
            val sel = MmkvManager.getSelectServer().orEmpty()
            val subsList = MmkvManager.decodeSubscriptions()
            for (sc in subsList) {
                val o = JSONObject()
                o.put("id", sc.guid)
                o.put("name", sc.subscription.remarks.ifEmpty { "Подписка" })
                val metaStr = MmkvManager.decodeSettingsString("sw_meta_${sc.guid}")
                o.put("meta", if (metaStr != null) JSONObject(metaStr) else JSONObject())
                val servers = JSONArray()
                for (g in MmkvManager.decodeServerList(sc.guid)) {
                    val cfg = MmkvManager.decodeServerConfig(g) ?: continue
                    val ms = MmkvManager.decodeServerAffiliationInfo(g)?.testDelayMillis ?: 0L
                    servers.put(
                        JSONObject().put("guid", g).put("name", cfg.remarks)
                            .put("type", cfg.configType.name).put("sel", g == sel).put("ping", ms)
                    )
                }
                o.put("servers", servers)
                arr.put(o)
            }
            return arr.toString()
        }

        /** Installed launchable apps with their current trigger state (synchronous). */
        @JavascriptInterface
        fun installedApps(): String = buildInstalledAppsJson()

        /**
         * Same as installedApps(), but the (expensive) enumeration + icon encoding
         * runs on a background thread and the result is delivered via window.onApps.
         * This keeps switching to the apps tab instant instead of freezing the UI.
         */
        @JavascriptInterface
        fun loadAppsAsync() {
            Thread {
                val json = try { buildInstalledAppsJson() } catch (_: Exception) { "[]" }
                runOnUiThread {
                    web.evaluateJavascript("window.onApps && window.onApps(${JSONObject.quote(json)})", null)
                }
            }.start()
        }

        private fun buildInstalledAppsJson(): String {
            val pm = packageManager
            val onSet = MmkvManager.decodeSettingsStringSet(AppTriggerService.KEY_TRIGGERS) ?: mutableSetOf()
            val offSet = MmkvManager.decodeSettingsStringSet(AppTriggerService.KEY_TRIGGERS_OFF) ?: mutableSetOf()
            val paused = MmkvManager.decodeSettingsStringSet(AppTriggerService.KEY_PAUSED) ?: mutableSetOf()
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val seen = HashSet<String>()
            val items = ArrayList<JSONObject>()
            for (ri in pm.queryIntentActivities(main, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == packageName || !seen.add(pkg)) continue
                val label = try { ri.loadLabel(pm).toString() } catch (_: Exception) { pkg }
                items.add(JSONObject().put("pkg", pkg).put("label", label)
                    .put("on", onSet.contains(pkg)).put("off", offSet.contains(pkg))
                    .put("active", !paused.contains(pkg)).put("icon", iconB64(pkg)))
            }
            items.sortBy { it.optString("label").lowercase() }
            return JSONArray(items).toString()
        }

        /**
         * Add/remove an app from one of the two trigger lists.
         * mode = "on"  → list of apps that turn the VPN ON when opened.
         * mode = "off" → list of apps that turn the VPN OFF when opened.
         * An app can only be in one list at a time.
         */
        @JavascriptInterface
        fun setTrigger(pkg: String, add: Boolean, mode: String) {
            val key = if (mode == "off") AppTriggerService.KEY_TRIGGERS_OFF else AppTriggerService.KEY_TRIGGERS
            val otherKey = if (mode == "off") AppTriggerService.KEY_TRIGGERS else AppTriggerService.KEY_TRIGGERS_OFF
            val set = MmkvManager.decodeSettingsStringSet(key)?.toMutableSet() ?: mutableSetOf()
            if (add) {
                set.add(pkg)
                // keep the lists mutually exclusive
                val other = MmkvManager.decodeSettingsStringSet(otherKey)?.toMutableSet() ?: mutableSetOf()
                if (other.remove(pkg)) MmkvManager.encodeSettings(otherKey, other)
            } else {
                set.remove(pkg)
            }
            MmkvManager.encodeSettings(key, set)
            runOnUiThread { syncWatcher() }
        }

        /** Pause/resume an app's rule without removing it from its list. */
        @JavascriptInterface
        fun setAppActive(pkg: String, active: Boolean) {
            val paused = MmkvManager.decodeSettingsStringSet(AppTriggerService.KEY_PAUSED)?.toMutableSet() ?: mutableSetOf()
            if (active) paused.remove(pkg) else paused.add(pkg)
            MmkvManager.encodeSettings(AppTriggerService.KEY_PAUSED, paused)
        }

        @JavascriptInterface
        fun hasUsageAccess(): Boolean = this@SidewireActivity.hasUsageAccess()

        @JavascriptInterface
        fun openUsageAccess() {
            runOnUiThread {
                try {
                    val i = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    i.data = Uri.parse("package:$packageName")
                    startActivity(i)
                } catch (_: Exception) {
                    try { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } catch (_: Exception) {}
                }
            }
        }

        /** Re-fetch subscription-userinfo (traffic/expiry/title) for all subs without re-importing servers. */
        @JavascriptInterface
        fun refreshMeta() {
            Thread {
                try {
                    for (sc in MmkvManager.decodeSubscriptions()) {
                        fetchAndStoreMeta(sc.guid, sc.subscription.url)
                    }
                } catch (_: Exception) {}
                runOnUiThread { web.evaluateJavascript("window.onSubDone&&window.onSubDone(-1)", null) }
            }.start()
        }

        @JavascriptInterface
        fun connect() {
            runOnUiThread { connectRequest() }
        }

        @JavascriptInterface
        fun disconnect() {
            CoreServiceManager.stopVService(this@SidewireActivity)
            // Keep the app-trigger watcher running (if trigger apps are set) so the
            // VPN can still auto-connect when a selected app is opened again.
        }

        @JavascriptInterface
        fun addSub(url: String) {
            Thread {
                val before = MmkvManager.decodeAllServerList().size
                try {
                    val guid = Utils.getUuid()
                    MmkvManager.encodeSubscription(
                        guid,
                        SubscriptionItem(
                            remarks = "Sidewire",
                            url = url.trim(),
                            enabled = true,
                            userAgent = hwidUserAgent(),
                            requestHeaders = hwidHeaders()
                        )
                    )
                    fetchAndStoreMeta(guid, url.trim())
                    AngConfigManager.updateConfigViaSubAll()
                } catch (_: Exception) {
                }
                val after = MmkvManager.decodeAllServerList().size
                runOnUiThread {
                    web.evaluateJavascript("window.onSubDone&&window.onSubDone(${after - before})", null)
                }
            }.start()
        }

        @JavascriptInterface
        fun updateSubs() {
            Thread {
                try {
                    // Inject HWID headers into every stored subscription, then refetch + refresh meta.
                    for (sc in MmkvManager.decodeSubscriptions()) {
                        sc.subscription.userAgent = hwidUserAgent()
                        sc.subscription.requestHeaders = hwidHeaders()
                        MmkvManager.encodeSubscription(sc.guid, sc.subscription)
                        fetchAndStoreMeta(sc.guid, sc.subscription.url)
                    }
                    AngConfigManager.updateConfigViaSubAll()
                } catch (_: Exception) {
                }
                runOnUiThread {
                    web.evaluateJavascript("window.onSubDone&&window.onSubDone(-1)", null)
                }
            }.start()
        }

        @JavascriptInterface
        fun removeSub(subId: String) {
            MmkvManager.removeServerViaSubid(subId)
            MmkvManager.removeSubscription(subId)
        }

        /** Device-wide byte counters; JS derives live speed from deltas. */
        @JavascriptInterface
        fun traffic(): String {
            return JSONObject()
                .put("rx", android.net.TrafficStats.getTotalRxBytes())
                .put("tx", android.net.TrafficStats.getTotalTxBytes())
                .toString()
        }

        /** TCP-pings every server across all subscriptions; results pushed to window.onPing. */
        /** Real latency test through the core (works for CUSTOM/JSON configs). */
        @JavascriptInterface
        fun pingAll() {
            Thread {
                try {
                    val guids = MmkvManager.decodeAllServerList()
                    MessageUtil.sendMsg2TestService(this@SidewireActivity, TestServiceMessage(key = AppConfig.MSG_MEASURE_CONFIG_CANCEL))
                    MmkvManager.clearAllTestDelayResults(guids)
                    runOnUiThread { web.evaluateJavascript("window.onPing&&window.onPing(false)", null) }
                    MessageUtil.sendMsg2TestService(
                        this@SidewireActivity,
                        TestServiceMessage(key = AppConfig.MSG_MEASURE_CONFIG_START, subscriptionId = "", serverGuids = guids)
                    )
                } catch (_: Exception) {}
            }.start()
        }

        /** Current measured delays: {guid: ms}. ms<=0 means not tested / failed. */
        @JavascriptInterface
        fun pings(): String {
            val o = JSONObject()
            for (g in MmkvManager.decodeAllServerList()) {
                o.put(g, MmkvManager.decodeServerAffiliationInfo(g)?.testDelayMillis ?: 0L)
            }
            return o.toString()
        }

        @JavascriptInterface
        fun openScanner() {
            runOnUiThread {
                startActivity(Intent(this@SidewireActivity, ScScannerActivity::class.java))
            }
        }

        @JavascriptInterface
        fun openPerApp() {
            runOnUiThread {
                startActivity(Intent(this@SidewireActivity, PerAppProxyActivity::class.java))
            }
        }

        // --- Settings toggles ---
        @JavascriptInterface
        fun getSetting(key: String): Boolean = MmkvManager.decodeSettingsBool(key, false)

        @JavascriptInterface
        fun setSetting(key: String, on: Boolean) {
            MmkvManager.encodeSettings(key, on)
        }

        @JavascriptInterface
        fun getAdBlock(): Boolean =
            MmkvManager.decodeSettingsString(AppConfig.PREF_REMOTE_DNS)?.contains("adguard") == true

        @JavascriptInterface
        fun setAdBlock(on: Boolean) {
            MmkvManager.encodeSettings(
                AppConfig.PREF_REMOTE_DNS,
                if (on) ADGUARD_DNS else AppConfig.DNS_PROXY
            )
        }

        @JavascriptInterface
        fun openVpnSettings() {
            runOnUiThread {
                try {
                    startActivity(Intent(Settings.ACTION_VPN_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
        }
    }

    companion object {
        private const val ADGUARD_DNS = "https://dns.adguard-dns.com/dns-query"
    }
}

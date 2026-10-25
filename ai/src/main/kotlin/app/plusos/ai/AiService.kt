// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import app.plusos.core.ai.AiRequest
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/**
 * The bound service other apps use (ai-service-design §4). Every call checks USE_AI for the calling UID; one request
 * runs at a time (BUSY otherwise); the model is unloaded 5 minutes after the last use or when every client released it;
 * a foreground notification shows while a model is loaded.
 */
class AiService : Service() {
    private lateinit var store: ModelStore
    private lateinit var host: ModelHost
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private val holders = mutableSetOf<Int>()
    private val idleUnload = Runnable { unloadNow("idle for 5 minutes") }

    override fun onCreate() {
        super.onCreate()
        store = ModelStore(this)
        host = ModelHost(File(cacheDir, "litertlm").apply { mkdirs() })
    }

    override fun onBind(intent: Intent): IBinder = binder

    private val binder = object : IAiService.Stub() {
        override fun models(): String {
            check()
            val catalog = store.catalog()
            val arr = JSONArray()
            catalog?.models?.forEach { m ->
                arr.put(JSONObject().put("id", m.id).put("name", m.name).put("installed", store.installed(m))
                    .put("size", m.size).put("license", m.license).put("context_tokens", m.contextTokens)
                    .put("loaded", host.loaded?.modelId == m.id))
            }
            return JSONObject().put("models", arr).put("loaded", host.loaded?.let { "${it.modelId}/${it.backend}" }).toString()
        }

        override fun prepare(modelId: String, backend: String, cb: IAiCallback) {
            val uid = check()
            val model = store.catalog()?.model(modelId)
            if (model == null || !store.installed(model)) { cb.onError("NO_MODEL", "model $modelId is not installed in +PLUS AI"); return }
            if (backend !in model.backends) { cb.onError("BAD_REQUEST", "backend must be one of ${model.backends}"); return }
            if (!busy.compareAndSet(false, true)) { cb.onError("BUSY", "another request is running"); return }
            // The callback is delivered after `busy` is released, so a client can call generate() from onReady().
            worker.execute {
                val info = try {
                    startForegroundNow(model.name)
                    val l = host.load(model.id, store.fileOf(model), backend, model.contextTokens)
                    synchronized(holders) { holders += uid }
                    scheduleIdleUnload()
                    JSONObject().put("model", l.modelId).put("backend", l.backend).put("init_ms", l.initMs).toString()
                } catch (t: Throwable) {
                    Log.w(ModelHost.TAG, "load failed: ${t.javaClass.simpleName}")
                    unloadNow("load failed")
                    busy.set(false)
                    runCatching { cb.onError("LOAD_FAILED", t.message ?: t.javaClass.simpleName) }
                    return@execute
                }
                busy.set(false)
                runCatching { cb.onReady(info) }
            }
        }

        override fun generate(requestJson: String, cb: IAiCallback) {
            check()
            val loaded = host.loaded ?: run { cb.onError("NO_MODEL", "call prepare() first"); return }
            val req = try { AiRequest.parse(requestJson, loaded.contextTokens) } catch (e: AiRequest.BadRequest) { cb.onError("BAD_REQUEST", e.message ?: "bad request"); return }
            if (!busy.compareAndSet(false, true)) { cb.onError("BUSY", "another request is running"); return }
            val death = IBinder.DeathRecipient { host.cancel() }
            runCatching { cb.asBinder().linkToDeath(death, 0) }
            worker.execute {
                main.removeCallbacks(idleUnload)
                val outcome = runCatching { host.generate(req) { chunk -> runCatching { cb.onToken(chunk) } } }
                runCatching { cb.asBinder().unlinkToDeath(death, 0) }
                busy.set(false)
                scheduleIdleUnload()
                outcome.onSuccess { runCatching { cb.onResult(it.toString()) } }.onFailure { t ->
                    runCatching { cb.onError(if (t.message?.contains("cancel", true) == true) "CANCELLED" else "FAILED", t.message ?: "failed") }
                }
            }
        }

        override fun cancel() { check(); host.cancel() }

        override fun release() {
            val uid = check()
            val empty = synchronized(holders) { holders -= uid; holders.isEmpty() }
            if (empty && !busy.get()) worker.execute { unloadNow("released by all clients") }
        }

        /**
         * The manifest's android:permission guards binding; check every call too (and return the caller's UID). The
         * app's own "Try it" screen is the owner using the app directly, so its own UID needs no grant.
         */
        private fun check(): Int {
            val uid = Binder.getCallingUid()
            if (uid != android.os.Process.myUid() && checkCallingPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("uid $uid lacks $PERMISSION")
            }
            return uid
        }
    }

    private fun scheduleIdleUnload() { main.removeCallbacks(idleUnload); main.postDelayed(idleUnload, IDLE_MS) }

    private fun unloadNow(why: String) {
        host.unload()
        synchronized(holders) { holders.clear() }
        Log.i(ModelHost.TAG, "model unloaded: $why")
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun startForegroundNow(modelName: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("running", "Model running", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "running").setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("+PLUS AI is running a model").setContentText(modelName).setOngoing(true).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    override fun onDestroy() { host.unload(); worker.shutdownNow(); super.onDestroy() }

    companion object {
        const val PERMISSION = "app.plusos.ai.permission.USE_AI"
        const val IDLE_MS = 5 * 60 * 1000L
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.ai

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.json.JSONObject

/**
 * Dev only (the staging build; never in the evaluation build): runs a file of requests through +PLUS AI and writes the
 * answers, to evaluate prompts on the phone's own model (2026-10-23: the phone's copy of Gemma 4 E2B handled numbers
 * worse than the PC's, so PC results did not carry over). Only adb can open it (android.permission.DUMP).
 *
 *   (once) adb shell am start -n app.plusos.ai/.BatchActivity   -- creates files/batch owned by the app; a folder adb
 *          creates is owned by the shell and the app gets EACCES reading from it
 *   adb push in.jsonl /sdcard/Android/data/app.plusos.ai/files/batch/in.jsonl
 *   adb shell am start -n app.plusos.ai/.BatchActivity [--es backend cpu]
 *   (logcat "PlusAI: batch done") then adb pull .../batch/out.jsonl
 *
 * in.jsonl: {"id": "...", "request": {<generate request>}} per line. out.jsonl: {"id", "text", "thought"?, timings} or
 * {"id", "error"}; ids already in out.jsonl are skipped, so a stopped run resumes.
 */
class BatchActivity : Activity() {
    private lateinit var status: TextView
    private var conn: ServiceConnection? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status = TextView(this).apply { setPadding(48, 160, 48, 48); text = "Batch: starting" }
        setContentView(status)
        val backend = intent.getStringExtra("backend") ?: "gpu"
        val c = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
                val ai = IAiService.Stub.asInterface(b)
                thread(name = "batch") { runCatching { run(ai, backend) }.onFailure { show("Batch failed: ${it.message}"); Log.w(ModelHost.TAG, "batch failed", it) } }
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        conn = c
        bindService(Intent(this, AiService::class.java).setAction("app.plusos.ai.BIND"), c, BIND_AUTO_CREATE)
    }

    private fun run(ai: IAiService, backend: String) {
        val dir = File(getExternalFilesDir(null), "batch").apply { mkdirs() }
        val input = File(dir, "in.jsonl")
        val output = File(dir, "out.jsonl")
        val done = if (output.exists()) output.readLines().mapNotNull { runCatching { JSONObject(it).getString("id") }.getOrNull() }.toSet() else emptySet()
        val todo = input.readLines().filter { it.isNotBlank() }.map { JSONObject(it) }.filter { it.getString("id") !in done }
        val models = JSONObject(ai.models()).getJSONArray("models")
        val model = (0 until models.length()).map { models.getJSONObject(it) }.first { it.optBoolean("installed") }.getString("id")
        show("Batch: loading $model on $backend")
        await { ai.prepare(model, backend, it) }
        output.appendText("")
        for ((i, line) in todo.withIndex()) {
            val id = line.getString("id")
            show("Batch: ${i + 1}/${todo.size} ($backend)")
            val rec = try {
                JSONObject(await { ai.generate(line.getJSONObject("request").toString(), it) }).put("id", id).put("backend", backend)
            } catch (e: Exception) {
                JSONObject().put("id", id).put("error", e.message ?: e.javaClass.simpleName)
            }
            output.appendText(rec.toString() + "\n")
        }
        Log.i(ModelHost.TAG, "batch done: ${todo.size} on $backend")
        show("Batch done: ${todo.size} on $backend")
    }

    private fun await(start: (IAiCallback) -> Unit): String {
        val q = LinkedBlockingQueue<Result<String>>(1)
        start(object : IAiCallback.Stub() {
            override fun onReady(infoJson: String?) { q.offer(Result.success(infoJson ?: "{}")) }
            override fun onToken(text: String?) {}
            override fun onResult(resultJson: String?) { q.offer(Result.success(resultJson ?: "{}")) }
            override fun onError(code: String?, message: String?) { q.offer(Result.failure(IllegalStateException("$code: $message"))) }
        })
        return (q.poll(10, TimeUnit.MINUTES) ?: throw IllegalStateException("timed out")).getOrThrow()
    }

    private fun show(s: String) = runOnUiThread { status.text = s }

    override fun onDestroy() {
        conn?.let { runCatching { unbindService(it) } }
        super.onDestroy()
    }
}

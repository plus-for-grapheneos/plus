// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
@file:OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)

package app.plusos.ai

import android.util.Log
import app.plusos.core.ai.AiRequest
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * The one loaded LiteRT-LM engine (adapted from the LLM Runner, which proved it on the Pixel 9 GPU). One request at a
 * time; thinking is off (the 2026-10-18 decision: no thinking for summaries or live decisions). Prompts and outputs
 * are never logged.
 */
class ModelHost(private val cacheDir: File) {
    @Volatile var loaded: Loaded? = null; private set
    @Volatile private var current: com.google.ai.edge.litertlm.Conversation? = null
    private val lock = Any()

    data class Loaded(val modelId: String, val file: File, val backend: String, val contextTokens: Int, val initMs: Long, val engine: Engine)

    fun load(modelId: String, file: File, backend: String, contextTokens: Int): Loaded = synchronized(lock) {
        loaded?.let { if (it.modelId == modelId && it.backend == backend) return it }
        unloadLocked()
        ExperimentalFlags.enableBenchmark = true // per-turn timing for the caller
        val b = if (backend == "cpu") Backend.CPU() else Backend.GPU()
        val e = Engine(EngineConfig(modelPath = file.path, backend = b, maxNumTokens = contextTokens, cacheDir = cacheDir.path))
        val t0 = System.nanoTime()
        e.initialize()
        val l = Loaded(modelId, file, backend, contextTokens, (System.nanoTime() - t0) / 1_000_000, e)
        loaded = l
        Log.i(TAG, "loaded $modelId on $backend in ${l.initMs} ms")
        l
    }

    fun unload() = synchronized(lock) { unloadLocked() }

    private fun unloadLocked() {
        loaded?.let { runCatching { it.engine.close() }; Log.i(TAG, "unloaded ${it.modelId}") }
        loaded = null
    }

    /** Runs [req] on the loaded model, streaming text to [onToken]; returns the timing JSON with the full text. */
    fun generate(req: AiRequest, onToken: (String) -> Unit): JSONObject = synchronized(lock) {
        val l = loaded ?: throw IllegalStateException("no model loaded")
        val history = req.messages.dropLast(1).map { if (it.role == "user") Message.user(it.content) else Message.model(Contents.of(it.content)) }
        val greedy = req.temperature <= 0.0
        val cfg = ConversationConfig(
            systemInstruction = req.system?.let { Contents.of(it) },
            initialMessages = history,
            samplerConfig = SamplerConfig(
                topK = if (greedy) 1 else 64, topP = if (greedy) 1.0 else 0.95,
                temperature = if (greedy) 0.0 else req.temperature, seed = 0,
            ),
        )
        val text = StringBuilder()
        val thought = StringBuilder()
        var error: Throwable? = null
        val done = CountDownLatch(1)
        val t0 = System.nanoTime()
        val conv = l.engine.createConversation(cfg)
        current = conv
        try {
            conv.sendMessageAsync(
                Message.user(req.messages.last().content),
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        val chunk = message.contents.toString()
                        text.append(chunk)
                        // Gemma's reasoning arrives on its own channel when thinking is on; it is never part of the answer.
                        message.channels.values.forEach { thought.append(it) }
                        if (chunk.isNotEmpty()) onToken(chunk)
                    }
                    override fun onDone() = done.countDown()
                    override fun onError(throwable: Throwable) { error = throwable; done.countDown() }
                },
                maxOutputToken = req.maxTokens,
                thinkingConfig = ThinkingConfig(req.thinking, req.thinkingBudget),
            )
            if (!done.await(10, TimeUnit.MINUTES)) { conv.cancelProcess(); throw IllegalStateException("generation timed out") }
            error?.let { throw IllegalStateException("generation failed: ${it.message}") }
            val bi = conv.getBenchmarkInfo()
            return JSONObject()
                .put("text", text.toString())
                .apply { if (thought.isNotEmpty()) put("thought", thought.toString()) }
                .put("model", l.modelId).put("backend", l.backend)
                .put("ttft_ms", Math.round(bi.timeToFirstTokenInSecond * 1000))
                .put("prefill_tokens", bi.lastPrefillTokenCount).put("prefill_tps", Math.round(bi.lastPrefillTokensPerSecond))
                .put("decode_tokens", bi.lastDecodeTokenCount).put("decode_tps", Math.round(bi.lastDecodeTokensPerSecond * 10) / 10.0)
                .put("total_ms", (System.nanoTime() - t0) / 1_000_000)
        } finally {
            current = null
            runCatching { conv.close() }
        }
    }

    fun cancel() { current?.cancelProcess() }

    companion object { const val TAG = "PlusAI" }
}

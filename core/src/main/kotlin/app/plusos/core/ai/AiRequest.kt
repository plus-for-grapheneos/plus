// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A client's generate request (ai-service-design §4), validated before it reaches the engine: known roles, bounded
 * size, the last turn from the user, max_tokens capped by the model's context, temperature in 0..2.
 */
data class AiRequest(
    val messages: List<Msg>,
    val system: String?,
    val maxTokens: Int,
    val temperature: Double,
    /** Gemma's thinking before the answer (off unless asked); its tokens are capped by [thinkingBudget]. */
    val thinking: Boolean = false,
    val thinkingBudget: Int = DEFAULT_THINKING_BUDGET,
) {
    @Serializable
    data class Msg(val role: String, val content: String)

    class BadRequest(message: String) : IllegalArgumentException(message)

    @Serializable
    private data class Wire(
        val messages: List<Msg> = emptyList(),
        val system: String? = null,
        @SerialName("max_tokens") val maxTokens: Int? = null,
        val temperature: Double? = null,
        val thinking: Boolean = false,
        @SerialName("thinking_budget") val thinkingBudget: Int? = null,
    )

    companion object {
        const val DEFAULT_MAX_TOKENS = 512
        const val DEFAULT_THINKING_BUDGET = 1024
        const val MAX_CHARS = 64_000
        private val JSON = Json { ignoreUnknownKeys = true }
        private val ROLES = setOf("user", "assistant")

        fun parse(json: String, contextTokens: Int): AiRequest {
            val w = try { JSON.decodeFromString(Wire.serializer(), json) } catch (e: Exception) { throw BadRequest("not a valid request: ${e.message}") }
            if (w.messages.isEmpty()) throw BadRequest("messages is empty")
            w.messages.firstOrNull { it.role !in ROLES }?.let { throw BadRequest("unknown role ${it.role}") }
            if (w.messages.last().role != "user") throw BadRequest("the last message must be from the user")
            val chars = w.messages.sumOf { it.content.length } + (w.system?.length ?: 0)
            if (chars > MAX_CHARS) throw BadRequest("request too long ($chars characters, limit $MAX_CHARS)")
            val t = w.temperature ?: 0.0
            if (t !in 0.0..2.0) throw BadRequest("temperature must be 0..2")
            val maxTokens = (w.maxTokens ?: DEFAULT_MAX_TOKENS).coerceIn(1, contextTokens)
            val budget = (w.thinkingBudget ?: DEFAULT_THINKING_BUDGET).coerceIn(1, contextTokens)
            return AiRequest(w.messages, w.system, maxTokens, t, w.thinking, budget)
        }
    }
}

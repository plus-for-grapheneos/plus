// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AiRequestTest {
    @Test
    fun parses_messages_and_defaults() {
        val r = AiRequest.parse("""{"messages":[{"role":"user","content":"Hi"}]}""", contextTokens = 4096)
        assertEquals(listOf(AiRequest.Msg("user", "Hi")), r.messages)
        assertEquals(AiRequest.DEFAULT_MAX_TOKENS, r.maxTokens)
        assertEquals(0.0, r.temperature)
        assertEquals(null, r.system)
    }

    @Test
    fun max_tokens_capped_by_context() {
        val r = AiRequest.parse("""{"messages":[{"role":"user","content":"Hi"}],"max_tokens":100000}""", contextTokens = 4096)
        assertEquals(4096, r.maxTokens)
    }

    @Test
    fun rejects_bad_requests() {
        fun bad(json: String) = assertThrows<AiRequest.BadRequest> { AiRequest.parse(json, 4096) }
        bad("""{"messages":[]}""")
        bad("""{"messages":[{"role":"robot","content":"x"}]}""")
        bad("""{"messages":[{"role":"user","content":"x"}],"temperature":5}""")
        bad("""not json""")
        bad("""{"messages":[{"role":"user","content":"${"x".repeat(AiRequest.MAX_CHARS + 1)}"}]}""")
    }

    @Test
    fun last_message_must_be_from_the_user() {
        assertThrows<AiRequest.BadRequest> {
            AiRequest.parse("""{"messages":[{"role":"user","content":"a"},{"role":"assistant","content":"b"}]}""", 4096)
        }
    }

    @Test
    fun thinking_is_off_unless_asked_and_its_budget_is_capped() {
        // 2026-10-23: summaries and spam checks compare thinking on the phone; a client may ask for it per request.
        val off = AiRequest.parse("""{"messages":[{"role":"user","content":"Hi"}]}""", contextTokens = 4096)
        assertEquals(false, off.thinking)
        val on = AiRequest.parse("""{"messages":[{"role":"user","content":"Hi"}],"thinking":true,"thinking_budget":100000}""", contextTokens = 4096)
        assertEquals(true, on.thinking)
        assertEquals(4096, on.thinkingBudget)
        assertEquals(AiRequest.DEFAULT_THINKING_BUDGET,
            AiRequest.parse("""{"messages":[{"role":"user","content":"Hi"}],"thinking":true}""", contextTokens = 4096).thinkingBudget)
    }
}

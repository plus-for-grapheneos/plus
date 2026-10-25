package app.plusos.ai;

import app.plusos.ai.IAiCallback;

/**
 * +PLUS AI, bound with action app.plusos.ai.BIND. Callers need app.plusos.ai.permission.USE_AI (a runtime permission
 * the owner grants per app). One request runs at a time; prompts are never stored or logged.
 */
interface IAiService {
    String models();
    void prepare(String modelId, String backend, IAiCallback cb);
    void generate(String requestJson, IAiCallback cb);
    void cancel();
    void release();
}

package app.plusos.ai;

/** Results of one prepare or generate call (ai-service-design section 4). */
oneway interface IAiCallback {
    void onReady(String infoJson);
    void onToken(String text);
    void onResult(String resultJson);
    void onError(String code, String message);
}

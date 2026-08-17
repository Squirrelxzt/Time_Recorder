package com.example.timerecorder.ai;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * AI 助手 API 配置存取。
 * 独立 SharedPreferences 文件（MODE_PRIVATE），字段由用户在 App 内设置界面自填。
 * 注意：仅本类访问，代码不打日志（防止 api_key 泄漏到 logcat）。
 */
public class AiConfig {

    private static final String PREFS_NAME = "AiPrefs";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_MODEL = "model";
    private static final String KEY_PERSONA = "persona";

    private final SharedPreferences prefs;

    public AiConfig(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public String getBaseUrl() {
        return prefs.getString(KEY_BASE_URL, "");
    }

    public String getApiKey() {
        return prefs.getString(KEY_API_KEY, "");
    }

    public String getModel() {
        return prefs.getString(KEY_MODEL, "");
    }

    /** 用户自定义 AI 风格/人设；留空表示用默认风格。 */
    public String getPersona() {
        return prefs.getString(KEY_PERSONA, "");
    }

    public void save(String baseUrl, String apiKey, String model) {
        save(baseUrl, apiKey, model, "");
    }

    public void save(String baseUrl, String apiKey, String model, String persona) {
        prefs.edit()
            .putString(KEY_BASE_URL, baseUrl.trim())
            .putString(KEY_API_KEY, apiKey.trim())
            .putString(KEY_MODEL, model.trim())
            .putString(KEY_PERSONA, persona == null ? "" : persona.trim())
            .apply();
    }

    /** baseUrl、apiKey、model 三者均非空才算已配置 */
    public boolean isConfigured() {
        return !getBaseUrl().isEmpty() && !getApiKey().isEmpty() && !getModel().isEmpty();
    }

    /**
     * 最终请求地址 = baseUrl（去除尾部斜杠）+ /chat/completions。
     * 设置界面提示用户 baseUrl 需包含 /v1（如 https://api.deepseek.com/v1）。
     */
    public String getChatCompletionsUrl() {
        String baseUrl = getBaseUrl().trim();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl + "/chat/completions";
    }
}

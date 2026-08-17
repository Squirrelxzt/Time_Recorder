package com.example.timerecorder.ai;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * OpenAI 兼容 Chat Completions 客户端。
 * 零第三方依赖：Android 内置 HttpURLConnection + org.json。
 */
public class OpenAiClient {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    /** 错误响应体只透出前 200 字符 */
    private static final int ERROR_BODY_LIMIT = 200;

    /** API 调用异常：code = -1 表示网络/解析类错误，否则为 HTTP 状态码 */
    public static class ApiException extends Exception {
        public final int code;
        public final String body;

        public ApiException(int code, String body) {
            super("HTTP " + code + ": " + body);
            this.code = code;
            this.body = body;
        }

        public ApiException(String message, Throwable cause) {
            super(message, cause);
            this.code = -1;
            this.body = "";
        }
    }

    /**
     * 发送一次 POST 请求，返回 2xx 响应体的 JSON。
     *
     * @throws ApiException 非 2xx 状态码或响应无法解析
     * @throws IOException  网络层异常（连接超时、连接失败等）
     */
    public JSONObject chatCompletion(String urlStr, String apiKey, String requestBody)
            throws IOException, ApiException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            boolean isSuccess = code >= 200 && code < 300;
            InputStream stream = isSuccess ? conn.getInputStream() : conn.getErrorStream();
            String body = readAll(stream);
            if (isSuccess) {
                return new JSONObject(body);
            }
            // 仅错误响应体截断前 200 字符，成功响应必须完整读取
            String errBody = body.length() > ERROR_BODY_LIMIT ? body.substring(0, ERROR_BODY_LIMIT) : body;
            throw new ApiException(code, errBody);
        } catch (org.json.JSONException e) {
            throw new ApiException("返回数据格式异常", e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 完整读取响应流，不做截断（截断仅在 chatCompletion 的错误分支执行） */
    private String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }
}

package com.example.timerecorder.ai;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.timerecorder.R;
import com.example.timerecorder.adapter.ChatAdapter;
import com.example.timerecorder.database.DatabaseHelper;
import com.example.timerecorder.model.ChatMessage;
import com.google.android.material.chip.Chip;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 日程助手聊天页。
 * - 顶栏：返回 + 标题 + 设置（API 配置）
 * - 中部：RecyclerView 消息气泡（空态 / 加载态"正在思考…" / 错误态）
 * - 底部：建议 chips + 输入框 + 发送
 *
 * Function Calling 对话循环在后台单线程执行：
 * apiMessages（List<JSONObject>）是 API 请求的真源（保留 tool_calls 结构），
 * uiMessages（List<ChatMessage>）仅用于界面显示。
 */
public class ChatActivity extends AppCompatActivity {

    private static final String TAG = "ChatActivity";
    private static final String STATE_API_MESSAGES = "api_messages";
    private static final String STATE_UI_MESSAGES = "ui_messages";
    private static final int MAX_ITERATIONS = 20;
    /** 连续 3 次空结果查询后拦停循环，直接返回给用户的提示（防模型无数据反复查询）。 */
    private static final String EMPTY_RESULT_REPLY =
            "查询结果为空：该时间范围内还没有活动记录，暂时无法生成总结。\n"
            + "你可以在“活动记录”页添加活动后重试，或让我帮你规划接下来的日程。";

    // 跨会话聊天记录持久化（SharedPreferences）
    private static final String HISTORY_PREFS = "ChatHistory";
    private static final String KEY_HISTORY = "api_messages";

    private AiConfig aiConfig;
    private OpenAiClient openAiClient;
    private ToolExecutor toolExecutor;
    private SummaryGenerator summaryGenerator;

    private RecyclerView rvChat;
    private ChatAdapter chatAdapter;
    private final List<JSONObject> apiMessages = new ArrayList<>();
    private LinearLayout llEmpty;
    private TextInputEditText etInput;
    private FloatingActionButton btnSend;
    private Chip chipSummaryToday;
    private Chip chipQueryToday;

    private SharedPreferences historyPrefs;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean requestInFlight = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        aiConfig = new AiConfig(this);
        openAiClient = new OpenAiClient();
        DatabaseHelper db = new DatabaseHelper(this);
        toolExecutor = new ToolExecutor(db);
        summaryGenerator = new SummaryGenerator(this, db);
        // AI 工具新增/删除日程后，后台重建受影响日的总结（fire-and-forget，无 UI 回调）
        toolExecutor.setMutationListener(affectedDate ->
                summaryGenerator.regenerate(affectedDate, null));
        historyPrefs = getSharedPreferences(HISTORY_PREFS, MODE_PRIVATE);

        initViews();
        setupListeners();

        if (savedInstanceState != null) {
            // 屏幕旋转等重建：用 Bundle 里的实时会话
            restoreState(savedInstanceState);
        } else {
            // 正常进入：恢复上次退出时保存的聊天记录
            restoreHistory();
        }
    }

    private void initViews() {
        rvChat = findViewById(R.id.rv_chat);
        rvChat.setLayoutManager(new LinearLayoutManager(this));
        chatAdapter = new ChatAdapter();
        rvChat.setAdapter(chatAdapter);

        llEmpty = findViewById(R.id.ll_chat_empty);
        etInput = findViewById(R.id.et_chat_input);
        btnSend = findViewById(R.id.btn_chat_send);
        chipSummaryToday = findViewById(R.id.chip_summary_today);
        chipQueryToday = findViewById(R.id.chip_query_today);

        updateEmptyVisibility();
    }

    private void setupListeners() {
        findViewById(R.id.btn_chat_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_chat_settings).setOnClickListener(v -> showSettingsDialog());
        findViewById(R.id.btn_chat_clear).setOnClickListener(v -> confirmClearChat());

        // 长按用户消息 → 重新发送
        chatAdapter.setOnMessageLongClickListener((message, position) -> {
            if (requestInFlight) {
                return;
            }
            String content = message.getContent();
            if (content == null || content.trim().isEmpty()) {
                return;
            }
            new AlertDialog.Builder(this)
                .setTitle(R.string.ai_resend)
                .setMessage(content)
                .setPositiveButton(R.string.ai_resend_confirm, (dialog, which) -> sendMessage(content))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        });

        btnSend.setOnClickListener(v -> sendMessage(etInput.getText().toString()));
        etInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage(etInput.getText().toString());
                return true;
            }
            return false;
        });

        chipSummaryToday.setOnClickListener(v -> sendMessage(getString(R.string.ai_chip_today_prompt)));
        chipQueryToday.setOnClickListener(v -> sendMessage(getString(R.string.ai_chip_query_prompt)));
    }

    // ---------- 发送与对话循环 ----------

    private void sendMessage(String rawText) {
        if (requestInFlight) {
            return;
        }
        if (rawText == null || rawText.trim().isEmpty()) {
            return;
        }
        if (!aiConfig.isConfigured()) {
            Toast.makeText(this, R.string.ai_not_configured, Toast.LENGTH_SHORT).show();
            showSettingsDialog();
            return;
        }

        etInput.setText("");
        String content = rawText.trim();

        // 首轮加入系统提示（默认规则 + 用户自定义风格）
        if (apiMessages.isEmpty()) {
            apiMessages.add(userRoleMessage("system", buildSystemPrompt()));
        }
        apiMessages.add(userRoleMessage("user", content));

        chatAdapter.addMessage(new ChatMessage(ChatMessage.ROLE_USER, content));
        updateEmptyVisibility();

        // 打字占位
        ChatMessage pending = new ChatMessage(ChatMessage.ROLE_ASSISTANT, "");
        pending.setPending(true);
        chatAdapter.addMessage(pending);
        final int pendingIndex = chatAdapter.getItemCount() - 1;
        scrollToBottom();

        requestInFlight = true;
        setInputEnabled(false);

        executor.execute(() -> {
            // 用数组持有器保证 lambda 捕获的引用 effectively final
            final String[] replyHolder = new String[1];
            final String[] errorHolder = new String[1];
            try {
                replyHolder[0] = runChatLoop();
            } catch (Exception t) {
                errorHolder[0] = mapError(t);
            }
            postToUi(() -> {
                if (errorHolder[0] != null) {
                    chatAdapter.updateMessage(pendingIndex,
                            new ChatMessage(ChatMessage.ROLE_ASSISTANT, errorHolder[0]));
                } else if (replyHolder[0] != null && !replyHolder[0].isEmpty()) {
                    chatAdapter.updateMessage(pendingIndex,
                            new ChatMessage(ChatMessage.ROLE_ASSISTANT, replyHolder[0]));
                } else {
                    chatAdapter.updateMessage(pendingIndex,
                            new ChatMessage(ChatMessage.ROLE_ASSISTANT, getString(R.string.ai_error_unknown)));
                }
                requestInFlight = false;
                setInputEnabled(true);
                scrollToBottom();
                saveHistory();
            });
        });
    }

    /**
     * Function Calling 对话循环。
     * 模型返回 tool_calls 时执行工具并把结果回填后继续，直到返回最终 content 或达到轮次上限。
     */
    private String runChatLoop() throws Exception {
        String url = aiConfig.getChatCompletionsUrl();
        String apiKey = aiConfig.getApiKey();
        // 上下文过长时自动压缩早期历史，防止超过模型窗口
        compactContextIfNeeded();
        int iterations = 0;
        int dsmlSeq = 0;
        int busyRetries = 0;
        // 连续空结果查询计数：超过阈值强制终止循环，防模型在无数据时反复查询
        int emptyStreak = 0;
        final int MAX_BUSY_RETRIES = 3;
        final long BUSY_RETRY_DELAY_MS = 3000;
        String lastCallKey = null;
        // 工具调用模式：1=auto（模型自主决定是否调用）→ 2=纯聊天（接口不接受工具参数时降级）。
        // 用 auto 而非 required：DeepSeek 在 required 下会持续发起工具调用、迟迟不输出最终答复。
        int toolMode = 1;
        String degradeReason = null;

        while (iterations < MAX_ITERATIONS) {
            JSONObject request = new JSONObject();
            request.put("model", aiConfig.getModel());
            request.put("messages", new JSONArray(apiMessages));
            if (toolMode <= 1) {
                request.put("tools", toolExecutor.buildTools());
                request.put("tool_choice", "auto");
                // DeepSeek V4 默认 thinking 模式会拒绝 tool_choice 参数（400），
                // 显式关闭 thinking 以恢复标准 function calling；OpenAI 等端点会忽略该字段。
                JSONObject thinking = new JSONObject();
                thinking.put("type", "disabled");
                request.put("thinking", thinking);
            }
            request.put("max_tokens", 2048);

            JSONObject resp;
            try {
                resp = openAiClient.chatCompletion(url, apiKey, request.toString());
            } catch (OpenAiClient.ApiException e) {
                // 429 限流/繁忙：自动等待后重试，避免一繁忙就报错
                if (e.code == 429 && busyRetries < MAX_BUSY_RETRIES) {
                    busyRetries++;
                    try {
                        Thread.sleep(BUSY_RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                    continue;
                }
                // 部分模型不支持 tools / tool_choice / thinking 会返回 400：
                // 自动降级 required → auto → 纯聊天，兼容各类模型与中转。
                if (e.code == 400 && toolMode < 2 && isToolRelated400(e.body)) {
                    toolMode++;
                    degradeReason = e.body;
                    continue;
                }
                throw e;
            }

            JSONObject choice = resp.getJSONArray("choices").getJSONObject(0);
            JSONObject message = choice.getJSONObject("message");
            String finishReason = choice.optString("finish_reason");
            JSONArray toolCalls = message.optJSONArray("tool_calls");
            String content = message.optString("content", "");

            // DeepSeek thinking 模式无标准 tool_calls，工具调用以 DSML 文本表达（如 <|DSML|invoke>）。
            // 本地解析并执行，避免把 DSML 原文当作回复展示给用户。
            List<JSONObject> dsmlCalls = content.contains("DSML")
                    ? parseDSMLInvokes(content) : Collections.emptyList();
            if (!dsmlCalls.isEmpty()) {
                // 已降级纯聊天后模型不应再发起工具调用
                if (toolMode >= 2) {
                    throw new IllegalArgumentException("unexpected tool calls after degrade");
                }
                String dsmlKey = buildDSMLCallKey(dsmlCalls);
                if (dsmlKey.equals(lastCallKey)) {
                    throw new IllegalArgumentException("tool loop stuck" + buildLoopTrace());
                }
                lastCallKey = dsmlKey;

                // 回填 assistant 消息（保留 DSML 文本；DeepSeek 合约要求输入排除 reasoning_content）
                JSONObject assistant = new JSONObject();
                assistant.put("role", "assistant");
                assistant.put("content", content);
                apiMessages.add(assistant);

                for (int i = 0; i < dsmlCalls.size(); i++) {
                    JSONObject call = dsmlCalls.get(i);
                    String callId = "call_dsml_" + dsmlSeq++;
                    String result = toolExecutor.execute(call.getString("name"), call.getJSONObject("args"));
                    apiMessages.add(new JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", callId)
                            .put("content", result));
                    // 空结果查询连续累计达阈值 → 拦停循环，直接给用户结论
                    emptyStreak = countEmptyQuery(call.getString("name"), result) ? emptyStreak + 1 : 0;
                    if (emptyStreak >= 3) {
                        return EMPTY_RESULT_REPLY;
                    }
                }
                iterations++;
                continue;
            }

            boolean hasTools = "tool_calls".equals(finishReason) && toolCalls != null && toolCalls.length() > 0;

            if (!hasTools) {
                if (content.isEmpty()) {
                    // DeepSeek thinking 模式下 content 可能为空（内容全在 reasoning_content），
                    // 兜底展示思考内容，避免直接报"空回复"。
                    String reasoning = message.optString("reasoning_content", "");
                    if (!reasoning.isEmpty()) {
                        content = "（本模型思考模式未被关闭，以下是其思考内容，非正式答复）\n\n" + reasoning;
                    } else {
                        throw new IllegalArgumentException("empty reply");
                    }
                }
                // 若发生过 tools 降级，透出首次失败原因，便于诊断接口兼容性
                if (degradeReason != null) {
                    content += "\n\n[提示: 本模型未启用日程工具调用，已降级为纯聊天。接口原因: " + degradeReason + "]";
                }
                return content;
            }

            // 降级后模型不应再调用工具
            if (toolMode >= 2) {
                throw new IllegalArgumentException("unexpected tool calls after degrade");
            }

            // 防卡死：连续两轮调用完全一致则中止
            String callKey = buildCallKey(toolCalls);
            if (callKey.equals(lastCallKey)) {
                throw new IllegalArgumentException("tool loop stuck" + buildLoopTrace());
            }
            lastCallKey = callKey;

            // ① 回填 assistant 消息：保留 role/content/tool_calls，剔除 reasoning_content 等输出字段。
            //   DeepSeek 要求输入中排除 reasoning_content，否则上下文断链、模型会重复发起相同工具调用。
            JSONObject assistant = new JSONObject();
            assistant.put("role", "assistant");
            if (!message.isNull("content")) {
                assistant.put("content", message.optString("content", ""));
            }
            assistant.put("tool_calls", message.getJSONArray("tool_calls"));
            apiMessages.add(assistant);

            // ② 逐个执行工具并追加 role=tool 消息
            for (int i = 0; i < toolCalls.length(); i++) {
                JSONObject call = toolCalls.getJSONObject(i);
                String callId = call.getString("id");
                JSONObject fn = call.getJSONObject("function");
                String name = fn.getString("name");
                // arguments 是 JSON 字符串，需二次解析
                JSONObject args = new JSONObject(fn.optString("arguments", "{}"));
                String result = toolExecutor.execute(name, args);
                apiMessages.add(new JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", callId)
                        .put("content", result));
                // 空结果查询连续累计达阈值 → 拦停循环，直接给用户结论
                emptyStreak = countEmptyQuery(name, result) ? emptyStreak + 1 : 0;
                if (emptyStreak >= 3) {
                    return EMPTY_RESULT_REPLY;
                }
            }

            iterations++;
        }
        throw new IllegalArgumentException("max iterations reached" + buildLoopTrace());
    }

    /** 生成最近几轮对话的跟踪摘要，用于 max iterations 时诊断循环原因。 */
    private String buildLoopTrace() {
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, apiMessages.size() - 10);
        for (int i = start; i < apiMessages.size(); i++) {
            JSONObject m = apiMessages.get(i);
            String role = m.optString("role", "?");
            sb.append("\n[").append(role).append("] ");
            JSONArray calls = m.optJSONArray("tool_calls");
            if (calls != null && calls.length() > 0) {
                for (int j = 0; j < calls.length(); j++) {
                    JSONObject item = calls.optJSONObject(j);
                    JSONObject fn = item != null ? item.optJSONObject("function") : null;
                    sb.append(fn != null ? fn.optString("name") : "?").append(" ");
                }
            } else {
                String c = m.optString("content", "");
                if (c.length() > 120) {
                    c = c.substring(0, 120);
                }
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ---------- 上下文自动压缩 ----------

    private static final int COMPACT_THRESHOLD = 30;   // 消息总数超过此值触发压缩
    private static final int COMPACT_COUNT = 15;        // 每次压缩最早的非 system 消息条数

    /** 压缩失败不影响主流程，仅记录日志并跳过 */
    private void compactContextIfNeeded() {
        try {
            doCompact();
        } catch (Exception e) {
            Log.d(TAG, "compact context failed: " + e.getMessage());
        }
    }

    /**
     * 把最早的若干条非 system 消息交给模型生成摘要，
     * 替换成一条 system 摘要消息，保留关键信息同时缩短上下文。
     */
    private void doCompact() throws Exception {
        if (apiMessages.size() <= COMPACT_THRESHOLD || apiMessages.size() < 2) {
            return;
        }
        int count = Math.min(COMPACT_COUNT, apiMessages.size() - 1);
        JSONArray toCompact = new JSONArray();
        for (int i = 1; i <= count; i++) {
            toCompact.put(apiMessages.get(i));
        }

        String summary = requestSummary(toCompact);
        if (summary == null || summary.trim().isEmpty()) {
            return; // 摘要为空则保守跳过，不动历史
        }

        List<JSONObject> kept = new ArrayList<>();
        kept.add(apiMessages.get(0)); // 保留 system
        JSONObject summaryMsg = new JSONObject();
        summaryMsg.put("role", "system");
        summaryMsg.put("content", "历史对话摘要（已自动压缩）: " + summary);
        kept.add(summaryMsg);
        for (int i = count + 1; i < apiMessages.size(); i++) {
            kept.add(apiMessages.get(i));
        }

        apiMessages.clear();
        apiMessages.addAll(kept);
        saveHistory();
        postToUi(() -> Toast.makeText(this, R.string.ai_context_compacted, Toast.LENGTH_SHORT).show());
    }

    /** 请求模型把一段历史消息浓缩成摘要，失败/空结果返回 "" */
    private String requestSummary(JSONArray history) throws Exception {
        JSONArray msgs = new JSONArray();
        for (int i = 0; i < history.length(); i++) {
            JSONObject m = history.getJSONObject(i);
            String role = m.optString("role", "");
            if (!"user".equals(role) && !"assistant".equals(role)) {
                continue;
            }
            if (m.isNull("content")) {
                continue;
            }
            JSONObject clean = new JSONObject();
            clean.put("role", role);
            clean.put("content", m.optString("content", ""));
            msgs.put(clean);
        }
        if (msgs.length() == 0) {
            return "";
        }
        JSONObject ask = new JSONObject();
        ask.put("role", "user");
        ask.put("content", "请用中文简要总结以上对话内容，保留：已添加/删除/查询的日程数据、用户对日程的偏好、尚未完成的事情。不要编造。");
        msgs.put(ask);

        JSONObject req = new JSONObject();
        req.put("model", aiConfig.getModel());
        req.put("messages", msgs);
        req.put("max_tokens", 400);

        JSONObject resp = openAiClient.chatCompletion(
                aiConfig.getChatCompletionsUrl(), aiConfig.getApiKey(), req.toString());
        JSONObject message = resp.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
        return message.optString("content", "");
    }

    private String buildCallKey(JSONArray calls) throws JSONException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < calls.length(); i++) {
            JSONObject fn = calls.getJSONObject(i).getJSONObject("function");
            sb.append(fn.optString("name")).append('(').append(fn.optString("arguments")).append(");");
        }
        return sb.toString();
    }

    /** DeepSeek DSML 工具调用 key（防卡死比对用）。 */
    private String buildDSMLCallKey(List<JSONObject> calls) throws JSONException {
        StringBuilder sb = new StringBuilder();
        for (JSONObject call : calls) {
            sb.append(call.optString("name")).append('(')
              .append(call.getJSONObject("args").toString()).append(");");
        }
        return sb.toString();
    }

    /**
     * 解析 DeepSeek DSML 文本中的工具调用（thinking 模式无标准 tool_calls 时的表达方式），
     * 返回 [{name, args}]；无法解析返回空列表。兼容半角 | 与全角 ｜ 竖线。
     */
    private List<JSONObject> parseDSMLInvokes(String content) throws JSONException {
        List<JSONObject> calls = new ArrayList<>();
        if (content == null || !content.contains("DSML")) {
            return calls;
        }
        Pattern invokePattern = Pattern.compile(
                "<[|｜]DSML[|｜]invoke\\s+name=\"([^\"]+)\"[^>]*>(.*?)</[|｜]DSML[|｜]invoke>",
                Pattern.DOTALL);
        Pattern paramPattern = Pattern.compile(
                "<[|｜]DSML[|｜]parameter\\s+name=\"([^\"]+)\"[^>]*>(.*?)</[|｜]DSML[|｜]parameter>",
                Pattern.DOTALL);
        Matcher im = invokePattern.matcher(content);
        while (im.find()) {
            String name = im.group(1).trim();
            String body = im.group(2);
            JSONObject args = new JSONObject();
            Matcher pm = paramPattern.matcher(body);
            while (pm.find()) {
                args.put(pm.group(1).trim(), pm.group(2).trim());
            }
            JSONObject call = new JSONObject();
            call.put("name", name);
            call.put("args", args);
            calls.add(call);
        }
        return calls;
    }

    /** 判断 400 错误是否因 tools / tool_choice / thinking 不兼容引起（用于自动降级重试）。 */
    private boolean isToolRelated400(String body) {
        if (body == null) {
            return false;
        }
        String b = body.toLowerCase();
        return b.contains("tool") || b.contains("function") || b.contains("thinking")
                || b.contains("required");
    }

    /** 判断某次工具调用是否为"空结果查询"（查无活动 / 总结未生成），用于空循环拦停。 */
    private boolean countEmptyQuery(String name, String result) {
        if (result == null || name == null || !name.startsWith("get_")) {
            return false;
        }
        try {
            JSONObject obj = new JSONObject(result);
            if (name.equals("get_daily_summary")) {
                return !obj.optBoolean("success", false);
            }
            return obj.optInt("count", -1) == 0;
        } catch (JSONException ignored) {
            return false;
        }
    }

    // ---------- 错误映射 ----------

    private String mapError(Throwable t) {
        if (t instanceof OpenAiClient.ApiException) {
            OpenAiClient.ApiException e = (OpenAiClient.ApiException) t;
            switch (e.code) {
                case 401:
                case 403:
                    return getString(R.string.ai_error_unauthorized) + " (" + e.code + ")";
                case 404:
                    return getString(R.string.ai_error_not_found) + " (" + e.code + ")";
                case 429:
                    return getString(R.string.ai_error_rate_limit) + " (" + e.code + ")";
                default:
                    // 统一带出 HTTP 状态码与接口返回的原因，便于定位（Key/模型名/额度/参数等）
                    String reason = e.body == null || e.body.isEmpty() ? "" : ": " + e.body;
                    return getString(R.string.ai_error_unknown) + " (" + e.code + ")" + reason;
            }
        }
        if (t instanceof SocketTimeoutException) {
            return getString(R.string.ai_error_timeout);
        }
        if (t instanceof IOException) {
            return getString(R.string.ai_error_network);
        }
        if (t instanceof JSONException) {
            return getString(R.string.ai_error_format);
        }
        if (t instanceof IllegalArgumentException) {
            // 对话循环中止（空回复 / 卡死 / 超轮次 / 降级后异常工具调用），带出具体原因
            return getString(R.string.ai_error_unknown) + " (" + t.getMessage() + ")";
        }
        return getString(R.string.ai_error_unknown) + " (" + t.getMessage() + ")";
    }

    // ---------- 设置对话框 ----------

    private void showSettingsDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_ai_settings, null);
        TextInputEditText etBaseUrl = view.findViewById(R.id.et_ai_base_url);
        TextInputEditText etApiKey = view.findViewById(R.id.et_ai_api_key);
        TextInputEditText etModel = view.findViewById(R.id.et_ai_model);
        TextInputEditText etPersona = view.findViewById(R.id.et_ai_persona);
        etBaseUrl.setText(aiConfig.getBaseUrl());
        etApiKey.setText(aiConfig.getApiKey());
        etModel.setText(aiConfig.getModel());
        etPersona.setText(aiConfig.getPersona());

        new AlertDialog.Builder(this)
            .setTitle(R.string.ai_setting_title)
            .setView(view)
            .setPositiveButton(R.string.ai_setting_save, (dialog, which) -> {
                aiConfig.save(etBaseUrl.getText().toString(),
                        etApiKey.getText().toString(),
                        etModel.getText().toString(),
                        etPersona.getText().toString());
                Toast.makeText(this, R.string.ai_setting_save, Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** 系统提示 = 默认规则 + 用户自定义风格/人设（若有）。 */
    private String buildSystemPrompt() {
        String persona = aiConfig.getPersona();
        if (persona == null || persona.trim().isEmpty()) {
            return ToolExecutor.SYSTEM_PROMPT;
        }
        return ToolExecutor.SYSTEM_PROMPT
                + "\n此外，请始终遵循用户自定义的风格/人设来交流：\n"
                + persona.trim();
    }

    /** 用最新规则替换历史里的第一条 system 消息；历史中无 system 则在最前插入。 */
    private void refreshSystemPrompt() {
        String latest = buildSystemPrompt();
        for (int i = 0; i < apiMessages.size(); i++) {
            JSONObject m = apiMessages.get(i);
            if ("system".equals(m.optString("role", ""))) {
                JSONObject sys = new JSONObject();
                try {
                    sys.put("role", "system");
                    sys.put("content", latest);
                } catch (JSONException ignored) {
                    return;
                }
                apiMessages.set(i, sys);
                return;
            }
        }
        apiMessages.add(0, userRoleMessage("system", latest));
    }

    // ---------- UI 辅助 ----------

    private void setInputEnabled(boolean enabled) {
        btnSend.setEnabled(enabled);
        etInput.setEnabled(enabled);
        chipSummaryToday.setEnabled(enabled);
        chipQueryToday.setEnabled(enabled);
    }

    private void updateEmptyVisibility() {
        llEmpty.setVisibility(chatAdapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
    }

    private void scrollToBottom() {
        int count = chatAdapter.getItemCount();
        if (count > 0) {
            rvChat.scrollToPosition(count - 1);
        }
    }

    private void postToUi(Runnable runnable) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        mainHandler.post(runnable);
    }

    // ---------- 聊天记录持久化 ----------

    /** 保存完整 API 消息（含 system/tool 结构）到 SharedPreferences，跨会话保留 */
    private void saveHistory() {
        historyPrefs.edit()
            .putString(KEY_HISTORY, new JSONArray(apiMessages).toString())
            .apply();
    }

    /** 从 SharedPreferences 恢复上次的聊天记录，重建 UI 消息列表 */
    private void restoreHistory() {
        String json = historyPrefs.getString(KEY_HISTORY, "");
        if (json == null || json.isEmpty()) {
            return;
        }
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                apiMessages.add(arr.getJSONObject(i));
            }
            // 用最新系统提示替换历史里的旧规则，升级后新规则能立即生效（无需清空聊天）
            refreshSystemPrompt();
            // 从 apiMessages 重建 UI 列表：跳过 system/tool 消息、content 为空的中间 assistant
            List<ChatMessage> ui = new ArrayList<>();
            for (JSONObject msg : apiMessages) {
                String role = msg.optString("role", "");
                if (!ChatMessage.ROLE_USER.equals(role) && !ChatMessage.ROLE_ASSISTANT.equals(role)) {
                    continue;
                }
                if (msg.isNull("content")) {
                    continue; // 带 tool_calls 的中间消息，content 为 null，不显示
                }
                String content = msg.optString("content", "");
                if (content.isEmpty()) {
                    continue;
                }
                ui.add(new ChatMessage(role, content));
            }
            if (!ui.isEmpty()) {
                chatAdapter.setMessages(ui);
                updateEmptyVisibility();
                scrollToBottom();
            }
        } catch (JSONException ignored) {
        }
    }

    private void confirmClearChat() {
        if (chatAdapter.getItemCount() == 0) {
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle(R.string.ai_clear)
            .setMessage(R.string.ai_clear_confirm)
            .setPositiveButton(R.string.ai_clear_ok, (dialog, which) -> clearChat())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void clearChat() {
        apiMessages.clear();
        chatAdapter.setMessages(new ArrayList<>());
        historyPrefs.edit().remove(KEY_HISTORY).apply();
        updateEmptyVisibility();
    }

    private JSONObject userRoleMessage(String role, String content) {
        JSONObject msg = new JSONObject();
        try {
            msg.put("role", role);
            msg.put("content", content);
        } catch (JSONException ignored) {
        }
        return msg;
    }

    // ---------- 生命周期 ----------

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_API_MESSAGES, new JSONArray(apiMessages).toString());
        outState.putSerializable(STATE_UI_MESSAGES, new ArrayList<>(chatAdapter.getMessages()));
    }

    @SuppressWarnings("unchecked")
    private void restoreState(Bundle savedInstanceState) {
        String apiStr = savedInstanceState.getString(STATE_API_MESSAGES);
        if (apiStr != null) {
            try {
                JSONArray arr = new JSONArray(apiStr);
                for (int i = 0; i < arr.length(); i++) {
                    apiMessages.add(arr.getJSONObject(i));
                }
            } catch (JSONException ignored) {
            }
        }

        Object restored = savedInstanceState.getSerializable(STATE_UI_MESSAGES);
        if (restored instanceof List) {
            List<ChatMessage> messages = new ArrayList<>((List<ChatMessage>) restored);
            // 清理"正在思考…"占位（旋转时后台请求已被中断）
            for (ChatMessage m : messages) {
                if (m.isPending()) {
                    m.setPending(false);
                    m.setContent(getString(R.string.ai_error_interrupted));
                }
            }
            chatAdapter.setMessages(messages);
            updateEmptyVisibility();
            scrollToBottom();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 离开页面时兜底保存（含用户直接退回主页的情况）
        saveHistory();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
        summaryGenerator.shutdown();
    }
}

package com.example.timerecorder.ai;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.timerecorder.database.DatabaseHelper;
import com.example.timerecorder.model.Activity;
import com.example.timerecorder.model.DailySummary;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 每日日程总结 + 评分（0-100）生成器。
 * - 单线程串行化生成，杜绝并发写库竞态；
 * - 回调统一经 mainHandler 切回主线程；
 * - ensureSummary 幂等（已有总结直接回调返回），regenerate 先删旧总结再异步生成。
 * 网络请求与数据库读写全部在后台线程执行。
 */
public class SummaryGenerator {

    private static final String TAG = "SummaryGenerator";
    private static final String DATE_FORMAT = "yyyy-MM-dd";

    /** 生成失败原因：AI 未配置 */
    public static final int STATE_NOT_CONFIGURED = 0;
    /** 生成失败原因：网络/解析/无活动等 */
    public static final int STATE_ERROR = 1;

    /** 生成结果回调（主线程）。 */
    public interface Callback {
        /** 生成成功，summary 非空（可能是数据库中已存在的旧总结）。 */
        void onSuccess(DailySummary summary);

        /** 生成失败/未配置。state 为 STATE_NOT_CONFIGURED / STATE_ERROR。 */
        void onError(int state, String message);
    }

    private final DatabaseHelper db;
    private final OpenAiClient client;
    private final AiConfig aiConfig;
    private final ExecutorService executor;
    private final Handler mainHandler;

    public SummaryGenerator(Context context, DatabaseHelper db) {
        this.db = db;
        this.client = new OpenAiClient();
        this.aiConfig = new AiConfig(context);
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void shutdown() {
        executor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
    }

    /** 幂等：该天已有总结则直接回调 onSuccess，否则异步生成。 */
    public void ensureSummary(long dateMillis, Callback cb) {
        DailySummary existing = db.getSummaryByDate(dateMillis);
        if (existing != null) {
            dispatchSuccess(cb, existing);
            return;
        }
        generate(dateMillis, cb);
    }

    /** 强制重新生成：先删除该天旧总结，再异步生成。 */
    public void regenerate(long dateMillis, Callback cb) {
        db.deleteSummary(dateMillis);
        generate(dateMillis, cb);
    }

    private void generate(long dateMillis, Callback cb) {
        final long date = dateMillis;
        executor.execute(() -> {
            if (!aiConfig.isConfigured()) {
                dispatchError(cb, STATE_NOT_CONFIGURED, "AI 未配置");
                return;
            }
            try {
                List<Activity> activities = db.getActivitiesByDate(date);
                if (activities == null || activities.isEmpty()) {
                    dispatchError(cb, STATE_ERROR, "当天没有活动记录");
                    return;
                }
                String raw = requestSummary(date, activities);
                DailySummary summary = parseSummary(date, raw);
                if (summary == null) {
                    dispatchError(cb, STATE_ERROR, "AI 返回格式异常");
                    return;
                }
                db.upsertSummary(date, summary.getScore(), summary.getSummary());
                dispatchSuccess(cb, summary);
            } catch (Exception e) {
                Log.w(TAG, "generate failed: " + e.getMessage());
                dispatchError(cb, STATE_ERROR, e.getMessage() == null ? "未知错误" : e.getMessage());
            }
        });
    }

    /**
     * 纯 chat 请求（无 tools），让模型按固定评分标准、结合活动动机与用户自定义风格，
     * 输出当日总结+评分。
     */
    private String requestSummary(long dateMillis, List<Activity> activities) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("日期：").append(formatDate(dateMillis)).append("\n\n今日活动（含动机）：\n");
        for (Activity a : activities) {
            sb.append("- ").append(a.getName())
              .append(" ").append(a.getStartTimeString()).append("-").append(a.getEndTimeString())
              .append("（").append(a.getDurationString()).append("）");
            if (!a.getMotive().isEmpty()) {
                sb.append("  动机：").append(a.getMotive());
            }
            sb.append("\n");
        }

        // 固定评分标准：四个维度加总 0-100，动机作为独立维度
        StringBuilder system = new StringBuilder(
                "你是一个关注健康作息的日程教练。根据给定某天的活动记录（含每项活动的“动机”），"
                + "用中文写 2-4 句日程总结，并给出 0-100 的综合评分。\n\n"
                + "评分标准（四个维度得分加总，共 100 分）：\n"
                + "1. 作息规律（0-40）：睡眠时长是否充足、作息是否规律稳定；\n"
                + "2. 时间利用与专注（0-40）：时间是否花在真正重要/有意义的事上，是否留有足够的深度工作或学习时间；\n"
                + "3. 劳逸结合（0-10）：是否有足够的休息、运动、放松，避免久坐与过度透支；\n"
                + "4. 动机契合（0-10）：活动是否契合用户记录的动机，是否在朝自己的目标靠近。\n\n"
                + "总结中要说明主要扣分/加分依据（可提及具体活动与动机），评分客观，不要盲目给高分。"
                + "必须只输出一个 JSON 对象，不要任何其他文字，格式：{\"score\": 0到100的整数, \"summary\": \"总结文字\"}");
        // 用户自定义风格同样作用于每日总结
        String persona = aiConfig.getPersona();
        if (persona != null && !persona.trim().isEmpty()) {
            system.append("\n\n请以用户自定义的风格/人设来写这段总结：\n").append(persona.trim());
        }

        JSONObject sys = new JSONObject();
        sys.put("role", "system");
        sys.put("content", system.toString());

        JSONObject usr = new JSONObject();
        usr.put("role", "user");
        usr.put("content", sb.toString());

        JSONArray msgs = new JSONArray();
        msgs.put(sys);
        msgs.put(usr);

        JSONObject req = new JSONObject();
        req.put("model", aiConfig.getModel());
        req.put("messages", msgs);
        req.put("max_tokens", 300);

        JSONObject resp = client.chatCompletion(
                aiConfig.getChatCompletionsUrl(), aiConfig.getApiKey(), req.toString());
        JSONObject message = resp.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
        return message.optString("content", "");
    }

    /** 解析模型输出为 DailySummary；无法解析返回 null。 */
    private DailySummary parseSummary(long dateMillis, String raw) {
        if (raw == null) {
            return null;
        }
        String json = raw.trim()
                .replaceAll("```(?:json)?", "")   // 剥掉 ```json 围栏
                .trim();
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start >= 0 && end > start) {
            json = json.substring(start, end + 1);
        }

        int score = -1;
        String summary = "";
        try {
            JSONObject obj = new JSONObject(json);
            score = obj.optInt("score", -1);
            summary = obj.optString("summary", "");
        } catch (Exception ignored) {
            // 非标准 JSON，走正则兜底
        }
        if (score < 0 || summary.isEmpty()) {
            int fallbackScore = extractScore(raw);
            if (fallbackScore >= 0) {
                score = fallbackScore;
            }
            if (summary.isEmpty()) {
                summary = extractSummary(raw);
            }
        }
        if (score < 0) {
            return null;
        }
        score = Math.max(0, Math.min(100, score));
        return new DailySummary(getDateOnly(dateMillis), score, summary, System.currentTimeMillis());
    }

    /** 优先提取 "score" 附近的数字，最后兜底任意数字。 */
    private int extractScore(String raw) {
        if (raw == null) {
            return -1;
        }
        Matcher m = Pattern.compile("score\\s*[:：]?\\s*\"?\\s*(\\d{1,3})",
                Pattern.CASE_INSENSITIVE).matcher(raw);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        Matcher m2 = Pattern.compile("(\\d{1,3})").matcher(raw);
        if (m2.find()) {
            try {
                return Integer.parseInt(m2.group(1));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    /** 兜底提取总结文字：去掉 JSON 骨架与评分数字，保留中文描述。 */
    private String extractSummary(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replaceAll("[{}]|\"", "");
        s = s.replaceAll("(?i)score\\s*[:：]?\\s*\\d+", "");
        s = s.replace("summary", "").replace(":", "").trim();
        return s;
    }

    private void dispatchSuccess(Callback cb, DailySummary summary) {
        mainHandler.post(() -> {
            if (cb != null) {
                cb.onSuccess(summary);
            }
        });
    }

    private void dispatchError(Callback cb, int state, String message) {
        mainHandler.post(() -> {
            if (cb != null) {
                cb.onError(state, message);
            }
        });
    }

    private long getDateOnly(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private String formatDate(long millis) {
        return new SimpleDateFormat(DATE_FORMAT, Locale.US).format(new Date(millis));
    }
}

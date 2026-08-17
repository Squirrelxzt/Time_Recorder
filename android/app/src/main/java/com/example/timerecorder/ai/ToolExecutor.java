package com.example.timerecorder.ai;

import com.example.timerecorder.database.DatabaseHelper;
import com.example.timerecorder.model.Activity;
import com.example.timerecorder.model.DailySummary;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 日程工具执行器：定义 Function Calling 的 tools schema，并执行模型请求的工具调用。
 * 每个工具返回 JSON 字符串，作为对话里 role=tool 的消息 content 回填给模型。
 *
 * 日期/时间解析统一用 Calendar + SimpleDateFormat（设备本地时区），
 * 与 DatabaseHelper / MainActivity 的 getDateOnly 语义一致，禁止使用 UTC。
 */
public class ToolExecutor {

    public static final String SYSTEM_PROMPT =
        "你是\"时间记录器\"App 的日程助手，帮助用户总结日程、查询/新增/删除活动，也可以闲聊。\n" +
        "规则：\n" +
        "1. 涉及\"今天/明天/昨天/本周\"等相对日期时，必须先调用 get_today_date 获取今天的日期和星期，再自行推算，不要凭经验。\n" +
        "2. 日期格式一律 yyyy-MM-dd，时间格式一律 HH:mm（24小时制）。\n" +
        "3. 新增/删除/查询日程必须调用对应工具，不要凭空编造数据。\n" +
        "   你拥有访问日程数据库的工具，涉及日程时必须调用工具；\n" +
        "   禁止回复\"无法访问数据库/无法执行实时操作/请打开App自行查看\"之类的话，因为你可以直接操作数据。\n" +
        "4. 用户未指明日期时默认今天。\n" +
        "5. 跨天活动会按天拆分（开始日尾段 开始~24:00、中间整天 00:00~24:00、结束日头段 00:00~结束）。\n" +
        "   用户说\"到明天0点\"应传 end_date=明天、end_time=00:00。\n" +
        "6. 回复简洁、口语化，直接给结论；如需确认信息就明确提问。\n" +
        "7. 生活引导风格：除直接回答外，结合用户日程主动给 1-2 条具体建议（作息规律、健康、专注/效率、劳逸结合等），\n" +
        "   帮用户养成好习惯，不要只做机械查询或执行。\n" +
        "8. 查询结果为空（某天没有活动、总结尚未生成）时，直接如实告诉用户（例如\"今天还没有活动记录，无法总结\"），\n" +
        "   给出结论即可；不要重复调用同一个工具，也不要用其他工具反复试探。\n" +
        "9. 需要新增多条活动时，在同一轮回复里并列发起多个 add_activity 调用（一条回复可同时调用多次工具），\n" +
        "   不要一条条串行添加；全部添加完成后直接回复结果（如\"已为你添加 N 条活动：…\"），不要再次查询确认。";

    private static final String DATE_FORMAT = "yyyy-MM-dd";
    private static final String TIME_FORMAT = "HH:mm";

    /** 工具对日程数据的增删变更回调（宿主据此重建受影响日的总结）。 */
    public interface MutationListener {
        void onMutation(long affectedDateMillis);
    }

    private final DatabaseHelper db;
    private MutationListener mutationListener;

    public ToolExecutor(DatabaseHelper db) {
        this.db = db;
    }

    /** 注册日程变更监听：AI 工具新增/删除活动后触发（fire-and-forget，无返回值）。 */
    public void setMutationListener(MutationListener mutationListener) {
        this.mutationListener = mutationListener;
    }

    /** 生成随请求发送的 tools 参数（Function Calling schema） */
    public JSONArray buildTools() throws JSONException {
        JSONArray tools = new JSONArray();
        tools.put(functionTool("get_today_date",
                "获取今天的日期和星期，用于理解\"今天/明天/昨天/本周\"等相对时间",
                new JSONObject(), new JSONArray()));
        tools.put(functionTool("get_activities",
                "获取指定日期的活动记录列表（名称/起止时间/时长）",
                stringProperties(new Object[][]{
                        {"date", "日期，格式 yyyy-MM-dd，如 2026-08-15"}
                }), new JSONArray().put("date")));
        tools.put(functionTool("get_activities_between",
                "获取一段日期范围（含两端）内的所有活动，按日期分组返回，用于\"总结某段时间/上周/本月\"等",
                stringProperties(new Object[][]{
                        {"start_date", "开始日期，格式 yyyy-MM-dd"},
                        {"end_date", "结束日期，格式 yyyy-MM-dd"}
                }), new JSONArray().put("start_date").put("end_date")));
        tools.put(functionTool("add_activity",
                "新增一段日程/活动（起止日期+起止时间，支持跨天自动按天拆分）",
                stringProperties(new Object[][]{
                        {"name", "活动名称"},
                        {"start_date", "开始日期，格式 yyyy-MM-dd"},
                        {"start_time", "开始时间，24小时制 HH:mm，如 09:00"},
                        {"end_date", "结束日期，格式 yyyy-MM-dd"},
                        {"end_time", "结束时间，24小时制 HH:mm；\"到次日0点\"应传 end_date=次日、end_time=00:00"}
                }), new JSONArray().put("name").put("start_date").put("start_time").put("end_date").put("end_time")));
        tools.put(functionTool("delete_activity",
                "删除某一天内指定名称的活动记录（名称精确匹配；跨天活动需分别在两天各删一次）",
                stringProperties(new Object[][]{
                        {"name", "要删除的活动名称"},
                        {"date", "日期，格式 yyyy-MM-dd"}
                }), new JSONArray().put("name").put("date")));
        tools.put(functionTool("get_daily_summary",
                "获取某一天已生成的 AI 日程总结与评分（0-100）。该天尚未生成时返回 success:false，"
                + "此时可提示用户打开 App 的今日总结卡片生成，或结合活动自行给建议",
                stringProperties(new Object[][]{
                        {"date", "日期，格式 yyyy-MM-dd"}
                }), new JSONArray().put("date")));
        return tools;
    }

    /** 执行模型请求的工具调用，返回回填给模型的 JSON 字符串 */
    public String execute(String name, JSONObject args) {
        try {
            switch (name) {
                case "get_today_date":
                    return getTodayDate();
                case "get_activities":
                    return getActivities(args);
                case "get_activities_between":
                    return getActivitiesBetween(args);
                case "add_activity":
                    return addActivity(args);
                case "delete_activity":
                    return deleteActivity(args);
                case "get_daily_summary":
                    return getDailySummary(args);
                default:
                    return "{\"success\":false,\"message\":\"未知工具: " + name + "\"}";
            }
        } catch (Exception e) {
            JSONObject error = new JSONObject();
            try {
                error.put("success", false);
                error.put("message", "工具执行出错: " + e.getMessage());
            } catch (JSONException ignored) {
            }
            return error.toString();
        }
    }

    // ---------- 工具实现 ----------

    private String getTodayDate() throws Exception {
        Calendar now = Calendar.getInstance();
        SimpleDateFormat df = new SimpleDateFormat(DATE_FORMAT, Locale.US);
        SimpleDateFormat weekdayFmt = new SimpleDateFormat("EEEE", Locale.SIMPLIFIED_CHINESE);
        JSONObject result = new JSONObject();
        result.put("today", df.format(now.getTime()));
        result.put("weekday", weekdayFmt.format(now.getTime()));
        return result.toString();
    }

    private String getActivities(JSONObject args) throws Exception {
        String dateStr = args.optString("date", "");
        long dateMillis;
        if (dateStr.isEmpty()) {
            dateMillis = System.currentTimeMillis();
        } else {
            dateMillis = parseDate(dateStr);
            if (dateMillis < 0) {
                return errorJson("日期格式不正确，必须是 yyyy-MM-dd");
            }
        }
        List<Activity> list = db.getActivitiesByDate(dateMillis);
        return buildDayJson(formatDate(dateMillis), list).toString();
    }

    private String getActivitiesBetween(JSONObject args) throws Exception {
        long startMillis = parseDate(args.optString("start_date", ""));
        long endMillis = parseDate(args.optString("end_date", ""));
        if (startMillis < 0 || endMillis < 0) {
            return errorJson("日期格式不正确，必须是 yyyy-MM-dd");
        }
        if (endMillis < startMillis) {
            return errorJson("结束日期不能早于开始日期");
        }

        List<Activity> list = db.getActivitiesBetween(startMillis, endMillis);

        // 按日期分组（数据已按 date 排序）
        JSONArray days = new JSONArray();
        JSONObject currentDay = null;
        String currentDate = null;
        for (Activity a : list) {
            String dateStr = formatDate(a.getDate());
            if (!dateStr.equals(currentDate)) {
                currentDay = new JSONObject();
                currentDay.put("date", dateStr);
                currentDay.put("activities", new JSONArray());
                days.put(currentDay);
                currentDate = dateStr;
            }
            currentDay.getJSONArray("activities").put(activityJson(a));
        }

        JSONObject result = new JSONObject();
        result.put("start_date", formatDate(startMillis));
        result.put("end_date", formatDate(endMillis));
        result.put("count", list.size());
        result.put("days", days);
        return result.toString();
    }

    private String addActivity(JSONObject args) throws Exception {
        String name = args.optString("name", "").trim();
        if (name.isEmpty()) {
            return errorJson("活动名称不能为空");
        }
        long startMillis = parseDateTime(args.optString("start_date", ""), args.optString("start_time", ""));
        long endMillis = parseDateTime(args.optString("end_date", ""), args.optString("end_time", ""));
        if (startMillis < 0 || endMillis < 0) {
            return errorJson("日期/时间格式不正确，日期必须是 yyyy-MM-dd，时间必须是 HH:mm");
        }
        if (endMillis <= startMillis) {
            return errorJson("结束时间必须晚于开始时间");
        }

        int count = db.addActivitySpan(name, startMillis, endMillis);

        // 日程变更：通知宿主重建受影响日的总结（开始日；跨天时其余日由宿主处理）
        if (mutationListener != null) {
            mutationListener.onMutation(startMillis);
        }

        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("count", count);
        result.put("message", count > 1 ? "已添加，跨天拆分为" + count + "条记录" : "已添加1条");
        return result.toString();
    }

    private String deleteActivity(JSONObject args) throws Exception {
        String name = args.optString("name", "").trim();
        long dateMillis = parseDate(args.optString("date", ""));
        if (dateMillis < 0) {
            return errorJson("日期格式不正确，必须是 yyyy-MM-dd");
        }

        int deleted = db.deleteActivitiesByNameAndDate(name, dateMillis);

        JSONObject result = new JSONObject();
        if (deleted > 0) {
            result.put("success", true);
            result.put("deleted", deleted);
            result.put("message", "已删除" + deleted + "条");
            // 日程变更：通知宿主重建该日总结
            if (mutationListener != null) {
                mutationListener.onMutation(dateMillis);
            }
        } else {
            result.put("success", false);
            result.put("deleted", 0);
            result.put("message", "未找到 " + formatDate(dateMillis) + " 名为\"" + name + "\"的活动");
        }
        return result.toString();
    }

    private String getDailySummary(JSONObject args) throws Exception {
        long dateMillis = parseDate(args.optString("date", ""));
        if (dateMillis < 0) {
            return errorJson("日期格式不正确，必须是 yyyy-MM-dd");
        }
        DailySummary summary = db.getSummaryByDate(dateMillis);
        if (summary == null) {
            return errorJson(formatDate(dateMillis) + " 当天总结尚未生成，可建议用户打开 App 的今日总结卡片触发生成");
        }
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("date", formatDate(dateMillis));
        result.put("score", summary.getScore());
        result.put("summary", summary.getSummary());
        return result.toString();
    }

    // ---------- 辅助 ----------

    private JSONObject functionTool(String name, String description, JSONObject properties, JSONArray required)
            throws JSONException {
        JSONObject parameters = new JSONObject();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", required);

        JSONObject function = new JSONObject();
        function.put("name", name);
        function.put("description", description);
        function.put("parameters", parameters);

        JSONObject tool = new JSONObject();
        tool.put("type", "function");
        tool.put("function", function);
        return tool;
    }

    private JSONObject stringProperties(Object[][] entries) throws JSONException {
        JSONObject properties = new JSONObject();
        for (Object[] entry : entries) {
            JSONObject p = new JSONObject();
            p.put("type", "string");
            p.put("description", (String) entry[1]);
            properties.put((String) entry[0], p);
        }
        return properties;
    }

    private JSONObject buildDayJson(String dateStr, List<Activity> list) throws JSONException {
        JSONObject day = new JSONObject();
        day.put("date", dateStr);
        day.put("count", list.size());
        JSONArray activities = new JSONArray();
        for (Activity a : list) {
            activities.put(activityJson(a));
        }
        day.put("activities", activities);
        return day;
    }

    private JSONObject activityJson(Activity a) throws JSONException {
        JSONObject item = new JSONObject();
        item.put("name", a.getName());
        item.put("start", a.getStartTimeString());
        item.put("end", a.getEndTimeString());
        item.put("duration", a.getDurationString());
        // 动机仅提供给模型参考（总结/建议时结合动机），不展示在 App 界面
        if (!a.getMotive().isEmpty()) {
            item.put("motive", a.getMotive());
        }
        return item;
    }

    private String errorJson(String message) throws JSONException {
        JSONObject error = new JSONObject();
        error.put("success", false);
        error.put("message", message);
        return error.toString();
    }

    /** 解析 yyyy-MM-dd，返回当天 00:00 毫秒；失败返回 -1 */
    private long parseDate(String dateStr) {
        try {
            SimpleDateFormat df = new SimpleDateFormat(DATE_FORMAT, Locale.US);
            df.setLenient(false);
            Date date = df.parse(dateStr);
            Calendar cal = Calendar.getInstance();
            cal.setTime(date);
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            return cal.getTimeInMillis();
        } catch (ParseException | NullPointerException e) {
            return -1;
        }
    }

    /** 解析 yyyy-MM-dd + HH:mm，返回毫秒；失败返回 -1 */
    private long parseDateTime(String dateStr, String timeStr) {
        try {
            SimpleDateFormat df = new SimpleDateFormat(DATE_FORMAT, Locale.US);
            df.setLenient(false);
            Date date = df.parse(dateStr);

            SimpleDateFormat tf = new SimpleDateFormat(TIME_FORMAT, Locale.US);
            tf.setLenient(false);
            Date time = tf.parse(timeStr);

            Calendar timeCal = Calendar.getInstance();
            timeCal.setTime(time);

            Calendar cal = Calendar.getInstance();
            cal.setTime(date);
            cal.set(Calendar.HOUR_OF_DAY, timeCal.get(Calendar.HOUR_OF_DAY));
            cal.set(Calendar.MINUTE, timeCal.get(Calendar.MINUTE));
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            return cal.getTimeInMillis();
        } catch (ParseException | NullPointerException e) {
            return -1;
        }
    }

    private String formatDate(long millis) {
        return new SimpleDateFormat(DATE_FORMAT, Locale.US).format(new Date(millis));
    }
}

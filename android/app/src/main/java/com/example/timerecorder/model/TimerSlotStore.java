package com.example.timerecorder.model;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 进行中活动槽位的持久化单一真源。
 * MainActivity（读写）与 TimerReceiver（仅判断有无）共用。
 * 旧版本单活动状态（has_active_activity 等键）在首次 load 时迁移到新 JSON 格式。
 */
public class TimerSlotStore {

    public static final String PREF_NAME = "TimeRecorderPrefs";
    private static final String KEY_ACTIVE_SLOTS = "active_slots";

    // 旧版本单活动键（迁移用）
    private static final String LEGACY_KEY_HAS_ACTIVE = "has_active_activity";
    private static final String LEGACY_KEY_NAME = "activity_name";
    private static final String LEGACY_KEY_START_HOUR = "start_hour";
    private static final String LEGACY_KEY_START_MINUTE = "start_minute";
    private static final String LEGACY_KEY_START_TIME = "start_time";

    private TimerSlotStore() {
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /** 读取全部进行中槽位；内部先执行旧数据迁移，非法项跳过，解析异常返回空列表（绝不崩溃）。 */
    public static List<TimerSlot> load(Context context) {
        SharedPreferences prefs = getPrefs(context);
        migrateLegacyIfNeeded(prefs);

        List<TimerSlot> slots = new ArrayList<>();
        String json = prefs.getString(KEY_ACTIVE_SLOTS, "");
        if (json.isEmpty()) {
            return slots;
        }
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                String name = obj.optString("name", "").trim();
                long startTime = obj.optLong("startTime", 0);
                if (name.isEmpty() || startTime <= 0) {
                    continue;
                }
                slots.add(new TimerSlot(name, startTime));
            }
        } catch (Exception e) {
            // 解析异常视为无进行中活动
        }
        return slots;
    }

    /** 保存全部进行中槽位（空闲槽位不入库）。 */
    public static void save(Context context, List<TimerSlot> slots) {
        JSONArray array = new JSONArray();
        if (slots != null) {
            for (TimerSlot slot : slots) {
                JSONObject obj = new JSONObject();
                try {
                    obj.put("name", slot.name == null ? "" : slot.name);
                    obj.put("startTime", slot.startTime);
                } catch (JSONException ignored) {
                }
                array.put(obj);
            }
        }
        getPrefs(context).edit().putString(KEY_ACTIVE_SLOTS, array.toString()).apply();
    }

    /** 是否仍有进行中的活动（供 TimerReceiver 判断是否继续轮询闹钟）。 */
    public static boolean hasActive(Context context) {
        return !load(context).isEmpty();
    }

    /** 旧版单活动状态迁移：has_active_activity=true 且新键为空时，转成一条并清除旧键。 */
    private static void migrateLegacyIfNeeded(SharedPreferences prefs) {
        if (!prefs.getBoolean(LEGACY_KEY_HAS_ACTIVE, false)) {
            return;
        }
        // 新键已有内容则不迁移，避免覆盖
        if (!prefs.getString(KEY_ACTIVE_SLOTS, "").isEmpty()) {
            return;
        }

        String name = prefs.getString(LEGACY_KEY_NAME, "").trim();
        long startTime = prefs.getLong(LEGACY_KEY_START_TIME, 0);
        if (!name.isEmpty() && startTime > 0) {
            JSONObject obj = new JSONObject();
            try {
                obj.put("name", name);
                obj.put("startTime", startTime);
            } catch (JSONException ignored) {
            }
            prefs.edit().putString(KEY_ACTIVE_SLOTS, new JSONArray().put(obj).toString()).apply();
        }

        // 一次性清除旧键；条件随旧键清除自然失效，只会迁移一次
        prefs.edit()
            .putBoolean(LEGACY_KEY_HAS_ACTIVE, false)
            .remove(LEGACY_KEY_NAME)
            .remove(LEGACY_KEY_START_HOUR)
            .remove(LEGACY_KEY_START_MINUTE)
            .remove(LEGACY_KEY_START_TIME)
            .apply();
    }
}

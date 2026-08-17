package com.example.timerecorder.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.example.timerecorder.model.Activity;
import com.example.timerecorder.model.DailySummary;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class DatabaseHelper extends SQLiteOpenHelper {
    
    private static final String DATABASE_NAME = "TimeRecorder.db";
    private static final int DATABASE_VERSION = 3;

    private static final String TABLE_ACTIVITIES = "activities";
    private static final String COLUMN_ID = "id";
    private static final String COLUMN_NAME = "name";
    private static final String COLUMN_START_HOUR = "start_hour";
    private static final String COLUMN_START_MINUTE = "start_minute";
    private static final String COLUMN_END_HOUR = "end_hour";
    private static final String COLUMN_END_MINUTE = "end_minute";
    private static final String COLUMN_DATE = "date";
    private static final String COLUMN_MOTIVE = "motive";

    // 每日总结表：date 为主键（当天 00:00 毫秒，与 activities.date 同口径）
    private static final String TABLE_DAILY_SUMMARIES = "daily_summaries";
    private static final String COLUMN_SCORE = "score";
    private static final String COLUMN_SUMMARY = "summary";
    private static final String COLUMN_UPDATED_AT = "updated_at";

    private static final String CREATE_TABLE_ACTIVITIES =
        "CREATE TABLE " + TABLE_ACTIVITIES + " (" +
        COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
        COLUMN_NAME + " TEXT, " +
        COLUMN_START_HOUR + " INTEGER, " +
        COLUMN_START_MINUTE + " INTEGER, " +
        COLUMN_END_HOUR + " INTEGER, " +
        COLUMN_END_MINUTE + " INTEGER, " +
        COLUMN_DATE + " INTEGER, " +
        COLUMN_MOTIVE + " TEXT" +
        ")";

    private static final String CREATE_TABLE_DAILY_SUMMARIES =
        "CREATE TABLE IF NOT EXISTS " + TABLE_DAILY_SUMMARIES + " (" +
        COLUMN_DATE + " INTEGER PRIMARY KEY, " +
        COLUMN_SCORE + " INTEGER, " +
        COLUMN_SUMMARY + " TEXT, " +
        COLUMN_UPDATED_AT + " INTEGER" +
        ")";

    // 按日期范围查询是高频路径，索引避免全表扫描
    private static final String CREATE_INDEX_ACTIVITIES_DATE =
        "CREATE INDEX IF NOT EXISTS idx_activities_date ON " +
        TABLE_ACTIVITIES + " (" + COLUMN_DATE + ")";

    public DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE_TABLE_ACTIVITIES);
        db.execSQL(CREATE_INDEX_ACTIVITIES_DATE);
        db.execSQL(CREATE_TABLE_DAILY_SUMMARIES);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // v1 -> v2：仅新增日期索引，不重建表，避免丢失已有数据
        if (oldVersion < 2) {
            db.execSQL(CREATE_INDEX_ACTIVITIES_DATE);
        }
        // v2 -> v3：activities 增加 motive 列（防御性检查，老行该列为 NULL 由读取层兜底）；新建每日总结表
        if (oldVersion < 3) {
            if (!hasColumn(db, TABLE_ACTIVITIES, COLUMN_MOTIVE)) {
                db.execSQL("ALTER TABLE " + TABLE_ACTIVITIES + " ADD COLUMN " + COLUMN_MOTIVE + " TEXT");
            }
            db.execSQL(CREATE_TABLE_DAILY_SUMMARIES);
        }
    }

    /** PRAGMA table_info 检查列是否存在，保证 ALTER 幂等。 */
    private boolean hasColumn(SQLiteDatabase db, String table, String column) {
        Cursor cursor = db.rawQuery("PRAGMA table_info(" + table + ")", null);
        boolean found = false;
        int nameIdx = cursor.getColumnIndex("name");
        while (cursor.moveToNext()) {
            if (column.equals(cursor.getString(nameIdx))) {
                found = true;
                break;
            }
        }
        cursor.close();
        return found;
    }
    
    public long addActivity(Activity activity) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_NAME, activity.getName());
        values.put(COLUMN_START_HOUR, activity.getStartHour());
        values.put(COLUMN_START_MINUTE, activity.getStartMinute());
        values.put(COLUMN_END_HOUR, activity.getEndHour());
        values.put(COLUMN_END_MINUTE, activity.getEndMinute());
        values.put(COLUMN_DATE, activity.getDate());
        values.put(COLUMN_MOTIVE, activity.getMotive() == null ? "" : activity.getMotive());

        long id = db.insert(TABLE_ACTIVITIES, null, values);
        return id;
    }
    
    public List<Activity> getTodayActivities() {
        List<Activity> activities = new ArrayList<>();
        
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        long startOfDay = calendar.getTimeInMillis();
        
        calendar.set(Calendar.HOUR_OF_DAY, 23);
        calendar.set(Calendar.MINUTE, 59);
        calendar.set(Calendar.SECOND, 59);
        calendar.set(Calendar.MILLISECOND, 999);
        long endOfDay = calendar.getTimeInMillis();
        
        String selectQuery = "SELECT * FROM " + TABLE_ACTIVITIES + 
            " WHERE " + COLUMN_DATE + " >= " + startOfDay + 
            " AND " + COLUMN_DATE + " <= " + endOfDay +
            " ORDER BY " + COLUMN_START_HOUR + ", " + COLUMN_START_MINUTE;
        
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery(selectQuery, null);
        
        if (cursor.moveToFirst()) {
            do {
                Activity activity = new Activity();
                activity.setId(cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)));
                activity.setName(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_NAME)));
                activity.setStartHour(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_START_HOUR)));
                activity.setStartMinute(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_START_MINUTE)));
                activity.setEndHour(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_END_HOUR)));
                activity.setEndMinute(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_END_MINUTE)));
                activity.setDate(cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_DATE)));
                int motiveIdx = cursor.getColumnIndexOrThrow(COLUMN_MOTIVE);
                activity.setMotive(cursor.isNull(motiveIdx) ? "" : cursor.getString(motiveIdx));
                activities.add(activity);
            } while (cursor.moveToNext());
        }
        
        cursor.close();
        
        return activities;
    }
    
    public void deleteActivity(long id) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(TABLE_ACTIVITIES, COLUMN_ID + " = ?", new String[]{String.valueOf(id)});
    }
    
    public void clearTodayActivities() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        long startOfDay = calendar.getTimeInMillis();
        
        calendar.set(Calendar.HOUR_OF_DAY, 23);
        calendar.set(Calendar.MINUTE, 59);
        calendar.set(Calendar.SECOND, 59);
        calendar.set(Calendar.MILLISECOND, 999);
        long endOfDay = calendar.getTimeInMillis();
        
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(TABLE_ACTIVITIES, COLUMN_DATE + " >= ? AND " + COLUMN_DATE + " <= ?",
            new String[]{String.valueOf(startOfDay), String.valueOf(endOfDay)});
    }
    
    public List<Activity> getActivitiesByDate(Calendar date) {
        List<Activity> activities = new ArrayList<>();
        
        // 复制日期对象，避免修改原对象
        Calendar calendar = (Calendar) date.clone();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        long startOfDay = calendar.getTimeInMillis();
        
        calendar.set(Calendar.HOUR_OF_DAY, 23);
        calendar.set(Calendar.MINUTE, 59);
        calendar.set(Calendar.SECOND, 59);
        calendar.set(Calendar.MILLISECOND, 999);
        long endOfDay = calendar.getTimeInMillis();
        
        String selectQuery = "SELECT * FROM " + TABLE_ACTIVITIES + 
            " WHERE " + COLUMN_DATE + " >= " + startOfDay + 
            " AND " + COLUMN_DATE + " <= " + endOfDay +
            " ORDER BY " + COLUMN_START_HOUR + ", " + COLUMN_START_MINUTE;
        
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery(selectQuery, null);
        
        if (cursor.moveToFirst()) {
            do {
                Activity activity = new Activity();
                activity.setId(cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)));
                activity.setName(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_NAME)));
                activity.setStartHour(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_START_HOUR)));
                activity.setStartMinute(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_START_MINUTE)));
                activity.setEndHour(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_END_HOUR)));
                activity.setEndMinute(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_END_MINUTE)));
                activity.setDate(cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_DATE)));
                int motiveIdx = cursor.getColumnIndexOrThrow(COLUMN_MOTIVE);
                activity.setMotive(cursor.isNull(motiveIdx) ? "" : cursor.getString(motiveIdx));
                activities.add(activity);
            } while (cursor.moveToNext());
        }
        
        cursor.close();

        return activities;
    }

    /**
     * 保存一段起止时间（可能跨天）的活动，自动按天拆分成多条记录。
     * 每条记录的 date 都归一化为当天 00:00:00.000，保证按日期查询稳定命中。
     * 与 MainActivity 手动添加共用同一套拆分逻辑（单一真源）。
     *
     * @return 实际插入的记录条数
     */
    public int addActivitySpan(String name, long startMillis, long endMillis) {
        return addActivitySpan(name, startMillis, endMillis, "");
    }

    /** 带动机的跨天拆分版本：拆分出的每条记录都携带 motive。 */
    public int addActivitySpan(String name, long startMillis, long endMillis, String motive) {
        Calendar startCal = Calendar.getInstance();
        startCal.setTimeInMillis(startMillis);
        Calendar endCal = Calendar.getInstance();
        endCal.setTimeInMillis(endMillis);

        int startHour = startCal.get(Calendar.HOUR_OF_DAY);
        int startMinute = startCal.get(Calendar.MINUTE);
        int endHour = endCal.get(Calendar.HOUR_OF_DAY);
        int endMinute = endCal.get(Calendar.MINUTE);
        long endDateOnly = getDateOnly(endCal.getTimeInMillis());

        // 防御：结束日期早于开始日期时不处理
        if (endDateOnly < getDateOnly(startCal.getTimeInMillis())) {
            return 0;
        }

        // 同一天，直接保存一条
        if (getDateOnly(startCal.getTimeInMillis()) == endDateOnly) {
            Activity activity = new Activity();
            activity.setName(name);
            activity.setStartHour(startHour);
            activity.setStartMinute(startMinute);
            activity.setEndHour(endHour);
            activity.setEndMinute(endMinute);
            activity.setDate(getDateOnly(startCal.getTimeInMillis()));
            activity.setMotive(motive);
            addActivity(activity);
            return 1;
        }

        // 跨天：逐天拆分
        int count = 0;
        Calendar cursor = (Calendar) startCal.clone();
        while (true) {
            // 当前段：cursor 时刻 → 当天 24:00
            Activity segment = new Activity();
            segment.setName(name);
            segment.setStartHour(cursor.get(Calendar.HOUR_OF_DAY));
            segment.setStartMinute(cursor.get(Calendar.MINUTE));
            segment.setEndHour(24);
            segment.setEndMinute(0);
            segment.setDate(getDateOnly(cursor.getTimeInMillis()));
            segment.setMotive(motive);
            addActivity(segment);
            count++;

            // 推进到次日 00:00
            cursor.set(Calendar.HOUR_OF_DAY, 0);
            cursor.set(Calendar.MINUTE, 0);
            cursor.set(Calendar.SECOND, 0);
            cursor.set(Calendar.MILLISECOND, 0);
            cursor.add(Calendar.DAY_OF_MONTH, 1);

            // 到达结束日：生成 00:00 → 结束时刻
            if (getDateOnly(cursor.getTimeInMillis()) == endDateOnly) {
                Activity lastSegment = new Activity();
                lastSegment.setName(name);
                lastSegment.setStartHour(0);
                lastSegment.setStartMinute(0);
                lastSegment.setEndHour(endHour);
                lastSegment.setEndMinute(endMinute);
                lastSegment.setDate(endDateOnly);
                lastSegment.setMotive(motive);
                addActivity(lastSegment);
                count++;
                break;
            }
            // 否则 cursor 落在某个整天（00:00-24:00），循环继续
        }
        return count;
    }

    /** 按指定日期（任意时刻毫秒值）查询当天活动，date 会先归一化到当天 00:00。 */
    public List<Activity> getActivitiesByDate(long dateMillis) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(dateMillis);
        long startOfDay = getDateOnly(calendar.getTimeInMillis());
        calendar.set(Calendar.HOUR_OF_DAY, 23);
        calendar.set(Calendar.MINUTE, 59);
        calendar.set(Calendar.SECOND, 59);
        calendar.set(Calendar.MILLISECOND, 999);
        long endOfDay = calendar.getTimeInMillis();
        return queryActivitiesBetween(startOfDay, endOfDay);
    }

    /** 查询 [startDateMillis, endDateMillis] 之间的活动（参数为各日期任意时刻，内部归一化）。 */
    public List<Activity> getActivitiesBetween(long startDateMillis, long endDateMillis) {
        Calendar start = Calendar.getInstance();
        start.setTimeInMillis(startDateMillis);
        long startOfDay = getDateOnly(start.getTimeInMillis());

        Calendar end = Calendar.getInstance();
        end.setTimeInMillis(endDateMillis);
        end.set(Calendar.HOUR_OF_DAY, 23);
        end.set(Calendar.MINUTE, 59);
        end.set(Calendar.SECOND, 59);
        end.set(Calendar.MILLISECOND, 999);
        long endOfDay = end.getTimeInMillis();
        return queryActivitiesBetween(startOfDay, endOfDay);
    }

    /** 删除某一天内名称精确匹配的所有活动记录，返回删除条数。 */
    public int deleteActivitiesByNameAndDate(String name, long dateMillis) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(dateMillis);
        long startOfDay = getDateOnly(calendar.getTimeInMillis());
        calendar.set(Calendar.HOUR_OF_DAY, 23);
        calendar.set(Calendar.MINUTE, 59);
        calendar.set(Calendar.SECOND, 59);
        calendar.set(Calendar.MILLISECOND, 999);
        long endOfDay = calendar.getTimeInMillis();

        SQLiteDatabase db = this.getWritableDatabase();
        int deleted = db.delete(TABLE_ACTIVITIES,
            COLUMN_DATE + " >= ? AND " + COLUMN_DATE + " <= ? AND " + COLUMN_NAME + " = ?",
            new String[]{String.valueOf(startOfDay), String.valueOf(endOfDay), name});
        return deleted;
    }

    /** 按 id 更新活动（编辑对话框保存用），返回受影响行数。 */
    public int updateActivity(Activity activity) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_NAME, activity.getName());
        values.put(COLUMN_START_HOUR, activity.getStartHour());
        values.put(COLUMN_START_MINUTE, activity.getStartMinute());
        values.put(COLUMN_END_HOUR, activity.getEndHour());
        values.put(COLUMN_END_MINUTE, activity.getEndMinute());
        values.put(COLUMN_DATE, activity.getDate());
        values.put(COLUMN_MOTIVE, activity.getMotive() == null ? "" : activity.getMotive());
        return db.update(TABLE_ACTIVITIES, values, COLUMN_ID + " = ?",
                new String[]{String.valueOf(activity.getId())});
    }

    /** 查询某天的每日总结；没有则返回 null。date 会先归一化到当天 00:00。 */
    public DailySummary getSummaryByDate(long dateMillis) {
        long date = getDateOnly(dateMillis);
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.query(TABLE_DAILY_SUMMARIES, null,
                COLUMN_DATE + " = ?", new String[]{String.valueOf(date)},
                null, null, null);
        DailySummary summary = null;
        if (cursor.moveToFirst()) {
            summary = new DailySummary();
            summary.setDate(cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_DATE)));
            summary.setScore(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_SCORE)));
            int sIdx = cursor.getColumnIndexOrThrow(COLUMN_SUMMARY);
            summary.setSummary(cursor.isNull(sIdx) ? "" : cursor.getString(sIdx));
            summary.setUpdatedAt(cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_UPDATED_AT)));
        }
        cursor.close();
        return summary;
    }

    /** 查询 [startDateMillis, endDateMillis]（各日期任意时刻，内部归一化）内的每日总结，按日期升序。 */
    public List<DailySummary> getSummariesBetween(long startDateMillis, long endDateMillis) {
        long startOfDay = getDateOnly(startDateMillis);
        Calendar end = Calendar.getInstance();
        end.setTimeInMillis(endDateMillis);
        end.set(Calendar.HOUR_OF_DAY, 23);
        end.set(Calendar.MINUTE, 59);
        end.set(Calendar.SECOND, 59);
        end.set(Calendar.MILLISECOND, 999);
        long endOfDay = end.getTimeInMillis();

        List<DailySummary> list = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.query(TABLE_DAILY_SUMMARIES, null,
                COLUMN_DATE + " >= ? AND " + COLUMN_DATE + " <= ?",
                new String[]{String.valueOf(startOfDay), String.valueOf(endOfDay)},
                null, null, COLUMN_DATE + " ASC");
        if (cursor.moveToFirst()) {
            do {
                DailySummary s = new DailySummary();
                s.setDate(cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_DATE)));
                s.setScore(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_SCORE)));
                int si = cursor.getColumnIndexOrThrow(COLUMN_SUMMARY);
                s.setSummary(cursor.isNull(si) ? "" : cursor.getString(si));
                s.setUpdatedAt(cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_UPDATED_AT)));
                list.add(s);
            } while (cursor.moveToNext());
        }
        cursor.close();
        return list;
    }

    /** 写入/覆盖某天的每日总结（date 为主键，重复写即覆盖）。 */
    public void upsertSummary(long dateMillis, int score, String summary) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_DATE, getDateOnly(dateMillis));
        values.put(COLUMN_SCORE, score);
        values.put(COLUMN_SUMMARY, summary == null ? "" : summary);
        values.put(COLUMN_UPDATED_AT, System.currentTimeMillis());
        db.insertWithOnConflict(TABLE_DAILY_SUMMARIES, null, values,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** 删除某天的每日总结（编辑/删除活动后触发重建前调用）。 */
    public void deleteSummary(long dateMillis) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(TABLE_DAILY_SUMMARIES, COLUMN_DATE + " = ?",
                new String[]{String.valueOf(getDateOnly(dateMillis))});
    }

    private long getDateOnly(long millis) {
        Calendar temp = Calendar.getInstance();
        temp.setTimeInMillis(millis);
        temp.set(Calendar.HOUR_OF_DAY, 0);
        temp.set(Calendar.MINUTE, 0);
        temp.set(Calendar.SECOND, 0);
        temp.set(Calendar.MILLISECOND, 0);
        return temp.getTimeInMillis();
    }

    private List<Activity> queryActivitiesBetween(long startMillis, long endMillis) {
        List<Activity> activities = new ArrayList<>();

        String selectQuery = "SELECT * FROM " + TABLE_ACTIVITIES +
            " WHERE " + COLUMN_DATE + " >= " + startMillis +
            " AND " + COLUMN_DATE + " <= " + endMillis +
            " ORDER BY " + COLUMN_DATE + ", " + COLUMN_START_HOUR + ", " + COLUMN_START_MINUTE;

        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery(selectQuery, null);

        if (cursor.moveToFirst()) {
            do {
                Activity activity = new Activity();
                activity.setId(cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)));
                activity.setName(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_NAME)));
                activity.setStartHour(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_START_HOUR)));
                activity.setStartMinute(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_START_MINUTE)));
                activity.setEndHour(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_END_HOUR)));
                activity.setEndMinute(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_END_MINUTE)));
                activity.setDate(cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_DATE)));
                int motiveIdx = cursor.getColumnIndexOrThrow(COLUMN_MOTIVE);
                activity.setMotive(cursor.isNull(motiveIdx) ? "" : cursor.getString(motiveIdx));
                activities.add(activity);
            } while (cursor.moveToNext());
        }

        cursor.close();

        return activities;
    }
}
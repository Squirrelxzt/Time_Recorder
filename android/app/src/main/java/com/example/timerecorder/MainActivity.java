package com.example.timerecorder;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import android.app.DatePickerDialog;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;

import com.example.timerecorder.adapter.ViewPagerAdapter;
import com.example.timerecorder.ai.ChatActivity;
import com.example.timerecorder.ai.SummaryGenerator;
import com.example.timerecorder.database.DatabaseHelper;
import com.example.timerecorder.fragment.ActivityRecordFragment;
import com.example.timerecorder.fragment.ScoreFragment;
import com.example.timerecorder.fragment.TimeAxisFragment;
import com.example.timerecorder.model.Activity;
import com.example.timerecorder.model.DailySummary;
import com.example.timerecorder.model.TimerSlot;
import com.example.timerecorder.model.TimerSlotStore;
import com.example.timerecorder.service.TimerService;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class MainActivity extends AppCompatActivity
        implements ActivityRecordFragment.Listener, ActivityRecordFragment.ViewReadyCallback {

    private static final String TAG = "MainActivity";

    private ViewPager2 viewPager;
    private TabLayout tabLayout;
    private Button btnDatePicker;
    private DatabaseHelper databaseHelper;
    private Calendar selectedDate, selectedStartTime, selectedEndTime, manualStartDate, manualEndDate;

    /** 进行中的活动槽位（持久化单一真源，空闲槽位不在此列表内）。null 表示尚未从 prefs 恢复。 */
    private List<TimerSlot> activeSlots;
    private boolean tickerRunning;
    private Handler tickerHandler;
    private Runnable tickerRunnable;
    /** initActivityRecordPage 的 Fragment 未就绪重试次数，避免无限循环 */
    private int initRecordPageRetryCount;

    /** 每日总结生成器（单线程串行 + 主线程回调）。 */
    private SummaryGenerator summaryGenerator;
    /** 总结请求序号：回调时比对，丢弃过期结果防止 UI 闪烁。 */
    private int summaryReqSeq;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        databaseHelper = new DatabaseHelper(this);
        summaryGenerator = new SummaryGenerator(this, databaseHelper);
        selectedDate = Calendar.getInstance();

        initViews();
        setupListeners();
        updateDateButton();
        // 首次加载由 initViews 中的延迟回调（Fragment 创建完成后）与 onResume 承担，
        // 这里 Fragment 尚未创建，加载属于白查，直接跳过。
    }

    private void initViews() {
        viewPager = findViewById(R.id.view_pager);
        tabLayout = findViewById(R.id.tab_layout);
        btnDatePicker = findViewById(R.id.btn_date_picker);

        // 初始化ViewPager适配器
        ViewPagerAdapter adapter = new ViewPagerAdapter(this);
        viewPager.setAdapter(adapter);

        // 禁用ViewPager的左右滑动
        viewPager.setUserInputEnabled(false);

        // 关联TabLayout和ViewPager
        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            if (position == 0) {
                tab.setText("时间轴");
            } else if (position == 1) {
                tab.setText("活动记录");
            } else {
                tab.setText("评分");
            }
        }).attach();

        // 监听ViewPager页面创建完成事件
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                // 页面选择时加载数据
                loadActivities();
                // 切到评分页时刷新（评分数据可能已被今日总结/编辑活动更新）
                if (position == 2) {
                    ScoreFragment scoreFragment = getScoreFragment();
                    if (scoreFragment != null) {
                        scoreFragment.refresh();
                    }
                }
            }
        });

        // 延迟加载数据，确保Fragment已创建
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            loadActivities();
        }, 500);

        // 初始化时间选择器的默认时间
        selectedStartTime = Calendar.getInstance();
        selectedEndTime = Calendar.getInstance();
        selectedEndTime.add(Calendar.HOUR, 1);
    }

    private void setupListeners() {
        // AI 助手入口：右下角悬浮按钮
        FloatingActionButton fabAiChat = findViewById(R.id.fab_ai_chat);
        if (fabAiChat != null) {
            fabAiChat.setOnClickListener(v -> startActivity(new Intent(this, ChatActivity.class)));
        }

        btnDatePicker.setOnClickListener(v -> showDatePicker());

        // 注册页面变更回调
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                if (position == 1) {
                    // 切换到活动记录页面时初始化
                    initActivityRecordPage();
                }
            }
        });
    }

    private void initActivityRecordPage() {
        ActivityRecordFragment fragment = getActivityRecordFragment();
        if (fragment == null) {
            // 冷启动时 Fragment 可能晚于 onPageSelected 才被 ViewPager 创建，
            // 首次进入本页会错过 onPageSelected 的注入时机。延迟重试，确保
            // 状态恢复与日期初始化得以执行（按钮点击已由 Fragment 从 Activity 兜底获取，不受影响）。
            if (initRecordPageRetryCount < 5) {
                initRecordPageRetryCount++;
                new Handler(Looper.getMainLooper()).postDelayed(this::initActivityRecordPage, 100);
            }
            return;
        }
        initRecordPageRetryCount = 0;

        // 绑定点击回调。按钮/列表的点击监听器由 Fragment 在 onViewCreated 中绑定，
        // 这里只需注入业务监听器（可重复调用，幂等）。
        fragment.setListener(this);
        // 视图就绪回调：fragment 视图重建时由它主动通知宿主补加载数据/初始化
        fragment.setViewReadyCallback(this);

        // 初始化手动添加活动的日期
        if (manualStartDate == null) {
            manualStartDate = Calendar.getInstance();
        }
        if (manualEndDate == null) {
            manualEndDate = Calendar.getInstance();
        }
        if (fragment.getBtnStartDate() != null) {
            updateManualDateButton(fragment, true);
        }
        if (fragment.getBtnEndDate() != null) {
            updateManualDateButton(fragment, false);
        }

        // 恢复进行中的槽位（内部幂等）并渲染 + 重启 ticker
        restoreSlots();
        refreshSlotsUi();
    }

    // ===== ActivityRecordFragment.Listener 实现 =====

    @Override
    public void onStartSlotClick(String name) {
        if (name == null || name.trim().isEmpty()) {
            Toast.makeText(this, "请输入活动名称", Toast.LENGTH_SHORT).show();
            return;
        }
        if (activeSlots == null) {
            activeSlots = new ArrayList<>();
        }
        activeSlots.add(new TimerSlot(name.trim(), System.currentTimeMillis()));
        TimerSlotStore.save(this, activeSlots);
        refreshSlotsUi();
        startBackgroundTimer();
        Toast.makeText(this, "活动已开始", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onEndSlotClick(int slotIndex) {
        if (activeSlots == null || slotIndex < 0 || slotIndex >= activeSlots.size()) {
            return;
        }
        TimerSlot slot = activeSlots.remove(slotIndex);
        long now = System.currentTimeMillis();
        Calendar startCal = Calendar.getInstance();
        startCal.setTimeInMillis(slot.startTime);
        Calendar endCal = Calendar.getInstance();
        endCal.setTimeInMillis(now);

        // 保存活动（跨天时自动按天分割）
        boolean crossDay = getDateOnly(startCal) != getDateOnly(endCal);
        saveActivitySpan(slot.name, startCal, endCal);
        Toast.makeText(this, crossDay ? "活动已记录（跨天，已自动分割）" : "活动已记录", Toast.LENGTH_SHORT).show();

        TimerSlotStore.save(this, activeSlots);
        refreshSlotsUi();
        if (activeSlots.isEmpty()) {
            // 全部结束：停掉后台闹钟，避免轮询自续
            stopBackgroundTimer();
        }
        loadActivities();
    }

    @Override
    public void onStartTimeClick() {
        showTimePicker(true);
    }

    @Override
    public void onEndTimeClick() {
        showTimePicker(false);
    }

    @Override
    public void onSaveActivityClick() {
        saveManualActivity();
    }

    @Override
    public void onStartDateClick() {
        showManualDatePicker(true);
    }

    @Override
    public void onEndDateClick() {
        showManualDatePicker(false);
    }

    @Override
    public void onActivityClick(Activity activity, int position) {
        showEditActivityDialog(activity);
    }

    @Override
    public void onActivityLongClick(Activity activity, int position) {
        new AlertDialog.Builder(MainActivity.this)
            .setTitle("删除活动")
            .setMessage("确定要删除这个活动吗？")
            .setPositiveButton("删除", (dialog, which) -> {
                long date = activity.getDate();
                databaseHelper.deleteActivity(activity.getId());
                loadActivities();
                // 该天活动被删，总结自动重建
                regenerateSummaryForDate(date);
                Toast.makeText(MainActivity.this, "已删除", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    @Override
    public void onSummaryCardClick() {
        long today = getDateOnly(Calendar.getInstance());
        if (getDateOnly(selectedDate) != today) {
            return;
        }
        // 无论当前是未配置/失败/成功，点击都重新触发生成
        regenerateSummaryForDate(today);
    }

    // ===== ActivityRecordFragment.ViewReadyCallback 实现 =====

    @Override
    public void onViewReady() {
        // Fragment 视图创建完成后由 Fragment 主动通知：首次进入时 Fragment 可能
        // 晚于页面切换回调创建，此处确保数据加载与页面初始化必然执行。
        initActivityRecordPage();
        loadActivities();
    }

    private ActivityRecordFragment getActivityRecordFragment() {
        Fragment fragment = getSupportFragmentManager().findFragmentByTag("f1");
        if (fragment instanceof ActivityRecordFragment) {
            return (ActivityRecordFragment) fragment;
        }
        return null;
    }

    private TimeAxisFragment getTimeAxisFragment() {
        Fragment fragment = getSupportFragmentManager().findFragmentByTag("f0");
        if (fragment instanceof TimeAxisFragment) {
            return (TimeAxisFragment) fragment;
        }
        return null;
    }

    private ScoreFragment getScoreFragment() {
        Fragment fragment = getSupportFragmentManager().findFragmentByTag("f2");
        if (fragment instanceof ScoreFragment) {
            return (ScoreFragment) fragment;
        }
        return null;
    }

    // ===== 多槽位：恢复 / 渲染 / ticker =====

    /** 从持久化恢复进行中槽位；activeSlots 非 null 表示本实例已恢复过，跳过（幂等）。 */
    private void restoreSlots() {
        if (activeSlots != null) {
            return;
        }
        activeSlots = TimerSlotStore.load(this);
        if (!activeSlots.isEmpty()) {
            startBackgroundTimer();
        }
    }

    /** 渲染槽位 UI（运行卡 + 末尾空闲卡）并按需启停 ticker。 */
    private void refreshSlotsUi() {
        ActivityRecordFragment fragment = getActivityRecordFragment();
        if (fragment == null) {
            return;
        }
        List<TimerSlot> slots = activeSlots == null ? new ArrayList<>() : activeSlots;
        fragment.renderSlots(slots);
        fragment.updateSlotTimers(slots);
        ensureTicker();
    }

    /** 有运行槽位则启动每秒 ticker，否则停止。 */
    private void ensureTicker() {
        if (activeSlots != null && !activeSlots.isEmpty()) {
            startTicker();
        } else {
            stopTicker();
        }
    }

    private void startTicker() {
        if (tickerRunning) {
            return;
        }
        tickerRunning = true;
        if (tickerHandler == null) {
            tickerHandler = new Handler(Looper.getMainLooper());
        }
        if (tickerRunnable == null) {
            tickerRunnable = new Runnable() {
                @Override
                public void run() {
                    if (!tickerRunning) {
                        return;
                    }
                    updateTimers();
                    if (activeSlots == null || activeSlots.isEmpty()) {
                        // 无运行槽位自动停止
                        stopTicker();
                        return;
                    }
                    tickerHandler.postDelayed(this, 1000);
                }
            };
        }
        tickerRunnable.run();
    }

    private void stopTicker() {
        tickerRunning = false;
        if (tickerHandler != null && tickerRunnable != null) {
            tickerHandler.removeCallbacks(tickerRunnable);
        }
    }

    /** 每秒 tick：仅刷新运行卡的计时文本，不重建视图。 */
    private void updateTimers() {
        if (activeSlots == null || activeSlots.isEmpty()) {
            return;
        }
        ActivityRecordFragment fragment = getActivityRecordFragment();
        if (fragment != null) {
            fragment.updateSlotTimers(activeSlots);
        }
    }

    private void showTimePicker(boolean isStartTime) {
        Calendar calendar = isStartTime ? selectedStartTime : selectedEndTime;
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        int minute = calendar.get(Calendar.MINUTE);

        android.app.TimePickerDialog timePickerDialog = new android.app.TimePickerDialog(
            this,
            (view, selectedHour, selectedMinute) -> {
                ActivityRecordFragment fragment = getActivityRecordFragment();
                if (fragment != null) {
                    if (isStartTime) {
                        selectedStartTime.set(Calendar.HOUR_OF_DAY, selectedHour);
                        selectedStartTime.set(Calendar.MINUTE, selectedMinute);
                        if (fragment.getBtnStartTime() != null) {
                            updateTimeButtonText(fragment.getBtnStartTime(), selectedStartTime);
                        }
                    } else {
                        selectedEndTime.set(Calendar.HOUR_OF_DAY, selectedHour);
                        selectedEndTime.set(Calendar.MINUTE, selectedMinute);
                        if (fragment.getBtnEndTime() != null) {
                            updateTimeButtonText(fragment.getBtnEndTime(), selectedEndTime);
                        }
                    }
                }
            },
            hour,
            minute,
            true
        );
        timePickerDialog.show();
    }

    private void updateTimeButtonText(Button button, Calendar calendar) {
        String timeText = String.format("%02d:%02d",
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE)
        );
        button.setText(timeText);
    }

    private void saveManualActivity() {
        ActivityRecordFragment fragment = getActivityRecordFragment();
        if (fragment == null) return;

        String activityName = fragment.getEtManualActivityName().getText().toString().trim();
        if (activityName.isEmpty()) {
            Toast.makeText(this, "请输入活动名称", Toast.LENGTH_SHORT).show();
            return;
        }

        // 合并用户选择的日期与时间
        Calendar startCal = (Calendar) manualStartDate.clone();
        startCal.set(Calendar.HOUR_OF_DAY, selectedStartTime.get(Calendar.HOUR_OF_DAY));
        startCal.set(Calendar.MINUTE, selectedStartTime.get(Calendar.MINUTE));
        Calendar endCal = (Calendar) manualEndDate.clone();
        endCal.set(Calendar.HOUR_OF_DAY, selectedEndTime.get(Calendar.HOUR_OF_DAY));
        endCal.set(Calendar.MINUTE, selectedEndTime.get(Calendar.MINUTE));

        // 比较开始日期和结束日期
        long startDateOnly = getDateOnly(startCal);
        long endDateOnly = getDateOnly(endCal);

        if (endDateOnly < startDateOnly) {
            // 结束日期早于开始日期，错误
            Toast.makeText(this, "结束日期不能早于开始日期", Toast.LENGTH_SHORT).show();
            return;
        }

        // 保存活动（跨天时自动按天分割）
        boolean crossDay = startDateOnly != endDateOnly;
        saveActivitySpan(activityName, startCal, endCal);
        if (crossDay) {
            Toast.makeText(this, "活动已保存（跨天，已自动分割）", Toast.LENGTH_SHORT).show();
        }

        loadActivities();

        if (fragment.getEtManualActivityName() != null) {
            fragment.getEtManualActivityName().setText("");
        }
        selectedStartTime = Calendar.getInstance();
        selectedEndTime = Calendar.getInstance();
        selectedEndTime.add(Calendar.HOUR, 1);
        if (fragment.getBtnStartTime() != null) {
            updateTimeButtonText(fragment.getBtnStartTime(), selectedStartTime);
        }
        if (fragment.getBtnEndTime() != null) {
            updateTimeButtonText(fragment.getBtnEndTime(), selectedEndTime);
        }
    }

    private long getDateOnly(Calendar calendar) {
        Calendar temp = (Calendar) calendar.clone();
        temp.set(Calendar.HOUR_OF_DAY, 0);
        temp.set(Calendar.MINUTE, 0);
        temp.set(Calendar.SECOND, 0);
        temp.set(Calendar.MILLISECOND, 0);
        return temp.getTimeInMillis();
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

    /**
     * 保存一段起止时间（可能跨天）的活动，自动按天拆分成多条记录。
     * 具体拆分逻辑已迁入 DatabaseHelper.addActivitySpan，此处为委托调用（单一真源，与 AI 工具共用）。
     */
    private void saveActivitySpan(String name, Calendar startCal, Calendar endCal) {
        databaseHelper.addActivitySpan(name, startCal.getTimeInMillis(), endCal.getTimeInMillis());
    }

    private void showDatePicker() {
        DatePickerDialog datePickerDialog = new DatePickerDialog(
            this,
            (view, year, month, dayOfMonth) -> {
                selectedDate.set(Calendar.YEAR, year);
                selectedDate.set(Calendar.MONTH, month);
                selectedDate.set(Calendar.DAY_OF_MONTH, dayOfMonth);
                updateDateButton();
                loadActivities();
            },
            selectedDate.get(Calendar.YEAR),
            selectedDate.get(Calendar.MONTH),
            selectedDate.get(Calendar.DAY_OF_MONTH)
        );
        datePickerDialog.show();
    }

    private void updateDateButton() {
        String dateStr = String.format("%d年%d月%d日",
            selectedDate.get(Calendar.YEAR),
            selectedDate.get(Calendar.MONTH) + 1,
            selectedDate.get(Calendar.DAY_OF_MONTH)
        );
        btnDatePicker.setText(dateStr);
    }

    private void showManualDatePicker(boolean isStartDate) {
        Calendar date = isStartDate ? manualStartDate : manualEndDate;
        DatePickerDialog datePickerDialog = new DatePickerDialog(
            this,
            (view, year, month, dayOfMonth) -> {
                if (isStartDate) {
                    manualStartDate.set(Calendar.YEAR, year);
                    manualStartDate.set(Calendar.MONTH, month);
                    manualStartDate.set(Calendar.DAY_OF_MONTH, dayOfMonth);
                } else {
                    manualEndDate.set(Calendar.YEAR, year);
                    manualEndDate.set(Calendar.MONTH, month);
                    manualEndDate.set(Calendar.DAY_OF_MONTH, dayOfMonth);
                }
                ActivityRecordFragment fragment = getActivityRecordFragment();
                if (fragment != null) {
                    updateManualDateButton(fragment, isStartDate);
                }
            },
            date.get(Calendar.YEAR),
            date.get(Calendar.MONTH),
            date.get(Calendar.DAY_OF_MONTH)
        );
        datePickerDialog.show();
    }

    private void updateManualDateButton(ActivityRecordFragment fragment, boolean isStartDate) {
        Calendar date = isStartDate ? manualStartDate : manualEndDate;
        Button btn = isStartDate ? fragment.getBtnStartDate() : fragment.getBtnEndDate();
        if (fragment != null && btn != null) {
            String dateStr = String.format("%d年%d月%d日",
                date.get(Calendar.YEAR),
                date.get(Calendar.MONTH) + 1,
                date.get(Calendar.DAY_OF_MONTH)
            );
            btn.setText(dateStr);
        }
    }

    // ===== 活动编辑 =====

    /** 点击活动项：弹出编辑对话框（名称/起止日期时间/动机），保存后更新数据库。 */
    private void showEditActivityDialog(Activity activity) {
        View view = getLayoutInflater().inflate(R.layout.dialog_edit_activity, null);
        TextInputEditText etName = view.findViewById(R.id.et_edit_name);
        TextInputEditText etMotive = view.findViewById(R.id.et_edit_motive);
        MaterialButton btnStartDate = view.findViewById(R.id.btn_edit_start_date);
        MaterialButton btnEndDate = view.findViewById(R.id.btn_edit_end_date);
        MaterialButton btnStartTime = view.findViewById(R.id.btn_edit_start_time);
        MaterialButton btnEndTime = view.findViewById(R.id.btn_edit_end_time);

        // 预填起止时刻。原结束为 24:00（endHour==24）时视为次日 00:00，
        // 保持语义并在保存时走跨天重拆恢复。
        Calendar startCal = buildCalendarFromActivityStart(activity);
        Calendar endCal = buildCalendarFromActivityEnd(activity);

        etName.setText(activity.getName());
        etMotive.setText(activity.getMotive());
        updateEditButtons(btnStartDate, btnEndDate, btnStartTime, btnEndTime, startCal, endCal);

        btnStartDate.setOnClickListener(v -> showEditDatePicker(startCal, () ->
                updateEditButtons(btnStartDate, btnEndDate, btnStartTime, btnEndTime, startCal, endCal)));
        btnEndDate.setOnClickListener(v -> showEditDatePicker(endCal, () ->
                updateEditButtons(btnStartDate, btnEndDate, btnStartTime, btnEndTime, startCal, endCal)));
        btnStartTime.setOnClickListener(v -> showEditTimePicker(startCal, () ->
                updateEditButtons(btnStartDate, btnEndDate, btnStartTime, btnEndTime, startCal, endCal)));
        btnEndTime.setOnClickListener(v -> showEditTimePicker(endCal, () ->
                updateEditButtons(btnStartDate, btnEndDate, btnStartTime, btnEndTime, startCal, endCal)));

        new AlertDialog.Builder(this)
            .setTitle(R.string.edit_activity_title)
            .setView(view)
            .setPositiveButton(R.string.edit_activity_save, (dialog, which) ->
                    saveEditedActivity(activity, startCal, endCal, etName, etMotive))
            .setNegativeButton(R.string.edit_activity_cancel, null)
            .show();
    }

    private Calendar buildCalendarFromActivityStart(Activity a) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(a.getDate());
        c.set(Calendar.HOUR_OF_DAY, a.getStartHour());
        c.set(Calendar.MINUTE, a.getStartMinute());
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    /** 结束时刻：endHour==24 表示 24:00，视为次日 00:00，保存时走跨天重拆。 */
    private Calendar buildCalendarFromActivityEnd(Activity a) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(a.getDate());
        if (a.getEndHour() == 24 && a.getEndMinute() == 0) {
            c.add(Calendar.DAY_OF_MONTH, 1);
            c.set(Calendar.HOUR_OF_DAY, 0);
            c.set(Calendar.MINUTE, 0);
        } else {
            c.set(Calendar.HOUR_OF_DAY, a.getEndHour());
            c.set(Calendar.MINUTE, a.getEndMinute());
        }
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    private void showEditDatePicker(Calendar target, Runnable onChanged) {
        new DatePickerDialog(this,
                (view, year, month, dayOfMonth) -> {
                    target.set(Calendar.YEAR, year);
                    target.set(Calendar.MONTH, month);
                    target.set(Calendar.DAY_OF_MONTH, dayOfMonth);
                    onChanged.run();
                },
                target.get(Calendar.YEAR),
                target.get(Calendar.MONTH),
                target.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void showEditTimePicker(Calendar target, Runnable onChanged) {
        new android.app.TimePickerDialog(this,
                (view, hour, minute) -> {
                    target.set(Calendar.HOUR_OF_DAY, hour);
                    target.set(Calendar.MINUTE, minute);
                    onChanged.run();
                },
                target.get(Calendar.HOUR_OF_DAY),
                target.get(Calendar.MINUTE),
                true).show();
    }

    private void updateEditButtons(MaterialButton btnStartDate, MaterialButton btnEndDate,
            MaterialButton btnStartTime, MaterialButton btnEndTime,
            Calendar startCal, Calendar endCal) {
        btnStartDate.setText(String.format("%d年%d月%d日",
                startCal.get(Calendar.YEAR), startCal.get(Calendar.MONTH) + 1,
                startCal.get(Calendar.DAY_OF_MONTH)));
        btnEndDate.setText(String.format("%d年%d月%d日",
                endCal.get(Calendar.YEAR), endCal.get(Calendar.MONTH) + 1,
                endCal.get(Calendar.DAY_OF_MONTH)));
        btnStartTime.setText(String.format("%02d:%02d",
                startCal.get(Calendar.HOUR_OF_DAY), startCal.get(Calendar.MINUTE)));
        btnEndTime.setText(String.format("%02d:%02d",
                endCal.get(Calendar.HOUR_OF_DAY), endCal.get(Calendar.MINUTE)));
    }

    private void saveEditedActivity(Activity original, Calendar startCal, Calendar endCal,
            TextInputEditText etName, TextInputEditText etMotive) {
        String name = etName.getText() == null ? "" : etName.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.edit_name_required, Toast.LENGTH_SHORT).show();
            return;
        }
        String motive = etMotive.getText() == null ? "" : etMotive.getText().toString().trim();

        long startDateOnly = getDateOnly(startCal);
        long endDateOnly = getDateOnly(endCal);
        if (endDateOnly < startDateOnly) {
            Toast.makeText(this, R.string.edit_time_invalid, Toast.LENGTH_SHORT).show();
            return;
        }
        // 同一天时结束时刻必须晚于开始时刻；跨天天然满足
        if (endDateOnly == startDateOnly && !endCal.after(startCal)) {
            Toast.makeText(this, R.string.edit_time_invalid, Toast.LENGTH_SHORT).show();
            return;
        }

        if (endDateOnly == startDateOnly) {
            // 同一天：原地更新（保留 id/date）
            Activity updated = new Activity();
            updated.setId(original.getId());
            updated.setName(name);
            updated.setStartHour(startCal.get(Calendar.HOUR_OF_DAY));
            updated.setStartMinute(startCal.get(Calendar.MINUTE));
            updated.setEndHour(endCal.get(Calendar.HOUR_OF_DAY));
            updated.setEndMinute(endCal.get(Calendar.MINUTE));
            updated.setDate(startDateOnly);
            updated.setMotive(motive);
            databaseHelper.updateActivity(updated);
        } else {
            // 跨天：删除原记录，按新跨度重拆（携带动机）
            databaseHelper.deleteActivity(original.getId());
            databaseHelper.addActivitySpan(name, startCal.getTimeInMillis(),
                    endCal.getTimeInMillis(), motive);
        }

        loadActivities();
        // 涉及到的每一天总结自动重建（跨天可能影响两天）
        regenerateSummaryForDate(startDateOnly);
        if (endDateOnly != startDateOnly) {
            regenerateSummaryForDate(endDateOnly);
        }
        Toast.makeText(this, R.string.edit_saved, Toast.LENGTH_SHORT).show();
    }

    // ===== 每日总结驱动 =====

    /** 进入页面时确保当天总结已生成：非今天不展示；已有直接显示；否则加载态 + 生成。 */
    private void ensureTodaySummary() {
        long today = getDateOnly(Calendar.getInstance());
        long shown = getDateOnly(selectedDate);
        if (shown != today) {
            ActivityRecordFragment fragment = getActivityRecordFragment();
            if (fragment != null) {
                fragment.showSummary(ActivityRecordFragment.SUMMARY_HIDDEN, null);
            }
            return;
        }
        DailySummary existing = databaseHelper.getSummaryByDate(today);
        if (existing != null) {
            ActivityRecordFragment fragment = getActivityRecordFragment();
            if (fragment != null) {
                fragment.showSummary(ActivityRecordFragment.SUMMARY_SUCCESS, existing);
            }
            return;
        }
        ActivityRecordFragment fragment = getActivityRecordFragment();
        if (fragment != null) {
            fragment.showSummary(ActivityRecordFragment.SUMMARY_LOADING, null);
        }
        requestSummary(today, true);
    }

    /** 某天活动被修改（编辑/删除）后调用：重建该天总结。 */
    private void regenerateSummaryForDate(long dateMillis) {
        long date = getDateOnly(dateMillis);
        ActivityRecordFragment fragment = getActivityRecordFragment();
        // 展示中的日期正好是该天：先显示加载态，避免旧总结残留
        if (fragment != null && getDateOnly(selectedDate) == date) {
            fragment.showSummary(ActivityRecordFragment.SUMMARY_LOADING, null);
        }
        requestSummary(date, false);
    }

    /**
     * 发起总结请求。skipIfExists=true 走幂等路径（已有总结直接返回），
     * false 走强制重建路径。seq 守卫丢弃过期回调防 UI 闪烁。
     */
    private void requestSummary(long dateMillis, boolean skipIfExists) {
        final int seq = ++summaryReqSeq;
        final long date = getDateOnly(dateMillis);
        SummaryGenerator.Callback cb = new SummaryGenerator.Callback() {
            @Override
            public void onSuccess(DailySummary summary) {
                if (seq != summaryReqSeq) {
                    return;
                }
                // 仅当展示日期仍为当天且等于目标日时刷新卡片
                if (getDateOnly(selectedDate) != date
                        || getDateOnly(selectedDate) != getDateOnly(Calendar.getInstance())) {
                    return;
                }
                ActivityRecordFragment fragment = getActivityRecordFragment();
                if (fragment != null) {
                    fragment.showSummary(ActivityRecordFragment.SUMMARY_SUCCESS, summary);
                }
            }

            @Override
            public void onError(int state, String message) {
                if (seq != summaryReqSeq) {
                    return;
                }
                if (getDateOnly(selectedDate) != date) {
                    return;
                }
                ActivityRecordFragment fragment = getActivityRecordFragment();
                if (fragment == null) {
                    return;
                }
                if (state == SummaryGenerator.STATE_NOT_CONFIGURED) {
                    fragment.showSummary(ActivityRecordFragment.SUMMARY_NOT_CONFIGURED, null);
                } else {
                    fragment.showSummary(ActivityRecordFragment.SUMMARY_ERROR, null);
                }
            }
        };
        if (skipIfExists) {
            summaryGenerator.ensureSummary(date, cb);
        } else {
            summaryGenerator.regenerate(date, cb);
        }
    }

    private void startBackgroundTimer() {
        try {
            Intent serviceIntent = new Intent(this, TimerService.class);
            serviceIntent.setAction("START_TIMER");
            startService(serviceIntent);
            Log.d(TAG, "Service started");
        } catch (Exception e) {
            Log.e(TAG, "Error starting service: " + e.getMessage());
        }

        try {
            TimerService.scheduleTimer(this);
        } catch (Exception e) {
            Log.e(TAG, "Error scheduling timer: " + e.getMessage());
        }
    }

    private void stopBackgroundTimer() {
        try {
            Intent serviceIntent = new Intent(this, TimerService.class);
            serviceIntent.setAction("STOP_TIMER");
            startService(serviceIntent);
        } catch (Exception e) {
            Log.e(TAG, "Error stopping service: " + e.getMessage());
        }

        try {
            TimerService.cancelTimer(this);
        } catch (Exception e) {
            Log.e(TAG, "Error canceling timer: " + e.getMessage());
        }
    }

    private void loadActivities() {
        List<Activity> activities = databaseHelper.getActivitiesByDate(selectedDate);

        TimeAxisFragment timeAxisFragment = getTimeAxisFragment();
        if (timeAxisFragment != null && timeAxisFragment.getTimeAxisView() != null) {
            timeAxisFragment.getTimeAxisView().setActivities(activities);
        }

        ActivityRecordFragment activityRecordFragment = getActivityRecordFragment();
        if (activityRecordFragment != null) {
            activityRecordFragment.setActivities(activities);
            if (activityRecordFragment.getEmptyView() != null) {
                activityRecordFragment.getEmptyView().setVisibility(
                        activities.isEmpty() ? View.VISIBLE : View.GONE);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 确保活动记录页监听器已注入、状态已恢复。
        // 关键场景：Activity 重建后 ViewPager2 直接恢复在活动记录页（而非"切换"过去），
        // onPageSelected 不触发，此时 initActivityRecordPage 只在 onResume 里被调用。
        initActivityRecordPage();
        // AI 助手可能增删了活动，返回主页时刷新列表
        loadActivities();
        // 每次打开页面自动确保当天总结已生成
        ensureTodaySummary();
        // 评分页数据可能已变化（今日总结生成/编辑活动），页面恢复时刷新
        ScoreFragment scoreFragment = getScoreFragment();
        if (scoreFragment != null) {
            scoreFragment.refresh();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopTicker();
        summaryGenerator.shutdown();
    }
}

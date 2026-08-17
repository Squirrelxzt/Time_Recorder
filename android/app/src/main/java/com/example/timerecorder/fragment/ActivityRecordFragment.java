package com.example.timerecorder.fragment;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import java.util.List;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;

import com.example.timerecorder.R;
import com.example.timerecorder.model.Activity;
import com.example.timerecorder.model.DailySummary;
import com.example.timerecorder.model.TimerSlot;

public class ActivityRecordFragment extends Fragment {

    /** 活动记录页的业务回调，由 MainActivity 实现。 */
    public interface Listener {
        void onStartSlotClick(String name);
        void onEndSlotClick(int slotIndex);
        void onStartTimeClick();
        void onEndTimeClick();
        void onSaveActivityClick();
        void onStartDateClick();
        void onEndDateClick();
        void onActivityClick(Activity activity, int position);
        void onActivityLongClick(Activity activity, int position);
        void onSummaryCardClick();
    }

    /** 今日总结卡片状态。 */
    public static final int SUMMARY_HIDDEN = 0;
    public static final int SUMMARY_LOADING = 1;
    public static final int SUMMARY_SUCCESS = 2;
    public static final int SUMMARY_NOT_CONFIGURED = 3;
    public static final int SUMMARY_ERROR = 4;

    /** 视图就绪回调：Fragment 创建晚于宿主页面切换回调，宿主借此在就绪后再加载数据/初始化。 */
    public interface ViewReadyCallback {
        void onViewReady();
    }

    private LinearLayout llSlots;
    private TextInputEditText etManualActivityName;
    private Button btnStartTime, btnEndTime, btnSaveActivity, btnStartDate, btnEndDate;
    private View llEmpty;
    /** 今日活动容器。用 LinearLayout 直接渲染（数据量小），规避 ScrollView 内嵌
        wrap_content RecyclerView 高度被截断、只显示第一条的问题。 */
    private LinearLayout llActivities;

    // 今日总结卡片（默认 gone，由 showSummary 控制）
    private MaterialCardView cardDailySummary;
    private TextView tvSummaryScore, tvSummaryText, tvSummaryStatus;

    // 与时间轴活动块共用同一套颜色，保证视觉一致
    private static final int[] COLOR_RES_IDS = new int[] {
            R.color.activity_color_1,
            R.color.activity_color_2,
            R.color.activity_color_3,
            R.color.activity_color_4,
            R.color.activity_color_5,
            R.color.activity_color_6
    };

    /** 空闲槽位输入框的草稿文本，renderSlots 重渲染时回填（旋转/切页不丢输入）。 */
    private String idleDraftName = "";

    private Listener listener;
    private ViewReadyCallback viewReadyCallback;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_activity_record, container, false);

        llSlots = view.findViewById(R.id.ll_slots);
        etManualActivityName = view.findViewById(R.id.et_manual_activity_name);
        btnStartTime = view.findViewById(R.id.btn_start_time);
        btnEndTime = view.findViewById(R.id.btn_end_time);
        btnSaveActivity = view.findViewById(R.id.btn_save_activity);
        btnStartDate = view.findViewById(R.id.btn_start_date);
        btnEndDate = view.findViewById(R.id.btn_end_date);
        llEmpty = view.findViewById(R.id.ll_empty);
        llActivities = view.findViewById(R.id.rv_activities);
        cardDailySummary = view.findViewById(R.id.card_daily_summary);
        tvSummaryScore = view.findViewById(R.id.tv_summary_score);
        tvSummaryText = view.findViewById(R.id.tv_summary_text);
        tvSummaryStatus = view.findViewById(R.id.tv_summary_status);

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // 按钮/列表点击在 Fragment 视图创建后绑定，与 Fragment 生命周期绑定，
        // 避免因 MainActivity 的页面切换回调时机/时序问题导致监听器丢失（点击无响应）。
        bindListeners();

        // 总结卡片点击 → 重新生成（未配置/失败/成功均可重试）
        cardDailySummary.setOnClickListener(v -> {
            Listener l = resolveListener();
            if (l != null) l.onSummaryCardClick();
        });

        // 通知宿主视图已就绪：Fragment 创建可能晚于页面切换回调，
        // 由宿主在此时机补加载数据与初始化，避免首次进入时列表为空。
        ViewReadyCallback callback = viewReadyCallback;
        if (callback == null && getActivity() instanceof ViewReadyCallback) {
            callback = (ViewReadyCallback) getActivity();
        }
        if (callback != null) {
            callback.onViewReady();
        }
    }

    public void setViewReadyCallback(ViewReadyCallback viewReadyCallback) {
        this.viewReadyCallback = viewReadyCallback;
    }

    private void bindListeners() {
        btnStartTime.setOnClickListener(v -> {
            Listener l = resolveListener();
            if (l != null) l.onStartTimeClick();
        });
        btnEndTime.setOnClickListener(v -> {
            Listener l = resolveListener();
            if (l != null) l.onEndTimeClick();
        });
        btnSaveActivity.setOnClickListener(v -> {
            Listener l = resolveListener();
            if (l != null) l.onSaveActivityClick();
        });
        btnStartDate.setOnClickListener(v -> {
            Listener l = resolveListener();
            if (l != null) l.onStartDateClick();
        });
        btnEndDate.setOnClickListener(v -> {
            Listener l = resolveListener();
            if (l != null) l.onEndDateClick();
        });
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * 优先使用已注入的监听器；若尚未注入（冷启动首次进入本页时 Fragment 晚于
     * onPageSelected 创建，注入可能错过），回退到宿主 Activity 的 Listener 实现。
     */
    private Listener resolveListener() {
        if (listener != null) {
            return listener;
        }
        if (getActivity() instanceof Listener) {
            return (Listener) getActivity();
        }
        return null;
    }

    /**
     * 全量重建槽位列表：前 N 个为运行卡（与 activeSlots 顺序一致），末尾固定一个空闲卡。
     * 幂等（removeAllViews），重复调用安全。
     */
    public void renderSlots(@NonNull List<TimerSlot> slots) {
        llSlots.removeAllViews();
        LayoutInflater inflater = getLayoutInflater();

        // 运行卡
        for (int i = 0; i < slots.size(); i++) {
            TimerSlot slot = slots.get(i);
            View item = inflater.inflate(R.layout.item_timer_slot, llSlots, false);
            View running = item.findViewById(R.id.ll_slot_running);
            View idle = item.findViewById(R.id.ll_slot_idle);
            running.setVisibility(View.VISIBLE);
            idle.setVisibility(View.GONE);
            ((TextView) item.findViewById(R.id.tv_slot_name)).setText(slot.name);
            ((TextView) item.findViewById(R.id.tv_slot_timer)).setText(formatElapsed(now() - slot.startTime));
            final int index = i;
            item.findViewById(R.id.btn_slot_end).setOnClickListener(v -> {
                Listener l = resolveListener();
                if (l != null) l.onEndSlotClick(index);
            });
            llSlots.addView(item);
        }

        // 空闲卡（模型不含空闲项，无条件渲染一个）
        View idleItem = inflater.inflate(R.layout.item_timer_slot, llSlots, false);
        View running = idleItem.findViewById(R.id.ll_slot_running);
        View idle = idleItem.findViewById(R.id.ll_slot_idle);
        running.setVisibility(View.GONE);
        idle.setVisibility(View.VISIBLE);
        TextInputEditText et = idleItem.findViewById(R.id.et_slot_name);
        // 先回填再挂 watcher，避免 setText 触发回调覆盖草稿
        et.setText(idleDraftName == null ? "" : idleDraftName);
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                idleDraftName = s == null ? "" : s.toString();
            }
        });
        idleItem.findViewById(R.id.btn_slot_start).setOnClickListener(v -> {
            String name = idleDraftName == null ? "" : idleDraftName.trim();
            if (!name.isEmpty()) {
                // 名称非空说明槽位被消费，清空草稿让重渲染后的空闲卡为空
                idleDraftName = "";
            }
            Listener l = resolveListener();
            if (l != null) l.onStartSlotClick(name);
        });
        llSlots.addView(idleItem);
    }

    /**
     * 只更新运行卡的计时文本，不重建视图（避免打断输入/闪烁）。
     * 依赖渲染约定：容器前 N 个 child 与 activeSlots 一一对应，由宿主先 renderSlots 保证。
     */
    public void updateSlotTimers(@NonNull List<TimerSlot> slots) {
        int count = Math.min(slots.size(), llSlots.getChildCount());
        for (int i = 0; i < count; i++) {
            View item = llSlots.getChildAt(i);
            TextView tv = item.findViewById(R.id.tv_slot_timer);
            if (tv == null) {
                continue;
            }
            long elapsed = now() - slots.get(i).startTime;
            tv.setText(formatElapsed(elapsed < 0 ? 0 : elapsed));
        }
    }

    /** 运行时长格式化：满 1 小时显示 H:MM:SS，否则 MM:SS。 */
    private static String formatElapsed(long millis) {
        long totalSecs = millis / 1000;
        long h = totalSecs / 3600;
        long m = (totalSecs % 3600) / 60;
        long s = totalSecs % 60;
        if (h > 0) {
            return String.format("%d:%02d:%02d", h, m, s);
        }
        return String.format("%02d:%02d", m, s);
    }

    private long now() {
        return System.currentTimeMillis();
    }

    public TextInputEditText getEtManualActivityName() {
        return etManualActivityName;
    }

    public Button getBtnStartTime() {
        return btnStartTime;
    }

    public Button getBtnEndTime() {
        return btnEndTime;
    }

    public Button getBtnSaveActivity() {
        return btnSaveActivity;
    }

    public Button getBtnStartDate() {
        return btnStartDate;
    }

    public Button getBtnEndDate() {
        return btnEndDate;
    }

    public View getEmptyView() {
        return llEmpty;
    }

    public LinearLayout getLlActivities() {
        return llActivities;
    }

    /**
     * 渲染今日活动列表（LinearLayout 直接 addView）。
     * 数据量小（一天的条数），无需 RecyclerView 复用，规避其在 ScrollView 内
     * wrap_content 高度被截断、只显示第一条的问题。
     */
    public void setActivities(@NonNull List<Activity> activities) {
        llActivities.removeAllViews();
        LayoutInflater inflater = getLayoutInflater();
        for (int i = 0; i < activities.size(); i++) {
            Activity activity = activities.get(i);
            View item = inflater.inflate(R.layout.item_activity, llActivities, false);

            ((TextView) item.findViewById(R.id.tv_activity_name)).setText(activity.getName());
            ((TextView) item.findViewById(R.id.tv_activity_time)).setText(
                    activity.getStartTimeString() + " - " + activity.getEndTimeString());
            ((TextView) item.findViewById(R.id.tv_activity_duration)).setText(activity.getDurationString());

            int color = ContextCompat.getColor(requireContext(), COLOR_RES_IDS[i % COLOR_RES_IDS.length]);
            item.findViewById(R.id.view_color_bar)
                    .setBackgroundTintList(ColorStateList.valueOf(color));

            final int position = i;
            item.setOnClickListener(v -> {
                Listener l = resolveListener();
                if (l != null) l.onActivityClick(activity, position);
            });
            item.setOnLongClickListener(v -> {
                Listener l = resolveListener();
                if (l != null) {
                    l.onActivityLongClick(activity, position);
                    return true;
                }
                return false;
            });

            llActivities.addView(item);
        }
    }

    /** 按状态展示今日总结卡片。state 见 SUMMARY_* 常量。 */
    public void showSummary(int state, DailySummary summary) {
        if (cardDailySummary == null) {
            return;
        }
        switch (state) {
            case SUMMARY_HIDDEN:
                cardDailySummary.setVisibility(View.GONE);
                break;
            case SUMMARY_LOADING:
                cardDailySummary.setVisibility(View.VISIBLE);
                tvSummaryScore.setText("…");
                tvSummaryText.setText("");
                tvSummaryStatus.setText("正在生成今日总结…");
                break;
            case SUMMARY_SUCCESS:
                cardDailySummary.setVisibility(View.VISIBLE);
                if (summary != null) {
                    tvSummaryScore.setText(String.valueOf(summary.getScore()));
                    tvSummaryText.setText(summary.getSummary());
                } else {
                    tvSummaryScore.setText("—");
                    tvSummaryText.setText("");
                }
                tvSummaryStatus.setText("由 AI 生成 · 点击重新生成");
                break;
            case SUMMARY_NOT_CONFIGURED:
                cardDailySummary.setVisibility(View.VISIBLE);
                tvSummaryScore.setText("—");
                tvSummaryText.setText("");
                tvSummaryStatus.setText("AI 未配置，点击重试");
                break;
            case SUMMARY_ERROR:
                cardDailySummary.setVisibility(View.VISIBLE);
                tvSummaryScore.setText("—");
                tvSummaryText.setText("");
                tvSummaryStatus.setText("生成失败，点击重试");
                break;
            default:
                break;
        }
    }
}

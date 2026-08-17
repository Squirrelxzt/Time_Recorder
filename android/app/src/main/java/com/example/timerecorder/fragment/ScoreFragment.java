package com.example.timerecorder.fragment;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import com.example.timerecorder.R;
import com.example.timerecorder.database.DatabaseHelper;
import com.example.timerecorder.model.DailySummary;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 评分视图：本周/本月每日评分热力格 + 平均分。
 * 数据来自 daily_summaries（由活动记录页的今日总结生成）。
 * 点击有评分的格子显示该天详情。
 */
public class ScoreFragment extends Fragment {

    private DatabaseHelper db;

    private MaterialButton btnWeek, btnMonth;
    private TextView tvAvgScore, tvAvgHint;
    private GridLayout grid;
    private LinearLayout llWeekHeader;
    private View llEmpty;
    private MaterialCardView cardDetail;
    private TextView tvDetail;

    private boolean weekMode = true;
    private List<DailySummary> current = new ArrayList<>();
    private Map<Long, DailySummary> summaryByDate = new HashMap<>();

    // 热力格配色
    private static final int SCORE_LOW = 0xFFE57373;    // 0-39 红
    private static final int SCORE_MID = 0xFFFFB74D;    // 40-69 橙
    private static final int SCORE_HIGH = 0xFF81C784;   // 70-100 绿
    private static final int CELL_EMPTY_BG = 0xFFF1F3FA;
    private static final int CELL_FUTURE_BG = 0xFFFAFBFE;
    private static final int CELL_BORDER = 0xFFE1E5EF;
    private static final int CELL_TODAY_BORDER = 0xFF3D5AFE;

    private static final String[] WEEK_LABELS = {"一", "二", "三", "四", "五", "六", "日"};

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_score, container, false);
        btnWeek = view.findViewById(R.id.btn_score_week);
        btnMonth = view.findViewById(R.id.btn_score_month);
        tvAvgScore = view.findViewById(R.id.tv_avg_score);
        tvAvgHint = view.findViewById(R.id.tv_avg_hint);
        grid = view.findViewById(R.id.grid_score);
        llWeekHeader = view.findViewById(R.id.ll_week_header);
        llEmpty = view.findViewById(R.id.ll_score_empty);
        cardDetail = view.findViewById(R.id.card_detail);
        tvDetail = view.findViewById(R.id.tv_detail);
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        db = new DatabaseHelper(requireContext());

        btnWeek.setOnClickListener(v -> setWeekMode(true));
        btnMonth.setOnClickListener(v -> setWeekMode(false));

        renderWeekHeader();
        updateButtonStyles();
        refresh();
    }

    /** 供宿主在切到本页 / 页面恢复时调用，保证数据最新。 */
    public void refresh() {
        if (db == null || grid == null) {
            return;
        }
        Calendar start = weekStart();
        Calendar end = (Calendar) start.clone();
        end.add(Calendar.DAY_OF_MONTH, weekMode ? 6 : start.getActualMaximum(Calendar.DAY_OF_MONTH) - 1);
        end.set(Calendar.HOUR_OF_DAY, 23);
        end.set(Calendar.MINUTE, 59);
        end.set(Calendar.SECOND, 59);
        end.set(Calendar.MILLISECOND, 999);

        current = db.getSummariesBetween(start.getTimeInMillis(), end.getTimeInMillis());
        summaryByDate = new HashMap<>();
        for (DailySummary s : current) {
            summaryByDate.put(s.getDate(), s);
        }

        renderAverage();
        renderGrid(start);
        llEmpty.setVisibility(current.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** 当前周期起始日：本周一 / 本月 1 号（当天 00:00）。 */
    private Calendar weekStart() {
        Calendar c = Calendar.getInstance();
        if (weekMode) {
            // 回退到本周一（DAY_OF_WEEK: 周日=1..周六=7，周一为 0 索引）
            c.add(Calendar.DAY_OF_MONTH, -((c.get(Calendar.DAY_OF_WEEK) + 5) % 7));
        } else {
            c.set(Calendar.DAY_OF_MONTH, 1);
        }
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    private void renderAverage() {
        int sum = 0;
        int count = 0;
        for (DailySummary s : current) {
            sum += s.getScore();
            count++;
        }
        if (count == 0) {
            tvAvgScore.setText("—");
            tvAvgHint.setText(weekMode ? "本周暂无评分" : "本月暂无评分");
        } else {
            tvAvgScore.setText(String.valueOf(Math.round(sum / (float) count)));
            tvAvgHint.setText(count + " 天有记录");
        }
    }

    private void renderGrid(Calendar startDay) {
        grid.removeAllViews();
        grid.setColumnCount(7);

        if (weekMode) {
            for (int i = 0; i < 7; i++) {
                Calendar c = (Calendar) startDay.clone();
                c.add(Calendar.DAY_OF_MONTH, i);
                addCell(c, true);
            }
            return;
        }

        // 月模式：从本月 1 号所在周的周一开始，补足整周
        Calendar first = Calendar.getInstance();
        first.set(Calendar.DAY_OF_MONTH, 1);
        int offset = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        int daysInMonth = first.getActualMaximum(Calendar.DAY_OF_MONTH);
        int total = ((offset + daysInMonth + 6) / 7) * 7;

        Calendar start = (Calendar) startDay.clone();
        start.add(Calendar.DAY_OF_MONTH, -offset);
        for (int i = 0; i < total; i++) {
            Calendar c = (Calendar) start.clone();
            c.add(Calendar.DAY_OF_MONTH, i);
            boolean inMonth = c.get(Calendar.YEAR) == first.get(Calendar.YEAR)
                    && c.get(Calendar.MONTH) == first.get(Calendar.MONTH);
            addCell(c, inMonth);
        }
    }

    private void addCell(Calendar date, boolean interactive) {
        TextView tv = new TextView(requireContext());
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = 0;
        lp.height = dp(52);
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        lp.rowSpec = GridLayout.spec(GridLayout.UNDEFINED);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        tv.setLayoutParams(lp);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(14);
        tv.setText(String.valueOf(date.get(Calendar.DAY_OF_MONTH)));

        long dateOnly = getDateOnly(date);
        DailySummary s = summaryByDate.get(dateOnly);
        long todayOnly = getDateOnly(Calendar.getInstance());
        boolean isToday = dateOnly == todayOnly;
        boolean isFuture = dateOnly > todayOnly;

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        if (s != null) {
            bg.setColor(colorForScore(s.getScore()));
            tv.setTextColor(Color.WHITE);
        } else {
            tv.setTextColor(ContextCompat.getColor(requireContext(), R.color.textSecondary));
            bg.setColor(isFuture ? CELL_FUTURE_BG : CELL_EMPTY_BG);
            bg.setStroke(dp(1), CELL_BORDER);
        }
        if (isToday) {
            bg.setStroke(dp(2), CELL_TODAY_BORDER);
        }
        tv.setBackground(bg);

        // 非本月补位格不响应；否则有评分显示详情，无评分（含未来）显示"暂无评分"
        if (interactive) {
            final DailySummary fs = s;
            final String label = String.format("%d月%d日",
                    date.get(Calendar.MONTH) + 1, date.get(Calendar.DAY_OF_MONTH));
            tv.setOnClickListener(v -> showDetail(label, fs));
        }
        grid.addView(tv);
    }

    private void showDetail(String dateLabel, DailySummary s) {
        cardDetail.setVisibility(View.VISIBLE);
        if (s != null) {
            tvDetail.setText(dateLabel + "  评分 " + s.getScore() + "/100\n\n" + s.getSummary());
        } else {
            tvDetail.setText(dateLabel + "  当天暂无评分");
        }
    }

    private void renderWeekHeader() {
        llWeekHeader.removeAllViews();
        for (int i = 0; i < 7; i++) {
            TextView tv = new TextView(requireContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tv.setLayoutParams(lp);
            tv.setGravity(Gravity.CENTER);
            tv.setText(WEEK_LABELS[i]);
            tv.setTextSize(12);
            tv.setTextColor(ContextCompat.getColor(requireContext(), R.color.textSecondary));
            llWeekHeader.addView(tv);
        }
    }

    private void setWeekMode(boolean week) {
        if (weekMode == week) {
            return;
        }
        weekMode = week;
        updateButtonStyles();
        refresh();
    }

    private void updateButtonStyles() {
        int primary = ContextCompat.getColor(requireContext(), R.color.colorPrimary);
        int variant = ContextCompat.getColor(requireContext(), R.color.surfaceVariant);
        int textPrimary = ContextCompat.getColor(requireContext(), R.color.textPrimary);
        int white = ContextCompat.getColor(requireContext(), R.color.white);

        btnWeek.setBackgroundTintList(ColorStateList.valueOf(weekMode ? primary : variant));
        btnWeek.setTextColor(weekMode ? white : textPrimary);
        btnMonth.setBackgroundTintList(ColorStateList.valueOf(weekMode ? variant : primary));
        btnMonth.setTextColor(weekMode ? textPrimary : white);
    }

    private int colorForScore(int score) {
        if (score < 40) {
            return SCORE_LOW;
        }
        if (score < 70) {
            return SCORE_MID;
        }
        return SCORE_HIGH;
    }

    private long getDateOnly(Calendar c) {
        Calendar temp = (Calendar) c.clone();
        temp.set(Calendar.HOUR_OF_DAY, 0);
        temp.set(Calendar.MINUTE, 0);
        temp.set(Calendar.SECOND, 0);
        temp.set(Calendar.MILLISECOND, 0);
        return temp.getTimeInMillis();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

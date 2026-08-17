package com.example.timerecorder.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;
import android.widget.Scroller;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.example.timerecorder.R;
import com.example.timerecorder.model.Activity;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 竖向 24 小时时间轴：时间自上而下流动（0:00 → 24:00）。
 * - 左侧固定时间刻度列，右侧为活动区；
 * - 活动块 top/bottom 由开始/结束时间映射，重叠活动按"连通分组"左右分层；
 * - 名称横排居中，字号随块大小自适应；
 * - "现在"时刻红色横线贯穿活动区；
 * - 支持垂直滚动 / 惯性滚动 / 双指缩放时间密度。
 */
public class VerticalTimeAxisView extends View {

    private static final String TAG = "TimeAxisView";

    private static final int HOURS_IN_DAY = 24;
    private static final float TIME_COL_WIDTH = 120f;      // 左侧时间刻度列宽
    private static final float PADDING_TOP = 10f;
    private static final float PADDING_BOTTOM = 10f;
    private static final float CORNER_RADIUS = 12f;        // 活动块圆角
    private static final float BLOCK_MIN_HEIGHT = 8f;      // 活动块最小可见高度
    private static final int SHADOW_COLOR = 0x26000000;

    // 字号（px，440dpi 下 ~2.75 density，40px ≈ 14.5sp）
    private static final float HOUR_TEXT_SIZE = 34f;       // 小时刻度标签
    private static final float NOW_TEXT_SIZE = 30f;        // 现在时刻标签
    private static final float BLOCK_TEXT_MAX = 40f;       // 活动块文字上限

    private Paint gridPaint;        // 整点横线
    private Paint halfGridPaint;    // 半点横线
    private Paint colBgPaint;       // 时间刻度列背景
    private Paint colDividerPaint;  // 刻度列分隔线
    private Paint hourTextPaint;    // 小时标签
    private Paint activityPaint;    // 活动块（渐变）
    private Paint activityTextPaint;// 活动名称
    private Paint nowPaint;         // 现在线
    private Paint nowTextPaint;     // 现在时间文本

    private List<Activity> activities;
    private int[] activityColors;
    private int activityTextColor;
    private int nowColor;

    /** 视图高映射 24h 的基准每像素小时（scale=1 时正好一屏）。 */
    private float basePxPerHour;
    /** 实际每像素小时 = basePxPerHour * scale。 */
    private float pxPerHour;
    private float scale = 1.0f;
    /** 垂直滚动偏移（0:00 在顶部）。 */
    private float translationY = 0f;
    private float lastY = 0f;
    private boolean isScrolling = false;
    private Scroller scroller;
    private VelocityTracker velocityTracker;
    private ScaleGestureDetector scaleGestureDetector;

    public VerticalTimeAxisView(Context context) {
        super(context);
        init();
    }

    public VerticalTimeAxisView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public VerticalTimeAxisView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        activities = new ArrayList<>();

        // 活动块需要软阴影，硬件加速下 API<28 不支持 setShadowLayer，统一走软件层。
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);

        // 刻度列背景 + 分隔线
        colBgPaint = new Paint();
        colBgPaint.setColor(ContextCompat.getColor(getContext(), R.color.axisHeaderBg));
        colDividerPaint = new Paint();
        colDividerPaint.setColor(ContextCompat.getColor(getContext(), R.color.axisHeaderDivider));
        colDividerPaint.setStrokeWidth(1f);

        // 整点 / 半点横线
        gridPaint = new Paint();
        gridPaint.setColor(ContextCompat.getColor(getContext(), R.color.axisLine));
        gridPaint.setStrokeWidth(1.5f);
        gridPaint.setAntiAlias(true);
        halfGridPaint = new Paint();
        halfGridPaint.setColor(ContextCompat.getColor(getContext(), R.color.axisMinorTick));
        halfGridPaint.setStrokeWidth(1f);
        halfGridPaint.setAntiAlias(true);

        // 小时标签
        hourTextPaint = new Paint();
        hourTextPaint.setColor(ContextCompat.getColor(getContext(), R.color.axisText));
        hourTextPaint.setTextSize(HOUR_TEXT_SIZE);
        hourTextPaint.setAntiAlias(true);

        // 活动块
        activityPaint = new Paint();
        activityPaint.setAntiAlias(true);
        activityPaint.setShadowLayer(6f, 0f, 2f, SHADOW_COLOR);

        activityTextPaint = new Paint();
        activityTextPaint.setAntiAlias(true);
        activityTextPaint.setColor(Color.WHITE);
        activityTextPaint.setShadowLayer(2f, 0f, 1f, 0x66000000);
        activityTextColor = ContextCompat.getColor(getContext(), R.color.axisTextOnBlock);

        // 现在线
        nowColor = ContextCompat.getColor(getContext(), R.color.axisNowLine);
        nowPaint = new Paint();
        nowPaint.setColor(nowColor);
        nowPaint.setStrokeWidth(3f);
        nowPaint.setAntiAlias(true);
        nowTextPaint = new Paint();
        nowTextPaint.setColor(nowColor);
        nowTextPaint.setTextSize(NOW_TEXT_SIZE);
        nowTextPaint.setAntiAlias(true);
        nowTextPaint.setFakeBoldText(true);

        activityColors = new int[] {
            ContextCompat.getColor(getContext(), R.color.activity_color_1),
            ContextCompat.getColor(getContext(), R.color.activity_color_2),
            ContextCompat.getColor(getContext(), R.color.activity_color_3),
            ContextCompat.getColor(getContext(), R.color.activity_color_4),
            ContextCompat.getColor(getContext(), R.color.activity_color_5),
            ContextCompat.getColor(getContext(), R.color.activity_color_6)
        };

        scroller = new Scroller(getContext());

        scaleGestureDetector = new ScaleGestureDetector(getContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float newScale = scale * detector.getScaleFactor();
                // 限制缩放范围
                if (newScale >= 0.8f && newScale <= 6.0f) {
                    // 以视图中心为基准进行缩放
                    float centerY = getHeight() / 2f;
                    float oldPxPerHour = basePxPerHour * scale;
                    float newPxPerHour = basePxPerHour * newScale;

                    // 计算中心点对应的小时位置
                    float centerHour = (centerY - PADDING_TOP - translationY) / oldPxPerHour;

                    scale = newScale;
                    pxPerHour = basePxPerHour * scale;

                    // 调整 translationY，使中心点保持不变
                    translationY = centerY - PADDING_TOP - centerHour * newPxPerHour;

                    limitScroll();
                    invalidate();
                }
                return true;
            }
        });
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // 默认一屏容纳完整 24 小时
        basePxPerHour = (getHeight() - PADDING_TOP - PADDING_BOTTOM) / HOURS_IN_DAY;
        pxPerHour = basePxPerHour * scale;
    }

    public void setActivities(List<Activity> activities) {
        this.activities = activities != null ? activities : new ArrayList<>();
        Log.d(TAG, "setActivities size=" + this.activities.size());
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();

        // 时间刻度列背景 + 分隔线
        canvas.drawRect(0, 0, TIME_COL_WIDTH, height, colBgPaint);
        canvas.drawLine(TIME_COL_WIDTH, 0, TIME_COL_WIDTH, height, colDividerPaint);

        // 小时标签 + 整点横线
        for (int hour = 0; hour <= HOURS_IN_DAY; hour++) {
            float y = PADDING_TOP + hour * pxPerHour + translationY;
            if (y < -60 || y > height + 60) {
                continue;
            }
            canvas.drawLine(TIME_COL_WIDTH, y, width, y, gridPaint);
            String label = hour + ":00";
            float textWidth = hourTextPaint.measureText(label);
            // 右对齐到刻度列分隔线前
            canvas.drawText(label, TIME_COL_WIDTH - 16 - textWidth, y - 8, hourTextPaint);
        }

        // 半点细线（贯穿活动区，极淡）
        for (int minute = 0; minute < HOURS_IN_DAY * 60; minute += 30) {
            if (minute % 60 == 0) {
                continue;
            }
            float y = PADDING_TOP + (minute / 60f) * pxPerHour + translationY;
            if (y < 0 || y > height) {
                continue;
            }
            canvas.drawLine(TIME_COL_WIDTH, y, width, y, halfGridPaint);
        }

        // 现在时刻
        drawNowLine(canvas);

        // 活动块
        drawActivities(canvas);
    }

    /** 现在时刻红色横线：贯穿活动区，左侧列标注 "HH:mm"。 */
    private void drawNowLine(Canvas canvas) {
        Calendar now = Calendar.getInstance();
        float nowFrac = now.get(Calendar.HOUR_OF_DAY) + now.get(Calendar.MINUTE) / 60f;
        float y = PADDING_TOP + nowFrac * pxPerHour + translationY;
        if (y < -40 || y > getHeight() + 40) {
            return;
        }
        canvas.drawLine(TIME_COL_WIDTH, y, getWidth(), y, nowPaint);

        // 左侧列：红点 + "HH:mm"
        String label = String.format("%02d:%02d", now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE));
        float textWidth = nowTextPaint.measureText(label);
        canvas.drawCircle(TIME_COL_WIDTH - 20, y, 5f, nowPaint);
        canvas.drawText(label, TIME_COL_WIDTH - 20 - textWidth - 6, y + NOW_TEXT_SIZE * 0.35f, nowTextPaint);
    }

    private void drawActivities(Canvas canvas) {
        int n = activities.size();
        if (n == 0) {
            return;
        }
        int width = getWidth();
        int height = getHeight();
        float areaWidth = width - TIME_COL_WIDTH;

        // 1. 并查集：把直接/间接重叠的活动归为同一组（横向共享宽度）
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (activitiesOverlap(activities.get(i), activities.get(j))) {
                    int ri = findRoot(parent, i);
                    int rj = findRoot(parent, j);
                    if (ri != rj) {
                        parent[ri] = rj;
                    }
                }
            }
        }

        // 2. 每组内部按开始时间贪心分层（横向列）
        Map<Integer, Integer> groupLayerCount = new HashMap<>();
        int[] localLayer = new int[n];
        Map<Integer, List<Integer>> groups = new HashMap<>();
        for (int i = 0; i < n; i++) {
            int root = findRoot(parent, i);
            groups.computeIfAbsent(root, k -> new ArrayList<>()).add(i);
        }
        for (Map.Entry<Integer, List<Integer>> entry : groups.entrySet()) {
            List<Integer> members = entry.getValue();
            List<Integer> sorted = new ArrayList<>(members);
            sorted.sort((i1, i2) -> Integer.compare(startMinute(activities.get(i1)),
                    startMinute(activities.get(i2))));
            List<Integer> layerLastIndex = new ArrayList<>();
            int maxLayer = 0;
            for (int idx : sorted) {
                int layer = 0;
                while (layer < layerLastIndex.size()
                        && activitiesOverlap(activities.get(idx),
                                activities.get(layerLastIndex.get(layer)))) {
                    layer++;
                }
                if (layer >= layerLastIndex.size()) {
                    layerLastIndex.add(idx);
                } else {
                    layerLastIndex.set(layer, idx);
                }
                localLayer[idx] = layer;
                maxLayer = Math.max(maxLayer, layer);
            }
            groupLayerCount.put(entry.getKey(), maxLayer + 1);
        }

        // 3. 绘制
        for (int i = 0; i < n; i++) {
            Activity activity = activities.get(i);
            int root = findRoot(parent, i);
            int layerCount = groupLayerCount.get(root);
            int layer = localLayer[i];

            float startFrac = startMinute(activity) / 60f;
            float endFrac;
            if (activity.getEndHour() == 24 && activity.getEndMinute() == 0) {
                endFrac = HOURS_IN_DAY;
            } else {
                endFrac = (activity.getEndHour() * 60 + activity.getEndMinute()) / 60f;
            }

            float top = PADDING_TOP + startFrac * pxPerHour + translationY;
            float bottom = PADDING_TOP + endFrac * pxPerHour + translationY;
            // 裁剪：完全在可视范围外的跳过
            if (bottom < -20 || top > height + 20) {
                continue;
            }
            // 时长过短保证最小可见高度
            if (bottom - top < BLOCK_MIN_HEIGHT) {
                bottom = top + BLOCK_MIN_HEIGHT;
            }

            float blockWidth = areaWidth / layerCount;
            float left = TIME_COL_WIDTH + layer * blockWidth + 4f;
            float right = left + blockWidth - 8f;

            int color = activityColors[i % activityColors.length];
            // 垂直渐变：上部为基色提亮，下部为基色
            int topColor = ColorUtils.blendARGB(color, Color.WHITE, 0.30f);
            activityPaint.setShader(new LinearGradient(
                    left, top, left, bottom, topColor, color, Shader.TileMode.CLAMP));

            RectF rect = new RectF(left, top, right, bottom);
            canvas.drawRoundRect(rect, CORNER_RADIUS, CORNER_RADIUS, activityPaint);
            activityPaint.setShader(null);

            float blockW = right - left;
            float blockH = bottom - top;
            if (blockW > 34 && blockH > 26) {
                drawActivityName(canvas, activity.getName(), left, top, blockW, blockH);
            }
        }
    }

    /**
     * 活动名称横排居中：字号随块宽/高自适应（上限较横向版提高，解决"字体小"），
     * 超宽逐字符截断加省略号。
     */
    private void drawActivityName(Canvas canvas, String name, float left, float top, float blockW, float blockH) {
        float textSize = Math.min(BLOCK_TEXT_MAX, Math.min(blockW * 0.20f, blockH * 0.45f));
        textSize = Math.max(13f, textSize);
        activityTextPaint.setTextSize(textSize);
        activityTextPaint.setColor(activityTextColor);

        float availW = blockW - 12f;
        String ellipsis = "…";
        float ellipsisW = activityTextPaint.measureText(ellipsis);

        String text = name;
        float textW = activityTextPaint.measureText(text);
        if (textW > availW) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < name.length(); i++) {
                sb.append(name.charAt(i));
                if (activityTextPaint.measureText(sb.toString()) + ellipsisW > availW) {
                    sb.deleteCharAt(sb.length() - 1);
                    sb.append(ellipsis);
                    break;
                }
            }
            text = sb.toString();
        }

        float textW2 = activityTextPaint.measureText(text);
        float x = left + (blockW - textW2) / 2f;
        Paint.FontMetrics fm = activityTextPaint.getFontMetrics();
        float baseline = top + (blockH - (fm.descent - fm.ascent)) / 2f - fm.ascent;
        canvas.drawText(text, x, baseline, activityTextPaint);
    }

    /** 并查集查找根节点（带路径压缩）。 */
    private int findRoot(int[] parent, int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]];
            x = parent[x];
        }
        return x;
    }

    /** 活动开始时刻的分钟数（按天计），用于排序。 */
    private int startMinute(Activity a) {
        return a.getStartHour() * 60 + a.getStartMinute();
    }

    private boolean activitiesOverlap(Activity a1, Activity a2) {
        int start1 = a1.getStartHour() * 60 + a1.getStartMinute();
        int end1 = a1.getEndHour() * 60 + a1.getEndMinute();
        int start2 = a2.getStartHour() * 60 + a2.getStartMinute();
        int end2 = a2.getEndHour() * 60 + a2.getEndMinute();

        return !(end1 <= start2 || start1 >= end2);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // 最小高度：能容纳完整 24 小时
        int desiredHeight = (int) (PADDING_TOP + PADDING_BOTTOM + HOURS_IN_DAY * 48f);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        int height;
        if (heightMode == MeasureSpec.EXACTLY) {
            height = heightSize;
        } else if (heightMode == MeasureSpec.AT_MOST) {
            height = Math.min(desiredHeight, heightSize);
        } else {
            height = desiredHeight;
        }

        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleGestureDetector.onTouchEvent(event);
        if (scaleGestureDetector.isInProgress()) {
            return true;
        }

        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain();
        }
        velocityTracker.addMovement(event);

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (!scroller.isFinished()) {
                    scroller.abortAnimation();
                }
                lastY = event.getY();
                isScrolling = true;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (isScrolling) {
                    float deltaY = event.getY() - lastY;
                    translationY += deltaY;
                    limitScroll();
                    lastY = event.getY();
                    invalidate();
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                isScrolling = false;
                velocityTracker.computeCurrentVelocity(1000);
                int velocityY = (int) velocityTracker.getYVelocity();
                fling(velocityY);
                velocityTracker.recycle();
                velocityTracker = null;
                break;
        }

        return super.onTouchEvent(event);
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            translationY = scroller.getCurrY();
            limitScroll();
            invalidate();
        }
    }

    private void fling(int velocityY) {
        float maxScroll = Math.max(0, HOURS_IN_DAY * pxPerHour - (getHeight() - PADDING_TOP - PADDING_BOTTOM));
        scroller.fling(
            0,
            (int) translationY,
            0,
            velocityY,
            0,
            0,
            (int) -maxScroll,
            0
        );
        invalidate();
    }

    private void limitScroll() {
        float maxScroll = Math.max(0, HOURS_IN_DAY * pxPerHour - (getHeight() - PADDING_TOP - PADDING_BOTTOM));
        translationY = Math.max(-maxScroll, Math.min(translationY, 0));
    }
}

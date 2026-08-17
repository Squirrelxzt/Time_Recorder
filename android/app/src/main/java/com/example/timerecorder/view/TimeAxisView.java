package com.example.timerecorder.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.example.timerecorder.model.Activity;

import java.util.ArrayList;
import java.util.List;

public class TimeAxisView extends View {

    private Paint axisPaint;
    private Paint textPaint;
    private Paint activityPaint;
    private List<Activity> activities;
    private int[] activityColors;
    
    private static final int HOURS_IN_DAY = 24;
    private static final int MINUTE_HEIGHT = 20; // 每分钟的高度
    private static final int AXIS_WIDTH = 80; // 时间轴宽度
    private static final int ACTIVITY_MARGIN = 10; // 活动块左右边距
    private static final int ACTIVITY_HEIGHT = 60; // 活动块高度
    
    private float scale = 1.0f; // 缩放比例
    private float translationY = 0; // 垂直滚动位置
    private float lastY = 0; // 上次触摸位置
    private boolean isScrolling = false; // 是否正在滚动
    private float[] hourLabelWidths; // 缓存小时标签宽度，避免每帧 measureText

    private ScaleGestureDetector scaleGestureDetector;
    
    public TimeAxisView(Context context) {
        super(context);
        init();
    }
    
    public TimeAxisView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    public TimeAxisView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }
    
    private void init() {
        activities = new ArrayList<>();
        
        axisPaint = new Paint();
        axisPaint.setColor(Color.parseColor("#333333"));
        axisPaint.setStrokeWidth(3f);
        axisPaint.setAntiAlias(true);
        
        textPaint = new Paint();
        textPaint.setColor(Color.parseColor("#666666"));
        textPaint.setTextSize(20f);
        textPaint.setAntiAlias(true);
        
        activityPaint = new Paint();
        activityPaint.setAntiAlias(true);
        
        activityColors = new int[] {
            Color.parseColor("#4CAF50"),
            Color.parseColor("#2196F3"),
            Color.parseColor("#FF9800"),
            Color.parseColor("#9C27B0"),
            Color.parseColor("#F44336"),
            Color.parseColor("#00BCD4")
        };
        
        // 初始化缩放手势检测器
        scaleGestureDetector = new ScaleGestureDetector(getContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float newScale = scale * detector.getScaleFactor();
                // 限制缩放范围
                if (newScale >= 0.2f && newScale <= 3.0f) {
                    scale = newScale;
                    invalidate();
                }
                return true;
            }
        });

        // 小时标签固定样式（20sp），宽度只算一次；onDraw 每帧复用，避免 measureText 开销
        hourLabelWidths = new float[HOURS_IN_DAY + 1];
        for (int hour = 0; hour <= HOURS_IN_DAY; hour++) {
            hourLabelWidths[hour] = textPaint.measureText(hour + ":00");
        }
    }
    
    public void setActivities(List<Activity> activities) {
        this.activities = activities != null ? activities : new ArrayList<>();
        invalidate();
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        int width = getWidth();
        int height = getHeight();
        float minuteHeight = MINUTE_HEIGHT * scale;

        // 绘制时间轴线
        canvas.drawLine(AXIS_WIDTH, 0, AXIS_WIDTH, height, axisPaint);

        // 刻度与标签样式固定，显式恢复（drawActivities 会临时改动 textSize）
        textPaint.setColor(Color.parseColor("#666666"));
        textPaint.setTextSize(20f);

        // 绘制小时刻度和标签
        for (int hour = 0; hour <= HOURS_IN_DAY; hour++) {
            for (int minute = 0; minute < 60; minute += 15) { // 每15分钟一个刻度
                float y = (hour * 60 + minute) * minuteHeight + translationY;

                if (y >= 0 && y <= height) {
                    // 刻度线
                    int lineLength = (minute % 60 == 0) ? 20 : 10;
                    canvas.drawLine(AXIS_WIDTH - lineLength, y, AXIS_WIDTH, y, axisPaint);

                    // 小时标签（宽度已缓存）
                    if (minute == 0) {
                        float textWidth = hourLabelWidths[hour];
                        canvas.drawText(hour + ":00", AXIS_WIDTH - textWidth - 5, y + 6, textPaint);
                    }
                }
            }
        }
        
        // 绘制活动块
        drawActivities(canvas, minuteHeight);
    }
    
    private void drawActivities(Canvas canvas, float minuteHeight) {
        int width = getWidth();
        int activityWidth = width - AXIS_WIDTH - ACTIVITY_MARGIN * 2;
        
        for (int i = 0; i < activities.size(); i++) {
            Activity activity = activities.get(i);
            
            // 计算位置
            float startY = (activity.getStartHour() * 60 + activity.getStartMinute()) * minuteHeight + translationY;
            float endY = (activity.getEndHour() * 60 + activity.getEndMinute()) * minuteHeight + translationY;
            float activityBlockHeight = endY - startY;
            
            // 确保活动块在可视范围内
            if (endY > 0 && startY < getHeight()) {
                // 选择颜色
                int color = activityColors[i % activityColors.length];
                activityPaint.setColor(color);
                
                // 绘制活动块
                float top = Math.max(0, startY);
                float bottom = Math.min(getHeight(), endY);
                RectF rect = new RectF(
                    AXIS_WIDTH + ACTIVITY_MARGIN,
                    top,
                    AXIS_WIDTH + ACTIVITY_MARGIN + activityWidth,
                    bottom
                );
                canvas.drawRoundRect(rect, 8, 8, activityPaint);
                
                // 计算文字位置
                float textY = (top + bottom) / 2 + 6;
                
                // 绘制活动名称
                textPaint.setColor(Color.WHITE);
                textPaint.setTextSize(16f);
                String name = activity.getName();
                if (activityBlockHeight > 20) {
                    if (activityWidth > 80) {
                        if (textPaint.measureText(name) > activityWidth - 20) {
                            // 如果名称太长，截断显示
                            while (name.length() > 0 && textPaint.measureText(name + "...") > activityWidth - 20) {
                                name = name.substring(0, name.length() - 1);
                            }
                            name += "...";
                        }
                        canvas.drawText(name, AXIS_WIDTH + ACTIVITY_MARGIN + 10, textY, textPaint);
                    }
                }
                
                // 绘制时间标注
                textPaint.setColor(Color.BLACK);
                textPaint.setTextSize(14f);
                String timeRange = activity.getStartTimeString() + " - " + activity.getEndTimeString();
                if (activityBlockHeight > 30) {
                    canvas.drawText(timeRange, AXIS_WIDTH + ACTIVITY_MARGIN + 10, textY + 20, textPaint);
                }
            }
        }
        
        // 恢复文字颜色
        textPaint.setColor(Color.parseColor("#666666"));
    }
    
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredHeight = HOURS_IN_DAY * 60 * MINUTE_HEIGHT;
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
        // 先处理缩放手势
        boolean scaleHandled = scaleGestureDetector.onTouchEvent(event);
        
        // 如果正在缩放，不处理滑动
        if (scaleGestureDetector.isInProgress()) {
            return true;
        }
        
        // 处理滑动手势
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                lastY = event.getY();
                isScrolling = true;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (isScrolling) {
                    float deltaY = event.getY() - lastY;
                    translationY += deltaY;
                    // 限制滚动范围
                    float totalHeight = HOURS_IN_DAY * 60 * MINUTE_HEIGHT * scale;
                    float maxScroll = Math.max(0, totalHeight - getHeight());
                    translationY = Math.max(0, Math.min(translationY, maxScroll));
                    lastY = event.getY();
                    invalidate();
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                isScrolling = false;
                break;
        }
        
        return super.onTouchEvent(event);
    }
}
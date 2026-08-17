package com.example.timerecorder.service;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

import java.util.Calendar;

public class TimerService extends Service {

    private static final String TAG = "TimerService";
    private static final int ALARM_REQUEST_CODE = 1001;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if ("START_TIMER".equals(action)) {
                startTimer();
            } else if ("STOP_TIMER".equals(action)) {
                stopTimer();
            }
        }
        return START_STICKY;
    }

    private void startTimer() {
        Log.d(TAG, "Timer started");
        // 这里可以添加其他初始化逻辑
    }

    private void stopTimer() {
        Log.d(TAG, "Timer stopped");
        stopSelf();
    }

    public static void scheduleTimer(Context context) {
        try {
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager == null) {
                Log.e(TAG, "AlarmManager is null");
                return;
            }
            
            Intent intent = new Intent(context, TimerReceiver.class);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    context, ALARM_REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            // 每分钟触发一次
            Calendar calendar = Calendar.getInstance();
            calendar.add(Calendar.MINUTE, 1);

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, 
                        calendar.getTimeInMillis(), pendingIntent);
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, 
                        calendar.getTimeInMillis(), pendingIntent);
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, 
                        calendar.getTimeInMillis(), pendingIntent);
            }
            
            Log.d(TAG, "Alarm scheduled");
        } catch (Exception e) {
            Log.e(TAG, "Error scheduling alarm: " + e.getMessage());
        }
    }

    public static void cancelTimer(Context context) {
        try {
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager == null) {
                Log.e(TAG, "AlarmManager is null");
                return;
            }
            
            Intent intent = new Intent(context, TimerReceiver.class);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    context, ALARM_REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            alarmManager.cancel(pendingIntent);
            Log.d(TAG, "Alarm canceled");
        } catch (Exception e) {
            Log.e(TAG, "Error canceling alarm: " + e.getMessage());
        }
    }
}
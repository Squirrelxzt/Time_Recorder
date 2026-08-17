package com.example.timerecorder.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.example.timerecorder.model.TimerSlotStore;

public class TimerReceiver extends BroadcastReceiver {

    private static final String TAG = "TimerReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "Timer receiver triggered");

        // 无进行中活动则取消闹钟，不再续期（避免闹钟无限自续）
        if (!TimerSlotStore.hasActive(context)) {
            TimerService.cancelTimer(context);
            return;
        }

        // 仍有进行中的活动：重新调度下一次触发（每分钟一次，作为进程被杀后的兜底）
        TimerService.scheduleTimer(context);
    }
}

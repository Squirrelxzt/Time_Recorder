package com.example.timerecorder.model;

/**
 * 一个进行中的活动槽位（并行计时的最小单元）。
 * 不含 running 布尔：startTime > 0 即视为运行中，避免两个字段不同步。
 */
public class TimerSlot {
    /** 活动名称（持久化前已 trim） */
    public String name;
    /** 开始时刻 epoch millis；运行中恒 > 0 */
    public long startTime;

    public TimerSlot() {
    }

    public TimerSlot(String name, long startTime) {
        this.name = name;
        this.startTime = startTime;
    }
}

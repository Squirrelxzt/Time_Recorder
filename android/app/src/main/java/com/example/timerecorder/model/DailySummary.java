package com.example.timerecorder.model;

import java.io.Serializable;

/** 某一天的 AI 日程总结与评分。date 为当天 00:00 毫秒（与 activities.date 同口径）。 */
public class DailySummary implements Serializable {
    private long date;
    private int score;
    private String summary;
    private long updatedAt;

    public DailySummary() {
    }

    public DailySummary(long date, int score, String summary, long updatedAt) {
        this.date = date;
        this.score = score;
        this.summary = summary;
        this.updatedAt = updatedAt;
    }

    public long getDate() {
        return date;
    }

    public void setDate(long date) {
        this.date = date;
    }

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = score;
    }

    public String getSummary() {
        return summary == null ? "" : summary;
    }

    public void setSummary(String summary) {
        this.summary = summary == null ? "" : summary;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}

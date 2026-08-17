package com.example.timerecorder.model;

import java.io.Serializable;

public class Activity implements Serializable {
    private long id;
    private String name;
    private int startHour;
    private int startMinute;
    private int endHour;
    private int endMinute;
    private long date;
    private String motive = "";

    public Activity() {
    }
    
    public Activity(String name, int startHour, int startMinute, int endHour, int endMinute) {
        this.name = name;
        this.startHour = startHour;
        this.startMinute = startMinute;
        this.endHour = endHour;
        this.endMinute = endMinute;
        this.date = System.currentTimeMillis();
    }
    
    public long getId() {
        return id;
    }
    
    public void setId(long id) {
        this.id = id;
    }
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public int getStartHour() {
        return startHour;
    }
    
    public void setStartHour(int startHour) {
        this.startHour = startHour;
    }
    
    public int getStartMinute() {
        return startMinute;
    }
    
    public void setStartMinute(int startMinute) {
        this.startMinute = startMinute;
    }
    
    public int getEndHour() {
        return endHour;
    }
    
    public void setEndHour(int endHour) {
        this.endHour = endHour;
    }
    
    public int getEndMinute() {
        return endMinute;
    }
    
    public void setEndMinute(int endMinute) {
        this.endMinute = endMinute;
    }
    
    public long getDate() {
        return date;
    }
    
    public void setDate(long date) {
        this.date = date;
    }

    public String getMotive() {
        return motive == null ? "" : motive;
    }

    public void setMotive(String motive) {
        this.motive = motive == null ? "" : motive;
    }
    
    public String getStartTimeString() {
        return String.format("%02d:%02d", startHour, startMinute);
    }
    
    public String getEndTimeString() {
        return String.format("%02d:%02d", endHour, endMinute);
    }
    
    public int getDurationMinutes() {
        int startTotal = startHour * 60 + startMinute;
        int endTotal = endHour * 60 + endMinute;
        return endTotal - startTotal;
    }
    
    public String getDurationString() {
        int duration = getDurationMinutes();
        int hours = duration / 60;
        int minutes = duration % 60;
        if (hours > 0) {
            return String.format("%d小时%d分钟", hours, minutes);
        } else {
            return String.format("%d分钟", minutes);
        }
    }
}
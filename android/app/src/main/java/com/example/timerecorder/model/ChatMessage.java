package com.example.timerecorder.model;

import java.io.Serializable;

/**
 * 聊天 UI 消息模型，仅用于界面显示。
 * API 请求的完整消息结构（含 tool_calls）由 ai 包单独维护 List<JSONObject>。
 */
public class ChatMessage implements Serializable {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    private String role;
    private String content;
    private boolean pending;

    public ChatMessage() {
    }

    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public boolean isPending() {
        return pending;
    }

    public void setPending(boolean pending) {
        this.pending = pending;
    }
}

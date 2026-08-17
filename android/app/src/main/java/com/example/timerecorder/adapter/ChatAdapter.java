package com.example.timerecorder.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.timerecorder.R;
import com.example.timerecorder.model.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 聊天消息气泡适配器。
 * 结构照搬 ActivityAdapter：用户消息右对齐（primaryContainer），助手消息左对齐（surfaceVariant）。
 */
public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.ViewHolder> {

    public static final int VIEW_TYPE_USER = 0;
    public static final int VIEW_TYPE_ASSISTANT = 1;

    private static final String TYPING_TEXT = "正在思考…";

    /** 长按用户消息回调（用于"重新发送"） */
    public interface OnMessageLongClickListener {
        void onMessageLongClick(ChatMessage message, int position);
    }

    private List<ChatMessage> messages;
    private OnMessageLongClickListener onLongClickListener;

    public ChatAdapter() {
        this.messages = new ArrayList<>();
    }

    public void setOnMessageLongClickListener(OnMessageLongClickListener listener) {
        this.onLongClickListener = listener;
    }

    public void setMessages(List<ChatMessage> messages) {
        this.messages = messages != null ? messages : new ArrayList<>();
        notifyDataSetChanged();
    }

    public void addMessage(ChatMessage message) {
        messages.add(message);
        notifyItemInserted(messages.size() - 1);
    }

    /** 原位更新（例如把"正在思考…"占位替换成最终回复） */
    public void updateMessage(int index, ChatMessage message) {
        messages.set(index, message);
        notifyItemChanged(index);
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    @Override
    public int getItemViewType(int position) {
        ChatMessage message = messages.get(position);
        return ChatMessage.ROLE_USER.equals(message.getRole()) ? VIEW_TYPE_USER : VIEW_TYPE_ASSISTANT;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layoutRes = viewType == VIEW_TYPE_USER ? R.layout.item_chat_user : R.layout.item_chat_assistant;
        View view = LayoutInflater.from(parent.getContext()).inflate(layoutRes, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ChatMessage message = messages.get(position);
        holder.tvText.setText(message.isPending() ? TYPING_TEXT : message.getContent());
        // 仅用户消息支持长按重发
        boolean userMessage = ChatMessage.ROLE_USER.equals(message.getRole());
        holder.itemView.setOnLongClickListener(userMessage && onLongClickListener != null
                ? v -> {
                    onLongClickListener.onMessageLongClick(message, position);
                    return true;
                }
                : null);
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvText;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvText = itemView.findViewById(R.id.tv_chat_text);
        }
    }
}

package com.chattingconnect.chat;

/** 메시지/상태/오류를 바깥(마인크래프트 표시부)으로 전달하는 콜백. */
public interface ChatListener {
    void onMessage(ChatMessage message);
    void onStatus(String status);
    void onError(String message);
}

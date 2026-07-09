package com.chattingconnect.chat;

import java.util.Map;

/** 플랫폼 중립 채팅/후원 메시지. msg 안의 이모티콘은 {@code {:key:}} 토큰으로 오고, 실제 URL은 {@link #emotes}에 담긴다. */
public final class ChatMessage {
    public enum Type { CHAT, DONATION }

    public final Platform platform;
    public final Type type;
    public final String nickname;
    public final String message;
    /** 이모티콘 key → 이미지 URL. 없으면 빈 맵. */
    public final Map<String, String> emotes;
    /** 후원 금액(치지직=치즈, SOOP=별풍선 등). CHAT이면 0. */
    public final int payAmount;

    public ChatMessage(Platform platform, Type type, String nickname, String message,
                       Map<String, String> emotes, int payAmount) {
        this.platform = platform;
        this.type = type;
        this.nickname = nickname;
        this.message = message;
        this.emotes = emotes;
        this.payAmount = payAmount;
    }
}

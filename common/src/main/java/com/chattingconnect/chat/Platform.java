package com.chattingconnect.chat;

/** 지원하는 방송 플랫폼. */
public enum Platform {
    CHZZK("치지직"),
    SOOP("SOOP");

    private final String displayName;

    Platform(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}

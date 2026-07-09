package com.chattingconnect.chat;

import java.util.function.Consumer;

/** 방송 플랫폼 채팅 수신 클라이언트 공통 인터페이스. */
public interface ChatClient {
    /** 비동기로 연결을 시작한다. */
    void connect();

    /** 연결을 종료하고 자원을 정리한다. */
    void close();

    /** 원시 수신 데이터를 디버그 싱크로 흘려보내도록 설정한다. */
    ChatClient debug(Consumer<String> sink);
}

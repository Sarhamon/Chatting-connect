package com.chattingconnect.twitch;

import com.chattingconnect.chat.ChatClient;
import com.chattingconnect.chat.ChatListener;
import com.chattingconnect.chat.ChatMessage;
import com.chattingconnect.chat.Platform;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 트위치 채팅 수신 클라이언트. 마인크래프트 클래스에 의존하지 않는 순수 Java 구현.
 * 인증 없이 익명(justinfan)으로 IRC-over-WebSocket에 접속해 읽기 전용으로 채팅/비트를 수신한다.
 * 서드파티 이모티콘(BTTV/FFZ/7TV)은 별도 API가 필요해 지원하지 않고, 트위치 1차 이모티콘만 처리한다.
 */
public final class TwitchClient implements ChatClient {

    private static final String WS_URL = "wss://irc-ws.chat.twitch.tv:443";
    /** 이모티콘 ID → CDN 이미지 URL. */
    private static final String EMOTE_CDN = "https://static-cdn.jtvnw.net/emoticons/v2/%s/default/dark/2.0";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String channel;
    private final ChatListener listener;
    private final ScheduledExecutorService scheduler;

    private volatile WebSocket webSocket;
    private volatile boolean closed;
    private volatile Consumer<String> debugSink;
    private final AtomicBoolean reconnectPending = new AtomicBoolean(false);
    private volatile int reconnectAttempts;

    public TwitchClient(String channel, ChatListener listener) {
        this.channel = channel.toLowerCase().replaceFirst("^#", "");
        this.listener = listener;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(daemonFactory());
    }

    @Override
    public TwitchClient debug(Consumer<String> sink) {
        this.debugSink = sink;
        return this;
    }

    @Override
    public void connect() {
        scheduler.execute(this::doConnect);
    }

    @Override
    public void close() {
        closed = true;
        WebSocket ws = webSocket;
        if (ws != null) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "");
        }
        scheduler.shutdownNow();
    }

    private void doConnect() {
        HTTP.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(WS_URL), new WsListener())
                .exceptionally(ex -> {
                    if (reconnectAttempts == 0) {
                        listener.onError("웹소켓 연결 실패: " + ex.getMessage());
                    }
                    scheduleReconnect();
                    return null;
                });
    }

    /** 연결이 끊기면 지수 백오프(최대 24초)로 재접속을 예약한다. close() 이후에는 동작하지 않는다. */
    private void scheduleReconnect() {
        if (closed || !reconnectPending.compareAndSet(false, true)) {
            return;
        }
        long delay = Math.min(3L << Math.min(reconnectAttempts, 3), 30L);
        if (reconnectAttempts == 0) {
            listener.onStatus("연결이 끊겼습니다. 재연결을 시도합니다...");
        }
        reconnectAttempts++;
        try {
            scheduler.schedule(() -> {
                reconnectPending.set(false);
                doConnect();
            }, delay, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            reconnectPending.set(false);
        }
    }

    private void handleLine(String line) {
        if (line.isEmpty()) {
            return;
        }
        String rest = line;
        String tagsPart = null;
        if (rest.startsWith("@")) {
            int sp = rest.indexOf(' ');
            tagsPart = rest.substring(1, sp);
            rest = rest.substring(sp + 1);
        }
        String source = null;
        if (rest.startsWith(":")) {
            int sp = rest.indexOf(' ');
            source = rest.substring(1, sp);
            rest = rest.substring(sp + 1);
        }
        int cmdEnd = rest.indexOf(' ');
        String command = cmdEnd < 0 ? rest : rest.substring(0, cmdEnd);
        String params = cmdEnd < 0 ? "" : rest.substring(cmdEnd + 1);

        switch (command) {
            case "PING" -> sendRaw("PONG " + params);
            case "001" -> {                                  // 접속·로그인 성공
                reconnectAttempts = 0;
                listener.onStatus("연결됨 · 채팅 수신을 시작합니다.");
            }
            case "RECONNECT" -> scheduleReconnect();          // 서버가 재접속 요청
            case "PRIVMSG" -> emitMessage(tagsPart, source, params, line);
            default -> { /* JOIN/NAMES/기타 무시 */ }
        }
    }

    private void emitMessage(String tagsPart, String source, String params, String raw) {
        int c = params.indexOf(" :");
        if (c < 0) {
            return;
        }
        String text = params.substring(c + 2);
        Map<String, String> tags = parseTags(tagsPart);

        Map<String, String> emotes = new LinkedHashMap<>();
        String body = applyEmotes(text, tags.get("emotes"), emotes);
        int bits = parseIntSafe(tags.get("bits"));
        String nickname = tags.getOrDefault("display-name", "");
        if (nickname.isEmpty()) {
            nickname = source == null ? "익명" : source.substring(0, Math.max(0, source.indexOf('!')));
        }
        if (body.isEmpty() && bits == 0 && emotes.isEmpty()) {
            return;
        }
        Consumer<String> sink = debugSink;
        if (sink != null) {
            sink.accept(raw);
        }
        ChatMessage.Type type = bits > 0 ? ChatMessage.Type.DONATION : ChatMessage.Type.CHAT;
        listener.onMessage(new ChatMessage(Platform.TWITCH, type, nickname, body, emotes, bits));
    }

    private static Map<String, String> parseTags(String tagsPart) {
        Map<String, String> tags = new HashMap<>();
        if (tagsPart == null || tagsPart.isEmpty()) {
            return tags;
        }
        for (String kv : tagsPart.split(";")) {
            int eq = kv.indexOf('=');
            if (eq >= 0) {
                tags.put(kv.substring(0, eq), unescapeTag(kv.substring(eq + 1)));
            }
        }
        return tags;
    }

    /** IRCv3 태그 이스케이프 해제(\s=공백, \:=; 등). */
    private static String unescapeTag(String v) {
        if (v.indexOf('\\') < 0) {
            return v;
        }
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            if (ch == '\\' && i + 1 < v.length()) {
                char next = v.charAt(++i);
                sb.append(switch (next) {
                    case 's' -> ' ';
                    case ':' -> ';';
                    case 'r' -> '\r';
                    case 'n' -> '\n';
                    default -> next;
                });
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /**
     * emotes 태그({@code id:start-end,.../id2:...})의 각 구간을 {@code {:id:}} 토큰으로 치환하고
     * id→CDN URL을 {@code out}에 담는다. 인덱스는 코드포인트 기준이라 서로게이트를 고려한다.
     */
    private static String applyEmotes(String msg, String emotesTag, Map<String, String> out) {
        if (emotesTag == null || emotesTag.isEmpty()) {
            return msg;
        }
        Map<Integer, String> startToId = new HashMap<>();
        Map<Integer, Integer> startToEnd = new HashMap<>();
        for (String block : emotesTag.split("/")) {
            int colon = block.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String id = block.substring(0, colon);
            out.put(id, String.format(EMOTE_CDN, id));
            for (String range : block.substring(colon + 1).split(",")) {
                int dash = range.indexOf('-');
                if (dash < 0) {
                    continue;
                }
                int start = parseIntSafe(range.substring(0, dash));
                int end = parseIntSafe(range.substring(dash + 1));
                startToId.put(start, id);
                startToEnd.put(start, end);
            }
        }
        int[] cps = msg.codePoints().toArray();
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < cps.length) {
            String id = startToId.get(i);
            if (id != null) {
                sb.append("{:").append(id).append(":}");
                i = startToEnd.get(i) + 1;
            } else {
                sb.appendCodePoint(cps[i]);
                i++;
            }
        }
        return sb.toString();
    }

    private static int parseIntSafe(String v) {
        if (v == null || v.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void sendRaw(String text) {
        WebSocket ws = webSocket;
        if (ws != null && !closed) {
            ws.sendText(text + "\r\n", true);
        }
    }

    private static ThreadFactory daemonFactory() {
        return runnable -> {
            Thread t = new Thread(runnable, "twitch-client");
            t.setDaemon(true);
            return t;
        };
    }

    /** 웹소켓 수신 리스너. IRC 라인(\r\n 구분)을 모아 한 줄씩 처리한다. */
    private final class WsListener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            webSocket = ws;
            ws.request(1);
            ws.sendText("CAP REQ :twitch.tv/tags twitch.tv/commands\r\n", true);
            ws.sendText("PASS SCHMOOPIIE\r\n", true);
            ws.sendText("NICK justinfan" + (10000 + (int) (Math.random() * 80000)) + "\r\n", true);
            ws.sendText("JOIN #" + channel + "\r\n", true);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                int nl;
                while ((nl = buffer.indexOf("\n")) >= 0) {
                    String line = buffer.substring(0, nl).replace("\r", "");
                    buffer.delete(0, nl + 1);
                    handleLine(line);
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            scheduleReconnect();
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            scheduleReconnect();
            return null;
        }
    }
}

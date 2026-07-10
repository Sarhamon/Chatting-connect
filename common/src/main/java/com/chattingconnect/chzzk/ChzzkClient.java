package com.chattingconnect.chzzk;

import com.chattingconnect.chat.ChatClient;
import com.chattingconnect.chat.ChatListener;
import com.chattingconnect.chat.ChatMessage;
import com.chattingconnect.chat.Platform;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 치지직 비공식 채팅 수신 클라이언트. 마인크래프트 클래스에 의존하지 않는 순수 Java 구현.
 * 연결 흐름: live-status → access-token → 웹소켓(cmd 프로토콜).
 */
public final class ChzzkClient implements ChatClient {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";
    private static final String WS_URL = "wss://kr-ss1.chat.naver.com/chat";
    private static final String PING = "{\"ver\":\"2\",\"cmd\":0}";
    private static final String PONG = "{\"ver\":\"2\",\"cmd\":10000}";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Gson GSON = new Gson();

    private final String channelId;
    private final ChatListener listener;
    private final ScheduledExecutorService scheduler;

    private volatile WebSocket webSocket;
    private volatile boolean closed;
    private volatile Consumer<String> debugSink;
    private volatile ScheduledFuture<?> pingTask;
    private final AtomicBoolean reconnectPending = new AtomicBoolean(false);
    private volatile int reconnectAttempts;

    public ChzzkClient(String channelId, ChatListener listener) {
        this.channelId = channelId;
        this.listener = listener;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(daemonFactory());
    }

    @Override
    public ChzzkClient debug(Consumer<String> sink) {
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
        try {
            JsonObject liveStatus = getJson("https://api.chzzk.naver.com/polling/v3/channels/"
                    + channelId + "/live-status");
            JsonObject content = liveStatus.getAsJsonObject("content");
            JsonElement chatChannelIdEl = content == null ? null : content.get("chatChannelId");
            if (chatChannelIdEl == null || chatChannelIdEl.isJsonNull()) {
                if (reconnectAttempts == 0) {
                    listener.onError("채널을 찾을 수 없거나 채팅 채널이 없습니다. 채널 ID와 방송 상태를 확인하세요.");
                }
                scheduleReconnect();
                return;
            }
            String chatChannelId = chatChannelIdEl.getAsString();

            JsonObject tokenResp = getJson("https://comm-api.game.naver.com/nng_main/v1/chats/access-token"
                    + "?channelId=" + chatChannelId + "&chatType=STREAMING");
            JsonObject tokenContent = tokenResp.getAsJsonObject("content");
            String accessToken = tokenContent.get("accessToken").getAsString();

            String connectFrame = buildConnectFrame(chatChannelId, accessToken);
            HTTP.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(WS_URL), new WsListener(connectFrame))
                    .exceptionally(ex -> {
                        if (reconnectAttempts == 0) {
                            listener.onError("웹소켓 연결 실패: " + ex.getMessage());
                        }
                        scheduleReconnect();
                        return null;
                    });
        } catch (Exception e) {
            if (!closed) {
                if (reconnectAttempts == 0) {
                    listener.onError("연결 중 오류: " + e.getMessage());
                }
                scheduleReconnect();
            }
        }
    }

    /** 연결이 끊기면 지수 백오프(최대 24초)로 재접속을 예약한다. close() 이후에는 동작하지 않는다. */
    private void scheduleReconnect() {
        if (closed || !reconnectPending.compareAndSet(false, true)) {
            return;
        }
        cancelPing();
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

    private void cancelPing() {
        ScheduledFuture<?> t = pingTask;
        if (t != null) {
            t.cancel(false);
            pingTask = null;
        }
    }

    private JsonObject getJson(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", UA)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + " (" + url + ")");
        }
        return GSON.fromJson(resp.body(), JsonObject.class);
    }

    private String buildConnectFrame(String chatChannelId, String accessToken) {
        JsonObject bdy = new JsonObject();
        bdy.add("uid", JsonNull.INSTANCE);
        bdy.addProperty("devType", 2001);
        bdy.addProperty("accTkn", accessToken);
        bdy.addProperty("auth", "READ");

        JsonObject frame = new JsonObject();
        frame.addProperty("ver", "2");
        frame.addProperty("cmd", 100);
        frame.addProperty("svcid", "game");
        frame.addProperty("cid", chatChannelId);
        frame.add("bdy", bdy);
        frame.addProperty("tid", 1);
        return GSON.toJson(frame);
    }

    private void handleMessage(String raw) {
        JsonObject obj;
        try {
            obj = GSON.fromJson(raw, JsonObject.class);
        } catch (Exception e) {
            return;
        }
        if (obj == null || !obj.has("cmd") || obj.get("cmd").isJsonNull()) {
            return;
        }
        int cmd = obj.get("cmd").getAsInt();
        switch (cmd) {
            case 0 -> sendRaw(PONG);                              // 서버 PING → PONG 응답
            case 10100 -> {                                      // 접속 성공
                reconnectAttempts = 0;
                listener.onStatus("연결됨 · 채팅 수신을 시작합니다.");
            }
            case 93101 -> emitMessages(obj, ChatMessage.Type.CHAT, raw);       // 일반 채팅
            case 93102 -> emitMessages(obj, ChatMessage.Type.DONATION, raw);   // 후원(치즈)
            default -> { /* 기타 cmd 무시 */ }
        }
    }

    private void emitMessages(JsonObject obj, ChatMessage.Type type, String raw) {
        JsonElement bdyEl = obj.get("bdy");
        if (bdyEl == null || !bdyEl.isJsonArray()) {
            return;
        }
        Consumer<String> sink = debugSink;
        if (sink != null) {
            sink.accept(raw);
        }
        JsonArray bdy = bdyEl.getAsJsonArray();
        for (JsonElement el : bdy) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject m = el.getAsJsonObject();
            String message = optString(m, "msg");
            JsonObject extras = parseExtras(m);
            Map<String, String> emotes = parseEmotes(extras);
            int payAmount = extras == null ? 0 : optInt(extras, "payAmount");
            String nickname = parseNickname(m, extras);
            if (message.isEmpty() && payAmount == 0 && emotes.isEmpty()) {
                continue;
            }
            listener.onMessage(new ChatMessage(Platform.CHZZK, type, nickname, message, emotes, payAmount));
        }
    }

    private String parseNickname(JsonObject m, JsonObject extras) {
        String profileStr = optString(m, "profile");
        if (!profileStr.isEmpty()) {
            try {
                JsonObject profile = GSON.fromJson(profileStr, JsonObject.class);
                String nickname = optString(profile, "nickname");
                if (!nickname.isEmpty()) {
                    return nickname;
                }
            } catch (Exception ignored) {
                // 프로필 파싱 실패 시 아래로
            }
        }
        if (extras != null) {
            String nickname = optString(extras, "nickname");
            if (!nickname.isEmpty()) {
                return nickname;
            }
        }
        return "익명";
    }

    private JsonObject parseExtras(JsonObject m) {
        String extrasStr = optString(m, "extras");
        if (extrasStr.isEmpty()) {
            return null;
        }
        try {
            return GSON.fromJson(extrasStr, JsonObject.class);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, String> parseEmotes(JsonObject extras) {
        Map<String, String> emotes = new LinkedHashMap<>();
        if (extras == null || !extras.has("emojis") || !extras.get("emojis").isJsonObject()) {
            return emotes;
        }
        for (Map.Entry<String, JsonElement> e : extras.getAsJsonObject("emojis").entrySet()) {
            if (e.getValue() != null && e.getValue().isJsonPrimitive()) {
                emotes.put(e.getKey(), e.getValue().getAsString());
            }
        }
        return emotes;
    }

    private static String optString(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return (el == null || el.isJsonNull() || !el.isJsonPrimitive()) ? "" : el.getAsString();
    }

    private static int optInt(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) {
            return 0;
        }
        try {
            return el.getAsInt();
        } catch (Exception e) {
            return 0;
        }
    }

    private void sendRaw(String text) {
        WebSocket ws = webSocket;
        if (ws != null && !closed) {
            ws.sendText(text, true);
        }
    }

    private static ThreadFactory daemonFactory() {
        return runnable -> {
            Thread t = new Thread(runnable, "chzzk-client");
            t.setDaemon(true);
            return t;
        };
    }

    /** 웹소켓 수신 리스너. 프래그먼트로 쪼개져 오는 텍스트를 모아 완결 시 처리한다. */
    private final class WsListener implements WebSocket.Listener {
        private final String connectFrame;
        private final StringBuilder buffer = new StringBuilder();

        WsListener(String connectFrame) {
            this.connectFrame = connectFrame;
        }

        @Override
        public void onOpen(WebSocket ws) {
            webSocket = ws;
            ws.request(1);
            ws.sendText(connectFrame, true);
            cancelPing();
            pingTask = scheduler.scheduleAtFixedRate(() -> sendRaw(PING), 20, 20, TimeUnit.SECONDS);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String msg = buffer.toString();
                buffer.setLength(0);
                handleMessage(msg);
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

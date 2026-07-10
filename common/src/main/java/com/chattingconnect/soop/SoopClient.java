package com.chattingconnect.soop;

import com.chattingconnect.chat.ChatClient;
import com.chattingconnect.chat.ChatListener;
import com.chattingconnect.chat.ChatMessage;
import com.chattingconnect.chat.Platform;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * SOOP(구 아프리카TV) 비공식 채팅 수신 클라이언트. 마인크래프트 클래스에 의존하지 않는 순수 Java 구현.
 * 연결 흐름: player_live_api(CHDOMAIN/CHATNO/CHPT) → 웹소켓(커스텀 패킷 프로토콜).
 * 패킷: STARTER(ESC\t) + 코드(4) + 길이(6, payload 바이트수) + "00" + payload, 필드는 \f 로 구분.
 */
public final class SoopClient implements ChatClient {

    private static final String STARTER = "\t";
    private static final String SEP = "\f";
    // 서비스 코드
    private static final String C_PING = "0000";
    private static final String C_CONNECT = "0001";
    private static final String C_ENTER = "0002";
    private static final String C_CHAT = "0005";
    private static final String C_TEXT_DONATION = "0018";
    private static final String C_VIDEO_DONATION = "0105";

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";
    private static final String LIVE_API = "https://live.sooplive.co.kr/afreeca/player_live_api.php";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Gson GSON = new Gson();

    private final String streamerId;
    private final ChatListener listener;
    private final ScheduledExecutorService scheduler;

    private volatile WebSocket webSocket;
    private volatile boolean closed;
    private volatile Consumer<String> debugSink;
    private volatile String chatNo;
    private volatile ScheduledFuture<?> pingTask;
    private final AtomicBoolean reconnectPending = new AtomicBoolean(false);
    private volatile int reconnectAttempts;

    public SoopClient(String streamerId, ChatListener listener) {
        this.streamerId = streamerId;
        this.listener = listener;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(daemonFactory());
    }

    @Override
    public SoopClient debug(Consumer<String> sink) {
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
            JsonObject channel = fetchChannel();
            int result = optInt(channel, "RESULT");
            if (result == 0) {
                if (reconnectAttempts == 0) {
                    listener.onError("방송 중이 아니거나 채널을 찾을 수 없습니다: " + streamerId);
                }
                scheduleReconnect();
                return;
            }
            String domain = optString(channel, "CHDOMAIN").toLowerCase();
            String chatPort = optString(channel, "CHPT");
            this.chatNo = optString(channel, "CHATNO");
            if (domain.isEmpty() || chatPort.isEmpty() || chatNo.isEmpty()) {
                if (reconnectAttempts == 0) {
                    listener.onError("채팅 서버 정보를 가져오지 못했습니다.");
                }
                scheduleReconnect();
                return;
            }
            int wsPort = Integer.parseInt(chatPort) + 1;
            URI uri = URI.create("wss://" + domain + ":" + wsPort + "/Websocket/" + streamerId);

            HTTP.newWebSocketBuilder()
                    .subprotocols("chat")
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(uri, new WsListener())
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

    private JsonObject fetchChannel() throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("bid", streamerId);
        form.put("type", "live");
        form.put("pwd", "");
        form.put("player_type", "html5");
        form.put("stream_type", "common");
        form.put("quality", "HD");
        form.put("mode", "landing");
        form.put("from_api", "0");
        form.put("is_revive", "false");

        HttpRequest req = HttpRequest.newBuilder(URI.create(LIVE_API + "?bjid=" + streamerId))
                .header("User-Agent", UA)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Referer", "https://play.sooplive.co.kr/" + streamerId + "/")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(encodeForm(form)))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + " (player_live_api)");
        }
        JsonObject root = GSON.fromJson(resp.body(), JsonObject.class);
        if (root == null || !root.has("CHANNEL") || !root.get("CHANNEL").isJsonObject()) {
            throw new IllegalStateException("응답에 CHANNEL 정보가 없습니다.");
        }
        return root.getAsJsonObject("CHANNEL");
    }

    private static String encodeForm(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static String packet(String code, String payload) {
        int len = payload.getBytes(StandardCharsets.UTF_8).length;
        return STARTER + code + String.format("%06d", len) + "00" + payload;
    }

    private void handlePacket(String pkt) {
        if (pkt.length() < 6 || !pkt.startsWith(STARTER)) {
            return;
        }
        String code = pkt.substring(2, 6);
        switch (code) {
            case C_CONNECT -> sendRaw(packet(C_ENTER, SEP + chatNo + SEP.repeat(5))); // 접속 승인 → 채팅방 입장
            case C_ENTER -> {                                                          // 입장 승인 → 핑 시작
                reconnectAttempts = 0;
                cancelPing();
                pingTask = scheduler.scheduleAtFixedRate(this::sendPing, 60, 60, TimeUnit.SECONDS);
                listener.onStatus("연결됨 · 채팅 수신을 시작합니다.");
            }
            case C_CHAT -> emitChat(pkt);
            case C_TEXT_DONATION -> emitDonation(pkt, 3, 4);   // fromUsername=parts[3], amount=parts[4]
            case C_VIDEO_DONATION -> emitDonation(pkt, 4, 5);  // fromUsername=parts[4], amount=parts[5]
            default -> { /* 입장/퇴장/시청자 등 무시 */ }
        }
    }

    private void emitChat(String pkt) {
        debug(pkt);
        String[] parts = pkt.split(SEP);
        String message = parts.length > 1 ? parts[1] : "";
        if (message.isEmpty()) {
            return;
        }
        String nickname = parts.length > 6 ? parts[6] : (parts.length > 2 ? parts[2] : "익명");
        listener.onMessage(new ChatMessage(Platform.SOOP, ChatMessage.Type.CHAT,
                nickname, message, Collections.emptyMap(), 0));
    }

    private void emitDonation(String pkt, int nickIdx, int amountIdx) {
        debug(pkt);
        String[] parts = pkt.split(SEP);
        String nickname = parts.length > nickIdx ? parts[nickIdx] : "익명";
        int amount = parts.length > amountIdx ? parseIntSafe(parts[amountIdx]) : 0;
        listener.onMessage(new ChatMessage(Platform.SOOP, ChatMessage.Type.DONATION,
                nickname, "", Collections.emptyMap(), amount));
    }

    private void debug(String pkt) {
        Consumer<String> sink = debugSink;
        if (sink != null) {
            sink.accept(pkt);
        }
    }

    private void sendPing() {
        sendRaw(packet(C_PING, SEP));
    }

    private void sendRaw(String text) {
        WebSocket ws = webSocket;
        if (ws != null && !closed) {
            ws.sendText(text, true);
        }
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

    private static String optString(JsonObject obj, String key) {
        var el = obj.get(key);
        return (el == null || el.isJsonNull() || !el.isJsonPrimitive()) ? "" : el.getAsString();
    }

    private static int optInt(JsonObject obj, String key) {
        var el = obj.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) {
            return 0;
        }
        try {
            return el.getAsInt();
        } catch (Exception e) {
            return 0;
        }
    }

    private static ThreadFactory daemonFactory() {
        return runnable -> {
            Thread t = new Thread(runnable, "soop-client");
            t.setDaemon(true);
            return t;
        };
    }

    /** 웹소켓 수신 리스너. SOOP는 텍스트/바이너리 프레임 모두 올 수 있어 둘 다 처리한다. */
    private final class WsListener implements WebSocket.Listener {
        private final StringBuilder textBuffer = new StringBuilder();
        private final java.io.ByteArrayOutputStream binBuffer = new java.io.ByteArrayOutputStream();

        @Override
        public void onOpen(WebSocket ws) {
            webSocket = ws;
            ws.request(1);
            sendRaw(packet(C_CONNECT, SEP + SEP + SEP + "16" + SEP)); // 접속 패킷
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                String pkt = textBuffer.toString();
                textBuffer.setLength(0);
                handlePacket(pkt);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            binBuffer.writeBytes(chunk);
            if (last) {
                String pkt = binBuffer.toString(StandardCharsets.UTF_8);
                binBuffer.reset();
                handlePacket(pkt);
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

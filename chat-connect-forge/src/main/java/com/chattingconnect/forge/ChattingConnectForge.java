package com.chattingconnect.forge;

import com.chattingconnect.chat.ChatClient;
import com.chattingconnect.chat.ChatListener;
import com.chattingconnect.chat.ChatMessage;
import com.chattingconnect.chat.Platform;
import com.chattingconnect.chzzk.ChzzkClient;
import com.chattingconnect.forge.emote.EmoteManager;
import com.chattingconnect.soop.SoopClient;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Forge 진입점. 클라이언트 사이드 채팅 연동 모드로, 여러 플랫폼(치지직·SOOP)의 방송 채팅·후원을
 * 마인크래프트 채팅창에 표시한다. 연결 관리는 정적 메서드로 노출하고, 커맨드 등록은 {@link ForgeClientCommands}가 담당한다.
 */
@Mod(ChattingConnectForge.MODID)
public class ChattingConnectForge {
    public static final String MODID = "chattingconnect";
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 치지직 이모티콘 토큰: {@code {:key:}} */
    private static final Pattern EMOTE_PATTERN = Pattern.compile("\\{:([^:}]+):}");

    private static final Map<Platform, ChatClient> activeClients = new EnumMap<>(Platform.class);

    private static final ChatListener LISTENER = new ChatListener() {
        @Override
        public void onMessage(ChatMessage message) {
            display(format(message));
        }

        @Override
        public void onStatus(String status) {
            info(status);
        }

        @Override
        public void onError(String message) {
            error(message);
        }
    };

    public ChattingConnectForge() {
        // 순수 클라이언트 모드라 별도 초기화 없음. 커맨드는 ForgeClientCommands에서 등록된다.
    }

    public static synchronized void connect(Platform platform, String id) {
        disconnect(platform);
        info(platform.displayName() + " 연결 중: " + id);
        ChatClient client = switch (platform) {
            case CHZZK -> new ChzzkClient(id, LISTENER);
            case SOOP -> new SoopClient(id, LISTENER);
        };
        client.debug(raw -> LOGGER.info("[{}-RAW] {}", platform, raw));
        activeClients.put(platform, client);
        client.connect();
    }

    public static synchronized void disconnect(Platform platform) {
        ChatClient client = activeClients.remove(platform);
        if (client != null) {
            client.close();
        }
    }

    public static synchronized boolean isConnected(Platform platform) {
        return activeClients.containsKey(platform);
    }

    private static Component format(ChatMessage msg) {
        MutableComponent prefix = platformIcon(msg.platform);
        if (msg.type == ChatMessage.Type.DONATION) {
            String currency = msg.platform == Platform.SOOP ? "별풍선" : "치즈";
            return prefix
                    .append(Component.literal(msg.nickname).withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal(" 님이 " + msg.payAmount + currency + " 후원").withStyle(ChatFormatting.GOLD))
                    .append(msg.message.isEmpty()
                            ? Component.empty()
                            : Component.literal(": ").withStyle(ChatFormatting.GOLD).append(renderBody(msg.message, msg.emotes)));
        }
        return prefix
                .append(Component.literal(msg.nickname + ": ").withStyle(ChatFormatting.AQUA))
                .append(renderBody(msg.message, msg.emotes));
    }

    /** 플랫폼 아이콘 접두사(코드포인트 1글자 + 공백). mixin이 이미지로 렌더한다. */
    private static MutableComponent platformIcon(Platform platform) {
        int codePoint = platform == Platform.SOOP ? EmoteManager.ICON_SOOP : EmoteManager.ICON_CHZZK;
        return Component.literal(EmoteManager.glyphString(codePoint) + " ").withStyle(ChatFormatting.WHITE);
    }

    /**
     * 메시지 본문의 이모티콘 토큰을 처리한다. 준비된 이모티콘은 예약 코드포인트 문자로(→ mixin이 이미지로 렌더),
     * 아직 다운로드 전이면 빈 공간(완료 시 그 자리에 이미지가 나타남)으로 표시한다.
     */
    private static Component renderBody(String message, Map<String, String> emotes) {
        MutableComponent out = Component.empty();
        Matcher m = EMOTE_PATTERN.matcher(message);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                out.append(Component.literal(message.substring(last, m.start())).withStyle(ChatFormatting.WHITE));
            }
            out.append(emote(m.group(1), emotes));
            last = m.end();
        }
        if (last < message.length()) {
            out.append(Component.literal(message.substring(last)).withStyle(ChatFormatting.WHITE));
        }
        return out;
    }

    private static Component emote(String key, Map<String, String> emotes) {
        String url = emotes.get(key);
        int codePoint = url == null ? -1 : EmoteManager.codepointFor(url);
        if (codePoint > 0) {
            return Component.literal(EmoteManager.glyphString(codePoint)).withStyle(ChatFormatting.WHITE);
        }
        return Component.literal(":" + key + ":").withStyle(ChatFormatting.LIGHT_PURPLE);
    }

    private static void info(String msg) {
        display(Component.literal("[ChattingConnect] ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(msg).withStyle(ChatFormatting.YELLOW)));
    }

    private static void error(String msg) {
        display(Component.literal("[ChattingConnect] ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(msg).withStyle(ChatFormatting.RED)));
    }

    private static void display(Component component) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.gui.getChat().addMessage(component);
            } else {
                LOGGER.info(component.getString());
            }
        });
    }
}

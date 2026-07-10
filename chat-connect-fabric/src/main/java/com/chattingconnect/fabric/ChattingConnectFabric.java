package com.chattingconnect.fabric;

import com.chattingconnect.chat.ChatClient;
import com.chattingconnect.chat.ChatListener;
import com.chattingconnect.chat.ChatMessage;
import com.chattingconnect.chat.Platform;
import com.chattingconnect.chzzk.ChzzkClient;
import com.chattingconnect.fabric.emote.EmoteManager;
import com.chattingconnect.soop.SoopClient;
import com.chattingconnect.twitch.TwitchClient;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.slf4j.Logger;

import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fabric 진입점(클라이언트). 여러 플랫폼(치지직·SOOP·트위치)의 방송 채팅·후원을 마인크래프트 채팅창에 표시한다.
 * 연결/표시 로직은 Forge 모듈과 동일하며, 로더 고유부(커맨드 등록·자동 접속·설정 경로)만 Fabric API를 쓴다.
 */
public class ChattingConnectFabric implements ClientModInitializer {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 치지직 이모티콘 토큰: {@code {:key:}} */
    private static final Pattern EMOTE_PATTERN = Pattern.compile("\\{:([^:}]+):}");

    private static final Map<Platform, ChatClient> activeClients = new EnumMap<>(Platform.class);
    /** 마지막으로 연결한 채널(재실행 시 자동 접속용). connect 시 저장, disconnect 시 삭제. */
    private static final Map<Platform, String> savedChannels = new EnumMap<>(Platform.class);

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

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(platformCommand("chzzk", Platform.CHZZK));
            dispatcher.register(platformCommand("twitch", Platform.TWITCH));
            // SOOP: 미션 후원 미해결로 이번 릴리스에서 제외. 재활성화 시 아래 주석 해제.
            // dispatcher.register(platformCommand("soop", Platform.SOOP));
        });
        // 클라이언트가 완전히 시작되면 저장된 채널로 자동 접속한다.
        ClientLifecycleEvents.CLIENT_STARTED.register(client ->
                ChatConfig.load().forEach(ChattingConnectFabric::connect));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> platformCommand(String name, Platform platform) {
        return ClientCommandManager.literal(name)
                .then(ClientCommandManager.literal("connect")
                        .then(ClientCommandManager.argument("id", StringArgumentType.word())
                                .executes(ctx -> {
                                    connect(platform, StringArgumentType.getString(ctx, "id"));
                                    return 1;
                                })))
                .then(ClientCommandManager.literal("disconnect")
                        .executes(ctx -> {
                            if (isConnected(platform)) {
                                disconnect(platform);
                                ctx.getSource().sendFeedback(Component.literal(platform.displayName() + " 연결을 종료했습니다."));
                            } else {
                                ctx.getSource().sendError(Component.literal("연결된 채널이 없습니다."));
                            }
                            return 1;
                        }));
    }

    public static synchronized void connect(Platform platform, String id) {
        teardown(platform);
        info(platform.displayName() + " 연결 중: " + id);
        ChatClient client = switch (platform) {
            case CHZZK -> new ChzzkClient(id, LISTENER);
            case SOOP -> new SoopClient(id, LISTENER);
            case TWITCH -> new TwitchClient(id, LISTENER);
        };
        client.debug(raw -> LOGGER.info("[{}-RAW] {}", platform, raw));
        activeClients.put(platform, client);
        client.connect();
        savedChannels.put(platform, id);
        ChatConfig.save(savedChannels);
    }

    public static synchronized void disconnect(Platform platform) {
        teardown(platform);
        if (savedChannels.remove(platform) != null) {
            ChatConfig.save(savedChannels);
        }
    }

    /** 연결만 정리하고 저장된 채널은 건드리지 않는다(재접속 전 기존 연결 종료용). */
    private static void teardown(Platform platform) {
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
            String currency = switch (msg.platform) {
                case SOOP -> "별풍선";
                case TWITCH -> "비트";
                case CHZZK -> "치즈";
            };
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
        int codePoint = switch (platform) {
            case SOOP -> EmoteManager.ICON_SOOP;
            case TWITCH -> EmoteManager.ICON_TWITCH;
            case CHZZK -> EmoteManager.ICON_CHZZK;
        };
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

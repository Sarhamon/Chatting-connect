package com.chattingconnect.forge;

import com.chattingconnect.chzzk.ChzzkClient;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Forge 진입점. 클라이언트 사이드 채팅 연동 모드로, 방송 채팅을 마인크래프트 채팅창에 표시한다.
 * 연결 관리는 정적 메서드로 노출하고, 커맨드 등록은 {@link ForgeClientCommands}가 담당한다.
 */
@Mod(ChattingConnectForge.MODID)
public class ChattingConnectForge {
    public static final String MODID = "chattingconnect";
    private static final Logger LOGGER = LogUtils.getLogger();

    private static ChzzkClient activeClient;

    public ChattingConnectForge() {
        // 순수 클라이언트 모드라 별도 초기화 없음. 커맨드는 ForgeClientCommands에서 등록된다.
    }

    public static synchronized void connect(String channelId) {
        disconnect();
        info("치지직 채널 연결 중: " + channelId);
        activeClient = new ChzzkClient(channelId, new ChzzkClient.ChatListener() {
            @Override
            public void onChat(String nickname, String message) {
                display(Component.literal("[치지직] ").withStyle(ChatFormatting.GREEN)
                        .append(Component.literal(nickname + ": ").withStyle(ChatFormatting.AQUA))
                        .append(Component.literal(message).withStyle(ChatFormatting.WHITE)));
            }

            @Override
            public void onStatus(String status) {
                info(status);
            }

            @Override
            public void onError(String message) {
                error(message);
            }
        });
        activeClient.connect();
    }

    public static synchronized void disconnect() {
        if (activeClient != null) {
            activeClient.close();
            activeClient = null;
        }
    }

    public static synchronized boolean isConnected() {
        return activeClient != null;
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

package com.chattingconnect.forge;

import com.chattingconnect.chat.Platform;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 클라이언트 커맨드 등록: {@code /chzzk connect|disconnect}, {@code /soop connect|disconnect}.
 * 클라이언트에서 로컬 실행되므로 싱글플레이/서버 접속 여부와 무관하게 동작한다.
 */
@Mod.EventBusSubscriber(modid = ChattingConnectForge.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ForgeClientCommands {

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(platformCommand("chzzk", Platform.CHZZK));
        event.getDispatcher().register(platformCommand("soop", Platform.SOOP));
        event.getDispatcher().register(platformCommand("twitch", Platform.TWITCH));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> platformCommand(String name, Platform platform) {
        return Commands.literal(name)
                .then(Commands.literal("connect")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> {
                                    ChattingConnectForge.connect(platform, StringArgumentType.getString(ctx, "id"));
                                    return 1;
                                })))
                .then(Commands.literal("disconnect")
                        .executes(ctx -> {
                            if (ChattingConnectForge.isConnected(platform)) {
                                ChattingConnectForge.disconnect(platform);
                                ctx.getSource().sendSuccess(() -> Component.literal(platform.displayName() + " 연결을 종료했습니다."), false);
                            } else {
                                ctx.getSource().sendFailure(Component.literal("연결된 채널이 없습니다."));
                            }
                            return 1;
                        }));
    }
}

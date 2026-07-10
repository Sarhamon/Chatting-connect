package com.chattingconnect.forge;

import com.chattingconnect.chat.Platform;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** 마지막으로 연결한 플랫폼별 채널을 {@code config/chattingconnect.json} 에 저장/복원한다. */
final class ChatConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();
    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("chattingconnect.json");

    private ChatConfig() {}

    static Map<Platform, String> load() {
        Map<Platform, String> out = new EnumMap<>(Platform.class);
        if (!Files.exists(FILE)) {
            return out;
        }
        try {
            Map<String, String> raw = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), MAP_TYPE);
            if (raw != null) {
                raw.forEach((key, value) -> {
                    if (value != null && !value.isEmpty()) {
                        try {
                            out.put(Platform.valueOf(key), value);
                        } catch (IllegalArgumentException ignored) {
                            // 알 수 없는 플랫폼 키 무시
                        }
                    }
                });
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("설정을 읽지 못했습니다: {}", e.getMessage());
        }
        return out;
    }

    static void save(Map<Platform, String> channels) {
        Map<String, String> raw = new LinkedHashMap<>();
        channels.forEach((platform, id) -> raw.put(platform.name(), id));
        try {
            Files.writeString(FILE, GSON.toJson(raw), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("설정을 저장하지 못했습니다: {}", e.getMessage());
        }
    }
}

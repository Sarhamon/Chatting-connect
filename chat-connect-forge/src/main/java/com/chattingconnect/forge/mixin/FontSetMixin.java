package com.chattingconnect.forge.mixin;

import com.chattingconnect.forge.emote.EmoteManager;
import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.logging.LogUtils;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 우리가 예약한 코드포인트 범위(사설 영역)에 대해 치지직 이모티콘 글리프를 반환하도록 FontSet에 주입한다.
 * 그 외 코드포인트는 건드리지 않아 바닐라 렌더링을 그대로 유지한다.
 */
@Mixin(FontSet.class)
public class FontSetMixin {

    private static final Logger CC_LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean CC_ACTIVE_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean CC_HIT_LOGGED = new AtomicBoolean(false);

    @Inject(method = "getGlyphInfo", at = @At("HEAD"), cancellable = true)
    private void chattingconnect$emoteGlyphInfo(int codePoint, boolean filterFishyGlyphs, CallbackInfoReturnable<GlyphInfo> cir) {
        GlyphInfo info = EmoteManager.getGlyphInfo(codePoint);
        if (info != null) {
            cir.setReturnValue(info);
        }
    }

    @Inject(method = "getGlyph", at = @At("HEAD"), cancellable = true)
    private void chattingconnect$emoteGlyph(int codePoint, CallbackInfoReturnable<BakedGlyph> cir) {
        if (CC_ACTIVE_LOGGED.compareAndSet(false, true)) {
            CC_LOGGER.info("[emote] FontSetMixin active");
        }
        BakedGlyph glyph = EmoteManager.getGlyph(codePoint);
        if (glyph != null) {
            if (CC_HIT_LOGGED.compareAndSet(false, true)) {
                CC_LOGGER.info("[emote] rendering emote glyph cp={}", codePoint);
            }
            cir.setReturnValue(glyph);
        }
    }
}

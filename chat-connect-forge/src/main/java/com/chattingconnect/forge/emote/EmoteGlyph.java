package com.chattingconnect.forge.emote;

import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.SheetGlyphInfo;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;

import java.util.function.Function;

/**
 * 이미 텍스처가 업로드된 이모티콘 글리프. FontSet이 요구하는 {@link GlyphInfo}를 구현하며,
 * 자체 텍스처를 가리키는 미리 만들어진 {@link BakedGlyph}를 반환한다.
 */
public final class EmoteGlyph implements GlyphInfo {
    private final BakedGlyph baked;
    private final float advance;

    public EmoteGlyph(BakedGlyph baked, float advance) {
        this.baked = baked;
        this.advance = advance;
    }

    public BakedGlyph baked() {
        return baked;
    }

    @Override
    public float getAdvance() {
        return advance;
    }

    @Override
    public BakedGlyph bake(Function<SheetGlyphInfo, BakedGlyph> function) {
        return baked;
    }
}

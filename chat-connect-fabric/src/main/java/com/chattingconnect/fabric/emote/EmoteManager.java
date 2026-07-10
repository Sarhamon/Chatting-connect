package com.chattingconnect.fabric.emote;

import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.GlyphRenderTypes;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 치지직 이모티콘을 마인크래프트 채팅에 인라인 이미지로 그리기 위한 매니저.
 * URL마다 사설 영역 코드포인트를 하나 배정하고, PNG를 비동기로 받아 텍스처로 업로드한 뒤
 * 해당 코드포인트용 {@link BakedGlyph}를 만든다. FontSet mixin이 이 글리프를 조회해 렌더링한다.
 */
public final class EmoteManager {

    /** 글리프에 배정하는 사설 사용 영역(PUA) 코드포인트 범위. */
    private static final int BASE = 0xE000;
    private static final int LIMIT = BASE + 4096;
    /** 0xE000~0xE00F: 플랫폼 아이콘용 고정 코드포인트. 그 위(EMOTE_BASE~)는 동적 이모티콘. */
    public static final int ICON_CHZZK = 0xE000;
    public static final int ICON_SOOP = 0xE001;
    public static final int ICON_TWITCH = 0xE002;
    private static final int EMOTE_BASE = 0xE010;
    /** 이모티콘 렌더 높이(px). 텍스트(8px)보다 크게 잡아 잘 보이게 한다. 키우면 위/아래 줄과 더 겹친다. */
    private static final float TARGET_HEIGHT = 12.0f;
    /** 이모티콘 세로 중심을 맞출 기준선(텍스트 줄 중앙 근처). */
    private static final float VERTICAL_CENTER = 4.0f;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Object LOCK = new Object();
    /** 이모티콘 이미지 URL → 배정된 코드포인트. */
    private static final Map<String, Integer> urlToCodepoint = new HashMap<>();
    /** 준비 완료된 코드포인트 → 글리프. (렌더 스레드에서 put, 여러 스레드에서 get) */
    private static final Map<Integer, EmoteGlyph> glyphs = new ConcurrentHashMap<>();
    /** 배정됐지만 아직 다운로드 전인 코드포인트에 쓰는 빈 글리프(공간만 차지, 렌더 없음). 렌더 스레드에서 지연 생성. */
    private static volatile EmoteGlyph pendingGlyph;
    private static volatile int nextCodepoint = EMOTE_BASE;

    /** 플랫폼 아이콘 코드포인트 → 번들 텍스처 위치(정사각형 가정). */
    private static final Map<Integer, ResourceLocation> ICON_TEXTURES = new HashMap<>();

    static {
        ICON_TEXTURES.put(ICON_CHZZK, new ResourceLocation("chattingconnect", "textures/gui/chzzk.png"));
        ICON_TEXTURES.put(ICON_SOOP, new ResourceLocation("chattingconnect", "textures/gui/soop.png"));
        ICON_TEXTURES.put(ICON_TWITCH, new ResourceLocation("chattingconnect", "textures/gui/twitch.png"));
    }

    private EmoteManager() {
    }

    /** 코드포인트를 담은 1글자 문자열(채팅 Component에 넣어 아이콘/이모티콘으로 렌더). */
    public static String glyphString(int codePoint) {
        return new String(Character.toChars(codePoint));
    }

    /** FontSet mixin이 호출(렌더 스레드): 준비됐으면 실제 글리프, 배정만 됐으면 빈 글리프, 범위 밖이면 null. */
    public static BakedGlyph getGlyph(int codePoint) {
        EmoteGlyph glyph = glyphInfoOrPending(codePoint);
        return glyph == null ? null : glyph.baked();
    }

    /** FontSet mixin이 호출(렌더 스레드): 준비됐으면 실제 글리프, 배정만 됐으면 빈 글리프, 범위 밖이면 null. */
    public static GlyphInfo getGlyphInfo(int codePoint) {
        return glyphInfoOrPending(codePoint);
    }

    private static EmoteGlyph glyphInfoOrPending(int codePoint) {
        if (codePoint < BASE || codePoint >= LIMIT) {
            return null;
        }
        EmoteGlyph glyph = glyphs.get(codePoint);
        if (glyph != null) {
            return glyph;
        }
        // 플랫폼 아이콘: 번들 텍스처로 즉시 빌드(다운로드 불필요).
        ResourceLocation iconTexture = ICON_TEXTURES.get(codePoint);
        if (iconTexture != null) {
            return buildStaticGlyph(codePoint, iconTexture);
        }
        // 배정은 됐지만 아직 이미지 미도착 → 공간만 차지하는 빈 글리프(다음 프레임에 이미지로 교체됨).
        return codePoint < nextCodepoint ? pending() : null;
    }

    /** 번들 텍스처(정사각형 가정)로 아이콘 글리프를 만들어 캐시한다. 렌더 스레드에서 호출됨. */
    private static EmoteGlyph buildStaticGlyph(int codePoint, ResourceLocation texture) {
        GlyphRenderTypes renderTypes = GlyphRenderTypes.createForColorTexture(texture);
        float up = 3.0F + VERTICAL_CENTER - TARGET_HEIGHT / 2.0F;
        float down = 3.0F + VERTICAL_CENTER + TARGET_HEIGHT / 2.0F;
        BakedGlyph baked = new BakedGlyph(renderTypes, 0.0F, 1.0F, 0.0F, 1.0F, 0.0F, TARGET_HEIGHT, up, down);
        EmoteGlyph glyph = new EmoteGlyph(baked, TARGET_HEIGHT + 1.0F);
        glyphs.put(codePoint, glyph);
        return glyph;
    }

    /**
     * 해당 이모티콘 URL에 배정된 코드포인트를 반환한다(항상 유효, 슬롯 소진 시 -1).
     * 처음 보는 URL이면 비동기 다운로드를 시작한다. 다운로드 완료 전이라도 코드포인트를 emit하면
     * 채팅이 매 프레임 재렌더되며 완료 시 그 자리에 이미지가 나타난다.
     */
    public static int codepointFor(String url) {
        int codePoint;
        boolean startLoad = false;
        synchronized (LOCK) {
            Integer existing = urlToCodepoint.get(url);
            if (existing != null) {
                return existing;
            }
            if (nextCodepoint >= LIMIT) {
                return -1;
            }
            codePoint = nextCodepoint++;
            urlToCodepoint.put(url, codePoint);
            startLoad = true;
        }
        if (startLoad) {
            download(url, codePoint);
        }
        return codePoint;
    }

    /** 빈 글리프(제로 면적 → 아무것도 안 그림, advance만 확보). 렌더 스레드에서 최초 1회 생성. */
    private static EmoteGlyph pending() {
        EmoteGlyph p = pendingGlyph;
        if (p == null) {
            ResourceLocation location = new ResourceLocation("chattingconnect", "pending");
            NativeImage blank = new NativeImage(1, 1, true);
            blank.setPixelRGBA(0, 0, 0);
            Minecraft.getInstance().getTextureManager().register(location, new DynamicTexture(blank));
            GlyphRenderTypes renderTypes = GlyphRenderTypes.createForColorTexture(location);
            // u0==u1, left==right → 제로 면적 쿼드 → 렌더 없음. advance만 이모티콘 폭만큼 확보.
            BakedGlyph baked = new BakedGlyph(renderTypes, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 3.0F, 3.0F);
            p = new EmoteGlyph(baked, TARGET_HEIGHT + 1.0F);
            pendingGlyph = p;
        }
        return p;
    }

    private static void download(String url, int codePoint) {
        LOGGER.info("[emote] downloading cp={} url={}", codePoint, url);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", UA)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray()).thenAccept(resp -> {
            LOGGER.info("[emote] response cp={} status={} bytes={}", codePoint, resp.statusCode(),
                    resp.body() == null ? 0 : resp.body().length);
            if (resp.statusCode() == 200) {
                byte[] data = resp.body();
                Minecraft.getInstance().execute(() -> register(codePoint, data));
            }
        }).exceptionally(ex -> {
            LOGGER.warn("[emote] download failed cp={} url={}: {}", codePoint, url, ex.toString());
            return null;
        });
    }

    /** 렌더 스레드에서 실행: PNG를 텍스처로 업로드하고 글리프를 만든다. */
    private static void register(int codePoint, byte[] data) {
        NativeImage image;
        try {
            image = NativeImage.read(new ByteArrayInputStream(data));
        } catch (Exception e) {
            LOGGER.warn("[emote] decode failed cp={}: {}", codePoint, e.toString());
            return; // 디코딩 실패 시 미준비 상태로 남겨 placeholder가 계속 표시된다.
        }
        int w = image.getWidth();
        int h = image.getHeight();
        ResourceLocation location = new ResourceLocation("chattingconnect", "emote/" + (codePoint - BASE));
        // DynamicTexture가 NativeImage 소유권을 가져가 텍스처 해제 시 닫는다.
        Minecraft.getInstance().getTextureManager().register(location, new DynamicTexture(image));

        float width = TARGET_HEIGHT * (float) w / (float) h;
        GlyphRenderTypes renderTypes = GlyphRenderTypes.createForColorTexture(location);
        // BakedGlyph.render는 (up-3, down-3)에 그린다. 중심을 VERTICAL_CENTER에 맞춰 위아래로 균등 확장.
        float up = 3.0F + VERTICAL_CENTER - TARGET_HEIGHT / 2.0F;
        float down = 3.0F + VERTICAL_CENTER + TARGET_HEIGHT / 2.0F;
        BakedGlyph baked = new BakedGlyph(renderTypes, 0.0F, 1.0F, 0.0F, 1.0F, 0.0F, width, up, down);
        glyphs.put(codePoint, new EmoteGlyph(baked, width + 1.0F));
        LOGGER.info("[emote] ready cp={} {}x{} texture={}", codePoint, w, h, location);
    }
}

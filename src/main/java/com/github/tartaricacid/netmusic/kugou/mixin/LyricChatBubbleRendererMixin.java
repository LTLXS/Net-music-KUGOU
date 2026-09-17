package com.github.tartaricacid.netmusic.kugou.mixin;

import com.github.tartaricacid.netmusic.kugou.KuGouLogger;
import com.github.tartaricacid.netmusic.NetMusic;
import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.github.tartaricacid.netmusic.client.event.ConfigEvent;
import com.github.tartaricacid.netmusic.compat.tlm.chatbubble.LyricChatBubbleData;
import com.github.tartaricacid.netmusic.compat.tlm.client.chatbubble.LyricChatBubbleRenderer;
import com.github.tartaricacid.netmusic.kugou.config.ClientConfig;
import com.github.tartaricacid.netmusic.kugou.lyric.KuGouMaidLyricCache;
import com.github.tartaricacid.netmusic.kugou.util.LyricFloorKey;
import com.github.tartaricacid.netmusic.kugou.lyric.LrcConverter;
import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.EntityMaidRenderer;
import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.chatbubble.EntityGraphics;
import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.chatbubble.IChatBubbleRenderer;
import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.WeakHashMap;

@Mixin(value = LyricChatBubbleRenderer.class, remap = false)
public abstract class LyricChatBubbleRendererMixin implements IChatBubbleRenderer {
    @Shadow(remap = false) @Nullable
    private volatile LyricRecord lyric;
    @Shadow(remap = false)
    private volatile boolean isLoading;
    @Shadow(remap = false)
    private long recordStartTick;
    @Shadow(remap = false)
    private Font font;
    @Shadow(remap = false)
    private void renderDefault(EntityGraphics graphics) {}

    @Unique
    private static final int ROMAJI_COLOR = 0xFF666666;

    @Unique
    private static final Map<Object, Integer> LINE_COUNT_MAP = new WeakHashMap<>();

    @Unique
    private static final Map<Object, Int2ObjectSortedMap<String>> ROMAJI_MAP = new WeakHashMap<>();

    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void kugou$onRendererInit(LyricChatBubbleData data, ResourceLocation bg, CallbackInfo ci) {
        try {
            if (data.getSongId() > 0) {
                return;
            }
            String songName = data.getSongName();
            KuGouMaidLyricCache.CachedLyric cached = KuGouMaidLyricCache.peekBySongName(songName);
            if (cached == null || cached.lrcText == null || cached.lrcText.isEmpty()) {
                KuGouLogger.warn(
                        "[NetMusicKuGou] no LRC in cache for songName='{}'", songName);
                return;
            }
            LyricRecord record = LrcConverter.toLyricRecordWithTranslation(
                    cached.lrcText, cached.transJson, songName);
            if (record == null) {
                KuGouLogger.warn(
                        "[NetMusicKuGou] failed to parse LRC for songName='{}'", songName);
                return;
            }
            ROMAJI_MAP.put(this, cached.romaji);
            // 反射写入 lyric 字段和 isLoading 字段
            java.lang.reflect.Field lyricField = LyricChatBubbleRenderer.class.getDeclaredField("lyric");
            lyricField.setAccessible(true);
            lyricField.set(this, record);

            java.lang.reflect.Field isLoadingField = LyricChatBubbleRenderer.class.getDeclaredField("isLoading");
            isLoadingField.setAccessible(true);
            isLoadingField.setBoolean(this, false);

            // === 自动对齐 startTick ===
            // 所以 audio 实际开始播放时间晚于 startTick。
            // 用 gameTime - firstLineMs/50 对齐 startTick
            long firstLineMs = 0L;
            var lyricsMap = record.getLyrics();
            if (lyricsMap != null && !lyricsMap.isEmpty()) {
                firstLineMs = lyricsMap.firstIntKey();
            }
            long now = Minecraft.getInstance().level.getGameTime();
            long alignedStartTick = now - (firstLineMs / 50L);
            try {
                java.lang.reflect.Field startTickField = LyricChatBubbleData.class.getDeclaredField("startTick");
                startTickField.setAccessible(true);
                startTickField.setLong(data, alignedStartTick);
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            KuGouLogger.error("[NetMusicKuGou] renderer init mixin failed", t);
        }
    }

    /**
     * @author KuGouAddon
     * @reason 把父模组"原文 + 翻译"双行扩展为"原文 + 翻译 + 罗马音"最多三行；
     *         行数由 {@link ClientConfig} 控制。
     *         <p>布局：翻译在顶（与父模组 2 行保持一致），原文在中，罗马音在底。
     */
    @Overwrite(remap = false)
    public void render(EntityMaidRenderer renderer, EntityGraphics graphics) {
        final LyricRecord tmpLyric = this.lyric;
        if (tmpLyric == null) {
            this.renderDefault(graphics);
            return;
        }
        Int2ObjectSortedMap<String> lyrics = tmpLyric.getLyrics();
        if (lyrics == null || lyrics.isEmpty()) {
            this.renderDefault(graphics);
            return;
        }
        if (this.recordStartTick < 0) {
            this.renderDefault(graphics);
            return;
        }

        int currentTick = (int) (graphics.getMaid().level().getGameTime() - this.recordStartTick);

        int currentKey = LyricFloorKey.floorKey(lyrics, currentTick);
        MutableComponent currentLyric = Component.literal(lyrics.get(currentKey));
        int currentLyricWidth = font.width(currentLyric);
        int currentLyricColor = ConfigEvent.MAID_ORIGINAL_COLOR;

        boolean showTranslation = ClientConfig.LYRIC_SHOW_TRANSLATION.get();
        MutableComponent transLyric = null;
        int transLyricWidth = 0;
        if (showTranslation) {
            Int2ObjectSortedMap<String> transLyrics = tmpLyric.getTransLyrics();
            if (transLyrics != null && !transLyrics.isEmpty()) {
                String transText = transLyrics.get(LyricFloorKey.floorKey(transLyrics, currentTick));
                if (transText != null && !transText.isEmpty() && !transText.isBlank()) {
                    transLyric = Component.literal(transText);
                    transLyricWidth = font.width(transLyric);
                }
            }
        }

        boolean showRomaji = ClientConfig.LYRIC_SHOW_ROMAJI.get();
        MutableComponent romajiLyric = null;
        int romajiLyricWidth = 0;
        if (showRomaji) {
            Int2ObjectSortedMap<String> romajiMap = ROMAJI_MAP.get(this);
            if (romajiMap != null && !romajiMap.isEmpty()) {
                int romajiFirstKey = romajiMap.firstIntKey();
                if (currentTick >= romajiFirstKey) {
                    int romajiTick = LyricFloorKey.floorKey(romajiMap, currentTick);
                    String romajiText = romajiMap.get(romajiTick);
                    if (romajiText != null && !romajiText.isEmpty() && !romajiText.isBlank()) {
                        romajiLyric = Component.literal(romajiText);
                        romajiLyricWidth = font.width(romajiLyric);
                    }
                }
            }
        }

        if (transLyric == null && romajiLyric == null) {
            currentLyricColor = ConfigEvent.MAID_TRANSLATED_COLOR;
        }

        int y = 2;
        int yTrans = -1, yCurrent = -1, yRomaji = -1;
        if (transLyric != null) {
            yTrans = y;
            y += 12;
        }
        yCurrent = y;
        if (romajiLyric != null) {
            y += 12;
        }
        yRomaji = y;

        int maxWidth = Math.max(currentLyricWidth, Math.max(transLyricWidth, romajiLyricWidth));
        graphics.drawWordWrap(font, currentLyric, (maxWidth - currentLyricWidth) / 2, yCurrent, 1000, currentLyricColor);
        if (transLyric != null) {
            graphics.drawWordWrap(font, transLyric, (maxWidth - transLyricWidth) / 2, yTrans, 1000, ConfigEvent.MAID_TRANSLATED_COLOR);
        }
        if (romajiLyric != null) {
            graphics.drawWordWrap(font, romajiLyric, (maxWidth - romajiLyricWidth) / 2, yRomaji, 1000, ROMAJI_COLOR);
        }

        int lineCount = 1 + (transLyric != null ? 1 : 0) + (romajiLyric != null ? 1 : 0);
        LINE_COUNT_MAP.put(this, lineCount);
    }

    /**
     * @author KuGouAddon
     * @reason 根据当前显示的实际行数动态返回气泡高度。
     *         1 行=12, 2 行=24, 3 行=36。父模组原版无脑返回 24（只要有 transLyrics）。
     */
    @Overwrite(remap = false)
    public int getHeight() {
        if (this.lyric == null) {
            return 12;
        }
        Integer count = LINE_COUNT_MAP.get(this);
        if (count == null) {
            int n = 1;
            final LyricRecord tmp = this.lyric;
            if (tmp.getTransLyrics() != null && !tmp.getTransLyrics().isEmpty()) n++;
            if (ClientConfig.LYRIC_SHOW_ROMAJI.get()
                    && ROMAJI_MAP.get(this) != null
                    && !ROMAJI_MAP.get(this).isEmpty()) {
                n++;
            }
            return n * 12;
        }
        return count * 12;
    }

    /**
     * @author KuGouAddon
     * @reason 根据当前显示的所有行（含罗马音）动态返回气泡宽度。
     */
    @Overwrite(remap = false)
    public int getWidth() {
        final LyricRecord tmpLyric = this.lyric;
        if (tmpLyric == null) {
            if (this.isLoading) {
                return this.font.width(Component.translatable("gui.netmusic.lyric.waiting"));
            }
            return this.font.width(Component.translatable("gui.netmusic.lyric.no_lyric"));
        }
        int currentTick = (int) (Minecraft.getInstance().level.getGameTime() - this.recordStartTick);

        int maxWidth = 0;
        Int2ObjectSortedMap<String> lyrics = tmpLyric.getLyrics();
        if (lyrics != null && !lyrics.isEmpty()) {
            maxWidth = Math.max(maxWidth,
                    this.font.width(Component.literal(lyrics.get(LyricFloorKey.floorKey(lyrics, currentTick)))));
        }
        if (ClientConfig.LYRIC_SHOW_TRANSLATION.get()) {
            Int2ObjectSortedMap<String> transLyrics = tmpLyric.getTransLyrics();
            if (transLyrics != null && !transLyrics.isEmpty()) {
                String transText = transLyrics.get(LyricFloorKey.floorKey(transLyrics, currentTick));
                if (transText != null && !transText.isEmpty() && !transText.isBlank()) {
                    maxWidth = Math.max(maxWidth, this.font.width(Component.literal(transText)));
                }
            }
        }
        if (ClientConfig.LYRIC_SHOW_ROMAJI.get()) {
            Int2ObjectSortedMap<String> romajiMap = ROMAJI_MAP.get(this);
            if (romajiMap != null && !romajiMap.isEmpty()) {
                int romajiFirstKey = romajiMap.firstIntKey();
                if (currentTick >= romajiFirstKey) {
                    int romajiTick = LyricFloorKey.floorKey(romajiMap, currentTick);
                    String romajiText = romajiMap.get(romajiTick);
                    if (romajiText != null && !romajiText.isEmpty() && !romajiText.isBlank()) {
                        maxWidth = Math.max(maxWidth, this.font.width(Component.literal(romajiText)));
                    }
                }
            }
        }
        return Math.max(60, maxWidth + 4);
    }
}

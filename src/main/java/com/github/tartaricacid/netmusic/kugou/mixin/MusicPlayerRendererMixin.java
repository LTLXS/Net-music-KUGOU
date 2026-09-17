package com.github.tartaricacid.netmusic.kugou.mixin;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.github.tartaricacid.netmusic.client.event.ConfigEvent;
import com.github.tartaricacid.netmusic.client.renderer.MusicPlayerRenderer;
import com.github.tartaricacid.netmusic.config.GeneralConfig;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.github.tartaricacid.netmusic.kugou.config.ClientConfig;
import com.github.tartaricacid.netmusic.kugou.lyric.BlockRomajiRegistry;
import com.github.tartaricacid.netmusic.kugou.util.LyricFloorKey;
import com.github.tartaricacid.netmusic.kugou.util.MirrorLyricRenderer;
import com.github.tartaricacid.netmusic.tileentity.TileEntityMusicPlayer;
import net.minecraft.core.BlockPos;
import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import org.apache.commons.lang3.StringUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mixin 到父模组 {@code MusicPlayerRenderer.renderLyric}，把渲染从 2 行（原文 + 翻译）
 * 扩展到最多 3 行（原文 + 翻译 + 罗马音），行数由 {@link ClientConfig#LYRIC_SHOW_TRANSLATION}
 * 和 {@link ClientConfig#LYRIC_SHOW_ROMAJI} 控制。
 * <p>
 * 父模组"停止播放就清空 lyricRecord"的位置我们同步清空
 * {@link BlockRomajiRegistry}，避免侧通道无限增长。
 */
@Mixin(value = MusicPlayerRenderer.class, remap = false)
public abstract class MusicPlayerRendererMixin {

    @Shadow(remap = false)
    private Font font;

    @Shadow(remap = false)
    private BlockEntityRenderDispatcher dispatcher;

    /**
     * @author KuGouAddon
     * @reason 把父模组的"原文 + 翻译"双行扩展为"原文 + 翻译 + 罗马音"最多三行，
     *         行数由 ClientConfig 控制。
     */
    @Overwrite(remap = false)
    private void renderLyric(TileEntityMusicPlayer te, PoseStack poseStack,
                              MultiBufferSource bufferIn, int combinedLightIn) {
        if (!GeneralConfig.ENABLE_PLAYER_LYRICS.get()) {
            return;
        }
        LyricRecord lyricRecord = te.lyricRecord;
        if (lyricRecord == null) {
            return;
        }
        Int2ObjectSortedMap<String> lyrics = lyricRecord.getLyrics();
        if (lyrics == null || lyrics.isEmpty()) {
            return;
        }

        if (!te.isPlay()) {
            te.lyricRecord = null;
            BlockRomajiRegistry.remove(te.getBlockPos());
            clearEggState(te.getBlockPos());
            return;
        }

        Camera camera = this.dispatcher.camera;
        int originalColor = ConfigEvent.PLAYER_ORIGINAL_COLOR;
        int transColor = ConfigEvent.PLAYER_TRANSLATED_COLOR;
        int romajiColor = ConfigEvent.PLAYER_TRANSLATED_COLOR;

        int currentTick = currentPlayTick(te);
        int currentKey = LyricFloorKey.floorKey(lyrics, currentTick);

        String lyric = lyrics.get(currentKey);
        MutableComponent currentLine = StringUtils.isNotBlank(lyric)
                ? Component.literal(lyric)
                : Component.empty();

        boolean mainLyricBlank = StringUtils.isBlank(lyric);

        boolean showTranslation = ClientConfig.LYRIC_SHOW_TRANSLATION.get();
        MutableComponent translatedLine = null;
        if (showTranslation && !mainLyricBlank) {
            Int2ObjectSortedMap<String> transLyrics = lyricRecord.getTransLyrics();
            if (transLyrics != null && !transLyrics.isEmpty()) {
                String transLyric = transLyrics.get(LyricFloorKey.floorKey(transLyrics, currentTick));
                if (StringUtils.isNotBlank(transLyric)) {
                    translatedLine = Component.literal(transLyric);
                }
            }
        }

        boolean showRomaji = ClientConfig.LYRIC_SHOW_ROMAJI.get();
        MutableComponent romajiLine = null;
        if (showRomaji && !mainLyricBlank) {
            Int2ObjectSortedMap<String> romajiMap = BlockRomajiRegistry.get(te.getBlockPos());
            if (romajiMap != null && !romajiMap.isEmpty()) {
                int romajiFirstKey = romajiMap.firstIntKey();
                if (currentTick >= romajiFirstKey) {
                    int romajiKey = LyricFloorKey.floorKey(romajiMap, currentTick);
                    String romajiText = romajiMap.get(romajiKey);
                    if (StringUtils.isNotBlank(romajiText)) {
                        romajiLine = Component.literal(romajiText);
                    }
                }
            }
        }

        int currentColor = (translatedLine == null && romajiLine == null)
                ? transColor : originalColor;

        float y = 0.5f;
        if (translatedLine != null || romajiLine != null) y += 0.5f;
        if (romajiLine != null) y += 0.5f;

        String songName = getSongNameFromCD(te);

        poseStack.pushPose();
        poseStack.translate(0.5, 1.625, 0.5);
        poseStack.mulPose(camera.rotation());
        poseStack.scale(-0.025F, -0.025F, 0.025F);

        float opacity = Minecraft.getInstance().options.getBackgroundOpacity(0.25F);
        int bgColor = (int) (opacity * 255.0F) << 24;

        MultiBufferSource.BufferSource textSource = Minecraft.getInstance().renderBuffers().bufferSource();
        com.mojang.blaze3d.systems.RenderSystem.disableCull();

        // 镜像版本：对顶点 x 取反，水平翻转文字（不依赖 scale(-1,1,1)）
        MultiBufferSource mirrorSrc = MirrorLyricRenderer.mirror(textSource);

        boolean swapEgg = isMirrorEggSong(songName)
                && isSwapMoment(te.getBlockPos(), songName, lyric, currentKey);

        if (swapEgg) {
            if (currentLine != null && currentLine != Component.empty()) {
                drawMirrored(currentLine, -y, currentColor, Font.DisplayMode.NORMAL,
                        mirrorSrc, poseStack, combinedLightIn, bgColor);
            }
            if (translatedLine != null) {
                drawMirrored(translatedLine, -y - 12, transColor, Font.DisplayMode.NORMAL,
                        mirrorSrc, poseStack, combinedLightIn, bgColor);
            }
        } else {
            Font.DisplayMode mainMode = Font.DisplayMode.NORMAL;
            if (currentLine != null && currentLine != Component.empty()) {
                float currentLineWidth = (float) (-this.font.width(currentLine) / 2);
                this.font.drawInBatch(currentLine, currentLineWidth, -y, currentColor, false,
                        poseStack.last().pose(), textSource, mainMode,
                        bgColor, combinedLightIn);
            }
            if (translatedLine != null) {
                float w = (float) (-this.font.width(translatedLine) / 2);
                this.font.drawInBatch(translatedLine, w, -y - 12, transColor, false,
                        poseStack.last().pose(), textSource, mainMode,
                        bgColor, combinedLightIn);
            }
        }
        if (romajiLine != null) {
            Font.DisplayMode romajiMode = Font.DisplayMode.NORMAL;
            float w = (float) (-this.font.width(romajiLine) / 2);
            this.font.drawInBatch(romajiLine, w, -y - 24, romajiColor, false,
                    poseStack.last().pose(), textSource, romajiMode,
                    bgColor, combinedLightIn);
        }

        textSource.endBatch();

        // 这里再单独绘制并 flush，确保它始终画在最上层，不会被音乐盒方块本身遮挡。
        if (isMirrorEggSong(songName)) {
            if (swapEgg) {
                Font.DisplayMode eggMode = Font.DisplayMode.SEE_THROUGH;
                if (currentLine != null && currentLine != Component.empty()) {
                    float w = (float) (-this.font.width(currentLine) / 2);
                    this.font.drawInBatch(currentLine, w, -y + 11, fadeColor(currentColor), false,
                            poseStack.last().pose(), textSource, eggMode, bgColor, combinedLightIn);
                }
                if (translatedLine != null) {
                    float w = (float) (-this.font.width(translatedLine) / 2);
                    this.font.drawInBatch(translatedLine, w, -y + 23, fadeColor(transColor), false,
                            poseStack.last().pose(), textSource, eggMode, bgColor, combinedLightIn);
                }
            } else {
                if (currentLine != null && currentLine != Component.empty()) {
                    drawMirrored(currentLine, -y + 11, fadeColor(currentColor), Font.DisplayMode.SEE_THROUGH,
                            mirrorSrc, poseStack, combinedLightIn, bgColor);
                }
                if (translatedLine != null) {
                    drawMirrored(translatedLine, -y + 23, fadeColor(transColor), Font.DisplayMode.SEE_THROUGH,
                            mirrorSrc, poseStack, combinedLightIn, bgColor);
                }
            }
        }
        textSource.endBatch();

        poseStack.popPose();
        com.mojang.blaze3d.systems.RenderSystem.enableCull();
    }

    /** 当前播放进度（已播放 tick）= 总时长 - 剩余时间。歌词 map 的 key 即 tick 偏移。 */
    private static int currentPlayTick(TileEntityMusicPlayer te) {
        int remain = te.getCurrentTime();
        try {
            IItemHandler inv = te.getPlayerInv();
            if (inv != null) {
                ItemStack cd = inv.getStackInSlot(0);
                if (!cd.isEmpty()) {
                    ItemMusicCD.SongInfo info = ItemMusicCD.getSongInfo(cd);
                    if (info != null) {
                        int total = info.songTime * 20 + 64;
                        return Math.max(0, total - remain);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private static final Set<String> MIRROR_EGG_KEYWORDS = Set.of(
            "アンノウン", "unknown mother", "unknownmothergoose",
            "あんのうん", "announ", "mothergoose"
    );

    private static String getSongNameFromCD(TileEntityMusicPlayer te) {
        try {
            IItemHandler inv = te.getPlayerInv();
            if (inv != null) {
                ItemStack cd = inv.getStackInSlot(0);
                if (!cd.isEmpty()) {
                    ItemMusicCD.SongInfo info = ItemMusicCD.getSongInfo(cd);
                    if (info != null) {
                        return info.songName;
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static boolean isMirrorEggSong(String songName) {
        if (songName == null) return false;
        String lower = songName.toLowerCase();
        for (String kw : MIRROR_EGG_KEYWORDS) {
            if (lower.contains(kw)) return true;
        }
        return false;
    }

    /**
     * 彩蛋（镜像歌词交换）状态：原先是全局静态字段，多个音乐盒同时播放 / 切换歌曲时
     * 会互相污染。改为按方块坐标（每个音乐盒）独立保存。
     * 数组 [lastSeenTick, occurCount, lastOccurLyricTick, swapFiredTick]，初始 [-1,0,-1,-1]。
     */
    private static final ConcurrentHashMap<BlockPos, int[]> EGG_SWAP_STATE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<BlockPos, String> EGG_SONG_BY_POS = new ConcurrentHashMap<>();

    private static void clearEggState(BlockPos pos) {
        EGG_SWAP_STATE.remove(pos);
        EGG_SONG_BY_POS.remove(pos);
    }

    private static boolean isSwapMoment(BlockPos pos, String songName, String lyric, int currentLineTick) {
        int[] st = EGG_SWAP_STATE.computeIfAbsent(pos, p -> new int[]{-1, 0, -1, -1});
        String lastEggSong = EGG_SONG_BY_POS.get(pos);
        if (!java.util.Objects.equals(songName, lastEggSong)) {
            EGG_SONG_BY_POS.put(pos, songName);
            st[0] = -1; st[1] = 0; st[2] = -1; st[3] = -1;
        }
        // tick 大幅回退：重播/换进度，重新计数
        if (st[0] >= 0 && currentLineTick < st[0] - 1000) {
            st[0] = -1; st[1] = 0; st[2] = -1; st[3] = -1;
        }
        st[0] = Math.max(st[0], currentLineTick);

        if (lyric == null || !lyric.contains("僕が見える")) {
            return false;
        }
        if (st[3] >= 0 && currentLineTick == st[3]) {
            return true;
        }
        if (currentLineTick > st[2]) {
            st[1]++;
            st[2] = currentLineTick;
            if (st[1] == 2 || st[1] == 3) {
                st[3] = currentLineTick;
                return true;
            }
        }
        return false;
    }

    private static int fadeColor(int color) {
        return 0xB3 << 24 | (color & 0x00FFFFFF);
    }

    /**
     * 用镜像 buffer 绘制一行文字（水平翻转）。镜像 buffer 在顶点层面把 x 取反，
     * 因此文字会绕 x=0 水平镜像，且不再依赖 PoseStack 上的 scale(-1,1,1)，
     * 从而不会与基础 scale(-0.025, -0.025, 0.025) 抵消、也不会"钉"在相机旋转的一侧。
     */
    private void drawMirrored(Component text, float y, int color, Font.DisplayMode mode,
                              MultiBufferSource mirrorSrc, PoseStack poseStack,
                              int combinedLightIn, int bgColor) {
        float w = (float) (-this.font.width(text) / 2);
        this.font.drawInBatch(text, w, y, color, false,
                poseStack.last().pose(), mirrorSrc, mode,
                bgColor, combinedLightIn);
    }
}

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
import net.neoforged.neoforge.items.IItemHandler;
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
                              MultiBufferSource bufferIn, int combinedLightIn, float partialTicks) {
        if (!GeneralConfig.ENABLE_PLAYER_LYRICS.get()) {
            return;
        }
        LyricRecord lyricRecord = te.lyricRecord;
        if (lyricRecord == null) {
            // 关键：这里不能 BlockRomajiRegistry.remove()！
            // 竞态条件：父模组清空 lyricRecord 是在 NetMusicSound 即将被新实例替换的"间隙"，
            // 旧歌清掉 lyricRecord 触发的 remove，会把下一首 onHandleHead 刚 put 进来的
            // 新 romaji 数据一并清掉（导致 romaji 行永远不显示）。
            // 让 entry 留在 registry，由下一首歌的 put 自然覆盖。
            return;
        }
        Int2ObjectSortedMap<String> lyrics = lyricRecord.getLyrics();
        if (lyrics == null || lyrics.isEmpty()) {
            return;
        }

        // 如果已经停止播放了，直接清空（父模组行为）
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

        // 原文为空时隐藏翻译/罗马音（属于其他 tick 的孤儿行）
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

        // 旋转逻辑：优先用 SableCompat.getLookVector 算 block→camera 方向，
        net.minecraft.world.phys.Vec3 lookVector = com.github.tartaricacid.netmusic.compat.sable.SableCompat.getLookVector(
                te.getBlockPos(), camera, partialTicks);
        if (lookVector != null) {
            double horizontalLen = Math.sqrt(lookVector.x * lookVector.x + lookVector.z * lookVector.z);
            float yawDeg = (float) Math.toDegrees(Math.atan2(-lookVector.x, lookVector.z));
            float pitchDeg = (float) -Math.toDegrees(Math.atan2(lookVector.y, horizontalLen));
            poseStack.mulPose(com.mojang.math.Axis.YN.rotationDegrees(yawDeg));
            poseStack.mulPose(com.mojang.math.Axis.XN.rotationDegrees(pitchDeg));
        } else {
            poseStack.mulPose(com.mojang.math.Axis.YN.rotationDegrees(camera.getYRot()));
            poseStack.mulPose(com.mojang.math.Axis.XN.rotationDegrees(-camera.getXRot()));
        }
        poseStack.scale(-0.025F, -0.025F, 0.025F);

        float opacity = Minecraft.getInstance().options.getBackgroundOpacity(0.25F);
        int bgColor = (int) (opacity * 255.0F) << 24;

        // 注意：必须用具体类型 MultiBufferSource.BufferSource（接口没有 endBatch()）
        MultiBufferSource.BufferSource textSource = Minecraft.getInstance().renderBuffers().bufferSource();
        com.mojang.blaze3d.systems.RenderSystem.disableCull();

        boolean swapEgg = isMirrorEggSong(songName)
                && isSwapMoment(te.getBlockPos(), songName, lyric, currentKey);

        if (swapEgg) {
            if (currentLine != null && currentLine != Component.empty()) {
                MirrorLyricRenderer.render(poseStack, textSource, this.font,
                        currentLine, -y, currentColor, combinedLightIn, true,
                        Font.DisplayMode.NORMAL);
            }
            if (translatedLine != null) {
                MirrorLyricRenderer.render(poseStack, textSource, this.font,
                        translatedLine, -y - 12, transColor, combinedLightIn, true,
                        Font.DisplayMode.NORMAL);
            }
        } else {
            if (currentLine != null && currentLine != Component.empty()) {
                float currentLineWidth = (float) (-this.font.width(currentLine) / 2);
                // 必须用 textSource（Sodium pipeline 不会 flush bufferIn 的 text RenderType）。
                this.font.drawInBatch(currentLine, currentLineWidth, -y, currentColor, false,
                        poseStack.last().pose(), textSource, Font.DisplayMode.NORMAL,
                        bgColor, combinedLightIn);
            }
            if (translatedLine != null) {
                float w = (float) (-this.font.width(translatedLine) / 2);
                this.font.drawInBatch(translatedLine, w, -y - 12, transColor, false,
                        poseStack.last().pose(), textSource, Font.DisplayMode.NORMAL,
                        bgColor, combinedLightIn);
            }
        }
        if (romajiLine != null) {
            float w = (float) (-this.font.width(romajiLine) / 2);
            this.font.drawInBatch(romajiLine, w, -y - 24, romajiColor, false,
                    poseStack.last().pose(), textSource, Font.DisplayMode.NORMAL,
                    bgColor, combinedLightIn);
        }

        if (isMirrorEggSong(songName)) {
            if (swapEgg) {
                if (currentLine != null && currentLine != Component.empty()) {
                    MirrorLyricRenderer.render(poseStack, textSource, this.font,
                            currentLine, -y + 11, fadeColor(currentColor), combinedLightIn,
                            false, Font.DisplayMode.SEE_THROUGH);
                }
                if (translatedLine != null) {
                    MirrorLyricRenderer.render(poseStack, textSource, this.font,
                            translatedLine, -y + 23, fadeColor(transColor), combinedLightIn,
                            false, Font.DisplayMode.SEE_THROUGH);
                }
            } else {
                if (currentLine != null && currentLine != Component.empty()) {
                    MirrorLyricRenderer.render(poseStack, textSource, this.font,
                            currentLine, -y + 11, fadeColor(currentColor), combinedLightIn,
                            true, Font.DisplayMode.SEE_THROUGH);
                }
                if (translatedLine != null) {
                    MirrorLyricRenderer.render(poseStack, textSource, this.font,
                            translatedLine, -y + 23, fadeColor(transColor), combinedLightIn,
                            true, Font.DisplayMode.SEE_THROUGH);
                }
            }
        }

        // 所以这里必须单独 end textSource 一次）。
        textSource.endBatch();

        poseStack.popPose();
        // 恢复 cull 状态，避免影响其他渲染
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

    /**
     * 彩蛋歌曲关键字列表。
     * 《アンノウン・マザーグース》的多种写法，用于触发镜像歌词效果。
     */
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

    /**
     * 检测当前歌曲是否为彩蛋歌曲（触发镜像效果）。
     */
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

    /**
     * "あなたには僕が見えるか?" 在<b>第二次</b>和<b>第三次</b>出现时触发交换，
     * 之后（第 4 次及以后）不再触发。
     * <p>
     * <b>用当前行 tick 作行标识</b>（当前行 key）：行切换时它必然
     * 变化、同行渲染期间必然不变——比文本比对（副歌同一句文本相同）和进度差
     * （歌词行粒度与渲染时机对不上）都可靠：
     * <ul>
     *   <li>首次出现 → occurCount=1，不交换</li>
     *   <li>第二次出现（新行）→ occurCount=2，交换，记录 swapFiredTick</li>
     *   <li>第三次出现（新行）→ occurCount=3，交换，记录 swapFiredTick</li>
     *   <li>swapFiredTick 行的后续帧 → 持续交换；tick 变化即停止</li>
     *   <li>tick 大幅回退（重播）或换歌 → 全部重置</li>
     * </ul>
     */
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
            // 第 2、3 次出现：触发本行交换
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
}

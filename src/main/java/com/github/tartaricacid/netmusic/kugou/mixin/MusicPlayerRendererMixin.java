package com.github.tartaricacid.netmusic.kugou.mixin;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.github.tartaricacid.netmusic.client.event.ConfigEvent;
import com.github.tartaricacid.netmusic.client.renderer.MusicPlayerRenderer;
import com.github.tartaricacid.netmusic.config.GeneralConfig;
import com.github.tartaricacid.netmusic.kugou.KuGouLogger;
import com.github.tartaricacid.netmusic.kugou.config.ClientConfig;
import com.github.tartaricacid.netmusic.kugou.lyric.BlockRomajiRegistry;
import com.github.tartaricacid.netmusic.tileentity.TileEntityMusicPlayer;
import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.apache.commons.lang3.StringUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

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
            return;
        }

        Camera camera = this.dispatcher.camera;
        int originalColor = ConfigEvent.PLAYER_ORIGINAL_COLOR;
        int transColor = ConfigEvent.PLAYER_TRANSLATED_COLOR;
        int romajiColor = ConfigEvent.PLAYER_TRANSLATED_COLOR; // 默认同翻译色

        // === 第 1 行：原文 ===
        String lyric = lyrics.get(lyrics.firstIntKey());
        MutableComponent currentLine = StringUtils.isNotBlank(lyric)
                ? Component.literal(lyric)
                : Component.empty();

        // 原文为空时隐藏翻译/罗马音（属于其他 tick 的孤儿行）
        boolean mainLyricBlank = StringUtils.isBlank(lyric);

        // === 第 2 行：翻译（按配置）===
        boolean showTranslation = ClientConfig.LYRIC_SHOW_TRANSLATION.get();
        MutableComponent translatedLine = null;
        if (showTranslation && !mainLyricBlank) {
            Int2ObjectSortedMap<String> transLyrics = lyricRecord.getTransLyrics();
            if (transLyrics != null && !transLyrics.isEmpty()) {
                String transLyric = transLyrics.get(transLyrics.firstIntKey());
                if (StringUtils.isNotBlank(transLyric)) {
                    translatedLine = Component.literal(transLyric);
                }
            }
        }

        // === 第 3 行：罗马音（按配置 + 侧通道）===
        boolean showRomaji = ClientConfig.LYRIC_SHOW_ROMAJI.get();
        MutableComponent romajiLine = null;
        if (showRomaji && !mainLyricBlank) {
            Int2ObjectSortedMap<String> romajiMap = BlockRomajiRegistry.get(te.getBlockPos());
            if (romajiMap != null && !romajiMap.isEmpty()) {
                // romajiMap 独立于原文，用原文 currentKey 找 <= currentKey 的最大 tick
                // currentKey < romajiFirstKey 时罗马音尚未开始，不显示
                int currentKey = lyrics.firstIntKey();
                int romajiFirstKey = romajiMap.firstIntKey();
                if (currentKey >= romajiFirstKey) {
                    int romajiKey = findFloorKey(romajiMap, currentKey);
                    String romajiText = romajiMap.get(romajiKey);
                    if (StringUtils.isNotBlank(romajiText)) {
                        romajiLine = Component.literal(romajiText);
                    }
                }
            }
        }

        // 单行时：原文用翻译色（与父模组原版一致）
        int currentColor = (translatedLine == null && romajiLine == null)
                ? transColor : originalColor;

        // 行数决定 y 偏移：每多一行 y + 0.5（与父模组同公式）
        float y = 0.5f;
        if (translatedLine != null || romajiLine != null) y += 0.5f;
        if (romajiLine != null) y += 0.5f;

        poseStack.pushPose();
        poseStack.translate(0.5, 1.625, 0.5);

        // 旋转逻辑：优先用 SableCompat.getLookVector 算 block→camera 方向，
        // 然后手动转 yaw/pitch（不是 camera.rotation()）。
        // SableCompat 处理 Sable 反作弊对 camera 的干扰。
        // lookVector 为 null（Sable 没装或返回 null）时 fallback 到 camera.getYRot/getXRot。
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

        // Iris + Sodium：bufferIn 是 Sodium pipeline 管理的，
        // 不会 flush text RenderType 到 GPU，drawInBatch 写入的顶点不会被提交。
        // 用 renderBuffers().bufferSource() 拿独立 BufferSource，画完 endBatch() 强制提交。
        // 注意：必须用具体类型 MultiBufferSource.BufferSource（接口没有 endBatch()）
        MultiBufferSource.BufferSource textSource = Minecraft.getInstance().renderBuffers().bufferSource();
        com.mojang.blaze3d.systems.RenderSystem.disableCull();

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
        if (romajiLine != null) {
            float w = (float) (-this.font.width(romajiLine) / 2);
            this.font.drawInBatch(romajiLine, w, -y - 24, romajiColor, false,
                    poseStack.last().pose(), textSource, Font.DisplayMode.NORMAL,
                    bgColor, combinedLightIn);
        }

        // 强制提交 textSource 的文字顶点到 GPU（bufferIn 不会被 endBatch，
        // 所以这里必须单独 end textSource 一次）。
        textSource.endBatch();

        poseStack.popPose();
        // 恢复 cull 状态，避免影响其他渲染
        com.mojang.blaze3d.systems.RenderSystem.enableCull();
    }

    /**
     * 在 sorted map 中找到 &le; targetTick 的最大 key。
     * <p>
     * 罗马音独立于 LyricRecord，没有 updateCurrentLine 的破坏性前进机制，
     * 需要在每帧根据 currentTick 自己定位当前行。
     */
    private static int findFloorKey(Int2ObjectSortedMap<String> map, int targetTick) {
        if (map == null || map.isEmpty()) return 0;
        int firstKey = map.firstIntKey();
        if (targetTick <= firstKey) return firstKey;
        int lastKey = map.lastIntKey();
        if (targetTick >= lastKey) return lastKey;
        int lo = 0, hi = map.size() - 1, best = firstKey;
        int[] keys = map.keySet().toIntArray();
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int k = keys[mid];
            if (k <= targetTick) {
                best = k;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return best;
    }
}

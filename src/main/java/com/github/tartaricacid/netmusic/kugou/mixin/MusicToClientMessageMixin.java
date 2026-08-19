package com.github.tartaricacid.netmusic.kugou.mixin;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.github.tartaricacid.netmusic.kugou.KuGouLogger;
import com.github.tartaricacid.netmusic.kugou.NetMusicKuGou;
import com.github.tartaricacid.netmusic.kugou.lyric.BlockRomajiRegistry;
import com.github.tartaricacid.netmusic.kugou.lyric.LrcConverter;
import com.github.tartaricacid.netmusic.kugou.lyric.LyricInjectCache;
import com.github.tartaricacid.netmusic.kugou.support.CdNbtHelper;
import com.github.tartaricacid.netmusic.network.message.MusicToClientMessage;
import com.github.tartaricacid.netmusic.tileentity.TileEntityMusicPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 父模组 {@code MusicToClientMessage.onHandle} 只为网易云 rawUrl 拉歌词，酷狗的没处理。
 * 本 Mixin 在 {@code onHandle} 入口从 CD 物品 NBT 读 LRC 文本 + 翻译 JSON 并解析，
 * 结果存到 {@link LyricInjectCache} 供 {@link NetMusicSoundMixin} 在构造器尾部取用；
 * 罗马音则存到 {@link BlockRomajiRegistry} 侧通道供 {@code MusicPlayerRendererMixin} 读。
 * <p>
 * 父模组的 {@code MusicPlayerRenderer.renderLyric} 已经支持双行渲染（原文 + 翻译），
 * 我们只需要把翻译数据也填进 {@code LyricRecord.transLyrics} 即可。
 * <p>
 * 罗马音因为 {@code LyricRecord} 字段限制塞不进去，单独走侧通道。
 */
@Mixin(value = MusicToClientMessage.class, remap = false)
public class MusicToClientMessageMixin {

    @Inject(method = "onHandle", at = @At("HEAD"), remap = false, cancellable = false)
    private static void netmusickugou$onHandleHead(MusicToClientMessage message, CallbackInfo ci) {
        LyricInjectCache.clearAll();
        try {
            Level level = Minecraft.getInstance().level;
            if (level == null) return;

            // 用反射读 private 字段 pos / songName（避免 @Accessor 的 abstract 类限制问题）
            java.lang.reflect.Field posField = MusicToClientMessage.class.getDeclaredField("pos");
            posField.setAccessible(true);
            BlockPos pos = (BlockPos) posField.get(message);

            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof TileEntityMusicPlayer musicPlay)) return;

            ItemStack cd = musicPlay.getPlayerInv().getStackInSlot(0);
            if (!CdNbtHelper.isMusicCd(cd)) return;

            CdNbtHelper.Lyric stored = CdNbtHelper.readLyric(cd);
            if (stored == null || stored.lrcText == null || stored.lrcText.isEmpty()) return;

            // === 读翻译 JSON（酷狗 KRC language 字段解码后的 JSON，可能为 null）===
            String transJson = CdNbtHelper.readLyricTranslation(cd);

            java.lang.reflect.Field songNameField = MusicToClientMessage.class.getDeclaredField("songName");
            songNameField.setAccessible(true);
            String songName = (String) songNameField.get(message);

            // === 解析 LRC + 翻译 + 罗马音（timePoint 对齐）===
            LrcConverter.KuGouLyricData data = LrcConverter.toLyricData(
                    stored.lrcText, transJson,
                    stored.songName != null ? stored.songName : songName);
            if (data == null || data.record == null) {
                KuGouLogger.warn("KuGou lyric: LRC parse returned null for CD at {}", pos);
                return;
            }
            LyricRecord record = data.record;
            LyricInjectCache.set(pos, record);
            // 罗马音存到侧通道，renderer 按需读取
            // 注意：即使空 map 也要 put，覆盖旧歌残留
            BlockRomajiRegistry.put(pos, data.romaji);
            int transLines = (record.getTransLyrics() != null) ? record.getTransLyrics().size() : 0;
            int romajiLines = data.romaji.size();
            if (romajiLines == 0) {
                KuGouLogger.warn(
                        "KuGou lyric: CD at {} song='{}' has 0 romaji lines ({} lyric, {} trans). KRC 的 type=0 罗马音字段缺失或对齐失败。",
                        pos, songName,
                        record.getLyrics() != null ? record.getLyrics().size() : 0,
                        transLines);
            } else {
                KuGouLogger.info(
                        "KuGou lyric: cached LyricRecord for CD at {} song='{}' ({} lyric, {} trans, {} romaji, transJson={})",
                        pos, songName,
                        record.getLyrics() != null ? record.getLyrics().size() : 0,
                        transLines,
                        romajiLines,
                        transJson != null ? "present" : "absent");
            }
        } catch (Exception e) {
            KuGouLogger.warn("KuGou lyric: failed to read lyric for message {}: {}",
                    message, e.getMessage());
        }
    }
}

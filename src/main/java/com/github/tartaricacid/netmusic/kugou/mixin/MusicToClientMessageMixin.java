package com.github.tartaricacid.netmusic.kugou.mixin;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.github.tartaricacid.netmusic.kugou.KuGouLogger;
import com.github.tartaricacid.netmusic.kugou.NetMusicKuGou;
import com.github.tartaricacid.netmusic.kugou.api.KuGouApiClient;
import com.github.tartaricacid.netmusic.kugou.lyric.BlockRomajiRegistry;
import com.github.tartaricacid.netmusic.kugou.lyric.LrcConverter;
import com.github.tartaricacid.netmusic.kugou.lyric.LyricInjectCache;
import com.github.tartaricacid.netmusic.kugou.support.CdAddonData;
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

import java.util.concurrent.TimeUnit;

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

            java.lang.reflect.Field posField = MusicToClientMessage.class.getDeclaredField("pos");
            posField.setAccessible(true);
            BlockPos pos = (BlockPos) posField.get(message);

            java.lang.reflect.Field songNameField = MusicToClientMessage.class.getDeclaredField("songName");
            songNameField.setAccessible(true);
            String songName = (String) songNameField.get(message);

            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof TileEntityMusicPlayer)) return;
            TileEntityMusicPlayer musicPlay = (TileEntityMusicPlayer) be;

            ItemStack cd = musicPlay.getPlayerInv().getStackInSlot(0);
            if (!CdNbtHelper.isMusicCd(cd)) return;

            CdNbtHelper.Lyric stored = CdNbtHelper.readLyric(cd);
            if (stored == null || stored.lrcText == null || stored.lrcText.isEmpty()) {
                fetchLyricOnTheFly(cd, pos, songName);
                return;
            }

            String transJson = CdNbtHelper.readLyricTranslation(cd);

            LrcConverter.KuGouLyricData data = LrcConverter.toLyricData(
                    stored.lrcText, transJson,
                    stored.songName != null ? stored.songName : songName);
            if (data == null || data.record == null) {
                KuGouLogger.warn("KuGou lyric: LRC parse returned null for CD at {}", pos);
                return;
            }
            LyricRecord record = data.record;
            LyricInjectCache.set(pos, record);
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

    /**
     * CD 上没有 LRC 时（刻录时异步歌词拉取尚未完成），在客户端即时补拉。
     * 拉取完成后写入 LyricInjectCache，NetMusicSoundMixin.tick() 会补设 lyricRecord。
     */
    private static void fetchLyricOnTheFly(ItemStack cd, BlockPos pos, String songName) {
        CdAddonData addon = CdNbtHelper.getData(cd);
        if (!addon.hasFileHash()) {
            KuGouLogger.warn("KuGou lyric: CD at {} has no LRC and no fileHash, cannot fetch", pos);
            return;
        }
        String fileHash = addon.fileHash();
        ItemMusicCD.SongInfo info = ItemMusicCD.getSongInfo(cd);
        if (info == null) return;
        String singer = (info.artists == null || info.artists.isEmpty())
                ? "" : String.join(", ", info.artists);
        String song = info.songName == null ? (songName != null ? songName : "") : info.songName;
        String keyword = (singer.isEmpty() ? "" : singer + " - ") + song;
        if (keyword.isEmpty()) return;
        int duration = info.songTime * 1000;

        KuGouLogger.info("KuGou lyric: on-the-fly fetch for CD at {} hash={} keyword='{}'", pos, fileHash, keyword);

        KuGouApiClient.searchLyricCandidates(fileHash, keyword, duration, song, singer)
                .orTimeout(10, TimeUnit.SECONDS)
                .thenAccept(list -> {
                    if (list == null || list.isEmpty()) {
                        KuGouLogger.warn("KuGou lyric: on-the-fly fetch no candidate for hash={}", fileHash);
                        return;
                    }
                    KuGouApiClient.getLyricWithFallback(list, "krc")
                            .thenAccept(content -> {
                                if (content != null && content.lyricContent != null && !content.lyricContent.isEmpty()) {
                                    injectLateLyric(cd, pos, content.lyricContent, content.languageJson, song);
                                } else {
                                    KuGouLogger.warn("KuGou lyric: on-the-fly fetch lyric body empty for hash={} (tried {} candidates, krc+lrc)",
                                            fileHash, list.size());
                                }
                            });
                })
                .exceptionally(e -> {
                    KuGouLogger.warn("KuGou lyric: on-the-fly fetch failed for hash={}: {}", fileHash, e.getMessage());
                    return null;
                });
    }

    /**
     * 歌词拉取完成后，解析并写入 LyricInjectCache + BlockRomajiRegistry + CD NBT。
     * 必须在主线程执行（因为写 CD NBT + LyricInjectCache 都是客户端操作）。
     */
    private static void injectLateLyric(ItemStack cd, BlockPos pos, String lrcText, String transJson, String songName) {
        Minecraft.getInstance().execute(() -> {
            try {
                LrcConverter.KuGouLyricData data = LrcConverter.toLyricData(lrcText, transJson, songName);
                if (data == null || data.record == null) {
                    KuGouLogger.warn("KuGou lyric: late inject parse returned null for CD at {}", pos);
                    return;
                }
                LyricInjectCache.set(pos, data.record);
                BlockRomajiRegistry.put(pos, data.romaji);
                CdNbtHelper.writeLyric(cd, lrcText, songName);
                if (transJson != null && !transJson.isEmpty()) {
                    CdNbtHelper.writeLyricTranslation(cd, transJson);
                }
                int lyricLines = data.record.getLyrics() != null ? data.record.getLyrics().size() : 0;
                int romajiLines = data.romaji.size();
                KuGouLogger.info(
                        "KuGou lyric: late inject for CD at {} song='{}' ({} lyric, {} romaji) — lyrics will appear shortly",
                        pos, songName, lyricLines, romajiLines);
            } catch (Exception e) {
                KuGouLogger.warn("KuGou lyric: late inject failed for CD at {}: {}", pos, e.getMessage());
            }
        });
    }
}

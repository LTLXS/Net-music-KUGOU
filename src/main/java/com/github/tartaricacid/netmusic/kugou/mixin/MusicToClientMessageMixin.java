package com.github.tartaricacid.netmusic.kugou.mixin;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.github.tartaricacid.netmusic.kugou.KuGouLogger;
import com.github.tartaricacid.netmusic.kugou.NetMusicKuGou;
import com.github.tartaricacid.netmusic.kugou.api.KuGouApiClient;
import com.github.tartaricacid.netmusic.kugou.compat.display.KuGouDisplayCompat;
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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
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

    private static final Field POS_FIELD;
    private static final Field SONG_NAME_FIELD;
    private static final Method URL_METHOD;
    private static final Method TIME_SECOND_METHOD;

    static {
        Field p = null, s = null;
        Method u = null, t = null;
        try {
            p = MusicToClientMessage.class.getDeclaredField("pos");
            p.setAccessible(true);
            s = MusicToClientMessage.class.getDeclaredField("songName");
            s.setAccessible(true);
            u = MusicToClientMessage.class.getMethod("url");
            u.setAccessible(true);
            t = MusicToClientMessage.class.getMethod("timeSecond");
            t.setAccessible(true);
        } catch (NoSuchFieldException | NoSuchMethodException e) {
            KuGouLogger.warn("KuGou lyric: failed to cache MusicToClientMessage reflection: {}", e.getMessage());
        }
        POS_FIELD = p;
        SONG_NAME_FIELD = s;
        URL_METHOD = u;
        TIME_SECOND_METHOD = t;
    }

    @Inject(method = "onHandle", at = @At("HEAD"), remap = false, cancellable = false)
    private static void netmusickugou$onHandleHead(MusicToClientMessage message, CallbackInfo ci) {
        LyricInjectCache.clearAll();
        try {
            Level level = Minecraft.getInstance().level;
            if (level == null) return;

            BlockPos pos = (BlockPos) POS_FIELD.get(message);
            String songName = (String) SONG_NAME_FIELD.get(message);

            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof TileEntityMusicPlayer)) return;
            TileEntityMusicPlayer musicPlay = (TileEntityMusicPlayer) be;

            // === 客户端也注册当前正在播放的歌曲，确保显示牌/渲染侧上下文一致 ===
            try {
                String url = (String) URL_METHOD.invoke(message);
                int timeSecond = ((Number) TIME_SECOND_METHOD.invoke(message)).intValue();
                String activeHash = KuGouDisplayCompat.extractHashFromNetmusiclibUrl(url);
                if (activeHash == null || activeHash.isEmpty()) {
                    ItemStack cd0 = musicPlay.getPlayerInv().getStackInSlot(0);
                    Optional<CdAddonData> addonOpt = CdNbtHelper.readOriginalInfo(cd0);
                    if (addonOpt.isPresent()) activeHash = addonOpt.get().fileHash();
                }
                if (activeHash != null && !activeHash.isEmpty()) {
                    KuGouDisplayCompat.registerActiveSong(pos, activeHash, songName, timeSecond);
                    // 歌曲开始播放：记录精确总时长（服务器刚写入 currentTime 的值），
                    // 歌词进度以 currentTime 为基准、从此处开启新会话（切歌/重放自动归零）
                    KuGouDisplayCompat.markPlayStart(pos, timeSecond);
                }
            } catch (Throwable ignored) {
            }

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

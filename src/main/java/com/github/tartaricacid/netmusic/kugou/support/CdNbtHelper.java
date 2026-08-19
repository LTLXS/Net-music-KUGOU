package com.github.tartaricacid.netmusic.kugou.support;

import com.github.tartaricacid.netmusic.kugou.init.InitDataComponent;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * 父 mod 的 ItemMusicCD 已完全 DataComponent 化，不再挂 NBT。
 * 本类对 {@link CdAddonData} 进行读写，封装在 ItemStack 的
 * {@link InitDataComponent#CD_ADDON_DATA} 组件上。
 *
 * <p>CD 上烧入的 songUrl 是酷狗短时签名 URL，过期后会 403。
 * 有了 fileHash + albumId，我们就能在过期时重新调一次
 * {@code KuGouApiClient.getSongUrl()} 拿新 URL。</p>
 */
public final class CdNbtHelper {
    private CdNbtHelper() {}

    /**
     * 判断 ItemStack 是不是 netmusic 的音乐 CD（避免对其他物品误操作）
     */
    public static boolean isMusicCd(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() instanceof ItemMusicCD;
    }

    /**
     * 读取 CD 上的 {@link CdAddonData}（不可变 record）。没有则返回 {@link CdAddonData#EMPTY}。
     */
    public static CdAddonData getData(ItemStack cd) {
        if (!isMusicCd(cd)) {
            return CdAddonData.EMPTY;
        }
        return cd.getOrDefault(InitDataComponent.CD_ADDON_DATA, CdAddonData.EMPTY);
    }

    /**
     * 用 mutator 函数修改 CD 上的 {@link CdAddonData}，整体写回。
     * <p>示例：{@code updateData(cd, d -> d.withFileHash(hash).withAlbumId(albumId))}</p>
     */
    public static void updateData(ItemStack cd, UnaryOperator<CdAddonData> mutator) {
        if (!isMusicCd(cd) || mutator == null) {
            return;
        }
        CdAddonData current = getData(cd);
        CdAddonData updated = mutator.apply(current);
        if (updated != null) {
            cd.set(InitDataComponent.CD_ADDON_DATA, updated);
        }
    }

    /**
     * 把识别信息写到 CD 的 DataComponent。如果 fileHash 为空则不写。
     */
    public static void writeOriginalInfo(ItemStack cd, String fileHash, String albumId) {
        if (!isMusicCd(cd) || fileHash == null || fileHash.isEmpty()) {
            return;
        }
        updateData(cd, d -> new CdAddonData(
                fileHash,
                albumId == null ? "" : albumId,
                System.currentTimeMillis(),
                d.lrc(),
                d.lrcTrans()
        ));
    }

    /**
     * 读取 CD 上记录的原曲识别信息。空时返回 {@link Optional#empty()}。
     */
    public static Optional<CdAddonData> readOriginalInfo(ItemStack cd) {
        if (!isMusicCd(cd)) {
            return Optional.empty();
        }
        CdAddonData data = getData(cd);
        if (!data.hasFileHash()) {
            return Optional.empty();
        }
        return Optional.of(data);
    }

    /**
     * 刷新 CD 上的 songUrl 字段。
     */
    public static void updateSongUrl(ItemStack cd, String newUrl) {
        if (!isMusicCd(cd) || newUrl == null || newUrl.isEmpty()) {
            return;
        }
        ItemMusicCD.SongInfo info = ItemMusicCD.getSongInfo(cd);
        if (info == null) {
            return;
        }
        info.songUrl = newUrl;
        ItemMusicCD.setSongInfo(info, cd);
    }

    /**
     * 从 CD 上读取当前 songUrl
     */
    public static String readSongUrl(ItemStack cd) {
        if (!isMusicCd(cd)) {
            return null;
        }
        ItemMusicCD.SongInfo info = ItemMusicCD.getSongInfo(cd);
        return info == null ? null : info.songUrl;
    }

    /**
     * 把 LRC 文本写到 CD DataComponent（烧录时用）。
     * <p>这里存的是 LRC 原始文本，不是解析后的结构。客户端拿到后再用
     * {@code LrcConverter} 解析，这样可以避免 LRC 解析逻辑被版本化到 NBT 上造成兼容性问题。</p>
     */
    public static void writeLyric(ItemStack cd, String lrcText, String songName) {
        if (!isMusicCd(cd) || lrcText == null || lrcText.isEmpty()) {
            return;
        }
        updateData(cd, d -> new CdAddonData(
                d.fileHash(),
                d.albumId(),
                d.burnTime() == 0 ? System.currentTimeMillis() : d.burnTime(),
                lrcText,
                d.lrcTrans()
        ));
        // songName 仍然存到 ItemMusicCD 自带的 SongInfo 字段
        ItemMusicCD.SongInfo info = ItemMusicCD.getSongInfo(cd);
        if (info != null && songName != null && !songName.isEmpty()) {
            info.songName = songName;
            ItemMusicCD.setSongInfo(info, cd);
        }
    }

    /**
     * 读取 CD 上的 LRC 文本和歌曲名。没有 LRC 则返回 null。
     */
    public static Lyric readLyric(ItemStack cd) {
        if (!isMusicCd(cd)) {
            return null;
        }
        CdAddonData data = getData(cd);
        if (!data.hasLrc()) {
            return null;
        }
        // songName 从父 mod 的 SongInfo 拿
        String song = null;
        ItemMusicCD.SongInfo info = ItemMusicCD.getSongInfo(cd);
        if (info != null) {
            song = info.songName;
        }
        return new Lyric(data.lrc(), song);
    }

    /**
     * CD 上记录的 LRC 文本 + 歌曲名
     */
    public static final class Lyric {
        public final String lrcText;
        public final String songName;

        Lyric(String lrcText, String songName) {
            this.lrcText = lrcText;
            this.songName = songName;
        }
    }

    /**
     * 写入翻译 JSON 文本（酷狗 KRC language 字段解码后的 JSON 字符串）到 CD DataComponent。
     */
    public static void writeLyricTranslation(ItemStack cd, String transJson) {
        if (!isMusicCd(cd) || transJson == null || transJson.isEmpty()) {
            return;
        }
        updateData(cd, d -> new CdAddonData(
                d.fileHash(),
                d.albumId(),
                d.burnTime(),
                d.lrc(),
                transJson
        ));
    }

    /**
     * 读取 CD 上的翻译 JSON 文本。没有则返回 null。
     */
    public static String readLyricTranslation(ItemStack cd) {
        if (!isMusicCd(cd)) {
            return null;
        }
        return getData(cd).lrcTrans();
    }
}

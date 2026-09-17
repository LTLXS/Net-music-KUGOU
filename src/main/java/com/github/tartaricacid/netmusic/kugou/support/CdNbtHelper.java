package com.github.tartaricacid.netmusic.kugou.support;

import com.github.tartaricacid.netmusic.kugou.init.InitDataComponent;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;
import java.util.function.UnaryOperator;

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
        ItemMusicCD.SongInfo info = ItemMusicCD.getSongInfo(cd);
        if (info != null && songName != null && !songName.isEmpty()) {
            info.songName = songName;
            ItemMusicCD.setSongInfo(info, cd);
        }
    }

    public static Lyric readLyric(ItemStack cd) {
        if (!isMusicCd(cd)) {
            return null;
        }
        CdAddonData data = getData(cd);
        if (!data.hasLrc()) {
            return null;
        }
        String song = null;
        ItemMusicCD.SongInfo info = ItemMusicCD.getSongInfo(cd);
        if (info != null) {
            song = info.songName;
        }
        return new Lyric(data.lrc(), song);
    }

    public static final class Lyric {
        public final String lrcText;
        public final String songName;

        Lyric(String lrcText, String songName) {
            this.lrcText = lrcText;
            this.songName = songName;
        }
    }

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

    public static String readLyricTranslation(ItemStack cd) {
        if (!isMusicCd(cd)) {
            return null;
        }
        return getData(cd).lrcTrans();
    }
}

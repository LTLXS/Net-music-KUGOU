package com.github.tartaricacid.netmusic.kugou.support;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;
import java.util.function.UnaryOperator;

public final class CdNbtHelper {
    private CdNbtHelper() {}

    public static boolean isMusicCd(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() instanceof ItemMusicCD;
    }

    public static CdAddonData getData(ItemStack cd) {
        if (!isMusicCd(cd)) {
            return CdAddonData.EMPTY;
        }
        CompoundTag tag = cd.getOrCreateTag();
        CompoundData data = CompoundData.fromNbt(tag.getCompound("NetMusicKuGouCdAddon"));
        return new CdAddonData(data.fileHash, data.albumId, data.burnTime, data.lrc, data.lrcTrans);
    }

    public static void updateData(ItemStack cd, UnaryOperator<CdAddonData> mutator) {
        if (!isMusicCd(cd) || mutator == null) {
            return;
        }
        CdAddonData current = getData(cd);
        CdAddonData updated = mutator.apply(current);
        if (updated != null) {
            CompoundTag tag = cd.getOrCreateTag();
            CompoundData d = new CompoundData(updated.fileHash(), updated.albumId(), updated.burnTime(), updated.lrc(), updated.lrcTrans());
            d.toNbt(tag);
        }
    }

    private static class CompoundData {
        final String fileHash;
        final String albumId;
        final long burnTime;
        final String lrc;
        final String lrcTrans;

        CompoundData(String fileHash, String albumId, long burnTime, String lrc, String lrcTrans) {
            this.fileHash = fileHash;
            this.albumId = albumId;
            this.burnTime = burnTime;
            this.lrc = lrc;
            this.lrcTrans = lrcTrans;
        }

        static CompoundData fromNbt(CompoundTag t) {
            return new CompoundData(
                    t.getString("fileHash"),
                    t.getString("albumId"),
                    t.getLong("burnTime"),
                    t.getString("lrc"),
                    t.getString("lrcTrans")
            );
        }

        void toNbt(CompoundTag tag) {
            CompoundData d = fromNbt(tag.getCompound("NetMusicKuGouCdAddon"));
            CompoundData updated = new CompoundData(fileHash, albumId, burnTime, lrc, lrcTrans);
            CompoundTag newTag = new CompoundTag();
            newTag.putString("fileHash", updated.fileHash);
            newTag.putString("albumId", updated.albumId);
            newTag.putLong("burnTime", updated.burnTime);
            newTag.putString("lrc", updated.lrc);
            newTag.putString("lrcTrans", updated.lrcTrans);
            tag.put("NetMusicKuGouCdAddon", newTag);
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

    /**
     * 逐曲酷狗元数据列表（用于 netMusicList 的「音乐列表」物品，一张列表CD 可含多首酷狗歌）。
     * <p>
     * 顶层 {@code NetMusicKuGouCdAddon} 只能存「一首歌」的信息，对多曲列表 CD 会互相覆盖；
     * 这里按每首歌的 {@code songUrl} 索引各自的 fileHash/albumId/歌词，播放时由
     * {@link #getSongAddon(ItemStack, String)} 取回对应那首歌的元数据。
     */
    private static final String SONGS_KEY = "NetMusicKuGouSongs";

    /**
     * 把一首歌的酷狗元数据写入（或更新）逐曲列表，按 {@code url} 去重。
     *
     * @param url 该歌的 {@code ItemMusicCD.SongInfo.songUrl}（酷狗真实直链，唯一标识一首歌）
     */
    public static void appendSongAddon(ItemStack cd, String url, String fileHash, String albumId,
                                       String lrc, String lrcTrans) {
        if (!isMusicCd(cd) || url == null || url.isEmpty()) {
            return;
        }
        CompoundTag tag = cd.getOrCreateTag();
        ListTag list = tag.getList(SONGS_KEY, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            if (url.equals(e.getString("url"))) {
                e.putString("fileHash", fileHash == null ? "" : fileHash);
                e.putString("albumId", albumId == null ? "" : albumId);
                e.putLong("burnTime", System.currentTimeMillis());
                e.putString("lrc", lrc == null ? "" : lrc);
                e.putString("lrcTrans", lrcTrans == null ? "" : lrcTrans);
                tag.put(SONGS_KEY, list);
                return;
            }
        }
        CompoundTag e = new CompoundTag();
        e.putString("url", url);
        e.putString("fileHash", fileHash == null ? "" : fileHash);
        e.putString("albumId", albumId == null ? "" : albumId);
        e.putLong("burnTime", System.currentTimeMillis());
        e.putString("lrc", lrc == null ? "" : lrc);
        e.putString("lrcTrans", lrcTrans == null ? "" : lrcTrans);
        list.add(e);
        tag.put(SONGS_KEY, list);
    }

    /**
     * 按 {@code url} 取回某首歌的酷狗元数据；不存在时返回 {@link CdAddonData#EMPTY}。
     */
    public static CdAddonData getSongAddon(ItemStack cd, String url) {
        if (!isMusicCd(cd) || url == null || url.isEmpty()) {
            return CdAddonData.EMPTY;
        }
        CompoundTag tag = cd.getOrCreateTag();
        if (!tag.contains(SONGS_KEY)) {
            return CdAddonData.EMPTY;
        }
        ListTag list = tag.getList(SONGS_KEY, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            if (url.equals(e.getString("url"))) {
                return new CdAddonData(
                        e.getString("fileHash"),
                        e.getString("albumId"),
                        e.getLong("burnTime"),
                        e.getString("lrc"),
                        e.getString("lrcTrans"));
            }
        }
        return CdAddonData.EMPTY;
    }
}

package com.github.tartaricacid.netmusic.kugou.compat.netmusiclist;

import com.github.tartaricacid.netmusic.kugou.KuGouLogger;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

/**
 网络音乐机（netMusicList，Forge 1.20.1）兼容层。
 1.20.1 的 netMusicList 没有可插拔音乐源 API，且歌词/HUD 硬编码网易云 URL，
 因此不做源注册，只做两件事（纯字符串/NBT 探测，编译期零依赖）：
 - isNetMusicListLoaded()：运行期探测 modId net_music_list 是否加载
 - isMusicListItem(ItemStack)：按 NBT key NetMusicSongInfoList 判定「音乐列表」物品，
   把酷狗歌逐曲烧进列表CD；歌词/预取按每首歌 songUrl 从 CdNbtHelper.NetMusicKuGouSongs 取回*/
public final class NetMusicListCompat {
    private NetMusicListCompat() {}

    public static final String MOD_ID = "net_music_list";

    /**
 netMusicList 的「音乐列表」物品在 NBT 中存放歌曲列表所用的 key。
 该 key 来自 netMusicList 源码（NetMusicListItem.listKey = "NetMusicSongInfoList"），
 这里仅用字符串探测，不引用其类，避免编译期依赖。
*/
    private static final String MUSIC_LIST_NBT_KEY = "NetMusicSongInfoList";

    public static boolean isNetMusicListLoaded() {
        try {
            return ModList.get() != null && ModList.get().isLoaded(MOD_ID);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isMusicListItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains(MUSIC_LIST_NBT_KEY);
    }

    static {
        if (isNetMusicListLoaded()) {
            KuGouLogger.info("[NetMusicListCompat] net_music_list detected, list-CD compat enabled.");
        }
    }
}

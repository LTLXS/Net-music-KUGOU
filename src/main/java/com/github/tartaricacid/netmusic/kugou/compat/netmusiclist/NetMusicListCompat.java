package com.github.tartaricacid.netmusic.kugou.compat.netmusiclist;

import com.github.tartaricacid.netmusic.kugou.KuGouLogger;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

/**
 * 网络音乐机（netMusicList，Forge 1.20.1）兼容层。
 * <p>
 * 与 1.21.1 不同，1.20.1 的 netMusicList <b>没有</b>任何可插拔的音乐源扩展 API，
 * 且其歌词/HUD 系统硬编码为网易云 {@code ?id=<long>} URL。因此本兼容层<b>不做</b>源注册，
 * 只做两件事（均不引用 netMusicList 的类、纯字符串/NBT 探测，编译期零依赖）：
 * <ol>
 *   <li>{@link #isNetMusicListLoaded()} —— 运行期探测 modId {@code net_music_list} 是否加载；</li>
 *   <li>{@link #isMusicListItem(ItemStack)} —— 通过 NBT key {@code NetMusicSongInfoList}
 *       判定一个物品是否为 netMusicList 的「音乐列表」物品（列表CD），从而把酷狗歌逐曲烧进其中。</li>
 * </ol>
 * <p>
 * 酷狗歌烧进列表CD 后，netMusicList 的播放器（随机/顺序/循环）会播放列表里的
 * {@code ItemMusicCD.SongInfo}；由于酷狗 {@code songUrl} 是真实直链，needkugou 的音频处理器按
 * host 命中注入 UA，音频无需额外处理。歌词/预取则按每首歌的 {@code songUrl} 在
 * {@code CdNbtHelper.NetMusicKuGouSongs} 逐曲取回（见对应类）。
 */
public final class NetMusicListCompat {
    private NetMusicListCompat() {}

    /** netMusicList 在 1.20.1 的 modId（与其 mods.toml / @Mod 注解一致）。 */
    public static final String MOD_ID = "net_music_list";

    /**
     * netMusicList 的「音乐列表」物品在 NBT 中存放歌曲列表所用的 key。
     * 该 key 来自 netMusicList 源码（{@code NetMusicListItem.listKey = "NetMusicSongInfoList"}），
     * 这里仅用字符串探测，不引用其类，避免编译期依赖。
     */
    private static final String MUSIC_LIST_NBT_KEY = "NetMusicSongInfoList";

    public static boolean isNetMusicListLoaded() {
        try {
            return ModList.get() != null && ModList.get().isLoaded(MOD_ID);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 判断给定物品是否为 netMusicList 的「音乐列表」物品（列表CD）。
     * 通过 NBT key 探测，不引用 netMusicList 类。
     */
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

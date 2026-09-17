package com.github.tartaricacid.netmusic.kugou.util;

import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;

import java.util.WeakHashMap;

/**
 * 在"按 tick 排序的歌词 map"中查找 &le; targetTick 的最大 key（当前行）。
 * <p>
 * 罗马音 / 翻译独立于 {@code LyricRecord}，没有 {@code updateCurrentLine} 的破坏性前进机制，
 * 需要在每帧根据当前播放进度自己定位当前行。
 * <p>
 * 原先每次调用都 {@code keySet().toIntArray()} 新建一个 int[]（每帧每个音乐盒/女仆气泡一次），
 * 这里把键数组按 map 实例缓存（{@link WeakHashMap}，map 随歌曲结束被 GC 后自动失效），
 * 消除热渲染路径上的重复数组分配。
 */
public final class LyricFloorKey {
    private LyricFloorKey() {
    }

    private static final WeakHashMap<Int2ObjectSortedMap<String>, int[]> KEY_CACHE = new WeakHashMap<>();

    public static int floorKey(Int2ObjectSortedMap<String> map, int targetTick) {
        if (map == null || map.isEmpty()) return 0;
        int first = map.firstIntKey();
        if (targetTick <= first) return first;
        int last = map.lastIntKey();
        if (targetTick >= last) return last;
        int[] keys = KEY_CACHE.computeIfAbsent(map, m -> m.keySet().toIntArray());
        int lo = 0, hi = keys.length - 1, best = first;
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

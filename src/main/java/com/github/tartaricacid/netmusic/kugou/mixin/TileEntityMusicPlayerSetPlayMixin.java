package com.github.tartaricacid.netmusic.kugou.mixin;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.github.tartaricacid.netmusic.kugou.KuGouLogger;
import com.github.tartaricacid.netmusic.kugou.compat.display.KuGouDisplayCompat;
import com.github.tartaricacid.netmusic.kugou.compat.netmusiclist.NetMusicListCompat;
import com.github.tartaricacid.netmusic.kugou.lyric.LrcConverter;
import com.github.tartaricacid.netmusic.kugou.support.CdAddonData;
import com.github.tartaricacid.netmusic.kugou.support.CdNbtHelper;
import com.github.tartaricacid.netmusic.kugou.support.KuGouPrefetch;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.github.tartaricacid.netmusic.tileentity.TileEntityMusicPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.IItemHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.UUID;

/**
 * 服务端 Mixin：在父模组 {@link TileEntityMusicPlayer#setPlayToClient} 调用的最开始，
 * 从 {@link KuGouPrefetch} 拿 onRightClickJukebox 已经异步预取好的 URL，
 * 同步写回 CD NBT + info.songUrl。
 * <p>
 * <b>性能（为什么取消同步 .get(30s)？）</b>
 * <p>
 * 上一版在 setPlayToClient HEAD 直接调用 forceRefreshOne(cd) 内部是：
 * <pre>
 *   KuGouApiClient.getSongUrl(...).get(30, SECONDS)   // 服务端主线程卡 2~4 秒
 * </pre>
 * 导致两个用户体感问题：
 * <ol>
 *   <li>"插CD会卡一下，好几次才能成功"——RightClickBlock 卡 3-5 秒，方块音响 use() 的状态竞争导致CD入槽失败。</li>
 *   <li>"等很久才播放"—— setPlayToClient 是向所有追踪玩家发 MusicToClientMessage 的最后闸门，
 *       在这里卡太久，客户端就会觉得"点了半天没动静"。</li>
 * </ol>
 * 新流程（并行 + 最多 200ms 等待）：
 * <ol>
 *   <li>RightClickBlock 服务端主线程：只提交 {@link KuGouPrefetch#asyncPrefetch}，0 阻塞；
 *       真实 HTTP 在 NetMusicKuGou-Prefetch 后台线程跑。</li>
 *   <li>几ms后 BlockMusicPlayer.use() → setPlayToClient HEAD →
 *       {@link KuGouPrefetch#tryTake(BlockPos, UUID, Level, String)}：
 *       <ul>
 *         <li>预取已完成 → 直接拿新 URL，0 额外延迟。</li>
 *         <li>预取还在跑 → 最多等 200ms（玩家几乎感知不到），等不到就先拿 CD 上的 URL 起播。</li>
 *       </ul></li>
 *   <li>异步预取在后台 finish 后：如果新 URL != 旧 URL，就走回调
 *       {@link KuGouPrefetch.OnRefreshChangedCallback#onUrlChanged} →
 *       NetMusicKuGou.scheduleReplayWithNewUrl() → 延迟 1 tick 用新 URL 重新 setPlayToClient，
 *       相当于"无缝切歌"：旧 URL 如果因为过期拉不起来，新 URL 1~2 秒后就接上；
 *       旧 URL 本身就能播的情况下也只是"听起来像重新起一次头"，不会整首没声。</li>
 * </ol>
 * <p>
 * <b>为什么一定要直接修改入参 info.songUrl？</b>
 * setPlayToClient 内部接下来 {@code clone = info.clone()}，clone 是 SongInfo 深拷贝，
 * clone.songUrl 会继承我们在这里 set 的新 URL。
 */
@Mixin(TileEntityMusicPlayer.class)
public class TileEntityMusicPlayerSetPlayMixin {

    @Inject(method = "setPlayToClient", at = @At("HEAD"), remap = false)
    private void kugou$refreshBeforeSetPlayToClient(ItemMusicCD.SongInfo info, CallbackInfo ci) {
        final long t0 = System.currentTimeMillis();
        UUID playerId = null;
        try {
            TileEntityMusicPlayer self = (TileEntityMusicPlayer) (Object) this;
            if (self.getLevel() != null && self.getLevel().isClientSide()) return;
            IItemHandler inv = self.getPlayerInv();
            if (inv == null) return;
            ItemStack cd = inv.getStackInSlot(0);
            if (cd == null || cd.isEmpty()) return;
            if (!CdNbtHelper.isMusicCd(cd)) return;
            Optional<CdAddonData> addonOpt = CdNbtHelper.readOriginalInfo(cd);
            if (addonOpt.isEmpty()) return;
            CdAddonData addon = addonOpt.get();
            if (addon.fileHash() == null || addon.fileHash().isEmpty()) return;

            BlockPos pos = self.getBlockPos();
            Level level = self.getLevel();

            // === 确定当前真正在播放的歌曲（用于显示牌跟随） ===
            // 播放列表/netMusicList 模式下，槽位 0 的 CD 可能与实际播放的歌曲不一致，
            // 所以用 setPlayToClient 收到的 info.songUrl 来识别真正播放的歌曲。
            String activeHash = null;
            String activeSongName = null;
            int activeSongTime = 0;
            if (info != null && info.songUrl != null
                    && info.songUrl.startsWith("netmusiclib://source/kugou")) {
                activeHash = KuGouDisplayCompat.extractHashFromNetmusiclibUrl(info.songUrl);
                activeSongName = info.songName;
                activeSongTime = info.songTime;
            }
            if ((activeHash == null || activeHash.isEmpty()) && addonOpt.isPresent()) {
                activeHash = addonOpt.get().fileHash();
            }
            if (activeHash != null && !activeHash.isEmpty()
                    && (activeSongName == null || activeSongTime <= 0)) {
                ItemMusicCD.SongInfo cdInfo = ItemMusicCD.getSongInfo(cd);
                if (cdInfo != null) {
                    if (activeSongName == null) activeSongName = cdInfo.songName;
                    if (activeSongTime <= 0) activeSongTime = cdInfo.songTime;
                }
            }
            if (activeHash != null && !activeHash.isEmpty()) {
                KuGouDisplayCompat.registerActiveSong(pos, activeHash,
                        activeSongName != null ? activeSongName : "", activeSongTime);
                // 服务端落库「精确总时长 + 待重置」：与 getKuGouContext/computeProgress 同侧（服务端），
                // 这样进度算式 progress = 总时长 - currentTime 才能拿到精确总时长，
                // 避免显示源首次求值时 currentTime 已播了几 tick 导致总时长偏小、歌词整体偏慢；
                // 且每次 setPlayToClient（切歌/重放/自然循环）都重新落库，重放即可归零。
                int totalSec = (info != null && info.songTime > 0) ? info.songTime : activeSongTime;
                int totalTick = totalSec * 20 + 64;
                KuGouDisplayCompat.notePlayStart(pos, totalTick);
                // 同步把 currentTime 先设为估算总时长：setPlayToClient 内部是异步 resolve，
                // 真正 setCurrentTime 在回调里才执行；若不在 HEAD 同步先设，
                // 显示源在异步间隙读到的是上一首歌残留的 currentTime，
                // 导致 progress 算错、翻牌板闪到结尾或卡在开头。
                self.setCurrentTime(totalTick);
            }

            // 父模组目前没有 owner 字段，只能先拿 null，KuGouPrefetch.tryTake 内部会遍历相同 pos 的预取兜底。

            String oldCdUrl = CdNbtHelper.readSongUrl(cd);
            String oldInfoUrl = (info != null) ? info.songUrl : null;

            // netMusicList 模式：songUrl 是 netmusiclib:// URI，交给 netMusicList 自己的解析器（MusicPlayManagerMixin）
            // 实时解析，needkugou 的「直链覆盖 + 预取/重放」逻辑必须跳过，否则会把 kugou 预取/重放错误地套在
            // netmusiclib 链接上，导致音频反复重启、卡顿。歌词归零由 getKuGouContext 的重放窗口处理，与播放无关。
            boolean skipUrlOverride = NetMusicListCompat.isNetMusicListLoaded()
                    && info != null && info.songUrl != null && info.songUrl.startsWith("netmusiclib://");

            if (!skipUrlOverride) {
            // ====== 1. 先尝试命中 onRightClickJukebox 已经提前启动的异步预取（并行节省时间）======
            KuGouPrefetch.PrefetchResult res = KuGouPrefetch.tryTake(pos, playerId, level, oldCdUrl);

            String pickedUrl = "";
            boolean fromPrefetch = false;
            if (res.hasFreshUrl()) {
                pickedUrl = res.url();
                fromPrefetch = true;
            } else {
                // ====== 2. 没命中（红石信号直接触发 playerMusic 等"根本没有 onRightClickJukebox 预取"的场景）时：
                pickedUrl = (oldCdUrl == null) ? "" : oldCdUrl;
                // 再创建新 future 会导致 URL 变更后触发 2 次 replay（同一变更被两个 future 各回调一次）。
                if (!res.entryFound() && !KuGouPrefetch.isOnReplayCooldown(pos) && level != null && pos != null && cd != null) {
                    UUID dummyOwner = new UUID(0L, pos.asLong()); // pos 作为 key，和 RightClick 的 "null playerId" 兜底遍历对齐
                    KuGouPrefetch.submitAsyncRefreshThenMaybeReplay(level, pos, dummyOwner, cd, pickedUrl);
                }
            }

            // ====== 3. 把选中的 URL 同时写回 CD NBT（预取命中时才写，没命中的异步回调会在 Replay 函数里写）======
            if (fromPrefetch && pickedUrl != null && !pickedUrl.isEmpty()) {
                if (!pickedUrl.equals(oldCdUrl)) {
                    CdNbtHelper.updateSongUrl(cd, pickedUrl);
                    CdNbtHelper.updateData(cd, d -> new CdAddonData(
                            d.fileHash(), d.albumId(), System.currentTimeMillis(), d.lrc(), d.lrcTrans()));
                }
            }

            // ====== 4. 无论来源，只要 pickedUrl 非空，就覆盖 info.songUrl（走 setPlayToClient 后续 resolve）======
            if (info != null && pickedUrl != null && !pickedUrl.isEmpty()) {
                if (!pickedUrl.equals(info.songUrl)) {
                    KuGouLogger.info(
                            "[SetPlayPrep] Overwrite info.songUrl: len{} -> len{}, source={}, cost={}ms, hash={}",
                            info.songUrl == null ? 0 : info.songUrl.length(),
                            pickedUrl.length(),
                            fromPrefetch ? "prefetch(hit)" : "cd-fallback(async-submitted)",
                            (System.currentTimeMillis() - t0),
                            addon.fileHash());
                    info.songUrl = pickedUrl;
                } else {
                    KuGouLogger.info(
                            "[SetPlayPrep] info.songUrl already matches picked URL (len={}, source={}, cost={}ms)",
                            pickedUrl.length(),
                            fromPrefetch ? "prefetch(hit)" : "cd-fallback(async-submitted)",
                            (System.currentTimeMillis() - t0));
                }
            } else if (info != null && (pickedUrl == null || pickedUrl.isEmpty())) {
                // 没有预取、CD 上也没有 URL：只能让原来的 info.songUrl 继续走，播放失败概率极高，打 ERROR
                KuGouLogger.error(
                        "[SetPlayPrep] NO URL (hash={}). info.songUrl len={}, cdUrl len={}, fallback cost={}ms",
                        addon.fileHash(),
                        info.songUrl == null ? 0 : info.songUrl.length(),
                        oldCdUrl == null ? 0 : oldCdUrl.length(),
                        (System.currentTimeMillis() - t0));
            }
            }

            // ====== 5. 把歌词写入 KuGouDisplayCompat 供 NetMusicDisplay 兼容层取用 ======
            // 优先用 activeHash（真正播放的歌曲），而不是槽位 0 的 CD。
            try {
                LyricRecord record = KuGouDisplayCompat.getLyricByHash(activeHash);
                if (record == null) {
                    CdNbtHelper.Lyric stored = CdNbtHelper.readLyric(cd);
                    if (stored != null && stored.lrcText != null && !stored.lrcText.isEmpty()
                            && activeHash != null && !activeHash.isEmpty()) {
                        String transJson = CdNbtHelper.readLyricTranslation(cd);
                        LrcConverter.KuGouLyricData data = LrcConverter.toLyricData(
                                stored.lrcText, transJson,
                                stored.songName != null ? stored.songName
                                        : (info != null ? info.songName : null));
                        if (data != null && data.record != null) {
                            record = data.record;
                        }
                    }
                }
                if (record != null && activeHash != null && !activeHash.isEmpty()) {
                    KuGouDisplayCompat.putAll(pos, activeHash, record);
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            // 绝对不能抛异常打断原 setPlayToClient
            KuGouLogger.error(
                    "[NetMusicKuGou] TileEntityMusicPlayerSetPlayMixin crashed, let original flow continue: {}",
                    t.getMessage(), t);
        }
    }
}

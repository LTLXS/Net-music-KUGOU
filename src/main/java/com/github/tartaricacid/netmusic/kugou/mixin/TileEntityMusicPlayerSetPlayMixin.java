package com.github.tartaricacid.netmusic.kugou.mixin;

import com.github.tartaricacid.netmusic.kugou.KuGouLogger;
import com.github.tartaricacid.netmusic.kugou.NetMusicKuGou;
import com.github.tartaricacid.netmusic.kugou.support.CdNbtHelper;
import com.github.tartaricacid.netmusic.kugou.support.UrlRefresher;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.github.tartaricacid.netmusic.tileentity.TileEntityMusicPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/**
 * 服务端 Mixin：在父模组 {@link TileEntityMusicPlayer#setPlayToClient} 调用的最开始
 * 同步检查并刷新可能过期的酷狗 URL。
 * <p>
 * 父模组 {@code setPlayToClient} 之后走的是 {@code MusicPlayResolverManager.resolve} 异步链，
 * resolver 列表在我们 addon 当前的实现下是空的（没有 registerResolver），
 * 所以 {@code resolve} 会走 fallback 分支返回 <b>原 info</b>，URL 永远不会被刷新。
 * <p>
 * 我们在 <b>setPlayToClient 调用的最开始</b> 主动跑 {@link UrlRefresher#tryRefreshOne(ItemStack)}，
 * 同步把过期 URL 替换成新的，<b>同时</b>直接修改入参 {@code info.songUrl}。
 * <p>
 * 修改入参 info.songUrl 是有效的：{@code setPlayToClient} 内部接下来 {@code clone = info.clone()}，
 * clone 是 SongInfo 的深拷贝，clone.songUrl 是新 String 对象（= 我们 set 的新 URL），
 * 然后 {@code resolve(clone)} 走 fallback 返回 clone，{@code resolved.songUrl = clone.songUrl} = 新 URL。
 * <p>
 * <b>触发条件</b>：{@code setPlayToClient} 在 {@code BlockMusicPlayer.use}（右键插 CD）
 * 和 {@code BlockMusicPlayer.playerMusic}（红石信号）里被调。两者调用前，CD 都已经在
 * {@code TileEntityMusicPlayer.playerInv} slot 0 里了，所以我们从 this 拿 slot 0 stack。
 */
@Mixin(TileEntityMusicPlayer.class)
public class TileEntityMusicPlayerSetPlayMixin {

    @Inject(method = "setPlayToClient", at = @At("HEAD"), remap = false)
    private void kugou$refreshBeforeSetPlayToClient(ItemMusicCD.SongInfo info, CallbackInfo ci) {
        try {
            TileEntityMusicPlayer self = (TileEntityMusicPlayer) (Object) this;
            IItemHandler inv = self.getPlayerInv();
            if (inv == null) return;
            ItemStack cd = inv.getStackInSlot(0);
            if (cd == null || cd.isEmpty()) return;
            if (!CdNbtHelper.isMusicCd(cd)) return;

            // 同步刷新 CD NBT 上的 URL（如果已过期）。
            // UrlRefresher.tryRefreshOne 内部是同步 HTTP 调用（HEAD + 可选 getSongUrl），
            // 在 setPlayToClient 入口调用会短暂阻塞 server tick（通常 < 200ms）。
            // 偶尔卡顿换取 URL 永远不失效，可以接受。
            UrlRefresher refresher = new UrlRefresher();
            boolean refreshed = refresher.tryRefreshOne(cd);
            if (refreshed) {
                String newUrl = CdNbtHelper.readSongUrl(cd);
                if (newUrl != null && !newUrl.isEmpty() && info != null
                        && info.songUrl != null && !newUrl.equals(info.songUrl)) {
                    KuGouLogger.info(
                            "[NetMusicKuGou] setPlayToClient: updated info.songUrl from '{}' to '{}'",
                            info.songUrl, newUrl);
                    info.songUrl = newUrl;
                }
            }
        } catch (Throwable t) {
            KuGouLogger.error(
                    "[NetMusicKuGou] TileEntityMusicPlayerSetPlayMixin failed: {}",
                    t.getMessage(), t);
        }
    }
}

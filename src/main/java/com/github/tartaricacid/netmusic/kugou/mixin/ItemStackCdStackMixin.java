package com.github.tartaricacid.netmusic.kugou.mixin;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.github.tartaricacid.netmusic.kugou.support.CdAddonData;
import com.github.tartaricacid.netmusic.kugou.support.CdNbtHelper;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 让同一首歌的唱片即使 songUrl / burnTime 被 UrlRefresher 刷新过也能堆叠。
 songUrl / burnTime 随每次刷新变化，会使同曲两张唱片 Components 不等而无法堆叠。
 处理：堆叠判定前抹掉这两个易变字段，仅以歌曲本身与稳定元数据
 （fileHash / albumId / 歌词）作为堆叠身份；不同歌曲仍不可堆叠。
 注入静态方法 ItemStack.isSameItemSameComponents（被 matches 与背包合并逻辑调用），覆盖所有堆叠判定。*/
@Mixin(ItemStack.class)
public abstract class ItemStackCdStackMixin {
    /** 递归保护：归一化副本回比较时走原版逻辑，避免无限递归。 */
    private static final ThreadLocal<Boolean> IN_CD_COMPARE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Inject(method = "isSameItemSameComponents(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void kugouCdSameComponents(ItemStack self, ItemStack other, CallbackInfoReturnable<Boolean> cir) {
        if (IN_CD_COMPARE.get()) {
            return; // 归一化副本的比较：交给原版逻辑
        }
        if (!CdNbtHelper.isMusicCd(self) || !CdNbtHelper.isMusicCd(other)) {
            return; // 非唱片：交给原版逻辑
        }
        IN_CD_COMPARE.set(Boolean.TRUE);
        try {
            ItemStack a = normalize(self.copy());
            ItemStack b = normalize(other.copy());
            cir.setReturnValue(ItemStack.isSameItemSameComponents(a, b));
        } finally {
            IN_CD_COMPARE.set(Boolean.FALSE);
        }
    }

    private static ItemStack normalize(ItemStack stack) {
        // 抹掉会随刷新变化的 songUrl
        ItemMusicCD.SongInfo si = ItemMusicCD.getSongInfo(stack);
        if (si != null) {
            si.songUrl = "";
            stack = ItemMusicCD.setSongInfo(si, stack);
        }
        // 抹掉会随刷新变化的 burnTime（addon 元数据）
        CdNbtHelper.updateData(stack, d -> new CdAddonData(
                d.fileHash(), d.albumId(), 0L, d.lrc(), d.lrcTrans()));
        return stack;
    }
}

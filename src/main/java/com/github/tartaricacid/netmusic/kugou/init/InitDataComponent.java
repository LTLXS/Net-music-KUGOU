package com.github.tartaricacid.netmusic.kugou.init;

import com.github.tartaricacid.netmusic.kugou.NetMusicKuGou;
import com.github.tartaricacid.netmusic.kugou.support.CdAddonData;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 父 mod 的 ItemMusicCD 已完全 DataComponent 化，不挂 NBT tag，
 * addon 需要自建 DataComponent 用来存放自己的元数据。
 *
 * <p>挂在 {@link net.minecraft.world.item.ItemStack} 上的字段：</p>
 * <ul>
 *   <li>{@link #CD_ADDON_DATA} — {@link CdAddonData}（fileHash, albumId, burnTime, lrc, lrcTrans）</li>
 * </ul>
 */
public class InitDataComponent {
    public static final DeferredRegister.DataComponents DATA_COMPONENT_TYPES =
            DeferredRegister.createDataComponents(
                    Registries.DATA_COMPONENT_TYPE, NetMusicKuGou.MOD_ID);

    /**
     * CD 附加数据组件
     * <p>键名：{@code netmusic_kugou:cd_addon_data}</p>
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<CdAddonData>> CD_ADDON_DATA =
            DATA_COMPONENT_TYPES.registerComponentType(
                    "cd_addon_data",
                    builder -> builder.persistent(CdAddonData.CODEC).networkSynchronized(CdAddonData.STREAM_CODEC)
            );
}

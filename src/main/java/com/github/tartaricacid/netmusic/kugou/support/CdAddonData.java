package com.github.tartaricacid.netmusic.kugou.support;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * 父 mod 的 ItemMusicCD 不挂 NBT tag，用 DataComponent。
 * addon 自己的元数据封装到本 record，挂到 {@link net.minecraft.world.item.ItemStack} 上。
 *
 * <p>字段含义：</p>
 * <ul>
 *   <li>{@code fileHash} — 酷狗 fileHash（URL 续期 key）</li>
 *   <li>{@code albumId} — 酷狗 albumId（URL 续期 key）</li>
 *   <li>{@code burnTime} — 烧录时间戳（ms）</li>
 *   <li>{@code lrc} — 原文 LRC（multiline string）</li>
 *   <li>{@code lrcTrans} — 翻译 LRC（multiline string，可能为空）</li>
 * </ul>
 */
public record CdAddonData(
        String fileHash,
        String albumId,
        long burnTime,
        String lrc,
        String lrcTrans
) {
    public static final CdAddonData EMPTY = new CdAddonData("", "", 0L, "", "");

    public static final Codec<CdAddonData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.optionalFieldOf("file_hash", "").forGetter(CdAddonData::fileHash),
            Codec.STRING.optionalFieldOf("album_id", "").forGetter(CdAddonData::albumId),
            Codec.LONG.optionalFieldOf("burn_time", 0L).forGetter(CdAddonData::burnTime),
            Codec.STRING.optionalFieldOf("lrc", "").forGetter(CdAddonData::lrc),
            Codec.STRING.optionalFieldOf("lrc_trans", "").forGetter(CdAddonData::lrcTrans)
    ).apply(instance, CdAddonData::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, CdAddonData> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, CdAddonData::fileHash,
            ByteBufCodecs.STRING_UTF8, CdAddonData::albumId,
            ByteBufCodecs.VAR_LONG, CdAddonData::burnTime,
            ByteBufCodecs.STRING_UTF8, CdAddonData::lrc,
            ByteBufCodecs.STRING_UTF8, CdAddonData::lrcTrans,
            CdAddonData::new
    );

    public boolean hasFileHash() {
        return fileHash != null && !fileHash.isEmpty();
    }

    public boolean hasLrc() {
        return lrc != null && !lrc.isEmpty();
    }
}

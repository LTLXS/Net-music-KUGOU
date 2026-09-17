package com.github.tartaricacid.netmusic.kugou.support;

import com.github.tartaricacid.netmusic.kugou.init.InitDataComponent;
import net.minecraft.core.Registry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.apache.commons.lang3.StringUtils;

import java.util.Optional;
import java.util.function.UnaryOperator;

public record CdAddonData(
        String fileHash,
        String albumId,
        long burnTime,
        String lrc,
        String lrcTrans
) {
    public static final CdAddonData EMPTY = new CdAddonData("", "", 0L, "", "");

    public boolean hasFileHash() {
        return fileHash != null && !fileHash.isEmpty();
    }

    public boolean hasLrc() {
        return lrc != null && !lrc.isEmpty();
    }

    public CdAddonData withFileHash(String hash) {
        return new CdAddonData(hash, albumId, burnTime, lrc, lrcTrans);
    }

    public CdAddonData withAlbumId(String albumId) {
        return new CdAddonData(fileHash, albumId, burnTime, lrc, lrcTrans);
    }
}

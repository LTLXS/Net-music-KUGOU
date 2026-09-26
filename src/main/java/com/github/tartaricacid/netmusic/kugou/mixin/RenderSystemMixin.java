package com.github.tartaricacid.netmusic.kugou.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 在所有 GUI 缩放下把字体图集过滤切为 NEAREST。
 Font 默认用 LINEAR 过滤渲染字体图集，非整数 GUI 缩放下字符边沿会被插值模糊。
 Mixin setShaderTexture(int, ResourceLocation)，路径含 "font" 时
 把该贴图 min/mag filter 切到 NEAREST；全局生效（含原版字体）。

 @see TextureManagerMixin 同样通过 per-texture filter 处理图集贴图*/
@Mixin(value = RenderSystem.class, remap = false)
public class RenderSystemMixin {

    /**
 注入点：RenderSystem.setShaderTexture(int, ResourceLocation) 调用前。
 当传入的 ResourceLocation 路径含 "font"（如 minecraft:font/ascii、
 minecraft:font/accents_regular 等字体图集）时，把贴图切到 NEAREST。
 注意：必须用方法描述符 (ILnet/.../ResourceLocation;)V 精确定位，否则
 Mixin 注解处理器会因为 setShaderTexture 有 4 个重载（int+RL / int+int /
 private _setShaderTexture(int+RL) / private _setShaderTexture(int+int)）而报
 "Unable to locate obfuscation mapping" 错误。描述符锁定了 (int, ResourceLocation)→void
 这一个公开静态方法。
*/
    @Inject(method = "setShaderTexture(ILnet/minecraft/resources/ResourceLocation;)V", at = @At("HEAD"))
    private static void netmusicKuGou$forceNearestFont(int unit, ResourceLocation location, CallbackInfo ci) {
        if (location == null) {
            return;
        }
        if (!location.getPath().contains("font")) {
            return;
        }
        AbstractTexture tex = Minecraft.getInstance().getTextureManager().getTexture(location);
        if (tex != null) {
            tex.setFilter(false, false);
        }
    }
}

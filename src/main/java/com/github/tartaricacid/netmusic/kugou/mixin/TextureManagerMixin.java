package com.github.tartaricacid.netmusic.kugou.mixin;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 字体图集懒加载兜底：路径含 "font" 的贴图注册到 TextureManager 时立刻切到 NEAREST。
 GlyphRenderTypes.createFor*Texture 返回时图集贴图常未加载（懒加载），
 而 register(ResourceLocation, AbstractTexture) 是所有贴图注册的统一入口；
 在 TAIL 注入时贴图已构造完毕，可直接 setFilter(false, false)。
 作用于全部原版字体图集，与 GlyphRenderTypesMixin 互补。*/
@Mixin(value = TextureManager.class, remap = false)
public class TextureManagerMixin {

    @Inject(method = "register(Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/client/renderer/texture/AbstractTexture;)V", at = @At("TAIL"))
    private void netmusicKuGou$onTextureRegistered(ResourceLocation location, AbstractTexture texture, CallbackInfo ci) {
        if (location == null || texture == null) {
            return;
        }
        if (location.getPath().contains("font")) {
            texture.setFilter(false, false);
        }
    }
}

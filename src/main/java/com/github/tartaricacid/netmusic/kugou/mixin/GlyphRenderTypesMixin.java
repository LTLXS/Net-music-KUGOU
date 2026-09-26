package com.github.tartaricacid.netmusic.kugou.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.GlyphRenderTypes;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 让字体图集（glyph atlas）在所有 GUI 缩放下保持 NEAREST 过滤。
 字体走 RenderType 管线，Font.drawInBatch 不调 setShaderTexture，
 入口是 createForIntensityTexture / createForColorTexture 两个工厂方法；
 在其 RETURN 时把图集贴图切到 setFilter(false, false)，之后每次 bind 均用 NEAREST。*/
@Mixin(value = GlyphRenderTypes.class, remap = false)
public class GlyphRenderTypesMixin {

    @Inject(method = "createForIntensityTexture(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/gui/font/GlyphRenderTypes;", at = @At("RETURN"))
    private static void netmusicKuGou$onIntensityTextureCreated(ResourceLocation location, CallbackInfoReturnable<GlyphRenderTypes> cir) {
        forceNearest(location, "intensity");
    }

    /**
 颜色图集（color / SDF TTF font）。
 对应 RenderType.text*(location) 系列。
 注意：SDF 字体在 shader 内部用距离场计算抗锯齿边缘，setFilter
 无法改变最终视觉效果。所以这个钩子只做日志，不再 setFilter。
 真正的 fix 是让 ASCII 走 bitmap 路径（关掉 forceUnicodeFont），
 见 com.github.tartaricacid.netmusic.kugou.mixin.ForceBitmapAsciiFontMixin。
*/
    @Inject(method = "createForColorTexture(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/gui/font/GlyphRenderTypes;", at = @At("RETURN"))
    private static void netmusicKuGou$onColorTextureCreated(ResourceLocation location, CallbackInfoReturnable<GlyphRenderTypes> cir) {
    }

    /**
 实际把图集贴图切到 NEAREST 的工具方法。
 字体图集可能在 createFor*Texture 返回时尚未加载（懒加载），
 所以 getTexture 可能返回 null。这种情况下我们无法立即设置 filter——
 真正的 fix 走 Mixin 在 TextureManager.register 上兜底（见
 com.github.tartaricacid.netmusic.kugou.mixin.TextureManagerMixin）。
*/
    private static void forceNearest(ResourceLocation location, String atlasType) {
        if (location == null) {
            return;
        }
        AbstractTexture tex = Minecraft.getInstance().getTextureManager().getTexture(location);
        if (tex != null) {
            tex.setFilter(false, false);
        }
    }
}

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
 * 让字体图集（glyph atlas）在所有 GUI 缩放下保持 NEAREST 过滤。
 * <p>
 * <b>为什么 hook 这里：</b>
 * <ul>
 *   <li>字体走 {@code RenderType} 渲染管线，{@code Font.drawInBatch} 不调
 *       {@code setShaderTexture}，所以钩 RenderSystem 没用</li>
 *   <li>真正的入口是 {@link GlyphRenderTypes#createForIntensityTexture} 和
 *       {@link GlyphRenderTypes#createForColorTexture}——它们接收字体图集
 *       {@link ResourceLocation}，构造 3 个 {@code RenderType}（normal/seeThrough/polygonOffset）</li>
 * </ul>
 * <p>
 * <b>做法：</b>在这两个工厂方法 RETURN 时，把图集贴图（若已加载）切到
 * {@code setFilter(false, false)} = NEAREST。之后字体图集每次被 bind 都会用 NEAREST，
 * 1.5× GUI 缩放下英文/数字的 8×9 bitmap 边沿锐利。
 */
@Mixin(value = GlyphRenderTypes.class, remap = false)
public class GlyphRenderTypesMixin {

    /**
     * 单色强度图集（intensity / bitmap font）。
     * 对应 {@code RenderType.textIntensity*}(location) 系列。
     */
    @Inject(method = "createForIntensityTexture(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/gui/font/GlyphRenderTypes;", at = @At("RETURN"))
    private static void netmusicKuGou$onIntensityTextureCreated(ResourceLocation location, CallbackInfoReturnable<GlyphRenderTypes> cir) {
        forceNearest(location, "intensity");
    }

    /**
     * 颜色图集（color / SDF TTF font）。
     * 对应 {@code RenderType.text*}(location) 系列。
     * <p>
     * <b>注意：</b>SDF 字体在 shader 内部用距离场计算抗锯齿边缘，{@code setFilter}
     * 无法改变最终视觉效果。所以这个钩子只做日志，不再 setFilter。
     * 真正的 fix 是让 ASCII 走 bitmap 路径（关掉 {@code forceUnicodeFont}），
     * 见 {@link com.github.tartaricacid.netmusic.kugou.mixin.ForceBitmapAsciiFontMixin}。
     */
    @Inject(method = "createForColorTexture(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/gui/font/GlyphRenderTypes;", at = @At("RETURN"))
    private static void netmusicKuGou$onColorTextureCreated(ResourceLocation location, CallbackInfoReturnable<GlyphRenderTypes> cir) {
        // no-op: SDF shader ignores filter setting
    }

    /**
     * 实际把图集贴图切到 NEAREST 的工具方法。
     * <p>
     * 字体图集可能在 {@code createFor*Texture} 返回时**尚未加载**（懒加载），
     * 所以 getTexture 可能返回 null。这种情况下我们无法立即设置 filter——
     * 真正的 fix 走 Mixin 在 {@code TextureManager.register} 上兜底（见
     * {@link com.github.tartaricacid.netmusic.kugou.mixin.TextureManagerMixin}）。
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

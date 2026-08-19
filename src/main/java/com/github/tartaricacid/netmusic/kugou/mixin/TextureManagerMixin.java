package com.github.tartaricacid.netmusic.kugou.mixin;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 字体图集懒加载兜底：当任何路径含 {@code "font"} 的贴图被注册到
 * {@link TextureManager} 时，立刻切到 NEAREST。
 * <p>
 * <b>为什么需要这个：</b>
 * <ul>
 *   <li>{@code GlyphRenderTypes.createFor*Texture(rl)} 返回时，字体图集贴图
 *       经常还没加载（懒加载）</li>
 *   <li>等贴图真被 bind 时已经过了我的钩子点</li>
 *   <li>{@link TextureManager#register(ResourceLocation, AbstractTexture)} 是所有
 *       贴图注册的统一入口——任何字体图集**最终**都会经过这里</li>
 *   <li>在 TAIL 注入：贴图对象已经构造完毕，可直接 {@code setFilter(false, false)}</li>
 * </ul>
 * <p>
 * <b>影响：</b>整个游戏所有字体（{@code minecraft:font/ascii}、
 * {@code minecraft:font/accents_*}、{@code minecraft:font/nonlatin_*} 等）都 NEAREST。
 * 与 {@link GlyphRenderTypesMixin} 互补。
 */
@Mixin(value = TextureManager.class, remap = false)
public class TextureManagerMixin {

    @Inject(method = "register(Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/client/renderer/texture/AbstractTexture;)V", at = @At("TAIL"))
    private void netmusicKuGou$onTextureRegistered(ResourceLocation location, AbstractTexture texture, CallbackInfo ci) {
        if (location == null || texture == null) {
            return;
        }
        // 字体图集路径形如 "font/ascii" / "font/accents_regular" / "font/nonlatin_european"
        if (location.getPath().contains("font")) {
            texture.setFilter(false, false);
        }
    }
}

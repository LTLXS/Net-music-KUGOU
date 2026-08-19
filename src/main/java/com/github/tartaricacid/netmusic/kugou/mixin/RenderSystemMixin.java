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
 * 让字体在所有 GUI 缩放下都保持清晰。
 * <p>
 * <b>背景</b>：{@link net.minecraft.client.gui.Font} 默认用 LINEAR 过滤渲染
 * 字体图集（glyph atlas）。在 1.5×、1.0× 等非整数 GUI 缩放下，字符边沿会被插值成糊状，
 * 即使 {@code forceUnicodeFont=true} 也救不了 ASCII（英文始终走 8×9 bitmap 路径）。
 * <p>
 * <b>做法</b>：Mixin {@link RenderSystem#setShaderTexture(int, ResourceLocation)}，
 * 检测到 location 路径含 {@code "font"} 关键字（即字体图集）时，把该贴图的 min/mag filter
 * 切到 NEAREST（{@code setFilter(false, false)}），之后所有字体渲染都 crisp。
 * <p>
 * <b>影响范围</b>：全局生效（不只是我们 add-on 的 GUI），但这正是用户想要的——整个游戏
 * 字体都清晰。贴图对象的 filter 在第一次绑定时被改，之后所有渲染都自动用 NEAREST。
 *
 * @see TextureManagerMixin 类似地通过 per-texture filter 控制 GUI 贴图清晰度
 */
@Mixin(value = RenderSystem.class, remap = false)
public class RenderSystemMixin {

    /**
     * 注入点：{@code RenderSystem.setShaderTexture(int, ResourceLocation)} 调用前。
     * <p>
     * 当传入的 ResourceLocation 路径含 {@code "font"}（如 {@code minecraft:font/ascii}、
     * {@code minecraft:font/accents_regular} 等字体图集）时，把贴图切到 NEAREST。
     * <p>
     * <b>注意</b>：必须用方法描述符 {@code (ILnet/.../ResourceLocation;)V} 精确定位，否则
     * Mixin 注解处理器会因为 {@code setShaderTexture} 有 4 个重载（int+RL / int+int /
     * private _setShaderTexture(int+RL) / private _setShaderTexture(int+int)）而报
     * "Unable to locate obfuscation mapping" 错误。描述符锁定了 (int, ResourceLocation)→void
     * 这一个公开静态方法。
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

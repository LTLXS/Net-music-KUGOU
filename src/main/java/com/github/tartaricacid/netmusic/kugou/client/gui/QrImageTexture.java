package com.github.tartaricacid.netmusic.kugou.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.concurrent.atomic.AtomicInteger;

/**
 把第三方登录（QQ / 微信）返回的二维码图片字节上传成 Minecraft 贴图。
 不走 NativeImage.read：它底层是 stb_image，会在调用线程栈上 alloca 解码缓冲，
 栈小的线程会抛 "Out of stack space"。这里用 JDK ImageIO 在堆上解码，再逐像素写入 NativeImage。
*/
public final class QrImageTexture {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private final ResourceLocation id;
    private DynamicTexture texture;
    private int width;
    private int height;

    private QrImageTexture(ResourceLocation id) {
        this.id = id;
    }

    public static QrImageTexture create() {
        return new QrImageTexture(new ResourceLocation(
                "netmusic_kugou", "login_qr_" + COUNTER.incrementAndGet()));
    }

    /** 必须在渲染线程调用（内部会做 GL 上传） */
    public boolean update(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            return false;
        }
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (src == null) {
                return false;
            }
            int w = src.getWidth();
            int h = src.getHeight();
            NativeImage img = new NativeImage(w, h, false);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = src.getRGB(x, y);
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;
                    // NativeImage 内部是 ABGR，需要做通道交换
                    img.setPixelRGBA(x, y, 0xFF000000 | (b << 16) | (g << 8) | r);
                }
            }
            close();
            DynamicTexture tex = new DynamicTexture(img);
            Minecraft.getInstance().getTextureManager().register(id, tex);
            this.texture = tex;
            this.width = w;
            this.height = h;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public ResourceLocation id() {
        return id;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public boolean ready() {
        return texture != null && width > 0 && height > 0;
    }

    public void close() {
        DynamicTexture tex = this.texture;
        this.texture = null;
        this.width = 0;
        this.height = 0;
        if (tex == null) {
            return;
        }
        try {
            Minecraft.getInstance().getTextureManager().release(id);
            tex.close();
        } catch (Throwable ignored) {
        }
    }
}

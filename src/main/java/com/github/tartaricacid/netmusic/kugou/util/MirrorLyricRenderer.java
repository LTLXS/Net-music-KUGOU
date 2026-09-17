package com.github.tartaricacid.netmusic.kugou.util;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

public final class MirrorLyricRenderer {

    private MirrorLyricRenderer() {
    }

    public static MultiBufferSource mirror(MultiBufferSource source) {
        return new MirrorBufferSource(source);
    }

    private static final class MirrorBufferSource implements MultiBufferSource {
        private final MultiBufferSource delegate;

        MirrorBufferSource(MultiBufferSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public VertexConsumer getBuffer(RenderType renderType) {
            return new MirrorVertexConsumer(delegate.getBuffer(renderType));
        }
    }

    private static final class MirrorVertexConsumer implements VertexConsumer {
        private final VertexConsumer delegate;

        private static final int VERTS = 4;
        private final Matrix4f[] mats = new Matrix4f[VERTS];
        private final float[] lx = new float[VERTS], ly = new float[VERTS], lz = new float[VERTS];
        private final int[] cr = new int[VERTS], cg = new int[VERTS], cb = new int[VERTS], ca = new int[VERTS];
        private final float[] pu = new float[VERTS], pv = new float[VERTS];
        private final int[] pu2 = new int[VERTS], pw2 = new int[VERTS];
        private final float[] pnx = new float[VERTS], pny = new float[VERTS], pnz = new float[VERTS];
        private final boolean[] pnor = new boolean[VERTS];
        private int idx = 0;

        private Matrix4f curMat;
        private float cx, cy, cz;
        private int tr, tg, tb, ta;
        private float tu, tv;
        private int tu2, tw2;
        private float tnx, tny, tnz;
        private boolean tnor;

        MirrorVertexConsumer(VertexConsumer delegate) {
            this.delegate = delegate;
        }

        @Override
        public VertexConsumer vertex(Matrix4f matrix, float x, float y, float z) {
            this.curMat = matrix;
            this.cx = -x;
            this.cy = y;
            this.cz = z;
            return this;
        }

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            // Font 始终使用 vertex(Matrix4f, ...)；若走到这里说明调用方不符合预期
            throw new UnsupportedOperationException("MirrorVertexConsumer expects vertex(Matrix4f, float, float, float)");
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            this.tr = r;
            this.tg = g;
            this.tb = b;
            this.ta = a;
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            this.tu = u;
            this.tv = v;
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            this.tu2 = u;
            this.tw2 = v;
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            this.tnx = x;
            this.tny = y;
            this.tnz = z;
            this.tnor = true;
            return this;
        }

        @Override
        public void endVertex() {
            int i = this.idx;
            this.mats[i] = this.curMat == null ? null : new Matrix4f(this.curMat);
            this.lx[i] = this.cx;
            this.ly[i] = this.cy;
            this.lz[i] = this.cz;
            this.cr[i] = this.tr;
            this.cg[i] = this.tg;
            this.cb[i] = this.tb;
            this.ca[i] = this.ta;
            this.pu[i] = this.tu;
            this.pv[i] = this.tv;
            this.pu2[i] = this.tu2;
            this.pw2[i] = this.tw2;
            this.pnx[i] = this.tnx;
            this.pny[i] = this.tny;
            this.pnz[i] = this.tnz;
            this.pnor[i] = this.tnor;
            this.idx++;
            if (this.idx == VERTS) {
                flush();
                this.idx = 0;
            }
        }

        private void flush() {
            // 逆序发出：抵消 x 取反导致的环绕方向翻转，使文字仍正面朝向
            for (int k = VERTS - 1; k >= 0; k--) {
                if (this.mats[k] != null) {
                    this.delegate.vertex(this.mats[k], this.lx[k], this.ly[k], this.lz[k]);
                } else {
                    this.delegate.vertex(this.lx[k], this.ly[k], this.lz[k]);
                }
                this.delegate.color(this.cr[k], this.cg[k], this.cb[k], this.ca[k]);
                this.delegate.uv(this.pu[k], this.pv[k]);
                this.delegate.uv2(this.pu2[k], this.pw2[k]);
                if (this.pnor[k]) {
                    this.delegate.normal(this.pnx[k], this.pny[k], this.pnz[k]);
                }
                this.delegate.endVertex();
            }
        }

        @Override
        public void defaultColor(int r, int g, int b, int a) {
            delegate.defaultColor(r, g, b, a);
        }

        @Override
        public void unsetDefaultColor() {
            delegate.unsetDefaultColor();
        }
    }
}

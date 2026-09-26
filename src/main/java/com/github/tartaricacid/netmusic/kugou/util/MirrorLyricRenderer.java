package com.github.tartaricacid.netmusic.kugou.util;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;


/*
           ....:::--==++**#%%%.          -%%+:        :=======================================      
       #@@@@@@@@@@@@@@@@@@@@@@*        -#@@@@@%=      +@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@.     
       =@@@@@@@@@@@@@@@@@@@@@@@.     =#@@@@@@@#:      +@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@.     
       .@@%%%%%#####**++==+=-..   :*@@@@@@@@#-        .----==---------%@@@@@@*:-:::::--%@@@@@@      
        :-=+=  -+*#%#    :@%##*+*%@@@@@@@@#:               %%%#**-    *@@@@@@#=========@@@@@@=      
       =@@@@@= :@@@@@#   %@@@@@-#@@@@@@%+:                *@@@@@@:    #@@@@@@@@@@@@@@@@@-:=++       
        #@@@@@- -@@@@@# *@@@@@:  -@@@*-   :#@+.          +@@@@@@@*-.  *@@@@@@#==========            
         %@@@@@: =%#*=-+@@@@@-    .-    :*@@@@@#-       *@@@@@@@@@@@%#@@@@@@@#==========++++***=    
         :#*=-. :+*+====:=+#-         -#@@@@@@@@=     =%@@@@@@##@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@:    
       .::::..::*@@@@@@#...::::    :+%@@@@@@@%=     :%@@@@@@@=  .-+#%%@%@@@@@@@@@@@@@@@@@@@@@@*     
       =@@@@@@@@@@@@@@@@@@@@@@@::+%@@@@@@@@%=        .+%@@@*.         .*******-...............      
       =@@@@@@@@@@@@@@@@@@@@@@@:%@@@@@@@@#-             -%@**########*#@@@@@@@#************:        
       -%#%%##@@@@@@@@@@#####%#  =@@@@#=.   =*:          +@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@=        
             +@@@@@@@@@* ++:      .*=.    :#@@@%=.       +@@@@@@+:::::-@@@@@@@=:::::*@@@@@@=        
           -%@@@@@@@@@@%@@@@%=          :#@@@@@@@%.      +@@@@@@*-===-=@@@@@@@+-=-=-*@@@@@@=        
         -#@@@@@@@@@@@@@@@@@@@@*:    .=%@@@@@@@@+        +@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@=        
      :+%@@@@@@@#@@@@@@*=%@@@@@#. .-*@@@@@@@@@*.         -*************@@@@@@@#*****%@@@@@#:        
      *@@@@@@@#.-@@@@@@*  =%@%=.=#@@@@@@@@@@+.                        .@@@@@@@-    -%@@@@@%-        
       :%@@@#-  =@@@@@@*    -.*@@@@@@@@@@%=.          #@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@%-      
         +#:    =@@@@@@*      .+@@@@@@%+:             +@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@#.    
                =@@@@@@*        :%@%+:                :%%#######*****++++++=====-------:-%@@#=:     
                 .......          .                                                       -.        
*/

/**
 镜像歌词渲染器（独立于 mixin 包：mixin 类的内部类被复制进目标类后
 不能再被 mixin 类加载器引用，否则抛 IllegalClassLoadError）。
 播放《アンノウン・マザーグース》时在主行下方渲染左右镜像的半透明歌词。
 不能用矩阵翻转：单轴反射会反转三角形绕序，被 text RenderType 背面剔除。
 方案：反射 Proxy 包装 VertexConsumer，拦截 addVertex 把 x 取反
 （歌词行以 x=0 居中，镜像后仍居中），每 4 个顶点（一个字形 quad）倒序回放，
 发射顺序反转抵消绕序翻转，文字呈左右镜像。*/
public final class MirrorLyricRenderer {

    private MirrorLyricRenderer() {
    }

    public static void render(PoseStack poseStack, MultiBufferSource.BufferSource textSource,
                              Font font, Component line, float baseline,
                              int lineColor, int combinedLightIn,
                              boolean mirrored, Font.DisplayMode mode) {
        if (line == null || line == Component.empty()) {
            return;
        }

        float w = (float) (-font.width(line) / 2);

        if (!mirrored) {
            font.drawInBatch(line, w, baseline, lineColor, false,
                    poseStack.last().pose(), textSource, mode,
                    0, combinedLightIn);
            return;
        }

        MirrorSource mirrorSource = new MirrorSource(textSource);
        try {
            font.drawInBatch(line, w, baseline, lineColor, false,
                    poseStack.last().pose(), mirrorSource, mode,
                    0, combinedLightIn);
        } finally {
            mirrorSource.flushAll();
        }
    }

    private static final class MirrorSource implements MultiBufferSource {
        private final MultiBufferSource.BufferSource delegate;
        private final java.util.List<MirrorHandler> handlers = new java.util.ArrayList<>();

        MirrorSource(MultiBufferSource.BufferSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public VertexConsumer getBuffer(RenderType renderType) {
            MirrorHandler handler = new MirrorHandler(delegate.getBuffer(renderType));
            handlers.add(handler);
            return handler.createProxy();
        }

        void flushAll() {
            for (MirrorHandler handler : handlers) {
                handler.flushPending();
            }
        }
    }

    /**
 InvocationHandler：缓冲顶点方法调用序列（addVertex → setColor/setUv...），
 每满 4 个顶点（一个 quad）倒序回放恢复绕序；addVertex 的 x 取反实现左右镜像。
 反射 Proxy 避免实现接口全部抽象方法（版本签名差异）。
*/
    private static final class MirrorHandler implements java.lang.reflect.InvocationHandler {
        private final VertexConsumer real;
        private final java.util.List<java.util.List<Object[]>> vertices = new java.util.ArrayList<>();
        private java.util.List<Object[]> current = new java.util.ArrayList<>();
        private VertexConsumer proxy;

        MirrorHandler(VertexConsumer real) {
            this.real = real;
        }

        VertexConsumer createProxy() {
            proxy = (VertexConsumer) java.lang.reflect.Proxy.newProxyInstance(
                    VertexConsumer.class.getClassLoader(),
                    new Class<?>[]{VertexConsumer.class}, this);
            return proxy;
        }

        @Override
        public Object invoke(Object p, java.lang.reflect.Method method, Object[] args) throws Throwable {
            try {
                if (method.getDeclaringClass() == Object.class) {
                    return switch (method.getName()) {
                        case "equals" -> p == args[0];
                        case "hashCode" -> System.identityHashCode(p);
                        default -> "MirrorHandlerProxy";
                    };
                }
                String name = method.getName();
                boolean isVertex = "addVertex".equals(name) || "vertex".equals(name);
                if (isVertex) {
                    if (!current.isEmpty()) {
                        vertices.add(current);
                        current = new java.util.ArrayList<>();
                        if (vertices.size() >= 4) {
                            emitReversed();
                        }
                    }
                    Object[] copy = args == null ? new Object[0] : args.clone();
                    flipX(copy);
                    current.add(new Object[]{method, copy});
                } else {
                    current.add(new Object[]{method, args == null ? new Object[0] : args.clone()});
                }
                Class<?> rt = method.getReturnType();
                if (rt == void.class) return null;
                if (rt == boolean.class) return false;
                if (rt == int.class) return 0;
                return proxy;
            } catch (Throwable t) {
                return method.invoke(real, args);
            }
        }

        /** 左右镜像：x → -x（歌词行以 x=0 居中，镜像后仍居中对齐） */
        private void flipX(Object[] args) {
            if (args == null) return;
            if (args.length == 4 && args[1] instanceof Float) {
                args[1] = -(Float) args[1];
            } else if (args.length == 3 && args[0] instanceof Float) {
                args[0] = -(Float) args[0];
            } else if (args.length == 3 && args[0] instanceof Double) {
                args[0] = -(Double) args[0];
            }
        }

        /** 倒序回放缓冲顶点：位置已镜像 + 发射顺序反转 = 绕序恢复 */
        private void emitReversed() throws Exception {
            for (int i = vertices.size() - 1; i >= 0; i--) {
                for (Object[] inv : vertices.get(i)) {
                    ((java.lang.reflect.Method) inv[0]).invoke(real, (Object[]) inv[1]);
                }
            }
            vertices.clear();
        }

        void flushPending() {
            try {
                if (!current.isEmpty()) {
                    vertices.add(current);
                    current = new java.util.ArrayList<>();
                }
                if (!vertices.isEmpty()) {
                    emitReversed();
                }
            } catch (Throwable ignored) {
            }
        }
    }
}

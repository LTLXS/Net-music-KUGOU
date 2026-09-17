package com.github.tartaricacid.netmusic.kugou.api;
import net.minecraft.network.chat.Component;

/**
 * 酷狗 API 错误码解释器
 * 用于将数字错误码转换为用户友好的提示信息
 */
public final class KuGouErrorCode {

    private KuGouErrorCode() {}

    /**
     * 解释错误码含义
     * @param errorCode 错误码
     * @return 用户友好的错误描述
     */
    public static String explain(int errorCode) {
        switch (errorCode) {
            case 0: return "netmusic_kugou.error.0";
            case 20002: return "netmusic_kugou.error.20002";
            case 131001: return "netmusic_kugou.error.131001";
            case 297002: return "netmusic_kugou.error.297002";
            case 51002: return "netmusic_kugou.error.51002";
            case 51003: return "netmusic_kugou.error.51003";
            case 51004: return "netmusic_kugou.error.51004";
            case 31002: return "netmusic_kugou.error.31002";
            case 31003: return "netmusic_kugou.error.31003";
            case 10001: return "netmusic_kugou.error.10001";
            case 10002: return "netmusic_kugou.error.10002";
            case 10003: return "netmusic_kugou.error.10003";
            case 10004: return "netmusic_kugou.error.10004";
            case 20001: return "netmusic_kugou.error.20001";
            case 30001: return "netmusic_kugou.error.30001";
            case 30002: return "netmusic_kugou.error.30002";
            case 30003: return "netmusic_kugou.error.30003";
            case 40001: return "netmusic_kugou.error.40001";
            case 40002: return "netmusic_kugou.error.40002";
            case 31833: return "netmusic_kugou.error.31833";
            case 61003: return "netmusic_kugou.error.61003";
            case 30101: return "netmusic_kugou.error.30101";
            case 35104: return "netmusic_kugou.error.35104";
            case 20006: return "netmusic_kugou.error.20006";
            case 20010: return "netmusic_kugou.error.20010";
            case 20008: return "netmusic_kugou.error.20008";
            default: return "netmusic_kugou.error.default";
        }
    }

    /**
     * 判断是否需要重新登录
     * @param errorCode 错误码
     * @return true 表示需要用户重新登录
     */
    public static boolean needRelogin(int errorCode) {
        return errorCode == 51002 || errorCode == 51003 || errorCode == 51004;
    }

    /**
     * 判断是否可以重试
     * @param errorCode 错误码
     * @return true 表示可以稍后重试
     */
    public static boolean canRetry(int errorCode) {
        return errorCode == 0 || errorCode == 10004 || errorCode == 20001;
    }

    /**
     * 获取完整的错误信息（包含错误码和解释）
     * @param errorCode 错误码
     * @return 完整错误信息
     */
    public static String getFullMessage(int errorCode) {
        String explanation = explain(errorCode);
        if (errorCode == 0) {
            return Component.translatable(explanation).getString();
        }
        return Component.translatable("netmusic_kugou.error.full", errorCode, Component.translatable(explanation).getString()).getString();
    }
}

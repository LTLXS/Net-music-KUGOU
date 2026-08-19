package com.github.tartaricacid.netmusic.kugou.api;

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
            case 0: return "成功";
            case 20002: return "请求格式错误（请检查模组版本）";
            case 131001: return "今日VIP领取次数已用完，请明天再试";
            case 297002: return "今日VIP升级已生效";
            case 51002: return "登录已过期，请在游戏内重新登录酷狗账号";
            case 51003: return "账号在其他设备登录，请重新登录";
            case 51004: return "账号被冻结或封禁";
            case 31002: return "VIP升级失败（可能是今日已升级）";
            case 31003: return "账号无VIP领取资格";
            case 10001: return "缺少必要参数";
            case 10002: return "参数格式错误";
            case 10003: return "签名验证失败";
            case 10004: return "请求过于频繁，请稍后再试";
            case 20001: return "服务端内部错误";
            case 30001: return "账号不存在";
            case 30002: return "密码错误";
            case 30003: return "验证码错误";
            case 40001: return "设备未注册";
            case 40002: return "设备已被禁用";
            default: return "未知错误";
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
            return explanation;
        }
        return String.format("错误码 %d: %s", errorCode, explanation);
    }
}
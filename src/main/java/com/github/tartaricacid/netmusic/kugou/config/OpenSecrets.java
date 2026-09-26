package com.github.tartaricacid.netmusic.kugou.config;

import com.github.tartaricacid.netmusic.kugou.KuGouLogger;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * 第三方登录（QQ / 微信）凭据的外部化读取。
 *
 * <p>密钥不再硬编码在源码中（避免提交到公开仓库导致泄露，被 GitHub secret scanning 报警）。
 * 读取优先级：
 *   1. 环境变量 KG_QQ_APPID / KG_QQ_APK_SIG_MD5 / KG_WX_APPID / KG_WX_SECRET（最高）
 *   2. classpath 下的 secret.properties（即构建时打进 jar 的那个，本地开发放 src/main/resources/secret.properties，已被 .gitignore 排除）
 *   3. 都为空则返回 null，调用方应给出明确提示并禁用对应登录方式。
 */
public final class OpenSecrets {
    private static final Properties PROPS = load();

    private static Properties load() {
        Properties p = new Properties();
        try (InputStream in = OpenSecrets.class.getClassLoader().getResourceAsStream("secret.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException e) {
            KuGouLogger.warn("[NetMusicKuGou] 读取 secret.properties 失败: {}", e.getMessage());
        }
        return p;
    }

    private static String get(String propKey, String envName) {
        String env = System.getenv(envName);
        if (env != null && !env.isEmpty()) {
            return env;
        }
        return PROPS.getProperty(propKey);
    }

    public static final String QQ_APPID = get("kg.qq.appid", "KG_QQ_APPID");
    public static final String QQ_APK_SIG_MD5 = get("kg.qq.apk_sig_md5", "KG_QQ_APK_SIG_MD5");
    public static final String WX_APPID = get("kg.wx.appid", "KG_WX_APPID");
    public static final String WX_SECRET = get("kg.wx.secret", "KG_WX_SECRET");

    private OpenSecrets() {
    }
}

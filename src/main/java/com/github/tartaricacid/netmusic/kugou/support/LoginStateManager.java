package com.github.tartaricacid.netmusic.kugou.support;

import com.github.tartaricacid.netmusic.kugou.KuGouLogger;
import com.github.tartaricacid.netmusic.kugou.config.KuGouConfig;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 酷狗登录态（token / userid / 设备指纹 / VIP 状态 / cookies）的持久化管理。
 * 状态以 JSON 落盘到 config/NETMUSICCANNEEDKUGOU/netmusic-kugou-state.json，
 * 进程退出或世界关闭时 {@link #saveState()} 写入，启动时 {@link #loadState()} 读回。
 */
public final class LoginStateManager {
    private static final Gson GSON = new Gson();
    private static final Path CONFIG_DIR = FMLPaths.CONFIGDIR.get().resolve("NETMUSICCANNEEDKUGOU");
    private static final Path STATE_FILE = CONFIG_DIR.resolve("netmusic-kugou-state.json");

    private LoginStateManager() {
    }

    public static void loadState() {
        if (!Files.exists(STATE_FILE)) {
            return;
        }
        try {
            String json = Files.readString(STATE_FILE);
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root.has("token")) KuGouConfig.token = root.get("token").getAsString();
            if (root.has("userid")) KuGouConfig.userid = root.get("userid").getAsString();
            if (root.has("dfid")) KuGouConfig.dfid = root.get("dfid").getAsString();
            if (root.has("mid")) KuGouConfig.mid = root.get("mid").getAsString();
            if (root.has("guid")) KuGouConfig.guid = root.get("guid").getAsString();
            if (root.has("vipType")) KuGouConfig.vipType = root.get("vipType").getAsString();
            if (root.has("vipToken")) KuGouConfig.vipToken = root.get("vipToken").getAsString();
            if (root.has("cookies")) {
                JsonObject cookiesObj = root.getAsJsonObject("cookies");
                for (String key : cookiesObj.keySet()) {
                    KuGouConfig.addCookie(key, cookiesObj.get(key).getAsString());
                }
            }
            KuGouLogger.info("Login state loaded. Logged in: {}", KuGouConfig.isLoggedIn());
        } catch (Exception e) {
            KuGouLogger.error("Failed to load login state", e);
        }
    }

    public static void saveState() {
        try {
            Files.createDirectories(CONFIG_DIR);
            JsonObject root = new JsonObject();
            root.addProperty("token", KuGouConfig.token != null ? KuGouConfig.token : "");
            root.addProperty("userid", KuGouConfig.userid != null ? KuGouConfig.userid : "");
            root.addProperty("dfid", KuGouConfig.dfid != null ? KuGouConfig.dfid : "");
            root.addProperty("mid", KuGouConfig.mid != null ? KuGouConfig.mid : "");
            root.addProperty("guid", KuGouConfig.guid != null ? KuGouConfig.guid : "");
            root.addProperty("vipType", KuGouConfig.vipType != null ? KuGouConfig.vipType : "");
            root.addProperty("vipToken", KuGouConfig.vipToken != null ? KuGouConfig.vipToken : "");

            JsonObject cookiesObj = new JsonObject();
            for (var entry : KuGouConfig.cookies.entrySet()) {
                cookiesObj.addProperty(entry.getKey(), entry.getValue());
            }
            root.add("cookies", cookiesObj);

            Files.writeString(STATE_FILE, GSON.toJson(root),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            KuGouLogger.error("Failed to save login state", e);
        }
    }
}

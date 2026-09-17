package com.github.tartaricacid.netmusic.kugou.compat.netmusiclist;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.github.tartaricacid.netmusic.kugou.KuGouLogger;
import com.github.tartaricacid.netmusic.kugou.api.KuGouApiClient;
import com.github.tartaricacid.netmusic.kugou.compat.display.KuGouDisplayCompat;
import com.github.tartaricacid.netmusic.kugou.config.KuGouConfig;
import com.github.tartaricacid.netmusic.kugou.lyric.BurnDataCache;
import com.github.tartaricacid.netmusic.kugou.util.HttpUtils;
import com.github.tartaricacid.netmusic.kugou.lyric.LrcConverter;
import com.google.common.collect.Lists;
import com.google.gson.JsonElement;
import net.neoforged.fml.loading.FMLPaths;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URL;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 酷狗音乐源对 {@code netMusicListNeoForge} 的兼容层（纯反射 + 动态代理，编译期零依赖）。
 * <p>
 * netMusicList 暴露两个扩展点：
 * <ul>
 *   <li>{@code IExtraMusicSource} —— 源注册、搜索、元数据、歌词、封面、剪贴板识别</li>
 *   <li>{@code IMusicParser} —— 把 {@code netmusiclib://} URI 实时解析成酷狗直链</li>
 * </ul>
 * 运行时通过 {@link ModList} 探测 {@code net_music_list} 是否加载，加载时注册酷狗源，
 * 否则本类完全不介入，needkugou 走原有逻辑。
 */
public final class NetMusicListCompat {
    public static final String MOD_ID = "net_music_list";
    private static final String TYPE = "kugou";

    private static final String C_I_EXTRA = "com.gly091020.netMusicListNeoforge.api.musicSource.IExtraMusicSource";
    private static final String C_I_PARSER = "com.gly091020.netMusicListNeoforge.api.musicParser.IMusicParser";
    private static final String C_EXTRA_MGR = "com.gly091020.netMusicListNeoforge.api.musicSource.ExtraMusicSourceManager";
    private static final String C_PARSER_MGR = "com.gly091020.netMusicListNeoforge.api.musicParser.MusicParserManager";

    // 搜索结果元数据缓存：hash -> Song（含 name/singer/albumId/duration）
    private static final Map<String, KuGouApiClient.Song> SEARCH_CACHE = new ConcurrentHashMap<>();
    // 歌词缓存：hash -> LyricRecord（搜索/刻录阶段预填，避免播放时阻塞）
    private static final Map<String, LyricRecord> LYRIC_CACHE = new ConcurrentHashMap<>();
    // 原始 LRC 文本缓存：hash -> [lrc, lrcTrans]（刻录时需传给 BurnDataCache 写入 CD NBT，
    // 方块 CD 歌词由 SetPlayMixin 从 CD NBT 读出后写入 KuGouDisplayCompat，不走 parseLyric）
    private static final Map<String, String[]> RAW_LRC_CACHE = new ConcurrentHashMap<>();
    // 封面缓存：hash -> 原始 PNG 字节（搜索阶段异步预取，best-effort）。
    // 注意：必须缓存【字节】而非 NativeImage 实例——netMusicList 会把 parseIcon 返回的
    // NativeImage 交给 DynamicTexture（注册后由纹理管理器释放）或显式 close()，
    // 若复用同一个实例，第二次调用会拿到已释放的野对象导致封面空白（“放一次后不再加载”）。
    // 每次 parseIcon 都从字节解码出独立的新 NativeImage，交给对方释放互不影响。
    private static final Map<String, byte[]> ICON_CACHE_BYTES = new ConcurrentHashMap<>();

    // 直链缓存：hash -> (url, expireMs)。netMusicList 每次重放都走 parse() -> KuGouApiClient.getSongUrl
    // （实测 165~1255ms 网络延迟），不缓存则「每次重放都卡一下」。这里命中即秒开（0ms 延迟），
    // 仅在首播/过期时才走网络。TTL 不宜过长：酷狗直链会过期，过长会在循环第 2 圈拿到失效链接。
    private static final long URL_TTL_MS = 5L * 60 * 1000; // 5 分钟
    private record UrlCache(String url, long expireMs) {}
    private static final Map<String, UrlCache> URL_CACHE = new ConcurrentHashMap<>();

    // 封面磁盘持久化：按 hash 落盘到 config/NETMUSICCANNEEDKUGOU/icons/<hash>，
    // 解决「退出重进游戏后之前加载过的封面也加载不出来」——内存缓存重启即丢，磁盘缓存可跨会话复用。
    private static final Path ICON_DIR = FMLPaths.CONFIGDIR.get()
            .resolve("NETMUSICCANNEEDKUGOU").resolve("icons");
    static {
        try {
            Files.createDirectories(ICON_DIR);
        } catch (Throwable ignored) {
        }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private static final Pattern KUGOU_HASH = Pattern.compile("(?i)[?#&]hash=([0-9a-zA-Z]+)");

    /**
     * 大栈线程池：stb_image（NativeImage.read 底层）在栈上 alloca 解码缓冲，
     * netMusicList 的 CD-Preview-Icon 等线程栈很小，会抛 "Out of stack space"。
     * 这里把解码放到 1MB 栈的线程上执行，规避该问题。
     */
    private static final ExecutorService ICON_DECODE_EXEC = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(null, r, "kugou-icon-decode", 1024L * 1024);
        t.setDaemon(true);
        return t;
    });

    private static boolean registered = false;

    public static boolean isNetMusicListLoaded() {
        try {
            return ModList.get() != null && ModList.get().isLoaded(MOD_ID);
        } catch (Throwable t) {
            return false;
        }
    }

    public static synchronized void tryInit() {
        if (registered || !isNetMusicListLoaded()) return;
        try {
            Class<?> iExtra = Class.forName(C_I_EXTRA);
            Class<?> iParser = Class.forName(C_I_PARSER);
            Class<?> extraMgr = Class.forName(C_EXTRA_MGR);
            Class<?> parserMgr = Class.forName(C_PARSER_MGR);
            Method regExtra = extraMgr.getMethod("registry", iExtra);
            Method regParser = parserMgr.getMethod("registry", iParser);

            Object sourceProxy = Proxy.newProxyInstance(iExtra.getClassLoader(),
                    new Class<?>[]{iExtra}, new SourceHandler());
            Object parserProxy = Proxy.newProxyInstance(iParser.getClassLoader(),
                    new Class<?>[]{iParser}, new ParserHandler());

            regExtra.invoke(null, sourceProxy);
            regParser.invoke(null, parserProxy);
            registered = true;
            KuGouLogger.info("[NetMusicListCompat] Registered KuGou source & parser to netMusicList");
        } catch (Throwable t) {
            KuGouLogger.warn("[NetMusicListCompat] Failed to register KuGou to netMusicList: {}", t.getMessage());
        }
    }

    // ====================== IExtraMusicSource 代理 ======================
    private static final class SourceHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            try {
                switch (name) {
                    case "getType": return TYPE;
                    case "getDisplayName": return Component.translatable("netmusic_kugou.source.name");
                    case "getHint": return Component.translatable("netmusic_kugou.source.hint");
                    case "searchable": return true;
                    case "parseMusic": return parseMusic((String) args[0], (Boolean) args[1]);
                    case "search": return search((String) args[0]);
                    case "parseLyric": return parseLyric((String) args[0]);
                    case "parseIcon": return parseIcon((String) args[0]);
                    case "autoParseFromClipboard": return autoParseFromClipboard((String) args[0]);
                    case "autoParseListFromClipboard": return null;
                    case "parsePlaylist": return Collections.emptyList();
                    default:
                        if (method.isDefault()) return invokeDefault(proxy, method, args);
                        return defaultValue(method.getReturnType());
                }
            } catch (Throwable t) {
                KuGouLogger.warn("[NetMusicListCompat] source method {} failed: {}", name, t.getMessage());
                return defaultValue(method.getReturnType());
            }
        }
    }

    // ====================== IMusicParser 代理 ======================
    private static final class ParserHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            try {
                switch (name) {
                    case "getType": return TYPE;
                    case "getPriority": return 0;
                    case "parse": return parse((Object) args[0]);
                    default:
                        if (method.isDefault()) return invokeDefault(proxy, method, args);
                        return defaultValue(method.getReturnType());
                }
            } catch (Throwable t) {
                KuGouLogger.warn("[NetMusicListCompat] parser method {} failed: {}", name, t.getMessage());
                return defaultValue(method.getReturnType());
            }
        }
    }

    private static Object invokeDefault(Object proxy, Method method, Object[] args) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
                    method.getDeclaringClass(), MethodHandles.lookup());
            return lookup.unreflectSpecial(method, method.getDeclaringClass())
                    .bindTo(proxy).invokeWithArguments(args == null ? List.of() : Arrays.asList(args));
        } catch (Throwable t) {
            KuGouLogger.warn("[NetMusicListCompat] default method {} failed: {}", method.getName(), t.getMessage());
            return defaultValue(method.getReturnType());
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0d;
        if (type == float.class) return 0f;
        return 0;
    }

    // ====================== 源逻辑实现 ======================
    /**
     * 刻录界面输入框可能因 setMaxLength 把 32 位 hash 截断成更短的前缀。
     * 若传入 hash 偏短，尝试用前缀从搜索缓存里找回完整的 32 位 hash。
     */
    private static String resolveFullHash(String hash) {
        if (hash == null) return null;
        if (hash.length() >= 30) return hash;
        // 可能传入的是 netmusiclib URI 或真实酷狗直链（播放后 songUrl 被改写时），
        // 尝试从中反解出完整 hash
        if (hash.contains("kugou") || hash.startsWith("http") || hash.contains("hash=") || hash.contains("id=")) {
            Matcher m = KUGOU_HASH.matcher(hash);
            if (m.find() && m.group(1).length() >= 30) return m.group(1);
            Matcher m2 = Pattern.compile("(?i)[?#&]id=([0-9a-zA-Z]+)").matcher(hash);
            if (m2.find()) return m2.group(1);
        }
        for (String key : SEARCH_CACHE.keySet()) {
            if (key.startsWith(hash)) {
                KuGouLogger.info("[NetMusicListCompat] recovered full hash {} for truncated {}", key, hash);
                return key;
            }
        }
        return hash;
    }

    private static ItemMusicCD.SongInfo parseMusic(String hash, boolean readOnly) {
        KuGouLogger.info("[NetMusicListCompat] parseMusic hashLen={} hitCache={} hash={}",
                hash == null ? -1 : hash.length(), hash != null && SEARCH_CACHE.containsKey(hash), hash);
        if (hash == null || hash.isEmpty()) return null;
        hash = resolveFullHash(hash);
        KuGouApiClient.Song song = SEARCH_CACHE.get(hash);
        if (song == null) {
            // 缓存未命中：玩家直接输入 hash 或搜索缓存已过期，兜底拉取元数据
            song = fetchSongMetaByHash(hash);
            if (song != null) {
                SEARCH_CACHE.put(hash, song);
                prefetchLyricAndIcon(song);
            }
        }
        // ★ 关键兜底：绝不能让 songTime=0。
        // play/getdata 接口已失效——对每个 hash 都返回"结构性成功但字段全空"的响应
        // （name=hash、duration=0、albumId/image 全空）。若直接用它构造 SongInfo，
        // 音乐机会执行 setCurrentTime(0*20+64)=64，把歌曲当成 3 秒长，
        // 于是每 3 秒自动重新触发播放 → 翻牌板歌词永远停在开头、重放也回不正。
        // 歌词搜索接口仍然可用（返回真实 durationMs），用它反查补全时长。
        if (song == null || song.duration <= 0) {
            KuGouApiClient.Song recovered = recoverSongMetaByLyricSearch(hash);
            if (recovered != null && recovered.duration > 0) {
                song = recovered;
                SEARCH_CACHE.put(hash, song);
            }
        }
        String name = hash;
        int duration = 0;
        String singer = "";
        String albumId = "";
        if (song != null) {
            name = song.name != null ? song.name : hash;
            duration = song.duration;
            singer = song.singer != null ? song.singer : "";
            albumId = song.albumId != null ? song.albumId : "";
        }
        // 让 needkugou 服务端 mixin 把 fileHash/albumId/lrc 写进 CD NBT。
        // 方块 CD 歌词由 SetPlayMixin 从 CD NBT 读出后写入 KuGouDisplayCompat，
        // 不走 parseLyric，所以必须把原始 LRC 文本写进 CD NBT。
        String[] rawLrc = RAW_LRC_CACHE.get(hash);
        if (rawLrc == null) {
            // 预取未完成或失败：同步兜底拉一次歌词（刻录时阻塞可接受）
            rawLrc = fetchRawLyricSync(hash, name, singer, duration);
        }
        String lrcText = rawLrc != null ? rawLrc[0] : null;
        String lrcTrans = rawLrc != null ? rawLrc[1] : null;
        BurnDataCache.set(hash, albumId, lrcText, lrcTrans);
        // 同步把原始 LRC 转成 LyricRecord 写入 LYRIC_CACHE，保证播放时 parseLyric 即时命中，
        // 不再依赖异步预取（prefetchLyricAndIcon）的竞态/限流——这正是「歌词有时不显示 / 完全不显示」的根因。
        if (rawLrc != null && rawLrc[0] != null && !rawLrc[0].isEmpty()) {
            try {
                LrcConverter.KuGouLyricData data = LrcConverter.toLyricData(rawLrc[0], rawLrc[1], name);
                if (data != null && data.record != null) {
                    LYRIC_CACHE.put(hash, data.record);
                    KuGouDisplayCompat.putLyricByHash(hash, data.record);
                    KuGouLogger.info("[NetMusicListCompat] parseMusic pre-filled LYRIC_CACHE hash={}", hash);
                }
            } catch (Throwable t) {
                KuGouLogger.warn("[NetMusicListCompat] parseMusic lyric convert failed: {}", t.getMessage());
            }
        }

        // 同步把封面字节写入 ICON_CACHE_BYTES，保证播放时 parseIcon 即时命中（对称修复封面“有时/完全不显示”）。
        // 与歌词同理：不再依赖搜索阶段的异步预取，避免竞态/限流导致封面拿不到。
        try {
            KuGouApiClient.Song coverSong = song;
            // fetchSongMetaByHash(getdata) 不含封面信息，缺 albumId/image 时改用 hash 搜酷狗兜底
            if (coverSong == null || coverSong.albumId == null || coverSong.albumId.isEmpty()
                    || coverSong.image == null || coverSong.image.isEmpty()) {
                coverSong = fetchSongMetaByHash(hash);
            }
            if (coverSong == null || coverSong.albumId == null || coverSong.albumId.isEmpty()
                    || coverSong.image == null || coverSong.image.isEmpty()) {
                coverSong = searchCoverSongByHash(hash);
            }
            if (coverSong != null) {
                byte[] cover = loadCoverBytes(coverSong);
                if (cover != null) {
                    cacheCoverBytes(hash, cover);
                    KuGouLogger.info("[NetMusicListCompat] parseMusic pre-filled ICON_CACHE hash={}", hash);
                } else {
                    KuGouLogger.warn("[NetMusicListCompat] parseMusic cover load null hash={}", hash);
                }
            }
        } catch (Throwable t) {
            KuGouLogger.warn("[NetMusicListCompat] parseMusic cover fetch failed: {}", t.getMessage());
        }

        // 预热直链缓存：刻录/搜索阶段异步解析一次，播放时命中缓存秒开（消除首次播放的网络延迟）。
        // 与下面同步流程解耦：仅 fire-and-forget，失败不影响本次刻录结果。
        final String warmHash = hash;
        final String warmAlbum = albumId;
        CompletableFuture.runAsync(() -> {
            try {
                String resolved = KuGouApiClient.getSongUrl(warmHash, warmAlbum)
                        .orTimeout(20, TimeUnit.SECONDS).join();
                if (resolved != null && !resolved.isEmpty()) {
                    URL_CACHE.put(warmHash, new UrlCache(resolved, System.currentTimeMillis() + URL_TTL_MS));
                    KuGouLogger.info("[NetMusicListCompat] warmed URL cache hash={}", warmHash);
                }
            } catch (Throwable ignored) {
            }
        });

        String uri = "netmusiclib://source/" + TYPE + "?id="
                + URLEncoder.encode(hash, StandardCharsets.UTF_8) + "&duration=" + duration;
        ItemMusicCD.SongInfo info = new ItemMusicCD.SongInfo();
        info.songName = name;
        info.songUrl = uri;
        info.songTime = duration;
        info.artists = Lists.newArrayList(singer);
        info.readOnly = readOnly;
        return info;
    }

    private static List<ItemMusicCD.SongInfo> search(String keyword) {
        try {
            List<KuGouApiClient.Song> songs = KuGouApiClient.search(keyword, 1, 30)
                    .orTimeout(30, TimeUnit.SECONDS).join();
            List<ItemMusicCD.SongInfo> out = new ArrayList<>();
            if (songs == null) return out;
            for (KuGouApiClient.Song s : songs) {
                if (s == null || s.hash == null) continue;
                KuGouLogger.info("[NetMusicListCompat] search result hashLen={} hash={} name={}",
                        s.hash.length(), s.hash, s.name);
                SEARCH_CACHE.put(s.hash, s);
                String uri = "netmusiclib://source/" + TYPE + "?id="
                        + URLEncoder.encode(s.hash, StandardCharsets.UTF_8) + "&duration=" + s.duration;
                ItemMusicCD.SongInfo info = new ItemMusicCD.SongInfo();
                info.songName = s.name != null ? s.name : s.hash;
                info.songUrl = uri;
                info.songTime = s.duration;
                info.artists = Lists.newArrayList(s.singer != null ? s.singer : "");
                info.readOnly = false;
                out.add(info);
                prefetchLyricAndIcon(s);
            }
            return out;
        } catch (Throwable t) {
            KuGouLogger.warn("[NetMusicListCompat] search failed: {}", t.getMessage());
            return Collections.emptyList();
        }
    }

    private static LyricRecord parseLyric(String hash) {
        if (hash == null || hash.isEmpty()) return null;
        hash = resolveFullHash(hash);
        LyricRecord cached = LYRIC_CACHE.get(hash);
        if (cached != null) {
            KuGouDisplayCompat.putLyricByHash(hash, cached);
            return cached;
        }
        KuGouLogger.info("[NetMusicListCompat] parseLyric cache MISS, fetching for hash={}", hash);
        try {
            // 缓存未命中：先尝试用 hash 反查元数据（玩家直接粘贴 hash / 搜索缓存已过期时也能量到歌词）
            KuGouApiClient.Song song = SEARCH_CACHE.get(hash);
            if (song == null) {
                song = fetchSongMetaByHash(hash);
                if (song != null) {
                    SEARCH_CACHE.put(hash, song);
                    prefetchLyricAndIcon(song);
                }
            }
            String songName = song != null && song.name != null ? song.name : hash;
            String singer = song != null && song.singer != null ? song.singer : "";
            String keyword = song != null
                    ? (singer + " - " + songName)
                    : "";
            int durationMs = song != null ? song.duration * 1000 : 0;

            var candidates = KuGouApiClient.searchLyricCandidates(hash, keyword, durationMs, songName, singer)
                    .orTimeout(12, TimeUnit.SECONDS).join();
            var content = KuGouApiClient.getLyricWithFallback(candidates, "krc")
                    .orTimeout(12, TimeUnit.SECONDS).join();
            if (content != null && content.lyricContent != null && !content.lyricContent.isEmpty()) {
                RAW_LRC_CACHE.put(hash, new String[]{content.lyricContent, content.languageJson});
                LrcConverter.KuGouLyricData data = LrcConverter.toLyricData(
                        content.lyricContent, content.languageJson, songName);
                if (data != null && data.record != null) {
                    LYRIC_CACHE.put(hash, data.record);
                    KuGouDisplayCompat.putLyricByHash(hash, data.record);
                    KuGouLogger.info("[NetMusicListCompat] parseLyric OK hash={}", hash);
                    return data.record;
                }
            }
            KuGouLogger.warn("[NetMusicListCompat] parseLyric: no usable content for hash={} (cands={})",
                    hash, candidates == null ? "null" : candidates.size());
        } catch (Throwable t) {
            KuGouLogger.warn("[NetMusicListCompat] parseLyric failed: {}", t.getMessage());
        }
        return null;
    }

    /**
     * 解码 PNG/JPG 字节为 NativeImage。
     * 不用 NativeImage.read（底层 stb_image 在调用线程栈上 alloca 解码缓冲），
     * netMusicList 的 CD-Preview-Icon 等线程栈很小会抛 "Out of stack space"，
     * 且 stb 对部分封面所需栈>1MB，靠加大栈不可靠。这里改用 JDK ImageIO（堆内存解码），
     * 再逐像素写入 NativeImage，彻底与线程栈大小无关。
     */
    private static NativeImage decodeNativeImage(byte[] bytes) throws Exception {
        Future<NativeImage> f = ICON_DECODE_EXEC.submit(() -> {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(bytes));
            if (src == null) throw new IOException("ImageIO 无法解码封面字节（返回 null）");
            int w = src.getWidth();
            int h = src.getHeight();
            NativeImage img = new NativeImage(w, h, false);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = src.getRGB(x, y);
                    int a = (argb >> 24) & 0xFF;
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;
                    int rgba = (a << 24) | (b << 16) | (g << 8) | r;
                    img.setPixelRGBA(x, y, rgba | 0xFF000000);
                }
            }
            return img;
        });
        return f.get(15, TimeUnit.SECONDS);
    }

    private static NativeImage parseIcon(String hash) {
        if (hash == null || hash.isEmpty()) return null;
        hash = resolveFullHash(hash);
        KuGouLogger.info("[NetMusicListCompat] parseIcon ENTER resolved='{}' thread={}", hash, Thread.currentThread().getName());
        // 从字节缓存解码【全新】 NativeImage，每次调用互不影响：
        // netMusicList 会把返回的 NativeImage 交给 DynamicTexture（注册后释放）或显式 close()，
        // 若复用同一实例，第二次调用会拿到已释放的野对象导致封面空白（“放一次后不再加载”）。
        byte[] cached = ICON_CACHE_BYTES.get(hash);
        if (cached == null) cached = loadCoverFromDisk(hash);   // 跨会话磁盘缓存
        if (cached == null) {
            // 1) 先短等一下，捕捉并发的搜索/刻录流程把带 albumId+image 的 Song 写入 SEARCH_CACHE
            KuGouApiClient.Song song = SEARCH_CACHE.get(hash);
            if (song == null) {
                long deadline = System.currentTimeMillis() + 1500;
                while (System.currentTimeMillis() < deadline && (song = SEARCH_CACHE.get(hash)) == null) {
                    try { Thread.sleep(100); } catch (InterruptedException e) { break; }
                }
            }
            // 2) 单 hash 兜底：用 getdata 接口按 hash 反查封面（data.img），可靠且不依赖关键词搜索
            if (song == null) song = fetchSongMetaByHash(hash);
            // 3) 再用 hash 关键词搜酷狗兜底。
            //    注意必须按"封面信息是否真的可用"判断，不能只看 song == null：
            //    getdata 已失效，会返回非 null 但 albumId/image 全空的空壳 Song，
            //    若这里写成 (song == null) 就永远走不到搜索兜底，封面必然 "no cover source worked"。
            if (song == null || song.albumId == null || song.albumId.isEmpty()
                    || song.image == null || song.image.isEmpty()) {
                song = searchCoverSongByHash(hash);
            }
            if (song != null) {
                cached = loadCoverBytes(song);
                if (cached != null) cacheCoverBytes(hash, cached);
            }
            KuGouLogger.info("[NetMusicListCompat] parseIcon fallback done resolved='{}' gotBytes={}", hash, cached != null);
        } else {
            ICON_CACHE_BYTES.putIfAbsent(hash, cached); // 磁盘命中回填内存
        }
        if (cached == null) {
            KuGouLogger.warn("[NetMusicListCompat] parseIcon RETURN null resolved='{}'", hash);
            return null;
        }
        return decodeAndReturn(hash, cached);
    }

    private static NativeImage decodeAndReturn(String hash, byte[] cached) {
        try {
            NativeImage img = decodeNativeImage(cached);
            KuGouLogger.info("[NetMusicListCompat] parseIcon RETURN image resolved='{}' w={} h={}", hash, img.getWidth(), img.getHeight());
            return img;
        } catch (Throwable t) {
            KuGouLogger.warn("[NetMusicListCompat] parseIcon decode failed hash={}: {}", hash, t.getMessage());
            return null;
        }
    }

    private static void cacheCoverBytes(String hash, byte[] bytes) {
        ICON_CACHE_BYTES.put(hash, bytes);
        try {
            Files.write(ICON_DIR.resolve(hash), bytes);
        } catch (Throwable ignored) {
        }
    }

    private static byte[] loadCoverFromDisk(String hash) {
        try {
            Path p = ICON_DIR.resolve(hash);
            if (Files.isRegularFile(p) && Files.size(p) > 0) return Files.readAllBytes(p);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String autoParseFromClipboard(String text) {
        if (text == null) return null;
        String lower = text.toLowerCase(Locale.ROOT);
        if (!lower.contains("kugou.com")) return null;
        Matcher m = KUGOU_HASH.matcher(text);
        if (m.find()) return m.group(1);
        return null;
    }

    private static URL parse(Object musicSource) {
        try {
            Method idMethod = musicSource.getClass().getMethod("identifier");
            String hash = (String) idMethod.invoke(musicSource);
            if (hash == null || hash.isEmpty()) return null;
            hash = resolveFullHash(hash);

            // ★ 直链缓存命中：重放/循环时秒开，消除「每次重放都卡一下」的延迟。
            UrlCache cached = URL_CACHE.get(hash);
            if (cached != null && cached.expireMs() > System.currentTimeMillis()) {
                KuGouLogger.info("[NetMusicListCompat] parse URL cache HIT hash={} (0ms)", hash);
                return URI.create(cached.url()).toURL();
            }

            KuGouApiClient.Song song = SEARCH_CACHE.get(hash);
            if (song == null) {
                // 播放时缓存未命中：尝试从酷狗 getdata 接口补全元数据，提高 getSongUrl 成功率
                song = fetchSongMetaByHash(hash);
                if (song != null) {
                    SEARCH_CACHE.put(hash, song);
                    prefetchLyricAndIcon(song);
                }
            }
            String albumId = song != null && song.albumId != null ? song.albumId : "";
            String url = KuGouApiClient.getSongUrl(hash, albumId)
                    .orTimeout(20, TimeUnit.SECONDS).join();
            if (url == null || url.isEmpty()) {
                KuGouLogger.warn("[NetMusicListCompat] getSongUrl returned empty for hash={}", hash);
                return null;
            }
            URL u = URI.create(url).toURL();
            URL_CACHE.put(hash, new UrlCache(url, System.currentTimeMillis() + URL_TTL_MS));
            return u;
        } catch (Throwable t) {
            KuGouLogger.warn("[NetMusicListCompat] parse failed", t);
            return null;
        }
    }

    // ====================== 异步预取（搜索阶段，不阻塞搜索返回）======================
    private static void prefetchLyricAndIcon(KuGouApiClient.Song s) {
        final String hash = s.hash;
        final String songName = s.name != null ? s.name : hash;
        final String singer = s.singer != null ? s.singer : "";
        final String keyword = singer + " - " + songName;
        final int durationMs = s.duration * 1000;

        KuGouApiClient.searchLyricCandidates(hash, keyword, durationMs, songName, singer)
                .thenCompose(list -> KuGouApiClient.getLyricWithFallback(list, "krc"))
                .thenAccept(content -> {
                    if (content != null && content.lyricContent != null && !content.lyricContent.isEmpty()) {
                        // 保留原始 LRC 文本，供 parseMusic 写入 BurnDataCache -> CD NBT，
                        // 方块 CD 歌词由 SetPlayMixin 从 CD NBT 读出后写入 KuGouDisplayCompat
                        RAW_LRC_CACHE.put(hash, new String[]{content.lyricContent, content.languageJson});
                        LrcConverter.KuGouLyricData data = LrcConverter.toLyricData(
                                content.lyricContent, content.languageJson, songName);
                        if (data != null && data.record != null) {
                            LYRIC_CACHE.put(hash, data.record);
                        }
                    }
                }).exceptionally(e -> null);

        prefetchIcon(s);
    }

    /**
     * 同步拉取原始 LRC 文本（刻录时兜底，阻塞可接受）。
     * 返回 [lrc, lrcTrans]，失败返回 null。
     */
    private static String[] fetchRawLyricSync(String hash, String songName, String singer, int durationSec) {
        try {
            String keyword = (singer != null ? singer : "") + " - " + (songName != null ? songName : "");
            int durationMs = durationSec * 1000;
            var candidates = KuGouApiClient.searchLyricCandidates(hash, keyword, durationMs, songName, singer)
                    .orTimeout(8, TimeUnit.SECONDS).join();
            var content = KuGouApiClient.getLyricWithFallback(candidates, "krc")
                    .orTimeout(8, TimeUnit.SECONDS).join();
            if (content != null && content.lyricContent != null && !content.lyricContent.isEmpty()) {
                String[] raw = new String[]{content.lyricContent, content.languageJson};
                RAW_LRC_CACHE.put(hash, raw);
                // 顺带填 LYRIC_CACHE，避免后续 parseLyric 再拉一次
                LrcConverter.KuGouLyricData data = LrcConverter.toLyricData(
                        content.lyricContent, content.languageJson, songName);
                if (data != null && data.record != null) {
                    LYRIC_CACHE.put(hash, data.record);
                }
                return raw;
            }
        } catch (Throwable t) {
            KuGouLogger.warn("[NetMusicListCompat] fetchRawLyricSync failed: {}", t.getMessage());
        }
        return null;
    }

    /**
     * 用「歌词搜索」接口反查歌曲元数据（歌名 / 歌手 / 时长）。
     * <p>
     * 为什么需要它：酷狗 {@code play/getdata}（{@link #fetchSongMetaByHash}）已普遍失效，
     * 对每个 hash 都返回 HTTP 200 且带 data 字段、但内容全空的响应，于是
     * {@code name} 退化为 hash、{@code duration=0}、{@code albumId}/{@code image} 为空。
     * 只要上游拿到 {@code songTime=0}，音乐机就会 {@code setCurrentTime(0*20+64)=64}
     * ——歌曲被当成 3 秒长，{@code currentTime} 掉到 16 以下立刻重新触发
     * {@code setPlayToClient}，表现就是"翻牌板歌词永远停在开头""重放三次回不正"。
     * <p>
     * 歌词搜索接口实测仍然可用并会返回真实 {@code durationMs}，因此用它兜底补全时长。
     *
     * @return 带正时长的 Song；反查失败返回 null
     */
    private static KuGouApiClient.Song recoverSongMetaByLyricSearch(String hash) {
        try {
            var cands = KuGouApiClient.searchLyricCandidates(hash, "", 0, "", "")
                    .orTimeout(10, TimeUnit.SECONDS).join();
            if (cands == null || cands.isEmpty()) {
                KuGouLogger.warn("[NetMusicListCompat] recover meta: no lyric candidate for hash={}", hash);
                return null;
            }
            KuGouApiClient.LyricCandidate best = cands.get(0);
            if (best == null || best.duration <= 0) {
                KuGouLogger.warn("[NetMusicListCompat] recover meta: candidate has no duration for hash={}", hash);
                return null;
            }
            int durSec = Math.round(best.duration / 1000.0f);
            String name = (best.songName != null && !best.songName.isEmpty()) ? best.songName : hash;
            String singer = (best.singer != null) ? best.singer : "";
            KuGouLogger.info(
                    "[NetMusicListCompat] recovered meta via lyric search: hash={}, name={}, singer={}, duration={}s",
                    hash, name, singer, durSec);
            return new KuGouApiClient.Song(hash, name, singer, "", hash, "", durSec, "");
        } catch (Throwable t) {
            KuGouLogger.warn("[NetMusicListCompat] recoverSongMetaByLyricSearch failed: {}", t.getMessage());
        }
        return null;
    }

    private static void prefetchIcon(KuGouApiClient.Song s) {
        CompletableFuture.supplyAsync(() -> loadCoverBytes(s)).thenAccept(bytes -> {
            if (bytes != null) cacheCoverBytes(s.hash, bytes);
        }).exceptionally(e -> null);
    }

    /**
     * 用 hash 直接搜酷狗，拿到带 albumId + image 的 Song 用于封面。
     * 这是「搜索缓存未命中」时（旧碟 / 刻录前预览）获取封面的兜底路径：
     * getdata 不含封面信息，只有搜索结果里才有 albumId + image。
     * 优先精确匹配 FileHash，避免拿到错误封面。
     */
    private static KuGouApiClient.Song searchCoverSongByHash(String hash) {
        try {
            List<KuGouApiClient.Song> songs = KuGouApiClient.search(hash, 1, 10)
                    .orTimeout(10, TimeUnit.SECONDS).join();
            if (songs == null || songs.isEmpty()) {
                KuGouLogger.warn("[NetMusicListCompat] searchCoverSongByHash empty for {}", hash);
                return null;
            }
            for (KuGouApiClient.Song s : songs) {
                if (s.hash != null && s.hash.equalsIgnoreCase(hash)) {
                    KuGouLogger.info("[NetMusicListCompat] searchCoverSongByHash exact match hash={}", hash);
                    return s;
                }
            }
            // 无精确匹配：退而取第一个带 albumId+image 的结果（封面通常是专辑图，错误概率低）
            for (KuGouApiClient.Song s : songs) {
                if (s.albumId != null && !s.albumId.isEmpty() && s.image != null && !s.image.isEmpty()) {
                    KuGouLogger.warn("[NetMusicListCompat] searchCoverSongByHash no exact match, use first usable hash={}", hash);
                    return s;
                }
            }
            KuGouLogger.warn("[NetMusicListCompat] searchCoverSongByHash no usable cover meta for {}", hash);
            return null;
        } catch (Throwable t) {
            KuGouLogger.warn("[NetMusicListCompat] searchCoverSongByHash failed: {}", t.getMessage());
            return null;
        }
    }

    /**
     * 按候选源逐个尝试下载封面，返回第一个成功解析出的图片。
     * <p>
     * 老接口 {@code www.kugou.com/yy/index.php?r=play/getdata} 现已普遍返回空 img，
     * 所以优先用搜索响应里的 Image 字段 / albumId 直接拼 CDN 地址。
     */
    private static byte[] loadCoverBytes(KuGouApiClient.Song s) {
        if (s == null) return null;
        KuGouLogger.info("[NetMusicListCompat] cover meta: hash={}, albumId='{}', image='{}'",
                s.hash, s.albumId, s.image);
        // parseIcon 会被 HUD 同步调用，串行试多个源时必须控制总耗时上限
        final long deadline = System.currentTimeMillis() + 12_000;
        for (String url : coverUrlCandidates(s)) {
            long leftMs = deadline - System.currentTimeMillis();
            if (leftMs <= 0) break;
            int timeoutSec = (int) Math.min(8, Math.max(1, leftMs / 1000));
            byte[] bytes = downloadImage(url, timeoutSec);
            if (bytes != null) {
                KuGouLogger.info("[NetMusicListCompat] cover ok for hash={} via {}", s.hash, url);
                return bytes;
            }
        }
        KuGouLogger.warn("[NetMusicListCompat] no cover source worked for hash={}", s.hash);
        return null;
    }

    /**
     * 生成封面 URL 候选列表（按可靠性排序）。
     */
    private static List<String> coverUrlCandidates(KuGouApiClient.Song s) {
        List<String> urls = new ArrayList<>();
        String albumId = s.albumId != null ? s.albumId : "";
        String image = s.image;

        // 1) 搜索响应自带的封面 URL（trans_param.union_cover / Image）。
        //    对齐 EchoMusic 的 formatPic：值本身已是完整 URL，只替换 {size}、补协议，不拼任何 CDN 前缀。
        if (image != null && !image.isEmpty()) {
            String v = formatPic(image);
            if (v.startsWith("https://")) urls.add(v);
        }

        // 2) 用 albumId 兜底直拼酷狗 CDN（实测 imge / imgessl 均可用）
        if (!albumId.isEmpty()) {
            urls.add("https://imge.kugou.com/stdmusic/400/" + albumId + ".jpg");
            urls.add("https://imgessl.kugou.com/stdmusic/400/" + albumId + ".jpg");
        }
        return urls;
    }

    /**
     * 对齐 EchoMusic 的 {@code formatPic}：替换 {@code {size}} 占位符、补全协议，不拼 CDN 前缀。
     */
    private static String formatPic(String value) {
        if (value == null || value.isEmpty()) return "";
        String pic = value.replace("{size}", "400");
        if (pic.startsWith("//")) {
            pic = "https:" + pic;
        } else if (pic.startsWith("http://")) {
            pic = "https://" + pic.substring(7);
        }
        return pic;
    }

    private static Map<String, String> kugouHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36");
        headers.put("Referer", "https://www.kugou.com/");
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        StringBuilder cookie = new StringBuilder();
        if (KuGouConfig.mid != null && !KuGouConfig.mid.isEmpty()) cookie.append("kg_mid=").append(KuGouConfig.mid).append("; ");
        if (KuGouConfig.dfid != null && !KuGouConfig.dfid.isEmpty()) cookie.append("dfid=").append(KuGouConfig.dfid).append("; ");
        if (KuGouConfig.guid != null && !KuGouConfig.guid.isEmpty()) cookie.append("guid=").append(KuGouConfig.guid).append("; ");
        if (cookie.length() > 0) headers.put("Cookie", cookie.substring(0, cookie.length() - 2));
        return headers;
    }

    private static byte[] downloadImage(String url) {
        return downloadImage(url, 8);
    }

    private static byte[] downloadImage(String url, int timeoutSec) {
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(Math.max(1, timeoutSec))).GET();
            for (Map.Entry<String, String> e : kugouHeaders().entrySet()) rb.header(e.getKey(), e.getValue());
            HttpRequest req = rb.build();
            HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                KuGouLogger.warn("[NetMusicListCompat] cover http status={} url={}", resp.statusCode(), url);
                return null;
            }
            byte[] body = resp.body();
            if (body == null || body.length == 0) return null;
            // 酷狗 CDN 在封面缺失时会返回 HTML/占位内容，先做魔数校验避免误用
            if (!looksLikeImage(body)) {
                KuGouLogger.warn("[NetMusicListCompat] cover not an image (len={}): {}", body.length, url);
                return null;
            }
            return body;
        } catch (Throwable t) {
            KuGouLogger.warn("[NetMusicListCompat] downloadImage failed: {} url={}", t.getMessage(), url);
        }
        return null;
    }

    /**
     * 按魔数判断字节流是否为真实图片，过滤 CDN 返回的 HTML 错误页/占位文本。
     */
    private static boolean looksLikeImage(byte[] b) {
        if (b == null || b.length < 12) return false;
        int b0 = b[0] & 0xFF, b1 = b[1] & 0xFF, b2 = b[2] & 0xFF, b3 = b[3] & 0xFF;
        if (b0 == 0xFF && b1 == 0xD8) return true;                                        // JPEG
        if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) return true;            // PNG
        if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46) return true;                          // GIF
        if (b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46) return true;            // RIFF/WebP
        return false;
    }

    /**
     * 通过酷狗网页 play/getdata 接口按 hash 反查歌曲元数据。
     * <p>
     * 用于玩家直接输入 hash、刻录时搜索缓存未命中、或播放时缓存丢失的场景，
     * 避免构造出 duration=0 的 SongInfo 导致 netMusicList 无法播放。
     */
    private static KuGouApiClient.Song fetchSongMetaByHash(String hash) {
        if (hash == null || hash.isEmpty()) return null;
        try {
            Map<String, String> headers = kugouHeaders();
            Map<String, Object> params = new HashMap<>();
            params.put("r", "play/getdata");
            params.put("hash", hash);
            HttpUtils.HttpResponse resp = HttpUtils.get("https://www.kugou.com/yy/index.php", headers, params);
            if (!resp.isOk() || resp.body == null || resp.body.isEmpty()) {
                KuGouLogger.warn("[NetMusicListCompat] fetchSongMetaByHash HTTP {}: {}", resp.statusCode, resp.body);
                return null;
            }
            JsonObject root = resp.asJson().getAsJsonObject();
            if (!root.has("data") || !root.get("data").isJsonObject()) {
                KuGouLogger.warn("[NetMusicListCompat] fetchSongMetaByHash no data field");
                return null;
            }
            JsonObject data = root.getAsJsonObject("data");
            String name = firstNonEmpty(
                    getJsonStr(data, "song_name"),
                    getJsonStr(data, "audio_name"),
                    getJsonStr(data, "songname"),
                    getJsonStr(data, "fileName"));
            String singer = firstNonEmpty(
                    getJsonStr(data, "singer_name"),
                    getJsonStr(data, "author_name"),
                    getJsonStr(data, "singername"));
            String album = getJsonStr(data, "album_name");
            String albumId = firstNonEmpty(
                    getJsonStr(data, "album_id"),
                    getJsonStr(data, "albumid"));
            String returnedHash = firstNonEmpty(
                    getJsonStr(data, "hash"),
                    getJsonStr(data, "file_hash"),
                    getJsonStr(data, "Hash"),
                    hash);
            int durationSec = parseDurationSeconds(data);
            String coverUrl = firstNonEmpty(
                    getJsonStr(data, "img"),
                    getJsonStr(data, "photo"),
                    getJsonStr(data, "album_img"),
                    getJsonStr(data, "album_sizable_cover"));
            if (coverUrl != null && !coverUrl.isEmpty()) {
                // 封面 URL 已拿到，交给异步预取流程下载缓存（内存+磁盘）
                prefetchIcon(new KuGouApiClient.Song(returnedHash, name, singer, album, returnedHash, albumId, durationSec, coverUrl));
            }
            if (name == null || name.isEmpty()) name = returnedHash;
            if (singer == null) singer = "";
            if (album == null) album = "";
            if (albumId == null) albumId = "";
            KuGouLogger.info("[NetMusicListCompat] fetched meta for hash={}, name={}, duration={}s",
                    returnedHash, name, durationSec);
            return new KuGouApiClient.Song(returnedHash, name, singer, album, returnedHash, albumId, durationSec, coverUrl);
        } catch (Throwable t) {
            KuGouLogger.warn("[NetMusicListCompat] fetchSongMetaByHash failed: {}", t.getMessage());
        }
        return null;
    }

    private static int parseDurationSeconds(JsonObject data) {
        try {
            JsonElement e = data.has("duration") ? data.get("duration") : null;
            if (e == null || e.isJsonNull()) e = data.has("timelength") ? data.get("timelength") : null;
            if (e == null || e.isJsonNull()) e = data.has("time_len") ? data.get("time_len") : null;
            if (e == null || e.isJsonNull()) e = data.has("timelengthms") ? data.get("timelengthms") : null;
            if (e == null || e.isJsonNull()) return 0;
            if (!e.isJsonPrimitive()) return 0;
            String s = e.getAsString();
            if (s.contains(".")) {
                double d = Double.parseDouble(s);
                return d > 999 ? (int) (d / 1000.0) : (int) d;
            }
            long v = Long.parseLong(s);
            return v > 999 ? (int) (v / 1000) : (int) v;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static String getJsonStr(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) return null;
        JsonElement e = obj.get(key);
        if (e == null || e.isJsonNull()) return null;
        if (!e.isJsonPrimitive()) return null;
        String s = e.getAsString();
        return s.isEmpty() ? null : s;
    }

    private static String firstNonEmpty(String... candidates) {
        for (String s : candidates) {
            if (s != null && !s.isEmpty()) return s;
        }
        return null;
    }

    private NetMusicListCompat() {
    }
}

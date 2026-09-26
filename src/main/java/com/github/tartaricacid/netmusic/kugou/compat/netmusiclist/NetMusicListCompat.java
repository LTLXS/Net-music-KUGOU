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
 酷狗音乐源对 netMusicListNeoForge 的兼容层（纯反射 + 动态代理，编译期零依赖）。
 netMusicList 暴露两个扩展点：
 - IExtraMusicSource —— 源注册、搜索、元数据、歌词、封面、剪贴板识别
 - IMusicParser —— 把 netmusiclib:// URI 实时解析成酷狗直链
 运行时通过 ModList 探测 net_music_list 是否加载，加载时注册酷狗源，
 否则本类完全不介入，needkugou 走原有逻辑。
*/
public final class NetMusicListCompat {
    public static final String MOD_ID = "net_music_list";
    private static final String TYPE = "kugou";

    private static final String C_I_EXTRA = "com.gly091020.netMusicListNeoforge.api.musicSource.IExtraMusicSource";
    private static final String C_I_PARSER = "com.gly091020.netMusicListNeoforge.api.musicParser.IMusicParser";
    private static final String C_EXTRA_MGR = "com.gly091020.netMusicListNeoforge.api.musicSource.ExtraMusicSourceManager";
    private static final String C_PARSER_MGR = "com.gly091020.netMusicListNeoforge.api.musicParser.MusicParserManager";

    private static final Map<String, KuGouApiClient.Song> SEARCH_CACHE = new ConcurrentHashMap<>();
    // 歌词缓存：hash -> LyricRecord（搜索/刻录阶段预填，避免播放时阻塞）
    private static final Map<String, LyricRecord> LYRIC_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, String[]> RAW_LRC_CACHE = new ConcurrentHashMap<>();
    // 封面缓存：hash -> 原始 PNG 字节（搜索阶段异步预取，best-effort）。
    // 注意：必须缓存【字节】而非 NativeImage 实例——netMusicList 会把 parseIcon 返回的
    // 若复用同一个实例，第二次调用会拿到已释放的野对象导致封面空白（“放一次后不再加载”）。
    private static final Map<String, byte[]> ICON_CACHE_BYTES = new ConcurrentHashMap<>();

    private static final long URL_TTL_MS = 5L * 60 * 1000;
    private record UrlCache(String url, long expireMs) {}
    private static final Map<String, UrlCache> URL_CACHE = new ConcurrentHashMap<>();

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
 大栈线程池：stb_image（NativeImage.read 底层）在栈上 alloca 解码缓冲，
 netMusicList 的 CD-Preview-Icon 等线程栈很小，会抛 "Out of stack space"。
 这里把解码放到 1MB 栈的线程上执行，规避该问题。
*/
    private static final ExecutorService ICON_DECODE_EXEC = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(null, r, "kugou-icon-decode", 1024L * 1024);
        t.setDaemon(true);
        return t;
    });

    /**
 封面并行预热线程池：netMusicList 的封面抓取是单线程串行的，若 parseIcon 在其中阻塞等待下载，
 多张 CD 的封面会变成 N×慢。这里把“按 hash 反查元数据 + 下载封面字节”放到独立线程池并行执行，
 parseIcon 仅负责命中缓存或入队，从而让多张 CD 的封面得以并行下载。
*/
    private static final ExecutorService COVER_PREFETCH_EXEC = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(null, r, "kugou-cover-prefetch", 256L * 1024);
        t.setDaemon(true);
        return t;
    });
    private static final Set<String> PREFETCHING = ConcurrentHashMap.newKeySet();

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

    /**
 刻录界面输入框可能因 setMaxLength 把 32 位 hash 截断成更短的前缀。
 若传入 hash 偏短，尝试用前缀从搜索缓存里找回完整的 32 位 hash。
*/
    private static String resolveFullHash(String hash) {
        if (hash == null) return null;
        if (hash.length() >= 30) return hash;
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
        // （name=hash、duration=0、albumId/image 全空）。若直接用它构造 SongInfo，
        // 于是每 3 秒自动重新触发播放 → 翻牌板歌词永远停在开头、重放也回不正。
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

        // 与下面同步流程解耦：仅 fire-and-forget，失败不影响本次刻录结果。
        final String warmHash = hash;
        final String warmAlbum = albumId;
        final long warmDuration = duration;
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
            // 刻录阶段后台触发歌曲 mp3 预缓存：播放时直接读本地文件，无需联网流式拉取，
            // 对慢网络用户是「秒播」的关键。仅当本地缓存文件缺失才下载，且尊重 netMusicList 的 enableCache 开关。
            triggerNetMusicListSongCache(warmHash, warmDuration);
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

    /**
 触发 netMusicList 把该酷狗歌曲的 mp3 预下载并落盘缓存（缓存键为 kugou:，
 与播放时 PlayMusicHandleMixin 的查找键一致）。
 这样刻录后播放直接读本地文件、无需联网流式拉取——对慢网络用户是「秒播」的关键优化。
 仅当本地缓存文件确实缺失时才发起下载（幂等，重复刻录不会重复下），且完全尊重 netMusicList 的
 enableCache 配置（其内部会直接 return）。
 通过反射调用 netMusicList 的 CacheManager，保持编译期零依赖。
*/
    private static void triggerNetMusicListSongCache(String hash, long duration) {
        if (hash == null || hash.isEmpty() || duration <= 0) return;
        try {
            Class<?> msCls = Class.forName("com.gly091020.netMusicListNeoforge.api.musicSource.MusicSource");
            Class<?> cmCls = Class.forName("com.gly091020.netMusicListNeoforge.util.CacheManager");
            // 构造 MusicSource("kugou", hash, duration)
            Object source = msCls.getConstructor(String.class, String.class, long.class)
                    .newInstance(TYPE, hash, duration);
            // 本地缓存文件已存在则跳过（getSongCache 在文件缺失时返回 null）
            Object existing = cmCls.getMethod("getSongCache", msCls).invoke(null, source);
            if (existing != null) {
                KuGouLogger.info("[NetMusicListCompat] song cache file exists, skip hash={}", hash);
                return;
            }
            cmCls.getMethod("startSongDownload", msCls, String.class)
                    .invoke(null, source, java.util.UUID.randomUUID().toString());
            KuGouLogger.info("[NetMusicListCompat] triggered netMusicList song cache download hash={}", hash);
        } catch (Throwable t) {
            KuGouLogger.warn("[NetMusicListCompat] triggerNetMusicListSongCache failed hash={}: {}",
                    hash, t.getMessage());
        }
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
 解码 PNG/JPG 字节为 NativeImage。
 不用 NativeImage.read（底层 stb_image 在调用线程栈上 alloca 解码缓冲），
 netMusicList 的 CD-Preview-Icon 等线程栈很小会抛 "Out of stack space"，
 且 stb 对部分封面所需栈>1MB，靠加大栈不可靠。这里改用 JDK ImageIO（堆内存解码），
 再逐像素写入 NativeImage，彻底与线程栈大小无关。
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
        // 优先命中内存/磁盘缓存（刻录阶段已预取或历史已落盘），命中即秒出，完全不走网络。
        byte[] cached = ICON_CACHE_BYTES.get(hash);
        if (cached == null) cached = loadCoverFromDisk(hash);
        if (cached != null) {
            ICON_CACHE_BYTES.putIfAbsent(hash, cached);
            return decodeAndReturn(hash, cached);
        }
        // 缓存未命中：交给并行预热线程池去下载，本次先返回 null。
        // netMusicList 的封面抓取是单线程串行的，若在这里阻塞等待会让多张 CD 的封面变成 N×慢；
        // 改为非阻塞入队后，串行循环会把所有可见 CD 的 hash 迅速全部入队，由线程池并行下载，
        // 待磁盘缓存写好，下一次抓取周期重试即命中出图。
        enqueueCoverPrefetch(hash);
        return null;
    }

    /**
 把封面下载任务丢进并行线程池（去重，避免重复入队）。任务内按 hash 反查元数据并下载封面字节写入缓存。
*/
    private static void enqueueCoverPrefetch(String hash) {
        if (!PREFETCHING.add(hash)) return;
        COVER_PREFETCH_EXEC.submit(() -> {
            try {
                KuGouApiClient.Song song = SEARCH_CACHE.get(hash);
                // 即便 SEARCH_CACHE 命中，只要缺 albumId/image（封面关键字段），也用移动端 getSongInfo 重新反查——
                // 这些字段才是拼封面 CDN 直链的凭证，旧缓存常为 null 导致"放一次后封面消失/永远拿不到"。
                if (song == null || song.albumId == null || song.albumId.isEmpty()
                        || song.image == null || song.image.isEmpty()) {
                    KuGouApiClient.Song fresh = fetchSongMetaByHash(hash);
                    if (fresh != null && (!fresh.albumId.isEmpty()
                            || (fresh.image != null && !fresh.image.isEmpty()))) {
                        song = fresh;
                    }
                }
                // 仍为 null / 仍缺封面字段：再退一步用 hash 关键词搜酷狗（极小概率命中）
                if (song == null || song.albumId == null || song.albumId.isEmpty()
                        || song.image == null || song.image.isEmpty()) {
                    song = searchCoverSongByHash(hash);
                }
                if (song != null) {
                    byte[] cover = loadCoverBytes(song);
                    if (cover != null) {
                        cacheCoverBytes(hash, cover);
                        KuGouLogger.info("[NetMusicListCompat] prefetch cached hash={}", hash);
                    } else {
                        KuGouLogger.warn("[NetMusicListCompat] prefetch cover null hash={}", hash);
                    }
                }
            } catch (Throwable t) {
                KuGouLogger.warn("[NetMusicListCompat] prefetch failed hash={}: {}", hash, t.getMessage());
            } finally {
                PREFETCHING.remove(hash);
            }
        });
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

            UrlCache cached = URL_CACHE.get(hash);
            if (cached != null && cached.expireMs() > System.currentTimeMillis()) {
                KuGouLogger.info("[NetMusicListCompat] parse URL cache HIT hash={} (0ms)", hash);
                return URI.create(cached.url()).toURL();
            }

            KuGouApiClient.Song song = SEARCH_CACHE.get(hash);
            if (song == null) {
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
 同步拉取原始 LRC 文本（刻录时兜底，阻塞可接受）。
 返回 [lrc, lrcTrans]，失败返回 null。
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
 用「歌词搜索」接口反查歌曲元数据（歌名 / 歌手 / 时长）。
 play/getdata（fetchSongMetaByHash）已普遍返回内容为空的响应，导致
 name 退化为 hash、duration=0；songTime=0 时歌曲被当作 3 秒长，
 currentTime 掉到 16 以下会立刻重新触发 setPlayToClient（歌词停在开头、重放不归零）。
 歌词搜索接口仍返回真实 durationMs，用它兜底补全时长。

 @return 带正时长的 Song；反查失败返回 null*/
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
 用 hash 反查歌名后再搜酷狗，拿到带 albumId + image 的 Song 用于封面。
 旧逻辑直接用 hash 当搜索关键词，但酷狗搜索按 hash 文本查必然 0 结果
 （日志表现为 total:0, lists:[]），导致"只有 hash、没有 albumId/image"的 CD
 一旦缓存失效就永远拿不到封面。
 新逻辑：先按 hash 找回歌名/歌手（歌词搜索接口优先，getdata 兜底），再用「歌名」
 去搜，从结果取首个带 albumId+image 的 Song；仍先用 hash 当关键词试一次，
 命中精确匹配则优先。
*/
    /**
 用 hash 直接搜酷狗，作为最后的兜底（极小概率命中，因 hash 不是可搜关键词）。
 主封面来源是 fetchSongMetaByHash 的移动端接口（返回 album_id + album_img）。
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
                if (s.hash != null && s.hash.equalsIgnoreCase(hash)
                        && s.albumId != null && !s.albumId.isEmpty() && s.image != null && !s.image.isEmpty()) {
                    KuGouLogger.info("[NetMusicListCompat] searchCoverSongByHash exact match hash={}", hash);
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
 按候选源逐个尝试下载封面，返回第一个成功解析出的图片。
 老接口 www.kugou.com/yy/index.php?r=play/getdata 现已普遍返回空 img，
 所以优先用搜索响应里的 Image 字段 / albumId 直接拼 CDN 地址。
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
            int timeoutSec = (int) Math.min(4, Math.max(1, leftMs / 1000));
            byte[] bytes = downloadImage(url, timeoutSec);
            if (bytes != null) {
                KuGouLogger.info("[NetMusicListCompat] cover ok for hash={} via {}", s.hash, url);
                return bytes;
            }
        }
        KuGouLogger.warn("[NetMusicListCompat] no cover source worked for hash={}", s.hash);
        return null;
    }

    private static List<String> coverUrlCandidates(KuGouApiClient.Song s) {
        List<String> urls = new ArrayList<>();
        String albumId = s.albumId != null ? s.albumId : "";
        String image = s.image;

        // 值本身已是完整 URL，只替换 {size}、补协议，不拼任何 CDN 前缀。
        if (image != null && !image.isEmpty()) {
            String v = formatPic(image);
            if (v.startsWith("https://")) urls.add(v);
        }

        // 2) 用 albumId 兜底直拼酷狗 CDN（实测 imge / imgessl 均可用）
        // albumId 为 0 / null 时 CDN 返回默认灰图，跳过以免封面错配
        if (!albumId.isEmpty() && !"0".equals(albumId) && !"null".equalsIgnoreCase(albumId)) {
            urls.add("https://imge.kugou.com/stdmusic/400/" + albumId + ".jpg");
            urls.add("https://imgessl.kugou.com/stdmusic/400/" + albumId + ".jpg");
        }
        return urls;
    }

    /**
 替换 {size} 占位符、补全协议，不拼 CDN 前缀。
*/
    private static String formatPic(String value) {
        if (value == null || value.isEmpty()) return "";
        String pic = value.replace("{size}", "400");
        if (pic.startsWith("//")) {
            pic = "https:" + pic;
        } else if (pic.startsWith("http://")) {
            pic = "https://" + pic.substring(7);
        }
        // 旧图床 c1.kgimg.com 常返回占位/错图，统一归一到有效 CDN
        pic = pic.replace("c1.kgimg.com", "imge.kugou.com");
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
 按魔数判断字节流是否为真实图片，过滤 CDN 返回的 HTML 错误页/占位文本。
*/
    private static boolean looksLikeImage(byte[] b) {
        if (b == null || b.length < 12) return false;
        int b0 = b[0] & 0xFF, b1 = b[1] & 0xFF, b2 = b[2] & 0xFF, b3 = b[3] & 0xFF;
        if (b0 == 0xFF && b1 == 0xD8) return true;
        if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) return true;
        if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46) return true;
        if (b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46) return true;
        return false;
    }

    /**
 通过酷狗网页 play/getdata 接口按 hash 反查歌曲元数据。
 用于玩家直接输入 hash、刻录时搜索缓存未命中、或播放时缓存丢失的场景，
 避免构造出 duration=0 的 SongInfo 导致 netMusicList 无法播放。
*/
    /**
 通过酷狗接口按 hash 反查歌曲元数据（歌名/歌手/时长/albumId/封面）。
 优先用移动端 getSongInfo.php?cmd=playInfo&hash= 接口——它对部分 hash 返回空
 data 的网页 getdata（status:0, err_code:20010）更可靠，且直接给出 album_id + album_img
 （拼封面 CDN 直链的关键字段）。网页 getdata 作为互补（个别情况下 album_id/img 更全）。
*/
    private static KuGouApiClient.Song fetchSongMetaByHash(String hash) {
        if (hash == null || hash.isEmpty()) return null;
        // 移动端 getSongInfo 可靠且直接给 album_id + album_img，优先采用。
        // 老 web(play/getdata) 接口会卡读超时且已普遍不返回封面，故仅作互补兜底，
        // 绝不在常规路径里串行先发——这正是之前「刻录/取封面要等十几秒」的根因。
        KuGouApiClient.Song mob = fetchSongMetaByHashMobile(hash);
        if (mob != null && metaUsable(mob)) return mob;
        KuGouApiClient.Song web = fetchSongMetaByHashWeb(hash);
        if (web != null && metaUsable(web)) return web;
        // 字段互补：一端有歌名一端有 albumId/image
        if (web != null && mob != null) {
            String name = firstNonEmpty(mob.name, web.name);
            String singer = firstNonEmpty(mob.singer, web.singer);
            String album = firstNonEmpty(mob.album, web.album);
            String albumId = firstNonEmpty(mob.albumId, web.albumId);
            String image = firstNonEmpty(mob.image, web.image);
            int dur = mob.duration > 0 ? mob.duration : web.duration;
            return new KuGouApiClient.Song(hash, name, singer, album, hash, albumId, dur, image);
        }
        return mob != null ? mob : web;
    }

    /** 仅当拿到了真实歌名（非 hash 本身）且至少有 albumId 或 image 时，才算"可用封面元数据"。 */
    private static boolean metaUsable(KuGouApiClient.Song s) {
        return s != null && !s.name.equalsIgnoreCase(s.hash)
                && ((s.albumId != null && !s.albumId.isEmpty())
                    || (s.image != null && !s.image.isEmpty()));
    }

    private static KuGouApiClient.Song fetchSongMetaByHashWeb(String hash) {
        try {
            Map<String, String> headers = kugouHeaders();
            Map<String, Object> params = new HashMap<>();
            params.put("r", "play/getdata");
            params.put("hash", hash);
            HttpUtils.HttpResponse resp = HttpUtils.get("https://www.kugou.com/yy/index.php", headers, params);
            if (!resp.isOk() || resp.body == null || resp.body.isEmpty()) {
                KuGouLogger.warn("[NetMusicListCompat] fetchSongMetaByHashWeb HTTP {}: {}", resp.statusCode, resp.body);
                return null;
            }
            JsonObject root = resp.asJson().getAsJsonObject();
            if (!root.has("data") || !root.get("data").isJsonObject()) {
                KuGouLogger.warn("[NetMusicListCompat] fetchSongMetaByHashWeb no data field");
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
            KuGouLogger.info("[NetMusicListCompat] fetched meta(web) for hash={}, name={}, duration={}s",
                    returnedHash, name, durationSec);
            return new KuGouApiClient.Song(returnedHash, name, singer, album, returnedHash, albumId, durationSec, coverUrl);
        } catch (Throwable t) {
            KuGouLogger.warn("[NetMusicListCompat] fetchSongMetaByHashWeb failed: {}", t.getMessage());
        }
        return null;
    }

    /**
 移动端 getSongInfo 接口：仅需 hash，返回 album_id + album_img（album_img / union_cover），
 对网页 getdata 返空（status:0, err_code:20010）的 hash 仍能解析出封面所需元数据。
*/
    private static KuGouApiClient.Song fetchSongMetaByHashMobile(String hash) {
        try {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
            Map<String, Object> params = new HashMap<>();
            HttpUtils.HttpResponse resp = HttpUtils.get(
                    "https://m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&hash=" + hash,
                    headers, params);
            if (!resp.isOk() || resp.body == null || resp.body.isEmpty()) {
                KuGouLogger.warn("[NetMusicListCompat] fetchSongMetaByHashMobile HTTP {}: {}", resp.statusCode, resp.body);
                return null;
            }
            JsonObject data = resp.asJson().getAsJsonObject();
            // 以 songName 是否存在作为有效性判据；移动端在部分有效响应里 status 为 0，不能据此判失败
            if (data == null || !data.has("songName") || data.get("songName").isJsonNull()) {
                KuGouLogger.warn("[NetMusicListCompat] fetchSongMetaByHashMobile no songName for {}", hash);
                return null;
            }
            String name = getJsonStr(data, "songName");
            String singer = firstNonEmpty(
                    getJsonStr(data, "singerName"),
                    getJsonStr(data, "author_name"),
                    getJsonStr(data, "choricSinger"));
            String album = getJsonStr(data, "album_name");
            String albumId = firstNonEmpty(
                    getJsonStr(data, "albumid"),
                    getJsonStr(data, "album_audio_id"),
                    getJsonStr(data, "req_albumid"));
            int durationSec = parseDurationSeconds(data);
            String coverUrl = firstNonEmpty(
                    getJsonStr(data, "album_img"),
                    getJsonStr(data, "union_cover"),
                    getJsonStr(data, "imgUrl"));
            if (name == null || name.isEmpty()) name = hash;
            if (singer == null) singer = "";
            if (albumId == null) albumId = "";
            KuGouLogger.info("[NetMusicListCompat] fetched meta(mobile) for hash={}, name={}, albumId='{}'",
                    hash, name, albumId);
            return new KuGouApiClient.Song(hash, name, singer, album, hash, albumId, durationSec, coverUrl);
        } catch (Throwable t) {
            KuGouLogger.warn("[NetMusicListCompat] fetchSongMetaByHashMobile failed: {}", t.getMessage());
        }
        return null;
    }

    private static int parseDurationSeconds(JsonObject data) {
        try {
            JsonElement e = data.has("duration") ? data.get("duration") : null;
            if (e == null || e.isJsonNull()) e = data.has("timelength") ? data.get("timelength") : null;
            if (e == null || e.isJsonNull()) e = data.has("time_len") ? data.get("time_len") : null;
            if (e == null || e.isJsonNull()) e = data.has("timelengthms") ? data.get("timelengthms") : null;
            if (e == null || e.isJsonNull()) e = data.has("timeLength") ? data.get("timeLength") : null;
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

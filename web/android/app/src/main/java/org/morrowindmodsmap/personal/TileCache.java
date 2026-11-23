package org.morrowindmodsmap.personal;

import android.app.Activity;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.widget.Toast;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.json.JSONObject;

final class TileCache {
    private static final String HOST = "https://tesmap.tesall.club";
    private static final String PREFIX = "/data/tiles/";
    private static final long MAX_CACHE = 5L * 1024L * 1024L * 1024L;
    private static final int MAX_TILE = 16 * 1024 * 1024;
    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final File root;
    private final String build;
    private final String expectedEtag;
    private final Object cacheLock = new Object();
    private final Object[] tileLocks = new Object[64];
    private long cacheBytes = -1;
    private boolean offlineShown;
    private boolean changedShown;

    TileCache(Activity activity) {
        this.activity = activity;
        for (int i = 0; i < tileLocks.length; i++) tileLocks[i] = new Object();
        root = new File(activity.getFilesDir(), "map-tiles");
        String readBuild = "";
        String readEtag = "";
        try (InputStream stream = activity.getAssets().open("public/data/snapshot.json")) {
            JSONObject json = new JSONObject(new String(readBytes(stream, 8192), StandardCharsets.UTF_8));
            readBuild = Long.toString(json.getLong("build"));
            readEtag = json.getString("etag");
        } catch (Exception ignored) {
        }
        build = readBuild;
        expectedEtag = readEtag;
        if (!build.isEmpty() && !expectedEtag.isEmpty()) {
            new Thread(() -> {
                try { verifySnapshot(); } catch (IOException ignored) { }
            }, "map-snapshot-check").start();
        }
    }

    boolean handles(WebResourceRequest request) {
        Uri uri = request.getUrl();
        return "GET".equals(request.getMethod()) && "https".equals(uri.getScheme())
            && "localhost".equals(uri.getHost()) && uri.getPath() != null
            && uri.getPath().startsWith(PREFIX);
    }

    WebResourceResponse load(WebResourceRequest request) {
        Uri uri = request.getUrl();
        String path = uri.getPath();
        if (!path.matches("^/data/tiles/[a-z0-9_-]+/[0-9]+/-?[0-9]+/-?[0-9]+\\.webp$")
                || build.isEmpty() || !build.equals(uri.getQueryParameter("v"))) return missing();
        String key = path.substring(PREFIX.length());
        Object tileLock = tileLocks[(key.hashCode() & Integer.MAX_VALUE) % tileLocks.length];
        synchronized (tileLock) {
            try {
                File target = new File(new File(root, build), key);
                synchronized (cacheLock) {
                    if (target.isFile()) {
                        target.setLastModified(System.currentTimeMillis());
                        return image(new FileInputStream(target));
                    }
                }
                if (!verifySnapshot()) return missing();
                byte[] bytes = download(HOST + path + "?v=" + build, MAX_TILE);
                if (bytes == null) return missing();
                if (bytes.length < 12 || bytes[0] != 'R' || bytes[1] != 'I' || bytes[2] != 'F' || bytes[3] != 'F'
                        || bytes[8] != 'W' || bytes[9] != 'E' || bytes[10] != 'B' || bytes[11] != 'P') return missing();
                synchronized (cacheLock) {
                    initializeSize();
                    File parent = target.getParentFile();
                    if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) return missing();
                    File tmp = new File(parent, "." + target.getName() + "." + UUID.randomUUID() + ".tmp");
                    try {
                        try (FileOutputStream output = new FileOutputStream(tmp)) { output.write(bytes); }
                        prune(bytes.length);
                        if (tmp.renameTo(target)) {
                            cacheBytes += target.length();
                            target.setLastModified(System.currentTimeMillis());
                        }
                    } finally {
                        if (tmp.exists()) tmp.delete();
                    }
                }
                synchronized (this) { offlineShown = false; }
                return image(new ByteArrayInputStream(bytes));
            } catch (IOException error) {
                notifyOffline();
                return missing();
            }
        }
    }

    private boolean verifySnapshot() throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(HOST + "/data/manifest.json").openConnection();
        try {
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Cache-Control", "no-cache");
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("Map manifest unavailable");
            String etag = connection.getHeaderField("ETag");
            if (expectedEtag.isEmpty() || etag == null || !expectedEtag.equals(etag)) {
                notifyChanged();
                return false;
            }
            synchronized (this) { changedShown = false; }
            return true;
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] download(String url, int limit) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(false);
            int status = connection.getResponseCode();
            if (status == 404) return null;
            if (status != 200) throw new IOException("Tile unavailable");
            try (InputStream input = connection.getInputStream()) { return readBytes(input, limit); }
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] readBytes(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int n;
        while ((n = input.read(buffer)) != -1) {
            if (output.size() + n > limit) throw new IOException("Response too large");
            output.write(buffer, 0, n);
        }
        return output.toByteArray();
    }

    private void initializeSize() {
        if (cacheBytes >= 0) return;
        cacheBytes = 0;
        List<File> files = new ArrayList<>();
        collect(root, files);
        for (File file : files) cacheBytes += file.length();
    }

    private static void collect(File dir, List<File> out) {
        File[] entries = dir.listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            if (entry.isDirectory()) collect(entry, out);
            else if (entry.isFile() && entry.getName().endsWith(".webp")) out.add(entry);
        }
    }

    private void prune(long incoming) {
        if (cacheBytes + incoming <= MAX_CACHE) return;
        List<File> files = new ArrayList<>();
        collect(root, files);
        files.sort(Comparator.comparingLong(File::lastModified));
        for (File file : files) {
            long size = file.length();
            if (file.delete()) cacheBytes -= size;
            if (cacheBytes + incoming <= MAX_CACHE) break;
        }
    }

    private synchronized void notifyOffline() {
        if (offlineShown) return;
        offlineShown = true;
        handler.post(() -> Toast.makeText(activity, R.string.map_offline, Toast.LENGTH_LONG).show());
    }

    private synchronized void notifyChanged() {
        if (changedShown) return;
        changedShown = true;
        handler.post(() -> Toast.makeText(activity, R.string.map_updated, Toast.LENGTH_LONG).show());
    }

    private static WebResourceResponse image(InputStream data) {
        return new WebResourceResponse("image/webp", null, 200, "OK", Collections.singletonMap("Cache-Control", "no-store"), data);
    }

    private static WebResourceResponse missing() {
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", Collections.singletonMap("Cache-Control", "no-store"), new ByteArrayInputStream(new byte[0]));
    }
}

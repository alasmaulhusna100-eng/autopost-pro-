package com.autopostpro.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;
import java.util.regex.*;

import okhttp3.*;

public class SmartEngine {

    static final String TAG = "SmartEngine";
    static final String API = "https://graph.facebook.com/v18.0";

    // ===== DATA MODEL =====
    public static class VideoItem {
        public int serial;
        public String uri, fileName, title, desc, time, status, postId, error;
        public VideoItem() { status = "PENDING"; }
    }

    public static class TitleDesc {
        public String title, desc, raw;
        public TitleDesc(String t, String d, String r) {
            title = t; desc = d; raw = r;
        }
    }

    public static class MatchResult {
        public int serial;
        public String uri, fileName, title, desc, method;
    }

    // ===== NUMBER EXTRACTION =====
    static int extractNum(String text) {
        if (text == null || text.isEmpty()) return -1;
        // Bangla digits
        Matcher bm = Pattern.compile("([০-৯]+)").matcher(text);
        if (bm.find()) {
            StringBuilder sb = new StringBuilder();
            for (char c : bm.group(1).toCharArray()) {
                int d = c - '০';
                if (d >= 0 && d <= 9) sb.append(d);
            }
            try { return Integer.parseInt(sb.toString()); } catch (Exception e) {}
        }
        // English patterns
        Pattern[] ps = {
            Pattern.compile("(?i)(?:episode|ep|part)\\s*(\\d+)"),
            Pattern.compile("(?i)S\\d+E(\\d+)"),
            Pattern.compile("^\\s*(\\d+)[.)\\-:]\\s"),
            Pattern.compile("^\\s*(\\d+)\\s*[_\\-]"),
            Pattern.compile("[_\\-\\s](\\d+)\\s*[_\\-.]"),
            Pattern.compile("\\b(\\d{1,3})\\b"),
        };
        for (Pattern p : ps) {
            Matcher m = p.matcher(text);
            if (m.find()) {
                try {
                    int n = Integer.parseInt(m.group(1));
                    if (n > 0 && n <= 999) return n;
                } catch (Exception e) {}
            }
        }
        return -1;
    }

    // ===== FILE PARSER =====
    public static List<TitleDesc> parseFile(Context ctx, Uri uri) {
        List<TitleDesc> res = new ArrayList<>();
        try {
            InputStream is = ctx.getContentResolver().openInputStream(uri);
            if (is == null) return res;
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            List<String> lines = new ArrayList<>();
            String l;
            while ((l = br.readLine()) != null) {
                if (!l.trim().isEmpty()) lines.add(l.trim());
            }
            br.close();
            if (lines.isEmpty()) return res;

            String first = lines.get(0);
            if (first.contains("|")) {
                for (String line : lines) {
                    String[] p = line.split("\\|", -1);
                    if (p.length >= 3)
                        res.add(new TitleDesc(p[1].trim(), p[2].trim(), line));
                    else if (p.length == 2)
                        res.add(new TitleDesc(p[0].trim(), p[1].trim(), line));
                }
            } else if (first.contains(",") && first.split(",").length >= 3) {
                int start = first.toLowerCase().contains("title") ? 1 : 0;
                for (int i = start; i < lines.size(); i++) {
                    String[] p = lines.get(i).split(",", -1);
                    if (p.length >= 3)
                        res.add(new TitleDesc(p[1].trim(), p[2].trim(), lines.get(i)));
                }
            } else {
                String curTitle = "";
                StringBuilder curDesc = new StringBuilder();
                Pattern hp = Pattern.compile(
                    "(?i)((?:episode|ep|part)\\s*\\d+|পর্ব\\s*[০-৯]+|\\d+[.)\\-:])\\s*[:\\-]?\\s*(.*)");
                for (String line : lines) {
                    if (line.matches("^[-=]{3,}$")) {
                        if (!curTitle.isEmpty()) {
                            res.add(new TitleDesc(curTitle, curDesc.toString().trim(), curTitle));
                            curTitle = ""; curDesc = new StringBuilder();
                        }
                        continue;
                    }
                    Matcher m = hp.matcher(line);
                    if (m.find()) {
                        if (!curTitle.isEmpty()) {
                            res.add(new TitleDesc(curTitle, curDesc.toString().trim(), curTitle));
                            curDesc = new StringBuilder();
                        }
                        curTitle = m.group(2).trim().isEmpty() ? line : m.group(2).trim();
                    } else {
                        if (curDesc.length() > 0) curDesc.append("\n");
                        curDesc.append(line);
                    }
                }
                if (!curTitle.isEmpty())
                    res.add(new TitleDesc(curTitle, curDesc.toString().trim(), curTitle));
                if (res.isEmpty()) {
                    for (int i = 0; i < lines.size(); i += 2) {
                        String t = lines.get(i);
                        String d = (i+1 < lines.size()) ? lines.get(i+1) : "";
                        res.add(new TitleDesc(t, d, t));
                    }
                }
            }
        } catch (Exception e) { Log.e(TAG, "Parse error", e); }
        return res;
    }

    // ===== SMART MATCHER =====
    public static List<MatchResult> match(List<String> vNames, List<String> vUris,
                                           List<TitleDesc> titles) {
        List<MatchResult> results = new ArrayList<>();
        if (vNames == null || vNames.isEmpty()) return results;

        // Extract numbers from videos
        int[][] vNums = new int[vNames.size()][2];
        for (int i = 0; i < vNames.size(); i++) {
            vNums[i][0] = i;
            vNums[i][1] = extractNum(vNames.get(i));
        }
        Arrays.sort(vNums, (a, b) -> {
            if (a[1] > 0 && b[1] > 0) return Integer.compare(a[1], b[1]);
            if (a[1] > 0) return -1;
            if (b[1] > 0) return 1;
            return vNames.get(a[0]).compareToIgnoreCase(vNames.get(b[0]));
        });

        // Build title map by number
        Map<Integer, TitleDesc> tMap = new HashMap<>();
        List<TitleDesc> tList = titles != null ? titles : new ArrayList<>();
        for (TitleDesc td : tList) {
            int n = extractNum(td.title + " " + td.raw);
            if (n > 0) tMap.put(n, td);
        }

        int serial = 1, tIdx = 0;
        for (int[] vn : vNums) {
            int vi = vn[0], vNum = vn[1];
            String title = "", desc = "", method = "SEQ";
            if (vNum > 0 && tMap.containsKey(vNum)) {
                TitleDesc td = tMap.get(vNum);
                title = td.title; desc = td.desc; method = "NUM";
            } else if (tIdx < tList.size()) {
                title = tList.get(tIdx).title;
                desc = tList.get(tIdx).desc;
                tIdx++;
            }
            MatchResult r = new MatchResult();
            r.serial = serial++;
            r.uri = vUris.get(vi);
            r.fileName = vNames.get(vi);
            r.title = title; r.desc = desc; r.method = method;
            results.add(r);
        }
        return results;
    }

    // ===== FACEBOOK HELPER =====
    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("autopost", Context.MODE_PRIVATE);
    }

    public static void saveToken(Context ctx, String token) {
        prefs(ctx).edit().putString("token", token).apply();
    }

    public static String getToken(Context ctx) {
        return prefs(ctx).getString("token", null);
    }

    public static void savePage(Context ctx, String id, String name, String token) {
        prefs(ctx).edit()
            .putString("page_id", id)
            .putString("page_name", name)
            .putString("page_token", token).apply();
    }

    public static String getPageId(Context ctx) {
        return prefs(ctx).getString("page_id", null);
    }

    public static String getPageToken(Context ctx) {
        return prefs(ctx).getString("page_token", null);
    }

    public static String getPageName(Context ctx) {
        return prefs(ctx).getString("page_name", null);
    }

    public static boolean isLoggedIn(Context ctx) {
        return getToken(ctx) != null && getPageId(ctx) != null;
    }

    public static void logout(Context ctx) {
        prefs(ctx).edit().clear().apply();
    }

    public static class PageInfo {
        public String id, name, token;
        public PageInfo(String i, String n, String t) { id=i; name=n; token=t; }
    }

    public static List<PageInfo> getPages(String userToken) {
        List<PageInfo> pages = new ArrayList<>();
        try {
            OkHttpClient c = new OkHttpClient();
            Request req = new Request.Builder()
                .url(API + "/me/accounts?fields=id,name,access_token&access_token=" + userToken)
                .build();
            Response res = c.newCall(req).execute();
            if (res.isSuccessful() && res.body() != null) {
                JSONArray data = new JSONObject(res.body().string()).getJSONArray("data");
                for (int i = 0; i < data.length(); i++) {
                    JSONObject p = data.getJSONObject(i);
                    pages.add(new PageInfo(p.getString("id"),
                        p.getString("name"), p.getString("access_token")));
                }
            }
        } catch (Exception e) { Log.e(TAG, "getPages error", e); }
        return pages;
    }

    public static boolean uploadVideo(Context ctx, VideoItem v,
                                       ProgressListener listener) {
        String pageId = getPageId(ctx);
        String pageToken = getPageToken(ctx);
        if (pageId == null || pageToken == null) return false;

        try {
            InputStream is = ctx.getContentResolver().openInputStream(Uri.parse(v.uri));
            if (is == null) return false;
            byte[] bytes = readAll(is);
            is.close();

            if (listener != null) listener.onProgress(10, "Uploading...");

            OkHttpClient client = new OkHttpClient.Builder()
                .writeTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                .build();

            MultipartBody.Builder body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("access_token", pageToken)
                .addFormDataPart("source", "video.mp4",
                    RequestBody.create(MediaType.parse("video/mp4"), bytes));
            if (v.title != null && !v.title.isEmpty())
                body.addFormDataPart("title", v.title);
            if (v.desc != null && !v.desc.isEmpty())
                body.addFormDataPart("description", v.desc);

            if (listener != null) listener.onProgress(40, "Sending...");

            Request req = new Request.Builder()
                .url(API + "/" + pageId + "/videos")
                .post(body.build()).build();
            Response res = client.newCall(req).execute();

            if (res.isSuccessful() && res.body() != null) {
                JSONObject json = new JSONObject(res.body().string());
                v.postId = json.optString("id", "");
                if (listener != null) listener.onProgress(100, "Done!");
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Upload error", e);
            v.error = e.getMessage();
        }
        return false;
    }

    private static byte[] readAll(InputStream is) throws Exception {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] d = new byte[8192];
        int n;
        while ((n = is.read(d)) != -1) buf.write(d, 0, n);
        return buf.toByteArray();
    }

    public interface ProgressListener {
        void onProgress(int pct, String msg);
    }

    // ===== STORAGE (JSON) =====
    public static void saveVideos(Context ctx, List<VideoItem> videos) {
        try {
            JSONArray arr = new JSONArray();
            for (VideoItem v : videos) {
                JSONObject o = new JSONObject();
                o.put("serial", v.serial);
                o.put("uri", v.uri);
                o.put("fileName", v.fileName);
                o.put("title", v.title);
                o.put("desc", v.desc);
                o.put("time", v.time);
                o.put("status", v.status);
                o.put("postId", v.postId != null ? v.postId : "");
                o.put("error", v.error != null ? v.error : "");
                arr.put(o);
            }
            prefs(ctx).edit().putString("videos", arr.toString()).apply();
        } catch (Exception e) { Log.e(TAG, "Save error", e); }
    }

    public static List<VideoItem> loadVideos(Context ctx) {
        List<VideoItem> list = new ArrayList<>();
        String json = prefs(ctx).getString("videos", null);
        if (json == null) return list;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                VideoItem v = new VideoItem();
                v.serial = o.getInt("serial");
                v.uri = o.getString("uri");
                v.fileName = o.getString("fileName");
                v.title = o.getString("title");
                v.desc = o.getString("desc");
                v.time = o.optString("time", "");
                v.status = o.getString("status");
                v.postId = o.optString("postId", "");
                v.error = o.optString("error", "");
                list.add(v);
            }
        } catch (Exception e) { Log.e(TAG, "Load error", e); }
        return list;
    }
                    }

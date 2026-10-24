package com.rogerio.telalimpa;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Tela Limpa 2: launcher minimalista para Android TV.
 * Saudação, relógio, clima, favoritos, mais usados, indicações e todos os apps.
 * Sem anúncios e sem serviços em segundo plano: só consulta a internet enquanto está na tela.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "tela_limpa";
    private static final String KEY_HIDDEN = "hidden_packages";
    private static final String KEY_FAVS = "favorite_packages";
    private static final String KEY_FAVS_INIT = "favorites_initialized";
    private static final String KEY_WEATHER = "weather_json";
    private static final String KEY_WEATHER_TIME = "weather_time";
    private static final String KEY_RECS = "recs_json_v2";
    private static final String KEY_RECS_TIME = "recs_time";
    private static final String USE_PREFIX = "use_";

    private static final long WEATHER_EVERY = 30L * 60 * 1000;
    private static final long RECS_EVERY = 6L * 60 * 60 * 1000;
    private static final long RETRY_AFTER = 5L * 60 * 1000;

    private static final int TILE_W = 104; // dp

    private static final int KIND_APP = 0;
    private static final int KIND_SETTINGS = 1;
    private static final int KIND_ORGANIZE = 2;

    private static class Entry {
        int kind;
        String label;
        String pkg;
        ComponentName component;
        ResolveInfo info;
        Drawable icon;
        int iconRes;
        boolean hidden;
        boolean favorite;
        int uses;
    }

    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ScrollView scroll;
    private FrameLayout root;
    private ImageView bgImage;
    private String currentBackdrop = "";
    private volatile boolean backdropBusy = false;
    private long backdropTry = 0;
    private LinearLayout sectionsHost;
    private TextView greetingView;
    private TextView dateView;
    private TextView tempView;
    private TextView descView;
    private TextView rangeView;

    private List<Entry> apps = new ArrayList<>();
    private boolean manageMode = false;
    private boolean goHome = false;
    private int columns = 6;
    private View firstTile;

    private volatile boolean weatherBusy = false;
    private volatile boolean recsBusy = false;
    private volatile boolean recsFailed = false;
    private long weatherTry = 0;
    private long recsTry = 0;

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            reload();
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateHeader();
            maybeRefresh();
            updateBackdrop();
            handler.postDelayed(this, 60000);
        }
    };

    // ------------------------------------------------------------ ciclo de vida

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        float density = getResources().getDisplayMetrics().density;
        float widthDp = getResources().getDisplayMetrics().widthPixels / density;
        columns = Math.max(4, Math.min(10, (int) ((widthDp - 96) / (TILE_W + 8))));

        scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.TRANSPARENT);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        // Margens generosas por causa do overscan de algumas TVs
        content.setPadding(dp(48), dp(28), dp(48), dp(32));
        content.addView(buildHeader());

        sectionsHost = new LinearLayout(this);
        sectionsHost.setOrientation(LinearLayout.VERTICAL);
        sectionsHost.setClipChildren(false);
        content.setClipChildren(false);
        content.addView(sectionsHost);

        scroll.addView(content);

        // Fundo: degradê do período do dia + imagem de um título em alta (quando houver)
        root = new FrameLayout(this);
        bgImage = new ImageView(this);
        bgImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bgImage.setAlpha(0f);
        root.addView(bgImage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x4D000000, 0xB3000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        updateHeader();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);
        reload();
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            unregisterReceiver(pkgReceiver);
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(tick);
        handler.post(tick);
        if (goHome) {
            goHome = false;
            manageMode = false;
            render();
            if (firstTile != null) firstTile.requestFocus();
            scroll.post(new Runnable() {
                @Override
                public void run() {
                    scroll.scrollTo(0, 0);
                }
            });
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Apertar Home de novo volta ao início
        goHome = true;
    }

    @Override
    public void onBackPressed() {
        // Launcher não "fecha"; só sai do modo Organizar
        if (manageMode) {
            manageMode = false;
            render();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            manageMode = !manageMode;
            render();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------------ cabeçalho

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);

        greetingView = new TextView(this);
        greetingView.setTextColor(0xFFE0E0E0);
        greetingView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        left.addView(greetingView);

        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        timeRow.setGravity(Gravity.BOTTOM);

        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 56);
        clock.setTextColor(Color.WHITE);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        timeRow.addView(clock);

        dateView = new TextView(this);
        dateView.setTextColor(0xFFAAAAAA);
        dateView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        dateView.setPadding(dp(20), 0, 0, dp(10));
        timeRow.addView(dateView);
        left.addView(timeRow);

        header.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);

        tempView = new TextView(this);
        tempView.setTextColor(Color.WHITE);
        tempView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        tempView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        tempView.setGravity(Gravity.END);
        right.addView(tempView);

        descView = new TextView(this);
        descView.setTextColor(0xFFE0E0E0);
        descView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        descView.setGravity(Gravity.END);
        right.addView(descView);

        rangeView = new TextView(this);
        rangeView.setTextColor(0xFFAAAAAA);
        rangeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        rangeView.setGravity(Gravity.END);
        right.addView(rangeView);

        TextView city = new TextView(this);
        city.setText(Config.CITY);
        city.setTextColor(0xFF9E9E9E);
        city.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        city.setGravity(Gravity.END);
        right.addView(city);

        header.addView(right, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        for (TextView tv : new TextView[]{greetingView, clock, dateView, tempView, descView, rangeView, city}) {
            tv.setShadowLayer(6f, 0f, 2f, 0xAA000000);
        }

        updateHeader();
        updateWeatherViews();
        return header;
    }

    private void updateHeader() {
        greetingView.setText(greeting());
        dateView.setText(dateString());
        if (root != null) root.setBackground(timeGradient());
    }

    /** Degradê de fundo conforme o período do dia (aparece quando não há imagem). */
    private Drawable timeGradient() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int a;
        int b;
        if (h >= 5 && h < 12) {
            a = 0xFFE8825A;
            b = 0xFF2E5E8C;
        } else if (h >= 12 && h < 18) {
            a = 0xFF2D7DD2;
            b = 0xFF1B3A6B;
        } else {
            a = 0xFF3B2A78;
            b = 0xFF0B0B24;
        }
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{a, b});
    }

    private String greeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String s;
        if (h >= 5 && h < 12) s = "Bom dia";
        else if (h >= 12 && h < 18) s = "Boa tarde";
        else s = "Boa noite";
        return s + ", " + Config.USER_NAME;
    }

    private String dateString() {
        String s = new SimpleDateFormat("EEEE, d 'de' MMMM", new Locale("pt", "BR")).format(new Date());
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void updateWeatherViews() {
        String raw = prefs.getString(KEY_WEATHER, null);
        if (raw == null) {
            tempView.setText("--°");
            descView.setText("Sem dados do tempo");
            rangeView.setText("");
            return;
        }
        try {
            JSONObject w = new JSONObject(raw);
            tempView.setText(Math.round(w.getDouble("temp")) + "°");
            descView.setText(describe(w.getInt("code")));
            rangeView.setText("Máx " + Math.round(w.getDouble("max")) + "° · Mín "
                    + Math.round(w.getDouble("min")) + "°");
        } catch (Exception e) {
            tempView.setText("--°");
            descView.setText("Sem dados do tempo");
            rangeView.setText("");
        }
    }

    private static String describe(int c) {
        if (c == 0) return "Céu limpo";
        if (c == 1) return "Poucas nuvens";
        if (c == 2) return "Parcialmente nublado";
        if (c == 3) return "Nublado";
        if (c == 45 || c == 48) return "Neblina";
        if (c >= 51 && c <= 57) return "Garoa";
        if (c >= 61 && c <= 67) return "Chuva";
        if (c >= 71 && c <= 77) return "Neve";
        if (c >= 80 && c <= 82) return "Pancadas de chuva";
        if (c == 85 || c == 86) return "Neve";
        if (c >= 95) return "Tempestade";
        return "Tempo instável";
    }

    // ------------------------------------------------------------ internet

    private void maybeRefresh() {
        long now = System.currentTimeMillis();
        if (!weatherBusy && now - weatherTry > RETRY_AFTER
                && now - prefs.getLong(KEY_WEATHER_TIME, 0) > WEATHER_EVERY) {
            weatherTry = now;
            fetchWeather();
        }
        if (!Config.TMDB_API_KEY.isEmpty() && !recsBusy && now - recsTry > RETRY_AFTER
                && now - prefs.getLong(KEY_RECS_TIME, 0) > RECS_EVERY) {
            recsTry = now;
            fetchRecs();
        }
    }

    private void fetchWeather() {
        weatherBusy = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String url = "https://api.open-meteo.com/v1/forecast?latitude=" + Config.LATITUDE
                            + "&longitude=" + Config.LONGITUDE
                            + "&current=temperature_2m,weather_code"
                            + "&daily=temperature_2m_max,temperature_2m_min"
                            + "&timezone=America%2FSao_Paulo&forecast_days=1";
                    JSONObject j = new JSONObject(Net.get(url));
                    JSONObject cur = j.getJSONObject("current");
                    JSONObject daily = j.getJSONObject("daily");
                    JSONObject out = new JSONObject();
                    out.put("temp", cur.getDouble("temperature_2m"));
                    out.put("code", cur.getInt("weather_code"));
                    out.put("max", daily.getJSONArray("temperature_2m_max").getDouble(0));
                    out.put("min", daily.getJSONArray("temperature_2m_min").getDouble(0));
                    prefs.edit()
                            .putString(KEY_WEATHER, out.toString())
                            .putLong(KEY_WEATHER_TIME, System.currentTimeMillis())
                            .apply();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            updateWeatherViews();
                        }
                    });
                } catch (Exception ignored) {
                    // sem internet: mantém o último valor guardado
                } finally {
                    weatherBusy = false;
                }
            }
        }).start();
    }

    private void fetchRecs() {
        recsBusy = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String url = "https://api.themoviedb.org/3/trending/all/week?language=pt-BR&api_key="
                            + Config.TMDB_API_KEY;
                    JSONArray results = new JSONObject(Net.get(url)).getJSONArray("results");
                    JSONArray out = new JSONArray();
                    for (int i = 0; i < results.length(); i++) {
                        JSONObject r = results.getJSONObject(i);
                        String poster = r.optString("poster_path", "");
                        String type = r.optString("media_type", "");
                        if (poster.isEmpty() || "null".equals(poster)) continue;
                        if (!type.equals("movie") && !type.equals("tv")) continue;
                        JSONObject o = new JSONObject();
                        o.put("id", r.getLong("id"));
                        o.put("title", r.has("title") ? r.optString("title") : r.optString("name"));
                        o.put("type", type);
                        o.put("poster", poster);
                        String back = r.optString("backdrop_path", "");
                        o.put("backdrop", "null".equals(back) ? "" : back);
                        o.put("rating", r.optDouble("vote_average", 0));
                        o.put("overview", r.optString("overview", ""));
                        String date = r.optString("release_date", r.optString("first_air_date", ""));
                        o.put("year", date.length() >= 4 ? date.substring(0, 4) : "");
                        out.put(o);
                        if (out.length() >= 12) break;
                    }
                    recsFailed = false;
                    prefs.edit()
                            .putString(KEY_RECS, out.toString())
                            .putLong(KEY_RECS_TIME, System.currentTimeMillis())
                            .apply();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            render();
                        }
                    });
                } catch (Exception ex) {
                    recsFailed = true;
                    if (prefs.getString(KEY_RECS, null) == null) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                render();
                            }
                        });
                    }
                } finally {
                    recsBusy = false;
                }
            }
        }).start();
    }

    private void loadPoster(final String path, final ImageView target) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File f = new File(getCacheDir(), "poster" + path.replace("/", "_"));
                    if (!f.exists()) Net.download("https://image.tmdb.org/t/p/w185" + path, f);
                    final Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
                    if (bmp != null) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                target.setImageBitmap(bmp);
                            }
                        });
                    }
                } catch (Exception ignored) {
                    // sem internet: o pôster fica em branco
                }
            }
        }).start();
    }

    // ------------------------------------------------------------ imagem de fundo

    /** Usa a arte de um título em alta como fundo, trocando a cada 15 minutos. */
    private void updateBackdrop() {
        String raw = prefs.getString(KEY_RECS, null);
        if (raw == null || backdropBusy) return;
        try {
            JSONArray arr = new JSONArray(raw);
            List<String> paths = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                String b = arr.getJSONObject(i).optString("backdrop", "");
                if (!b.isEmpty()) paths.add(b);
            }
            if (paths.isEmpty()) return;
            long now = System.currentTimeMillis();
            String want = paths.get((int) ((now / (15L * 60 * 1000)) % paths.size()));
            if (!want.equals(currentBackdrop) && now - backdropTry > RETRY_AFTER) {
                backdropTry = now;
                loadBackdrop(want);
            }
        } catch (Exception ignored) {
        }
    }

    private void loadBackdrop(final String path) {
        backdropBusy = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File f = new File(getCacheDir(), "back" + path.replace("/", "_"));
                    if (!f.exists()) Net.download("https://image.tmdb.org/t/p/w780" + path, f);
                    final Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
                    if (bmp != null) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                currentBackdrop = path;
                                backdropTry = 0;
                                bgImage.setImageBitmap(bmp);
                                bgImage.setAlpha(0f);
                                bgImage.animate().alpha(1f).setDuration(700).start();
                            }
                        });
                    }
                } catch (Exception ignored) {
                    // sem internet: mantém a imagem atual ou só o degradê
                } finally {
                    backdropBusy = false;
                }
            }
        }).start();
    }

    // ------------------------------------------------------------ dados dos apps

    private void reload() {
        PackageManager pm = getPackageManager();
        Set<String> hidden = stringSet(KEY_HIDDEN);
        Set<String> favs = stringSet(KEY_FAVS);

        Map<String, Entry> byPkg = new LinkedHashMap<>();
        collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, byPkg);
        collect(pm, Intent.CATEGORY_LAUNCHER, byPkg);

        List<Entry> list = new ArrayList<>(byPkg.values());
        Collections.sort(list, (a, b) -> a.label.compareToIgnoreCase(b.label));

        if (!prefs.getBoolean(KEY_FAVS_INIT, false) && !list.isEmpty()) {
            for (Entry e : list) {
                for (String name : Config.DEFAULT_FAVORITES) {
                    if (e.label.equalsIgnoreCase(name)) favs.add(e.pkg);
                }
            }
            prefs.edit().putStringSet(KEY_FAVS, favs).putBoolean(KEY_FAVS_INIT, true).apply();
        }

        for (Entry e : list) {
            e.hidden = hidden.contains(e.pkg);
            e.favorite = favs.contains(e.pkg);
            e.uses = prefs.getInt(USE_PREFIX + e.pkg, 0);
        }
        apps = list;
        render();
    }

    private void collect(PackageManager pm, String category, Map<String, Entry> out) {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
        List<ResolveInfo> list = pm.queryIntentActivities(q, 0);
        for (ResolveInfo ri : list) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || out.containsKey(pkg)) continue;
            Entry e = new Entry();
            e.kind = KIND_APP;
            e.pkg = pkg;
            e.info = ri;
            e.component = new ComponentName(pkg, ri.activityInfo.name);
            CharSequence l = ri.loadLabel(pm);
            e.label = l != null ? l.toString() : pkg;
            out.put(pkg, e);
        }
    }

    private Entry special(int kind, String label, int iconRes) {
        Entry e = new Entry();
        e.kind = kind;
        e.label = label;
        e.iconRes = iconRes;
        return e;
    }

    private Set<String> stringSet(String key) {
        return new HashSet<>(prefs.getStringSet(key, Collections.<String>emptySet()));
    }

    // ------------------------------------------------------------ montagem da tela

    private void render() {
        View cf = getCurrentFocus();
        Object prevTag = cf != null ? cf.getTag() : null;
        firstTile = null;
        sectionsHost.removeAllViews();

        if (manageMode) {
            TextView hint = new TextView(this);
            hint.setText("Modo Organizar: clique num app para alternar entre Normal, ★ Favorito e Oculto. "
                    + "Clique em Concluir (ou aperte Voltar) para sair.");
            hint.setTextColor(0xFFFFC107);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            hint.setPadding(0, dp(20), 0, 0);
            sectionsHost.addView(hint);
        } else {
            List<Entry> favs = new ArrayList<>();
            for (Entry e : apps) {
                if (e.favorite && !e.hidden) favs.add(e);
            }
            if (favs.size() > columns) favs = new ArrayList<>(favs.subList(0, columns));
            if (!favs.isEmpty()) {
                addSection("Favoritos");
                sectionsHost.addView(tileRow(favs, "fav"));
            }

            List<Entry> top = new ArrayList<>();
            for (Entry e : apps) {
                if (!e.hidden && e.uses > 0) top.add(e);
            }
            Collections.sort(top, (a, b) -> b.uses - a.uses);
            if (top.size() > columns) top = new ArrayList<>(top.subList(0, columns));
            if (!top.isEmpty()) {
                addSection("Mais usados");
                sectionsHost.addView(tileRow(top, "top"));
            }

            addSection("Indicações da semana");
            sectionsHost.addView(recsRow());
        }

        addSection(manageMode ? "Organizar apps" : "Todos os apps");
        sectionsHost.addView(allGrid());

        View target = null;
        if (prevTag != null) target = sectionsHost.findViewWithTag(prevTag);
        if (target == null) target = firstTile;
        if (target != null) target.requestFocus();
        updateBackdrop();
    }

    private void addSection(String title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(0xFFEEEEEE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setShadowLayer(6f, 0f, 2f, 0xAA000000);
        t.setPadding(0, dp(22), 0, dp(8));
        sectionsHost.addView(t);
    }

    private View tileRow(List<Entry> list, String section) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        for (Entry e : list) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    dp(TILE_W), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            row.addView(appTile(e, section), lp);
        }
        return row;
    }

    private View allGrid() {
        GridLayout g = new GridLayout(this);
        g.setColumnCount(columns);
        g.setClipChildren(false);

        List<Entry> all = new ArrayList<>();
        for (Entry e : apps) {
            if (manageMode || !e.hidden) all.add(e);
        }
        all.add(special(KIND_SETTINGS, "Configurações", android.R.drawable.ic_menu_preferences));
        all.add(special(KIND_ORGANIZE, manageMode ? "Concluir" : "Organizar",
                manageMode ? android.R.drawable.ic_menu_save : android.R.drawable.ic_menu_edit));

        for (Entry e : all) {
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = dp(TILE_W);
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.setMargins(0, 0, dp(8), dp(8));
            g.addView(appTile(e, "all"), lp);
        }
        return g;
    }

    private View appTile(final Entry e, String section) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setPadding(dp(6), dp(10), dp(6), dp(8));
        item.setFocusable(true);
        item.setClickable(true);
        item.setBackground(focusBackground());
        item.setTag(section + ":" + (e.kind == KIND_APP ? e.pkg : "k" + e.kind));
        addFocusZoom(item);

        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);

        TextView label = new TextView(this);
        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(6), 0, 0);

        if (e.kind == KIND_APP) {
            if (e.icon == null) e.icon = e.info.loadIcon(getPackageManager());
            Drawable.ConstantState cs = e.icon.getConstantState();
            icon.setImageDrawable(cs != null ? cs.newDrawable(getResources()) : e.icon);
            String text = e.label;
            if (manageMode) {
                if (e.favorite) text = "★ " + text;
                else if (e.hidden) text = text + " · oculto";
            }
            label.setText(text);
            item.setAlpha(e.hidden ? 0.4f : 1f);
        } else {
            icon.setImageResource(e.iconRes);
            icon.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
            icon.setPadding(dp(14), dp(14), dp(14), dp(14));
            label.setText(e.label);
        }

        item.addView(icon, new LinearLayout.LayoutParams(dp(60), dp(60)));
        item.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        item.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onTileClick(e);
            }
        });
        if (firstTile == null) firstTile = item;
        return item;
    }

    private View recsRow() {
        JSONArray arr = null;
        String raw = prefs.getString(KEY_RECS, null);
        if (raw != null) {
            try {
                arr = new JSONArray(raw);
            } catch (Exception ignored) {
                arr = null;
            }
        }
        if (arr == null || arr.length() == 0) {
            TextView t = new TextView(this);
            t.setTextColor(0xFF9E9E9E);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            if (Config.TMDB_API_KEY.isEmpty()) {
                t.setText("Para ver filmes e séries em alta, coloque sua chave gratuita do TMDB no arquivo Config.java.");
            } else if (recsFailed) {
                t.setText("Não foi possível carregar as indicações agora. Verifique a internet e a chave do TMDB.");
            } else {
                t.setText("Carregando indicações...");
            }
            return t;
        }

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hs.setClipToPadding(false);
        hs.setClipChildren(false);
        hs.setPadding(dp(6), dp(10), dp(6), dp(10));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject r = arr.optJSONObject(i);
            if (r == null) continue;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(10);
            row.addView(posterTile(r), lp);
        }
        hs.addView(row);
        return hs;
    }

    private View posterTile(final JSONObject r) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setPadding(dp(4), dp(4), dp(4), dp(4));
        item.setFocusable(true);
        item.setClickable(true);
        item.setBackground(focusBackground());
        item.setTag("rec:" + r.optLong("id"));
        addFocusZoom(item);

        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackgroundColor(0xFF1E1E1E);
        poster.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(10));
            }
        });
        poster.setClipToOutline(true);
        item.addView(poster, new LinearLayout.LayoutParams(dp(112), dp(168)));

        TextView title = new TextView(this);
        title.setText(r.optString("title"));
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(0, dp(6), 0, 0);
        item.addView(title, new LinearLayout.LayoutParams(dp(112), ViewGroup.LayoutParams.WRAP_CONTENT));

        item.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showRec(r);
            }
        });
        loadPoster(r.optString("poster"), poster);
        if (firstTile == null) firstTile = item;
        return item;
    }

    private Drawable focusBackground() {
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(0x44FFFFFF);
        focused.setStroke(dp(2), Color.WHITE);
        focused.setCornerRadius(dp(12));
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(0x26FFFFFF);
        normal.setCornerRadius(dp(12));
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, focused);
        s.addState(new int[]{}, normal);
        return s;
    }

    /** Dá um leve "zoom" no item que está com o foco do controle remoto. */
    private void addFocusZoom(View v) {
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
                float s = hasFocus ? 1.07f : 1f;
                view.animate().scaleX(s).scaleY(s).setDuration(120).start();
            }
        });
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ------------------------------------------------------------ ações

    private void onTileClick(Entry e) {
        switch (e.kind) {
            case KIND_SETTINGS:
                try {
                    startActivity(new Intent(Settings.ACTION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ex) {
                    Toast.makeText(this, "Não foi possível abrir as configurações", Toast.LENGTH_SHORT).show();
                }
                break;
            case KIND_ORGANIZE:
                manageMode = !manageMode;
                render();
                break;
            default:
                if (manageMode) {
                    cycleState(e);
                } else {
                    launch(e);
                }
        }
    }

    /** Organizar: Normal -> Favorito -> Oculto -> Normal. */
    private void cycleState(Entry e) {
        Set<String> favs = stringSet(KEY_FAVS);
        Set<String> hid = stringSet(KEY_HIDDEN);
        if (favs.contains(e.pkg)) {
            favs.remove(e.pkg);
            hid.add(e.pkg);
        } else if (hid.contains(e.pkg)) {
            hid.remove(e.pkg);
        } else {
            favs.add(e.pkg);
        }
        prefs.edit().putStringSet(KEY_FAVS, favs).putStringSet(KEY_HIDDEN, hid).apply();
        e.favorite = favs.contains(e.pkg);
        e.hidden = hid.contains(e.pkg);
        render();
    }

    private void launch(Entry e) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.setComponent(e.component);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
            e.uses++;
            prefs.edit().putInt(USE_PREFIX + e.pkg, e.uses).apply();
        } catch (Exception ex) {
            Toast.makeText(this, "Não foi possível abrir " + e.label, Toast.LENGTH_SHORT).show();
        }
    }

    private void showRec(JSONObject r) {
        String title = r.optString("title");
        String year = r.optString("year");
        double rating = r.optDouble("rating", 0);
        String kind = "tv".equals(r.optString("type")) ? "Série" : "Filme";
        String head = kind;
        if (!year.isEmpty()) head += " · " + year;
        if (rating > 0) head += String.format(Locale.US, " · ★ %.1f", rating);
        String overview = r.optString("overview", "");
        if (overview.isEmpty()) overview = "Sem descrição disponível.";
        final String query = title + " trailer";

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(head + "\n\n" + overview)
                .setPositiveButton("Ver trailer", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        openTrailer(query);
                    }
                })
                .setNegativeButton("Fechar", null)
                .show();
    }

    private void openTrailer(String query) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ex) {
            Toast.makeText(this, "Nenhum app disponível para abrir o trailer", Toast.LENGTH_LONG).show();
        }
    }
}

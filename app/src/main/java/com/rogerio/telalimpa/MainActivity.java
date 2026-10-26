package com.rogerio.telalimpa;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.role.RoleManager;
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
import android.os.Build;
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
import android.widget.EditText;
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
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
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
    private static final String KEY_ORDER = "app_order";
    private static final String KEY_WEATHER = "weather_json";
    private static final String KEY_WEATHER_TIME = "weather_time";
    private static final String KEY_RECS = "recs_json_v3";
    private static final String KEY_RECS_TIME = "recs_time_v5";
    private static final String KEY_BG = "bg_json_v2";
    private static final String KEY_CITY = "city_name";
    private static final String KEY_LAT = "city_lat";
    private static final String KEY_LON = "city_lon";
    private static final String KEY_HOME_SEEN = "home_seen";
    private static final String KEY_CH_LIST = "channel_list";
    private static final String KEY_CH_INFO = "channel_info_v1";
    private static final String CH_USE_PREFIX = "chuse_";
    private static final String USE_PREFIX = "use_";

    private static final long WEATHER_EVERY = 30L * 60 * 1000;
    private static final long RECS_EVERY = 6L * 60 * 60 * 1000;
    private static final long RETRY_AFTER = 5L * 60 * 1000;

    // Cartões retangulares (proporção 16:9), em dp
    private static final int TILE_W = 148;
    private static final int TILE_H = 83;
    private static final int BIG_W = 224;
    private static final int BIG_H = 126;

    private static final int KIND_APP = 0;
    private static final int KIND_SETTINGS = 1;
    private static final int KIND_ORGANIZE = 2;
    private static final int KIND_DEFAULT = 3;
    private static final int KIND_CITY = 4;
    private static final int KIND_CHANNELS = 5;

    private static class Entry {
        int kind;
        String label;
        String pkg;
        ComponentName component;
        ResolveInfo info;
        Drawable icon;
        Drawable banner;
        boolean bannerLoaded;
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
    private ImageView bgImage2;
    private int slideIndex = -1;
    private String currentBackdrop = "";
    private volatile boolean backdropBusy = false;
    private long backdropTry = 0;
    private LinearLayout sectionsHost;
    private TextView greetingView;
    private TextView dateView;
    private LinearLayout captionBox;
    private TextView captionLabel;
    private TextView captionTitle;
    private TextView captionSub;
    private TextView defaultHintView;
    private TextView tempView;
    private TextView descView;
    private TextView rangeView;

    private List<Entry> apps = new ArrayList<>();
    private boolean manageMode = false;
    private String movingPkg = null;
    private boolean lastDefault = false;
    private TextView cityView;
    private volatile int weatherEpoch = 0;
    private volatile boolean chBusy = false;
    private long chTry = 0;
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
            handler.postDelayed(this, 60000);
        }
    };

    private final Runnable slide = new Runnable() {
        @Override
        public void run() {
            nextBackdrop();
            handler.postDelayed(this, Config.BACKGROUND_SECONDS * 1000L);
        }
    };

    // ------------------------------------------------------------ ciclo de vida

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        markIfHome(getIntent());

        float density = getResources().getDisplayMetrics().density;
        float widthDp = getResources().getDisplayMetrics().widthPixels / density;
        columns = Math.max(3, Math.min(10, (int) ((widthDp - 96) / (TILE_W + 16))));

        scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.TRANSPARENT);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        // Margens generosas por causa do overscan de algumas TVs
        content.setPadding(dp(48), dp(28), dp(48), dp(32));
        content.addView(buildHeader());
        content.addView(buildCaption());

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
        // Segunda camada: a imagem nova aparece (fade) por cima da anterior
        bgImage2 = new ImageView(this);
        bgImage2.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bgImage2.setAlpha(0f);
        root.addView(bgImage2, new FrameLayout.LayoutParams(
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
        handler.removeCallbacks(slide);
        handler.post(slide);
        boolean nowDefault = isDefaultHome();
        if (nowDefault != lastDefault) {
            lastDefault = nowDefault;
            render();
        }
        if (goHome) {
            goHome = false;
            movingPkg = null;
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
        handler.removeCallbacks(slide);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Apertar Home de novo volta ao início
        markIfHome(intent);
        goHome = true;
    }

    @Override
    public void onBackPressed() {
        // Launcher não "fecha"; só sai do modo Organizar
        if (manageMode) {
            movingPkg = null;
            manageMode = false;
            render();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            movingPkg = null;
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

        defaultHintView = new TextView(this);
        defaultHintView.setTextColor(0xFFFFC107);
        defaultHintView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        defaultHintView.setText("A Tela Limpa ainda não é a tela inicial padrão. "
                + "Clique em \"Tornar padrão\" no fim da lista de apps.");
        defaultHintView.setPadding(0, dp(6), 0, 0);
        defaultHintView.setVisibility(View.GONE);
        left.addView(defaultHintView);

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
        cityView = city;
        city.setText(cityName());
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

    /** Destaque com o título do filme/série que está no fundo agora. */
    private View buildCaption() {
        captionBox = new LinearLayout(this);
        captionBox.setOrientation(LinearLayout.VERTICAL);
        captionBox.setPadding(0, dp(18), dp(120), 0);
        captionBox.setAlpha(0f);
        captionBox.setVisibility(View.GONE);

        captionLabel = new TextView(this);
        captionLabel.setTextColor(0xFFFFC107);
        captionLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        captionLabel.setTypeface(Typeface.DEFAULT_BOLD);
        captionBox.addView(captionLabel);

        captionTitle = new TextView(this);
        captionTitle.setTextColor(Color.WHITE);
        captionTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        captionTitle.setTypeface(Typeface.DEFAULT_BOLD);
        captionTitle.setMaxLines(2);
        captionTitle.setEllipsize(TextUtils.TruncateAt.END);
        captionBox.addView(captionTitle);

        captionSub = new TextView(this);
        captionSub.setTextColor(0xFFE0E0E0);
        captionSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        captionSub.setPadding(0, dp(2), 0, 0);
        captionBox.addView(captionSub);

        for (TextView tv : new TextView[]{captionLabel, captionTitle, captionSub}) {
            tv.setShadowLayer(8f, 0f, 2f, 0xCC000000);
        }
        return captionBox;
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
        if (!Config.YOUTUBE_API_KEY.isEmpty() && !chBusy && now - chTry > RETRY_AFTER
                && needsChannelInfo()) {
            chTry = now;
            fetchChannels();
        }
    }

    private void fetchWeather() {
        weatherBusy = true;
        final int epoch = weatherEpoch;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String url = "https://api.open-meteo.com/v1/forecast?latitude=" + cityLat()
                            + "&longitude=" + cityLon()
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
                    if (epoch != weatherEpoch) return; // a cidade mudou durante a consulta
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
                    JSONArray out = new JSONArray();
                    String[] kinds = {"movie", "tv"};
                    for (String type : kinds) {
                        String url = "https://api.themoviedb.org/3/trending/" + type
                                + "/week?language=pt-BR&api_key=" + Config.TMDB_API_KEY;
                        JSONArray results = new JSONObject(Net.get(url)).getJSONArray("results");
                        int added = 0;
                        for (int i = 0; i < results.length() && added < 12; i++) {
                            JSONObject r = results.getJSONObject(i);
                            String poster = r.optString("poster_path", "");
                            if (poster.isEmpty() || "null".equals(poster)) continue;
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
                            added++;
                        }
                    }
                    // Lançamentos recentes para o fundo: filmes em cartaz e séries no ar
                    try {
                        JSONArray bg = new JSONArray();
                        String[][] src = {
                                {"movie", "/movie/now_playing?language=pt-BR&region=BR&page=1", "Em cartaz"},
                                {"tv", "/tv/on_the_air?language=pt-BR&page=1", "Série no ar"}
                        };
                        for (String[] s : src) {
                            String u = "https://api.themoviedb.org/3" + s[1]
                                    + "&api_key=" + Config.TMDB_API_KEY;
                            JSONArray res = new JSONObject(Net.get(u)).getJSONArray("results");
                            int n = 0;
                            for (int i = 0; i < res.length() && n < 12; i++) {
                                JSONObject r = res.getJSONObject(i);
                                String back = r.optString("backdrop_path", "");
                                if (back.isEmpty() || "null".equals(back)) continue;
                                JSONObject o = new JSONObject();
                                o.put("type", s[0]);
                                o.put("label", s[2]);
                                o.put("title", r.has("title") ? r.optString("title") : r.optString("name"));
                                o.put("backdrop", back);
                                o.put("rating", r.optDouble("vote_average", 0));
                                String d = r.optString("release_date", r.optString("first_air_date", ""));
                                o.put("year", d.length() >= 4 ? d.substring(0, 4) : "");
                                bg.put(o);
                                n++;
                            }
                        }
                        prefs.edit().putString(KEY_BG, bg.toString()).apply();
                    } catch (Exception ignoredBg) {
                        // sem os lançamentos, o fundo usa os títulos em alta
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

    /**
     * Títulos do fundo: lançamentos (em cartaz / no ar) e, se ainda não chegaram,
     * os títulos em alta. Intercala filmes e séries.
     */
    private List<JSONObject> backdropList() {
        List<JSONObject> movies = new ArrayList<>();
        List<JSONObject> shows = new ArrayList<>();
        List<JSONObject> out = new ArrayList<>();
        String[] sources = {KEY_BG, KEY_RECS};
        for (String key : sources) {
            String raw = prefs.getString(key, null);
            if (raw == null) continue;
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject r = arr.optJSONObject(i);
                    if (r == null || r.optString("backdrop", "").isEmpty()) continue;
                    if ("tv".equals(r.optString("type"))) shows.add(r);
                    else movies.add(r);
                }
            } catch (Exception ignored) {
            }
            if (!movies.isEmpty() || !shows.isEmpty()) break;
        }
        int n = Math.max(movies.size(), shows.size());
        for (int i = 0; i < n; i++) {
            if (i < movies.size()) out.add(movies.get(i));
            if (i < shows.size()) out.add(shows.get(i));
        }
        return out;
    }

    private void applyCaption(JSONObject r) {
        captionLabel.setText(r.optString("label", "Em alta").toUpperCase(new Locale("pt", "BR")));
        captionTitle.setText(r.optString("title", ""));
        String year = r.optString("year", "");
        double rating = r.optDouble("rating", 0);
        StringBuilder sb = new StringBuilder();
        if (!year.isEmpty()) sb.append(year);
        if (rating > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(String.format(Locale.US, "★ %.1f", rating));
        }
        captionSub.setText(sb.toString());
        captionSub.setVisibility(sb.length() == 0 ? View.GONE : View.VISIBLE);
    }

    /** Passa para a próxima imagem da apresentação de fundo. */
    private void nextBackdrop() {
        if (backdropBusy) return;
        List<JSONObject> list = backdropList();
        if (list.isEmpty()) return;
        if (slideIndex < 0) slideIndex = new Random().nextInt(list.size());
        else slideIndex++;
        JSONObject pick = list.get(slideIndex % list.size());
        if (pick.optString("backdrop").equals(currentBackdrop) && list.size() > 1) {
            slideIndex++;
            pick = list.get(slideIndex % list.size());
        }
        String path = pick.optString("backdrop");
        File f = new File(getCacheDir(), "back" + path.replace("/", "_"));
        // Sem internet e sem a imagem guardada: espera um pouco antes de tentar de novo
        if (!f.exists() && System.currentTimeMillis() - backdropTry < RETRY_AFTER) return;
        loadBackdrop(path, pick);
    }

    private void loadBackdrop(final String path, final JSONObject info) {
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
                                showBackdrop(bmp, path, info);
                            }
                        });
                    }
                } catch (Exception ignored) {
                    // sem internet: mantém a imagem atual ou só o degradê
                    backdropTry = System.currentTimeMillis();
                } finally {
                    backdropBusy = false;
                }
            }
        }).start();
    }

    /** Mostra a nova imagem com um fade suave por cima da anterior. */
    private void showBackdrop(final Bitmap bmp, String path, final JSONObject info) {
        currentBackdrop = path;
        backdropTry = 0;
        // O título troca junto com a imagem: some, muda o texto e volta
        captionBox.animate().cancel();
        captionBox.animate().alpha(0f).setDuration(400).withEndAction(new Runnable() {
            @Override
            public void run() {
                applyCaption(info);
                captionBox.setVisibility(View.VISIBLE);
                captionBox.animate().alpha(1f).setDuration(900).start();
            }
        }).start();
        bgImage2.setImageBitmap(bmp);
        bgImage2.setAlpha(0f);
        bgImage2.animate().alpha(1f).setDuration(1500).withEndAction(new Runnable() {
            @Override
            public void run() {
                bgImage.setImageBitmap(bmp);
                bgImage.setAlpha(1f);
                bgImage2.setAlpha(0f);
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

        // Ordem escolhida por você no modo Organizar (apps novos entram no fim, em ordem alfabética)
        String savedOrder = prefs.getString(KEY_ORDER, "");
        if (!savedOrder.isEmpty()) {
            final List<String> order = Arrays.asList(savedOrder.split("\n"));
            Collections.sort(list, (a, b) -> {
                int ia = order.indexOf(a.pkg);
                int ib = order.indexOf(b.pkg);
                return Integer.compare(ia < 0 ? Integer.MAX_VALUE : ia, ib < 0 ? Integer.MAX_VALUE : ib);
            });
        }

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
            if (movingPkg != null) {
                hint.setText("Movendo: use as setas do controle para mudar o app de lugar "
                        + "e aperte OK para confirmar.");
            } else {
                hint.setText("Modo Organizar: clique num app para escolher Mover, Favorito ou Ocultar. "
                        + "Clique em Concluir (ou aperte Voltar) para sair.");
            }
            hint.setTextColor(0xFFFFC107);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            hint.setPadding(0, dp(20), 0, 0);
            sectionsHost.addView(hint);
        } else {
            List<Entry> favs = new ArrayList<>();
            for (Entry e : apps) {
                if (e.favorite && !e.hidden) favs.add(e);
            }
            if (!favs.isEmpty()) {
                addSection("Favoritos");
                sectionsHost.addView(scrollRow(tileRow(favs, "fav", true)));
            }

            List<Entry> top = new ArrayList<>();
            for (Entry e : apps) {
                if (!e.hidden && e.uses > 0) top.add(e);
            }
            Collections.sort(top, (a, b) -> b.uses - a.uses);
            if (top.size() > columns) top = new ArrayList<>(top.subList(0, columns));
            if (!top.isEmpty()) {
                addSection("Mais usados");
                sectionsHost.addView(tileRow(top, "top", false));
            }

            addSection("Meus canais");
            sectionsHost.addView(scrollRow(channelsRow()));

            if (Config.TMDB_API_KEY.isEmpty()) {
                addSection("Filmes e séries");
                sectionsHost.addView(recsRow("movie"));
            } else {
                addSection("Filmes em alta");
                sectionsHost.addView(recsRow("movie"));
                addSection("Séries em alta");
                sectionsHost.addView(recsRow("tv"));
            }
        }

        addSection(manageMode ? "Organizar apps" : "Todos os apps");
        sectionsHost.addView(allGrid());

        View target = null;
        if (prevTag != null) target = sectionsHost.findViewWithTag(prevTag);
        if (target == null) target = firstTile;
        if (target != null) target.requestFocus();
        lastDefault = isDefaultHome();
        defaultHintView.setVisibility(lastDefault ? View.GONE : View.VISIBLE);
        if (currentBackdrop.isEmpty()) nextBackdrop();
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

    private LinearLayout tileRow(List<Entry> list, String section, boolean big) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        for (Entry e : list) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    dp((big ? BIG_W : TILE_W) + 8), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            row.addView(appTile(e, section, big), lp);
        }
        return row;
    }

    /** Linha que rola na horizontal, sem cortar o zoom do item selecionado. */
    private View scrollRow(LinearLayout row) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hs.setClipToPadding(false);
        hs.setClipChildren(false);
        hs.setPadding(dp(6), dp(10), dp(6), dp(10));
        hs.addView(row);
        return hs;
    }

    private View allGrid() {
        GridLayout g = new GridLayout(this);
        g.setColumnCount(columns);
        g.setClipChildren(false);

        List<Entry> all = new ArrayList<>();
        for (Entry e : apps) {
            if (manageMode || !e.hidden) all.add(e);
        }
        if (!isDefaultHome()) {
            all.add(special(KIND_DEFAULT, "Tornar padrão", android.R.drawable.ic_menu_set_as));
        }
        all.add(special(KIND_CITY, "Mudar cidade", android.R.drawable.ic_menu_mylocation));
        all.add(special(KIND_SETTINGS, "Configurações", android.R.drawable.ic_menu_preferences));
        all.add(special(KIND_ORGANIZE, manageMode ? "Concluir" : "Organizar",
                manageMode ? android.R.drawable.ic_menu_save : android.R.drawable.ic_menu_edit));

        for (Entry e : all) {
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = dp(TILE_W + 8);
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.setMargins(0, 0, dp(8), dp(8));
            g.addView(appTile(e, "all", false), lp);
        }
        return g;
    }

    private View appTile(final Entry e, String section, boolean big) {
        int w = big ? BIG_W : TILE_W;
        int h = big ? BIG_H : TILE_H;

        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setPadding(dp(4), dp(4), dp(4), dp(6));
        item.setFocusable(true);
        item.setClickable(true);
        item.setBackground(focusBackground());
        item.setTag(section + ":" + (e.kind == KIND_APP ? e.pkg : "k" + e.kind));
        addFocusZoom(item);

        // Cartão retangular de cantos arredondados
        ImageView card = new ImageView(this);
        roundCorners(card, 12);

        TextView label = new TextView(this);
        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, big ? 15 : 13);
        label.setShadowLayer(4f, 0f, 1f, 0xAA000000);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(6), 0, 0);

        if (e.kind == KIND_APP) {
            if (!e.bannerLoaded) {
                e.bannerLoaded = true;
                try {
                    e.banner = e.info.activityInfo.loadBanner(getPackageManager());
                } catch (Exception ex) {
                    e.banner = null;
                }
            }
            if (e.banner != null) {
                // Apps de TV costumam trazer um banner 16:9 pronto
                Drawable.ConstantState bs = e.banner.getConstantState();
                card.setScaleType(ImageView.ScaleType.CENTER_CROP);
                card.setImageDrawable(bs != null ? bs.newDrawable(getResources()) : e.banner);
            } else {
                if (e.icon == null) e.icon = e.info.loadIcon(getPackageManager());
                Drawable.ConstantState cs = e.icon.getConstantState();
                card.setBackground(cardBackground());
                card.setScaleType(ImageView.ScaleType.FIT_CENTER);
                int pad = dp(h * 18 / 100);
                card.setPadding(pad, pad, pad, pad);
                card.setImageDrawable(cs != null ? cs.newDrawable(getResources()) : e.icon);
            }
            String text = e.label;
            if (manageMode) {
                if (e.favorite) text = "★ " + text;
                else if (e.hidden) text = text + " · oculto";
                if (e.pkg.equals(movingPkg)) text = "↔ " + text;
            }
            label.setText(text);
            item.setAlpha(e.hidden ? 0.4f : 1f);
        } else {
            card.setBackground(cardBackground());
            card.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int pad = dp(h * 24 / 100);
            card.setPadding(pad, pad, pad, pad);
            card.setImageResource(e.iconRes);
            card.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
            label.setText(e.label);
        }

        item.addView(card, new LinearLayout.LayoutParams(dp(w), dp(h)));
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

    private View recsRow(String type) {
        JSONArray arr = null;
        String raw = prefs.getString(KEY_RECS, null);
        if (raw != null) {
            try {
                arr = new JSONArray(raw);
            } catch (Exception ignored) {
                arr = null;
            }
        }
        List<JSONObject> items = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject r = arr.optJSONObject(i);
                if (r != null && type.equals(r.optString("type"))) items.add(r);
            }
        }
        if (items.isEmpty()) {
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

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        for (JSONObject r : items) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(10);
            row.addView(posterTile(r), lp);
        }
        View hs = scrollRow(row);
        return hs;
    }

    private View posterTile(final JSONObject r) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setPadding(dp(4), dp(4), dp(4), dp(4));
        item.setFocusable(true);
        item.setClickable(true);
        item.setBackground(focusBackground());
        item.setTag("rec:" + r.optString("type") + r.optLong("id"));
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
        item.addView(poster, new LinearLayout.LayoutParams(dp(124), dp(186)));

        TextView title = new TextView(this);
        title.setText(r.optString("title"));
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(0, dp(6), 0, 0);
        item.addView(title, new LinearLayout.LayoutParams(dp(124), ViewGroup.LayoutParams.WRAP_CONTENT));

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
        focused.setCornerRadius(dp(16));
        ColorDrawable normal = new ColorDrawable(Color.TRANSPARENT);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, focused);
        s.addState(new int[]{}, normal);
        return s;
    }

    // ------------------------------------------------------------ tela inicial padrão

    /** Guarda a hora em que o sistema abriu a Tela Limpa como tela inicial (botão Home ou ao ligar a TV). */
    private void markIfHome(Intent i) {
        if (i != null && i.hasCategory(Intent.CATEGORY_HOME)) {
            prefs.edit().putLong(KEY_HOME_SEEN, System.currentTimeMillis()).apply();
        }
    }

    private boolean isDefaultHome() {
        // 1) Android 10+: o sistema informa se este app é o app inicial
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                RoleManager rm = (RoleManager) getSystemService(Context.ROLE_SERVICE);
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)
                        && rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                    return true;
                }
            } catch (Exception ignored) {
                // segue para as outras verificações
            }
        }
        // 2) Qual app responde ao botão Home
        ResolveInfo ri = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY);
        if (ri != null && getPackageName().equals(ri.activityInfo.packageName)) return true;
        // 3) Algumas TVs não informam acima: vale se o sistema abriu a Tela Limpa como inicial há pouco
        return System.currentTimeMillis() - prefs.getLong(KEY_HOME_SEEN, 0) < 7L * 24 * 60 * 60 * 1000;
    }

    // ------------------------------------------------------------ meus canais do YouTube

    private List<String> channelList() {
        String raw = prefs.getString(KEY_CH_LIST, null);
        List<String> out = new ArrayList<>();
        if (raw == null) {
            out.addAll(Arrays.asList(Config.DEFAULT_CHANNELS));
        } else {
            for (String s : raw.split("\n")) {
                if (!s.trim().isEmpty()) out.add(s.trim());
            }
        }
        return out;
    }

    private void saveChannelList(List<String> list) {
        prefs.edit().putString(KEY_CH_LIST, TextUtils.join("\n", list)).apply();
    }

    private JSONObject channelInfoAll() {
        try {
            String raw = prefs.getString(KEY_CH_INFO, null);
            if (raw != null) return new JSONObject(raw);
        } catch (Exception ignored) {
        }
        return new JSONObject();
    }

    private boolean needsChannelInfo() {
        JSONObject all = channelInfoAll();
        for (String q : channelList()) {
            if (!all.has(q)) return true;
        }
        return false;
    }

    /** Linha de canais: os mais abertos pela Tela Limpa vêm primeiro; no fim, o botão Trocar canais. */
    private LinearLayout channelsRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        JSONObject all = channelInfoAll();
        List<String> ordered = channelList();
        Collections.sort(ordered, (a, b) ->
                prefs.getInt(CH_USE_PREFIX + b, 0) - prefs.getInt(CH_USE_PREFIX + a, 0));
        for (String q : ordered) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    dp(TILE_W + 8), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            row.addView(channelTile(q, all.optJSONObject(q)), lp);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                dp(TILE_W + 8), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        row.addView(appTile(special(KIND_CHANNELS, "Trocar canais",
                android.R.drawable.ic_menu_edit), "ch", false), lp);
        return row;
    }

    private View channelTile(final String q, JSONObject info) {
        String title = info != null ? info.optString("title", "") : "";
        if (title.isEmpty()) title = q.startsWith("@") ? q.substring(1) : q;
        final String id = info != null ? info.optString("id", "") : "";
        String thumb = info != null ? info.optString("thumb", "") : "";

        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setPadding(dp(4), dp(4), dp(4), dp(6));
        item.setFocusable(true);
        item.setClickable(true);
        item.setBackground(focusBackground());
        item.setTag("ch:" + q);
        addFocusZoom(item);

        FrameLayout card = new FrameLayout(this);
        card.setBackground(cardBackground());
        roundCorners(card, 12);

        int av = dp(TILE_H * 62 / 100);
        int[] palette = {0xFFE53935, 0xFF8E24AA, 0xFF3949AB, 0xFF00897B, 0xFFF4511E, 0xFF6D4C41};
        GradientDrawable oval = new GradientDrawable();
        oval.setShape(GradientDrawable.OVAL);
        oval.setColor(palette[(q.hashCode() & 0x7fffffff) % palette.length]);

        ImageView avatar = new ImageView(this);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setBackground(oval);
        avatar.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        avatar.setClipToOutline(true);
        card.addView(avatar, new FrameLayout.LayoutParams(av, av, Gravity.CENTER));

        // Letra inicial enquanto a foto não chega (ou se não houver chave do YouTube)
        TextView letter = new TextView(this);
        String base = q.startsWith("@") ? q.substring(1) : q;
        letter.setText(base.isEmpty() ? "?" : base.substring(0, 1).toUpperCase());
        letter.setTextColor(Color.WHITE);
        letter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        letter.setTypeface(Typeface.DEFAULT_BOLD);
        letter.setGravity(Gravity.CENTER);
        card.addView(letter, new FrameLayout.LayoutParams(av, av, Gravity.CENTER));

        if (!id.isEmpty() && !thumb.isEmpty()) loadChannelThumb(thumb, id, avatar, letter);

        TextView label = new TextView(this);
        label.setText(title);
        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setShadowLayer(4f, 0f, 1f, 0xAA000000);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(6), 0, 0);

        item.addView(card, new LinearLayout.LayoutParams(dp(TILE_W), dp(TILE_H)));
        item.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        item.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openChannel(q, id);
            }
        });
        if (firstTile == null) firstTile = item;
        return item;
    }

    private void loadChannelThumb(final String url, final String id,
                                  final ImageView target, final TextView letter) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File f = new File(getCacheDir(), "chan_" + id + ".jpg");
                    if (!f.exists()) Net.download(url, f);
                    final Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
                    if (bmp != null) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                target.setImageBitmap(bmp);
                                letter.setVisibility(View.GONE);
                            }
                        });
                    }
                } catch (Exception ignored) {
                    // sem internet: fica a letra inicial
                }
            }
        }).start();
    }

    /** Abre o canal no app do YouTube da TV (ou o que houver). */
    private void openChannel(String q, String id) {
        prefs.edit().putInt(CH_USE_PREFIX + q, prefs.getInt(CH_USE_PREFIX + q, 0) + 1).apply();
        String url;
        if (!id.isEmpty()) {
            url = "https://www.youtube.com/channel/" + id;
        } else if (q.startsWith("@")) {
            url = "https://www.youtube.com/" + Uri.encode(q, "@");
        } else {
            url = "https://www.youtube.com/results?search_query=" + Uri.encode(q);
        }
        Intent base = new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        String[] pkgs = {"com.google.android.youtube.tv", "com.google.android.youtube"};
        for (String p : pkgs) {
            try {
                startActivity(new Intent(base).setPackage(p));
                return;
            } catch (Exception ignored) {
                // tenta o próximo
            }
        }
        try {
            startActivity(base);
        } catch (Exception ex) {
            Toast.makeText(this, "Não foi possível abrir o YouTube", Toast.LENGTH_LONG).show();
        }
    }

    /** Descobre id, nome e foto dos canais que ainda não foram consultados (precisa da chave do YouTube). */
    private void fetchChannels() {
        if (Config.YOUTUBE_API_KEY.isEmpty() || chBusy) return;
        chBusy = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject all = channelInfoAll();
                    boolean changed = false;
                    for (String q : channelList()) {
                        if (all.has(q)) continue;
                        try {
                            all.put(q, resolveChannel(q));
                            changed = true;
                        } catch (Exception ignored) {
                            // tenta de novo mais tarde
                        }
                    }
                    if (changed) {
                        prefs.edit().putString(KEY_CH_INFO, all.toString()).apply();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                render();
                            }
                        });
                    }
                } finally {
                    chBusy = false;
                }
            }
        }).start();
    }

    private JSONObject resolveChannel(String q) throws Exception {
        String key = Config.YOUTUBE_API_KEY;
        String url;
        if (q.startsWith("@")) {
            url = "https://www.googleapis.com/youtube/v3/channels?part=snippet&forHandle="
                    + Uri.encode(q) + "&key=" + key;
        } else {
            url = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=channel&maxResults=1&q="
                    + Uri.encode(q) + "&key=" + key;
        }
        JSONArray items = new JSONObject(Net.get(url)).optJSONArray("items");
        JSONObject out = new JSONObject();
        if (items == null || items.length() == 0) {
            // canal não encontrado: guarda vazio para não consultar de novo
            out.put("id", "");
            out.put("title", "");
            out.put("thumb", "");
            return out;
        }
        JSONObject it = items.getJSONObject(0);
        JSONObject sn = it.getJSONObject("snippet");
        String id = q.startsWith("@") ? it.getString("id") : sn.getString("channelId");
        String thumb = "";
        JSONObject th = sn.optJSONObject("thumbnails");
        if (th != null) {
            JSONObject m = th.optJSONObject("medium");
            if (m == null) m = th.optJSONObject("default");
            if (m != null) thumb = m.optString("url", "");
        }
        out.put("id", id);
        out.put("title", sn.optString("title", q));
        out.put("thumb", thumb);
        return out;
    }

    private void showChannelsDialog() {
        final String[] items = {"Adicionar canal", "Remover canal", "Restaurar lista inicial"};
        new AlertDialog.Builder(this)
                .setTitle("Meus canais")
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) askNewChannel();
                        else if (which == 1) askRemoveChannel();
                        else restoreChannels();
                    }
                })
                .show();
    }

    private void askNewChannel() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("@nomedocanal (ou o nome do canal)");
        new AlertDialog.Builder(this)
                .setTitle("Adicionar canal")
                .setView(input)
                .setPositiveButton("Adicionar", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        addChannel(input.getText().toString().trim());
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void addChannel(String q) {
        if (q.isEmpty()) return;
        List<String> list = channelList();
        for (String s : list) {
            if (s.equalsIgnoreCase(q)) {
                Toast.makeText(this, "Esse canal já está na lista", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        list.add(q);
        saveChannelList(list);
        chTry = 0;
        fetchChannels();
        render();
        Toast.makeText(this, "Canal adicionado", Toast.LENGTH_SHORT).show();
    }

    private void askRemoveChannel() {
        final List<String> list = channelList();
        if (list.isEmpty()) {
            Toast.makeText(this, "A lista de canais está vazia", Toast.LENGTH_SHORT).show();
            return;
        }
        JSONObject all = channelInfoAll();
        String[] names = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            JSONObject info = all.optJSONObject(list.get(i));
            String t = info != null ? info.optString("title", "") : "";
            names[i] = t.isEmpty() ? list.get(i) : t + " (" + list.get(i) + ")";
        }
        new AlertDialog.Builder(this)
                .setTitle("Remover qual canal?")
                .setItems(names, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        list.remove(which);
                        saveChannelList(list);
                        render();
                        Toast.makeText(MainActivity.this, "Canal removido", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void restoreChannels() {
        prefs.edit().remove(KEY_CH_LIST).apply();
        chTry = 0;
        fetchChannels();
        render();
        Toast.makeText(this, "Lista inicial restaurada", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------ cidade do clima

    private String cityName() {
        return prefs.getString(KEY_CITY, Config.CITY);
    }

    private String cityLat() {
        return prefs.getString(KEY_LAT, String.valueOf(Config.LATITUDE));
    }

    private String cityLon() {
        return prefs.getString(KEY_LON, String.valueOf(Config.LONGITUDE));
    }

    private void showCityDialog() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Ex.: Petrópolis");
        new AlertDialog.Builder(this)
                .setTitle("Cidade do clima (agora: " + cityName() + ")")
                .setView(input)
                .setPositiveButton("Buscar", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        searchCity(input.getText().toString().trim());
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void searchCity(final String query) {
        if (query.isEmpty()) return;
        Toast.makeText(this, "Buscando " + query + "...", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<String> labels = new ArrayList<>();
                final List<String[]> data = new ArrayList<>();
                boolean failed = false;
                try {
                    String url = "https://geocoding-api.open-meteo.com/v1/search?name=" + Uri.encode(query)
                            + "&count=6&language=pt&format=json";
                    JSONArray res = new JSONObject(Net.get(url)).optJSONArray("results");
                    if (res != null) {
                        for (int i = 0; i < res.length(); i++) {
                            JSONObject r = res.getJSONObject(i);
                            String name = r.optString("name", "");
                            if (name.isEmpty()) continue;
                            String admin = r.optString("admin1", "");
                            String country = r.optString("country", "");
                            labels.add(name + (admin.isEmpty() ? "" : ", " + admin)
                                    + (country.isEmpty() ? "" : " · " + country));
                            data.add(new String[]{name,
                                    String.valueOf(r.getDouble("latitude")),
                                    String.valueOf(r.getDouble("longitude"))});
                        }
                    }
                } catch (Exception ex) {
                    failed = true;
                }
                final boolean err = failed;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (err) {
                            Toast.makeText(MainActivity.this,
                                    "Não foi possível buscar. Verifique a internet.", Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (labels.isEmpty()) {
                            Toast.makeText(MainActivity.this,
                                    "Nenhuma cidade encontrada para " + query, Toast.LENGTH_LONG).show();
                            return;
                        }
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("Escolha a cidade")
                                .setItems(labels.toArray(new String[0]),
                                        new android.content.DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(android.content.DialogInterface d, int which) {
                                                String[] c = data.get(which);
                                                saveCity(c[0], c[1], c[2]);
                                            }
                                        })
                                .setNegativeButton("Cancelar", null)
                                .show();
                    }
                });
            }
        }).start();
    }

    private void saveCity(String name, String lat, String lon) {
        weatherEpoch++;
        prefs.edit()
                .putString(KEY_CITY, name)
                .putString(KEY_LAT, lat)
                .putString(KEY_LON, lon)
                .remove(KEY_WEATHER)
                .remove(KEY_WEATHER_TIME)
                .apply();
        cityView.setText(name);
        updateWeatherViews();
        weatherTry = 0;
        if (!weatherBusy) {
            weatherTry = System.currentTimeMillis();
            fetchWeather();
        }
        Toast.makeText(this, "Cidade alterada para " + name, Toast.LENGTH_SHORT).show();
    }

    /** Pede ao Android para usar a Tela Limpa como tela inicial (quem confirma é você). */
    private void requestDefaultHome() {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                RoleManager rm = (RoleManager) getSystemService(Context.ROLE_SERVICE);
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)
                        && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 1);
                    return;
                }
            } catch (Exception ignored) {
                // cai para as opções abaixo
            }
        }
        try {
            startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
            return;
        } catch (Exception ignored) {
            // esta TV não tem essa tela
        }
        showDefaultHelp();
    }

    private void showDefaultHelp() {
        StringBuilder others = new StringBuilder();
        List<ResolveInfo> homes = getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
        for (ResolveInfo ri : homes) {
            String p = ri.activityInfo.packageName;
            if (!p.equals(getPackageName())) others.append("\n• ").append(p);
        }
        String msg = "1) Aperte o botão Home do controle e, se a TV perguntar, escolha "
                + "\"Tela Limpa\" e \"Sempre\".\n\n"
                + "2) Pelo computador (ADB):\n"
                + "adb shell cmd package set-home-activity com.rogerio.telalimpa/.MainActivity\n\n"
                + "Launcher(s) nativo(s) desta TV:"
                + (others.length() == 0 ? " nenhum encontrado" : others.toString()) + "\n\n"
                + "Desativar o nativo é opcional. Só faça depois de confirmar que a Tela Limpa "
                + "funciona como tela inicial:\n"
                + "adb shell pm disable-user --user 0 NOME_DO_PACOTE\n"
                + "Para desfazer: adb shell pm enable NOME_DO_PACOTE";
        new AlertDialog.Builder(this)
                .setTitle("Tornar a Tela Limpa a tela inicial")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show();
    }

    private void roundCorners(View v, final int radiusDp) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(radiusDp));
            }
        });
        v.setClipToOutline(true);
    }

    /** Fundo escuro translúcido para ícones que não têm banner. */
    private Drawable cardBackground() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xB3141A26);
        g.setCornerRadius(dp(12));
        return g;
    }

    /** Dá um leve "zoom" no item que está com o foco do controle remoto. */
    private void addFocusZoom(View v) {
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
                float s = hasFocus ? 1.05f : 1f;
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
            case KIND_DEFAULT:
                requestDefaultHome();
                break;
            case KIND_CITY:
                showCityDialog();
                break;
            case KIND_CHANNELS:
                showChannelsDialog();
                break;
            case KIND_ORGANIZE:
                movingPkg = null;
                manageMode = !manageMode;
                render();
                break;
            default:
                if (manageMode) {
                    showAppOptions(e);
                } else {
                    launch(e);
                }
        }
    }

    /** Organizar: menu com as opções do app. */
    private void showAppOptions(final Entry e) {
        final String[] items = {
                "Mover (mudar de posição)",
                e.favorite ? "Remover dos favoritos" : "Adicionar aos favoritos",
                e.hidden ? "Mostrar na tela" : "Ocultar da tela"
        };
        new AlertDialog.Builder(this)
                .setTitle(e.label)
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) {
                            movingPkg = e.pkg;
                            render();
                        } else if (which == 1) {
                            toggleFavorite(e);
                        } else {
                            toggleHidden(e);
                        }
                    }
                })
                .show();
    }

    private void toggleFavorite(Entry e) {
        Set<String> favs = stringSet(KEY_FAVS);
        Set<String> hid = stringSet(KEY_HIDDEN);
        if (favs.contains(e.pkg)) {
            favs.remove(e.pkg);
        } else {
            favs.add(e.pkg);
            hid.remove(e.pkg);
        }
        saveFlags(e, favs, hid);
    }

    private void toggleHidden(Entry e) {
        Set<String> favs = stringSet(KEY_FAVS);
        Set<String> hid = stringSet(KEY_HIDDEN);
        if (hid.contains(e.pkg)) {
            hid.remove(e.pkg);
        } else {
            hid.add(e.pkg);
            favs.remove(e.pkg);
        }
        saveFlags(e, favs, hid);
    }

    private void saveFlags(Entry e, Set<String> favs, Set<String> hid) {
        prefs.edit().putStringSet(KEY_FAVS, favs).putStringSet(KEY_HIDDEN, hid).apply();
        e.favorite = favs.contains(e.pkg);
        e.hidden = hid.contains(e.pkg);
        render();
    }

    /** Enquanto um app está sendo movido, as setas mudam o lugar dele e OK confirma. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (manageMode && movingPkg != null) {
            int delta;
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    delta = -1;
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    delta = 1;
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    delta = -columns;
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    delta = columns;
                    break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BACK:
                    if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                        movingPkg = null;
                        render();
                    }
                    return true;
                default:
                    return super.dispatchKeyEvent(event);
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN) moveApp(delta);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void moveApp(int delta) {
        int from = -1;
        for (int i = 0; i < apps.size(); i++) {
            if (apps.get(i).pkg.equals(movingPkg)) {
                from = i;
                break;
            }
        }
        if (from < 0) return;
        int to = Math.max(0, Math.min(apps.size() - 1, from + delta));
        if (to == from) return;
        Entry moved = apps.remove(from);
        apps.add(to, moved);
        StringBuilder sb = new StringBuilder();
        for (Entry x : apps) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(x.pkg);
        }
        prefs.edit().putString(KEY_ORDER, sb.toString()).apply();
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

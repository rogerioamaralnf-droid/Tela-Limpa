package com.rogerio.telalimpa;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Launcher minimalista para Android TV: relógio + grade de apps.
 * Sem anúncios, sem recomendações, sem rede, sem serviços em segundo plano.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "tela_limpa";
    private static final String KEY_HIDDEN = "hidden_packages";
    private static final int COLUMNS = 6;

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
    }

    private SharedPreferences prefs;
    private GridView grid;
    private final List<Entry> shown = new ArrayList<>();
    private TileAdapter adapter;
    private boolean manageMode = false;

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            reload();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        // Margens generosas por causa do overscan de algumas TVs
        root.setPadding(dp(56), dp(32), dp(56), dp(24));

        // Cabeçalho: hora + data
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.BOTTOM);

        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 56);
        clock.setTextColor(Color.WHITE);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        header.addView(clock);

        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE, d 'de' MMMM");
        date.setFormat12Hour("EEEE, d 'de' MMMM");
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        date.setTextColor(0xFFAAAAAA);
        date.setPadding(dp(24), 0, 0, dp(10));
        header.addView(date);

        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Grade de apps
        grid = new GridView(this);
        grid.setNumColumns(COLUMNS);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(dp(12));
        grid.setVerticalSpacing(dp(12));
        grid.setSelector(buildSelector());
        grid.setDrawSelectorOnTop(false);
        grid.setClipToPadding(false);
        grid.setPadding(0, dp(24), 0, 0);
        grid.setVerticalScrollBarEnabled(false);
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        grid.setFocusable(true);
        adapter = new TileAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> onTileClick(shown.get(position)));

        root.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
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
        grid.requestFocus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Apertar Home de novo volta ao início
        if (manageMode) {
            manageMode = false;
            reload();
        }
        grid.setSelection(0);
        grid.requestFocus();
    }

    @Override
    public void onBackPressed() {
        // Launcher não "fecha"; só sai do modo Organizar
        if (manageMode) {
            manageMode = false;
            reload();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            manageMode = !manageMode;
            reload();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---------------------------------------------------------------- dados

    private void reload() {
        PackageManager pm = getPackageManager();
        Set<String> hidden = hiddenSet();

        Map<String, Entry> byPkg = new LinkedHashMap<>();
        collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, byPkg, hidden);
        collect(pm, Intent.CATEGORY_LAUNCHER, byPkg, hidden);

        List<Entry> apps = new ArrayList<>(byPkg.values());
        Collections.sort(apps, (a, b) -> a.label.compareToIgnoreCase(b.label));

        shown.clear();
        for (Entry e : apps) {
            if (manageMode || !e.hidden) shown.add(e);
        }
        shown.add(special(KIND_SETTINGS, "Configurações", android.R.drawable.ic_menu_preferences));
        shown.add(special(KIND_ORGANIZE,
                manageMode ? "Concluir" : "Organizar",
                manageMode ? android.R.drawable.ic_menu_save : android.R.drawable.ic_menu_edit));
        adapter.notifyDataSetChanged();
    }

    private void collect(PackageManager pm, String category, Map<String, Entry> out, Set<String> hidden) {
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
            e.hidden = hidden.contains(pkg);
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

    private Set<String> hiddenSet() {
        return new HashSet<>(prefs.getStringSet(KEY_HIDDEN, Collections.<String>emptySet()));
    }

    private void toggleHidden(String pkg) {
        Set<String> s = hiddenSet();
        if (!s.remove(pkg)) s.add(pkg);
        prefs.edit().putStringSet(KEY_HIDDEN, s).apply();
    }

    // ---------------------------------------------------------------- ações

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
                reload();
                grid.setSelection(shown.size() - 1);
                break;
            default:
                if (manageMode) {
                    int sel = grid.getSelectedItemPosition();
                    toggleHidden(e.pkg);
                    reload();
                    if (sel >= 0) grid.setSelection(Math.min(sel, shown.size() - 1));
                } else {
                    launch(e);
                }
        }
    }

    private void launch(Entry e) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.setComponent(e.component);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(this, "Não foi possível abrir " + e.label, Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ UI

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private Drawable buildSelector() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x33FFFFFF);
        d.setStroke(dp(2), Color.WHITE);
        d.setCornerRadius(dp(14));
        return d;
    }

    private class TileAdapter extends BaseAdapter {
        private static final int ID_ICON = 1;
        private static final int ID_LABEL = 2;

        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView : newItem();
            Entry e = shown.get(position);
            ImageView icon = v.findViewById(ID_ICON);
            TextView label = v.findViewById(ID_LABEL);

            if (e.kind == KIND_APP) {
                if (e.icon == null) e.icon = e.info.loadIcon(getPackageManager());
                icon.setColorFilter(null);
                icon.setPadding(0, 0, 0, 0);
                icon.setImageDrawable(e.icon);
                label.setText(e.hidden ? e.label + " · oculto" : e.label);
                v.setAlpha(e.hidden ? 0.4f : 1f);
            } else {
                icon.setImageResource(e.iconRes);
                icon.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
                icon.setPadding(dp(18), dp(18), dp(18), dp(18));
                label.setText(e.label);
                v.setAlpha(1f);
            }
            return v;
        }

        private View newItem() {
            LinearLayout item = new LinearLayout(MainActivity.this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(dp(8), dp(14), dp(8), dp(10));
            item.setLayoutParams(new AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(148)));

            ImageView icon = new ImageView(MainActivity.this);
            icon.setId(ID_ICON);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            item.addView(icon, new LinearLayout.LayoutParams(dp(84), dp(84)));

            TextView label = new TextView(MainActivity.this);
            label.setId(ID_LABEL);
            label.setTextColor(Color.WHITE);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, dp(8), 0, 0);
            item.addView(label, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return item;
        }
    }
}

package com.ocpilot.app.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import com.ocpilot.app.MainActivity;
import com.ocpilot.app.R;
import com.ocpilot.app.core.Models;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class DiffScreen extends Screen {
    private final String project, session, wantedFile;
    private List<Models.Diff> files = new ArrayList<>();
    private List<String> lines = new ArrayList<>();
    private FileAdapter fileAdapter;
    private LineAdapter lineAdapter;
    private ListView lineList;
    private TextView empty;
    private android.widget.Button more, previous;
    private int selected, offset;
    public DiffScreen(MainActivity activity, String project, String session, String wantedFile) { super(activity); this.project = project; this.session = session; this.wantedFile = wantedFile; }
    @Override public View build(Context c) {
        LinearLayout body = Widgets.column(c); body.setPaddingRelative(Theme.dp(c, 16), 0, Theme.dp(c, 16), Theme.dp(c, 8));
        Widgets.add(body, Widgets.ghost(c, R.string.refresh, this::load)); empty = Widgets.muted(c, R.string.no_diff); Widgets.add(body, empty);
        ListView fileList = new ListView(c); fileList.setDivider(null); fileAdapter = new FileAdapter(); fileList.setAdapter(fileAdapter);
        fileList.setOnItemClickListener((p, v, index, id) -> { selected = index; offset = 0; select(); });
        body.addView(fileList, new LinearLayout.LayoutParams(-1, Theme.dp(c, 140)));
        HorizontalScrollView horizontal = new HorizontalScrollView(c); horizontal.setFillViewport(true);
        lineList = new ListView(c); lineList.setDivider(null); lineAdapter = new LineAdapter(); lineList.setAdapter(lineAdapter);
        horizontal.addView(lineList, new android.widget.FrameLayout.LayoutParams(Theme.dp(c, 800), ViewGroup.LayoutParams.MATCH_PARENT));
        body.addView(horizontal, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout controls = Widgets.row(c); previous = Widgets.ghost(c, R.string.show_earlier, () -> { offset = Math.max(0, offset - 2000); renderLines(); }); more = Widgets.ghost(c, R.string.show_more, () -> { offset += 2000; renderLines(); });
        Widgets.weighted(controls, previous); Widgets.weighted(controls, more); body.addView(controls); select();
        return shell(c, body, R.string.diff, true);
    }
    @Override public void onShow() { super.onShow(); load(); }
    private void load() { track(app.api.diff(project, session, callback(result -> {
        files = result; selected = Math.min(selected, Math.max(0, files.size() - 1));
        if (wantedFile != null) for (int i = 0; i < files.size(); i++) if (files.get(i).file.equals(wantedFile)) selected = i;
        offset = 0; fileAdapter.notifyDataSetChanged(); select();
    }))); }
    private void select() {
        if (lineAdapter == null) return; lines = new ArrayList<>();
        if (!files.isEmpty()) {
            String patch = files.get(selected).patch;
            if (!patch.isEmpty()) lines.addAll(Arrays.asList(patch.split("\r\n|\r|\n", -1)));
            empty.setText(patch.isEmpty() ? R.string.no_text_diff : R.string.no_diff); empty.setVisibility(patch.isEmpty() ? View.VISIBLE : View.GONE);
        } else { empty.setText(R.string.no_diff); empty.setVisibility(View.VISIBLE); }
        int length = 0; for (String line : lines) length = Math.max(length, Math.min(600, line.length()));
        ViewGroup.LayoutParams params = lineList.getLayoutParams(); params.width = Math.max(activity.getResources().getDisplayMetrics().widthPixels - Theme.dp(activity, 32), Theme.dp(activity, Math.min(4200, length * 8 + 32))); lineList.setLayoutParams(params);
        renderLines();
    }
    private void renderLines() { lineAdapter.notifyDataSetChanged(); more.setVisibility(offset + 2000 < lines.size() ? View.VISIBLE : View.GONE); previous.setVisibility(offset > 0 ? View.VISIBLE : View.GONE); lineList.setSelection(0); }
    private final class FileAdapter extends BaseAdapter {
        @Override public int getCount() { return files.size(); }
        @Override public Object getItem(int i) { return files.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View recycled, ViewGroup parent) {
            TextView view = recycled instanceof TextView ? (TextView) recycled : Widgets.mono(activity, ""); view.setTextIsSelectable(false); view.setMinHeight(Theme.dp(activity, 48)); view.setPaddingRelative(Theme.dp(activity, 8), Theme.dp(activity, 8), Theme.dp(activity, 8), Theme.dp(activity, 8));
            Models.Diff diff = files.get(i); view.setText(activity.getString(R.string.diff_file_label, diff.file, activity.getString(R.string.diff_counts, diff.additions, diff.deletions))); view.setTextColor(Theme.of(activity).accent2); return view;
        }
    }
    private final class LineAdapter extends BaseAdapter {
        @Override public int getCount() { return Math.min(2000, Math.max(0, lines.size() - offset)); }
        @Override public Object getItem(int i) { return lines.get(offset + i); }
        @Override public long getItemId(int i) { return offset + i; }
        @Override public View getView(int i, View recycled, ViewGroup parent) {
            TextView t = recycled instanceof TextView ? (TextView) recycled : Widgets.mono(activity, ""); String line = lines.get(offset + i); Theme palette = Theme.of(activity);
            t.setSingleLine(true); t.setText(line); t.setTypeface(Typeface.MONOSPACE); t.setTextColor(line.startsWith("+") && !line.startsWith("+++") ? palette.success : line.startsWith("-") && !line.startsWith("---") ? palette.error : palette.text);
            t.setBackgroundColor(line.startsWith("+") && !line.startsWith("+++") ? Theme.alpha(palette.success, 18) : line.startsWith("-") && !line.startsWith("---") ? Theme.alpha(palette.error, 18) : palette.background);
            t.setPaddingRelative(Theme.dp(activity, 8), Theme.dp(activity, 2), Theme.dp(activity, 8), Theme.dp(activity, 2)); return t;
        }
    }
}

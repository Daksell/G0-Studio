package ru.g0studio;

import static ru.g0studio.Texts.tr;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.InputType;
import android.text.Layout;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;

final class LibraryScreen {
    interface Host {
        Protocol.Preset preset();
        String presetName();
        int activeSlot();
        int mode();
        boolean connected();
        void previewPreset(Protocol.Preset data, String name);
        void applyPreset(Protocol.Preset data, String name, boolean store);
        void connect();
        void disconnect();
        void reload();
        void reset();
        String logText();
        void openTiming();
        String language();
        void language(String code);
        void refreshPage();
    }

    private static final int IMPORT = 21;
    private static final int EXPORT = 22;
    private static final int MAX_IMPORT_BYTES = 4 * 1024 * 1024;
    private static final String FILE_FORMAT = "g0studio-library-1";
    private static final String DEVICE = "Double G0 Pro";
    private static final String PRESETS = "presets";
    private static final String[] LANGUAGE_CODES = {"ru", "en", "fr"};
    private static final String[] LANGUAGE_NAMES = {"Русский", "English", "Français"};

    private final Activity activity;
    private final Host host;
    private final Ui ui;
    private final SharedPreferences preferences;
    private final String appVersion;
    private JSONArray presets;

    LibraryScreen(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
        ui = new Ui(activity);
        appVersion = version();
        // Activity-scoped preferences preserve the files saved by earlier releases.
        preferences = activity.getPreferences(0);
        try {
            presets = new JSONArray(preferences.getString("library", "[]"));
        } catch (JSONException invalidLibrary) {
            presets = new JSONArray();
        }
    }

    View libraryView() {
        LinearLayout content = content();
        content.addView(ui.heading(tr("Мои полные пресеты"), 20));
        LinearLayout toolbar = ui.row();
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setBaselineAligned(false);
        Button fromCurrent = ui.button(tr("＋ Из текущего"), this::saveCurrent);
        fromCurrent.setTextSize(11);
        fromCurrent.setIncludeFontPadding(false);
        fromCurrent.setMaxLines(2);
        fromCurrent.setPadding(ui.dp(5), 0, ui.dp(5), 0);
        boolean hasCurrent = host.preset() != null;
        fromCurrent.setEnabled(hasCurrent);
        if (hasCurrent) ui.styleButton(fromCurrent, true);
        LinearLayout.LayoutParams captureSize = flexible();
        captureSize.weight = 1.32f;
        toolbar.addView(fromCurrent, captureSize);
        LinearLayout.LayoutParams importSize = flexible();
        importSize.setMarginStart(ui.dp(8));
        toolbar.addView(ui.button(tr("Импорт"), this::importFile), importSize);
        LinearLayout.LayoutParams exportSize = flexible();
        exportSize.setMarginStart(ui.dp(8));
        Button export = ui.button(tr("Экспорт"), this::exportFile);
        export.setEnabled(presets.length() > 0);
        toolbar.addView(export, exportSize);
        ui.add(content, toolbar, 12);

        int visible = 0;
        for (int index = presets.length() - 1; index >= 0; index--) {
            JSONObject entry = presets.optJSONObject(index);
            if (entry == null || !entry.has("raw")) continue;
            ui.add(content, presetRow(entry), visible++ == 0 ? 18 : 8);
        }
        if (visible == 0) ui.add(content, emptyLibrary(), 18);
        return scroll(content);
    }

    View settingsView() {
        LinearLayout content = content();
        boolean connected = host.connected();
        ui.add(content, actionRow("connect", tr("Подключить G0 Pro"), null, true, host::connect), 0);
        ui.add(content, actionRow("disconnect", tr("Отключить"), null, connected, host::disconnect), 9);
        ui.add(content, actionRow("reload", tr("Перезагрузка"), null, connected, host::reload), 9);
        ui.add(content, actionRow("reset", tr("Сброс к заводским настройкам"), null,
                connected, this::confirmReset), 9);
        ui.add(content, actionRow("log", tr("Журнал обмена"), null, true, this::showLog), 9);
        if (BuildVariant.TIMING_DIAGNOSTICS) {
            ui.add(content, actionRow("timing", tr("Тест задержек"), null, true, host::openTiming), 9);
        }
        ui.add(content, actionRow("language", tr("Язык"), languageName(), true, this::chooseLanguage), 9);
        ui.add(content, actionRow("info", tr("О версии"), null, true, this::showAbout), 9);
        return scroll(content);
    }

    private View actionRow(String icon, String title, String detail, boolean enabled, Runnable action) {
        int iconWidth = ui.dp(23);
        int iconGap = ui.dp(10);
        int sideWidth = iconWidth + iconGap;
        TextView caption = ui.text(title, 15, enabled ? Ui.TEXT : Ui.MUTED);
        Glyph symbol = new Glyph(icon, enabled ? Ui.ACCENT : Ui.MUTED);
        LinearLayout row = new LinearLayout(activity) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                if (MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED) {
                    int textWidth = Math.max(1, MeasureSpec.getSize(widthSpec)
                            - getPaddingLeft() - getPaddingRight() - 2 * sideWidth);
                    if (caption.getMaxWidth() != textWidth) caption.setMaxWidth(textWidth);
                }
                super.onMeasure(widthSpec, heightSpec);
            }

            @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                super.onLayout(changed, left, top, right, bottom);
                Layout lines = caption.getLayout();
                float inset = 0;
                if (lines != null && lines.getLineCount() > 1) {
                    inset = lines.getLineLeft(0);
                    for (int line = 1; line < lines.getLineCount(); line++) {
                        inset = Math.min(inset, lines.getLineLeft(line));
                    }
                }
                symbol.setTranslationX(Math.max(0, inset));
            }
        };
        row.setGravity(Gravity.CENTER);
        row.setBaselineAligned(false);
        row.setMinimumHeight(ui.dp(49));
        row.setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6));
        row.setBackground(ui.pressable(Ui.SURFACE, 0, 14));
        row.setEnabled(enabled);
        row.setOnClickListener(enabled ? view -> action.run() : null);
        row.setFocusable(enabled);
        row.setContentDescription(detail == null ? title : title + ", " + detail);
        LinearLayout.LayoutParams iconSize = new LinearLayout.LayoutParams(iconWidth, iconWidth);
        iconSize.setMarginEnd(iconGap);
        row.addView(symbol, iconSize);
        LinearLayout words = ui.column();
        words.setGravity(Gravity.CENTER);
        caption.setGravity(Gravity.CENTER);
        caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        words.addView(caption, new LinearLayout.LayoutParams(-2, -2));
        if (detail != null) {
            TextView subtitle = ui.text(detail, 11, Ui.MUTED);
            subtitle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            words.addView(subtitle, new LinearLayout.LayoutParams(-2, -2));
        }
        row.addView(words, new LinearLayout.LayoutParams(-2, -2));
        // Reserve the same space at both ends so only the text determines its center.
        row.addView(new Space(activity), new LinearLayout.LayoutParams(sideWidth, 1));
        return row;
    }

    void saveCurrent() {
        Protocol.Preset current = host.preset();
        if (current == null) {
            toast(tr("Сначала прочитай настройки датчика"));
            return;
        }
        Protocol.Preset snapshot = current.copy();
        int mode = host.mode();
        String currentName = host.presetName();
        askName(currentName == null || currentName.trim().isEmpty() ? tr("Мой звук") : currentName,
                name -> {
                    if (addPreset(snapshot, name, mode)) {
                        toast(tr("Полный пресет сохранён в телефоне"));
                        host.refreshPage();
                    }
                });
    }

    void backupPreset(Protocol.Preset data, String name) {
        if (data != null) addPreset(data, name, host.mode());
    }

    private boolean addPreset(Protocol.Preset data, String name, int mode) {
        try {
            Protocol.Preset snapshot = data.copy();
            snapshot.syncMask();
            JSONObject entry = new JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("name", name)
                    .put("format", 1)
                    .put("device", DEVICE)
                    .put("raw", Protocol.hex(snapshot.data))
                    .put("mask", snapshot.mask)
                    .put("mode", mode)
                    .put("micReverb", snapshot.micReverb == null ? "" : Protocol.hex(snapshot.micReverb));
            presets.put(entry);
            persist();
            return true;
        } catch (JSONException | IllegalArgumentException error) {
            toast(tr(error.getMessage()));
            return false;
        }
    }

    private void persist() {
        preferences.edit().putString("library", presets.toString()).apply();
    }

    private Protocol.Preset decode(JSONObject entry) throws JSONException {
        if (entry.optInt("format") != 1 || !DEVICE.equals(entry.optString("device"))) {
            throw new JSONException("Несовместимый формат пресета");
        }
        String raw = entry.getString("raw");
        if (raw.length() != 650) throw new JSONException("Несовместимый формат пресета");
        Protocol.Preset preset = new Protocol.Preset(Protocol.unhex(raw));
        preset.mask = entry.optInt("mask", preset.mask) & 15;
        String reverb = entry.optString("micReverb");
        if (!reverb.isEmpty()) {
            if (reverb.length() != 26) throw new JSONException("Неверный микрофонный эффект");
            preset.micReverb = Protocol.unhex(reverb);
        }
        return preset;
    }

    private View presetRow(JSONObject preset) {
        String name = displayName(preset);
        return settingRow("preset", name, null, Ui.ACCENT, true, () -> chooseAction(preset));
    }

    private String displayName(JSONObject preset) {
        String name = preset.optString("name").trim();
        return name.isEmpty() ? tr("Без имени") : name;
    }

    private void chooseAction(JSONObject entry) {
        String[] labels = {
                tr("Открыть для просмотра"), tr("Применить к датчику"),
                tr("Записать в текущий быстрый слот"), tr("Переименовать"),
                tr("Копировать"), tr("Удалить")
        };
        new AlertDialog.Builder(activity).setTitle(displayName(entry))
                .setItems(labels, (dialog, action) -> {
                    try {
                        switch (action) {
                            case 0:
                                host.previewPreset(decode(entry), displayName(entry));
                                break;
                            case 1:
                            case 2:
                                confirmApply(entry, action == 2);
                                break;
                            case 3:
                                askName(displayName(entry), name -> rename(entry, name));
                                break;
                            case 4:
                                askName(displayName(entry) + tr(" · копия"), name -> duplicate(entry, name));
                                break;
                            case 5:
                                confirmDelete(entry);
                                break;
                            default:
                                break;
                        }
                    } catch (JSONException | IllegalArgumentException invalid) {
                        toast(tr(invalid.getMessage()));
                    }
                }).setNegativeButton(tr("Отмена"), null).show();
    }

    private void rename(JSONObject entry, String name) {
        try {
            entry.put("name", name);
            persist();
            host.refreshPage();
        } catch (JSONException error) {
            toast(tr(error.getMessage()));
        }
    }

    private void duplicate(JSONObject entry, String name) {
        try {
            JSONObject copy = new JSONObject(entry.toString())
                    .put("id", UUID.randomUUID().toString())
                    .put("name", name);
            presets.put(copy);
            persist();
            host.refreshPage();
        } catch (JSONException error) {
            toast(tr(error.getMessage()));
        }
    }

    private void confirmDelete(JSONObject entry) {
        new AlertDialog.Builder(activity).setTitle(tr("Удалить"))
                .setMessage(tr("Удалить «") + displayName(entry) + tr("» из телефона?"))
                .setPositiveButton(tr("Удалить"), (dialog, button) -> {
                    for (int position = 0; position < presets.length(); position++) {
                        if (presets.optJSONObject(position) == entry) {
                            presets.remove(position);
                            persist();
                            host.refreshPage();
                            return;
                        }
                    }
                }).setNegativeButton(tr("Отмена"), null).show();
    }

    private void confirmApply(JSONObject entry, boolean store) throws JSONException {
        int slot = host.activeSlot();
        if (!host.connected() || slot < 1 || slot > 4) {
            toast(tr("Подключись и выбери аппаратный слот 1–4"));
            return;
        }
        Protocol.Preset source = decode(entry);
        String title = store ? tr("Запись в слот ") + slot : tr("Применить полный пресет");
        String message = store
                ? tr("Текущий слот будет перезаписан. Предыдущее состояние сохранится в резервной копии телефона. ")
                : tr("Изменится текущий звук датчика. ");
        if (host.mode() == 1) message += tr("В режиме корпуса микрофонные настройки не отправляются. ");
        new AlertDialog.Builder(activity).setTitle(title).setMessage(message.trim())
                .setPositiveButton(tr(store ? "Записать" : "Применить"), (dialog, button) -> {
                    if (host.connected() && host.activeSlot() == slot) {
                        host.applyPreset(source, displayName(entry), store);
                    } else {
                        toast(tr("Выбери и прочитай нужный быстрый слот."));
                    }
                }).setNegativeButton(tr("Отмена"), null).show();
    }

    private void askName(String initial, Consumer<String> action) {
        EditText name = new EditText(activity);
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setSelectAllOnFocus(true);
        name.setText(initial);
        LinearLayout input = ui.column();
        input.setPadding(ui.dp(23), ui.dp(10), ui.dp(23), 0);
        input.addView(name, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(tr("Название пресета"))
                .setView(input).setPositiveButton(tr("Сохранить"), null)
                .setNegativeButton(tr("Отмена"), null).create();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        dialog.setOnShowListener(ignored -> {
            name.requestFocus();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
                String result = name.getText().toString().trim();
                if (result.isEmpty()) {
                    name.setError(tr("Введи название"));
                    return;
                }
                dialog.dismiss();
                action.accept(result);
            });
        });
        dialog.show();
    }

    private void chooseLanguage() {
        int selected = 0;
        for (int i = 0; i < LANGUAGE_CODES.length; i++) {
            if (LANGUAGE_CODES[i].equals(host.language())) selected = i;
        }
        new AlertDialog.Builder(activity).setTitle(tr("Язык"))
                .setSingleChoiceItems(LANGUAGE_NAMES, selected, (dialog, choice) -> {
                    dialog.dismiss();
                    host.language(LANGUAGE_CODES[choice]);
                }).setNegativeButton(tr("Отмена"), null).show();
    }

    private String languageName() {
        for (int i = 0; i < LANGUAGE_CODES.length; i++) {
            if (LANGUAGE_CODES[i].equals(host.language())) return LANGUAGE_NAMES[i];
        }
        return LANGUAGE_NAMES[0];
    }

    private void confirmReset() {
        if (!host.connected()) return;
        new AlertDialog.Builder(activity).setTitle(tr("Сбросить настройки G0 Pro?"))
                .setMessage(tr("Эквалайзеры и эффекты на датчике вернутся к заводским настройкам. Сохранённые в телефоне пресеты останутся."))
                .setPositiveButton(tr("Сбросить"), (dialog, button) -> {
                    if (host.connected()) host.reset();
                }).setNegativeButton(tr("Отмена"), null).show();
    }

    private void showLog() {
        String log = host.logText();
        TextView eventText = ui.text(log == null || log.isEmpty() ? "—" : log, 13, Ui.TEXT);
        eventText.setTypeface(Typeface.MONOSPACE);
        eventText.setTextIsSelectable(true);
        eventText.setPadding(ui.dp(23), ui.dp(12), ui.dp(23), ui.dp(12));
        ScrollView viewer = new ScrollView(activity);
        viewer.addView(eventText);
        new AlertDialog.Builder(activity).setTitle(tr("Последние события")).setView(viewer)
                .setPositiveButton(tr("ОК"), null).show();
    }

    private void showAbout() {
        new AlertDialog.Builder(activity).setTitle("G0 Studio " + appVersion)
                .setMessage(tr("Контроллер Double G0 Pro"))
                .setPositiveButton(tr("ОК"), null).show();
    }

    private String version() {
        try {
            return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException unavailable) {
            return "";
        }
    }

    private void importFile() {
        openFilePicker(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json")
                .addCategory(Intent.CATEGORY_OPENABLE), IMPORT);
    }

    private void exportFile() {
        openFilePicker(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json")
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_TITLE, "G0-Studio-presets.json"), EXPORT);
    }

    private void openFilePicker(Intent picker, int request) {
        try {
            activity.startActivityForResult(picker, request);
        } catch (android.content.ActivityNotFoundException unavailable) {
            toast(tr("Файл: ") + tr("Ошибка"));
        }
    }

    void onActivityResult(int request, int result, Intent data) {
        if ((request != IMPORT && request != EXPORT) || result != Activity.RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        String snapshot = request == EXPORT ? presets.toString() : null;
        new Thread(() -> {
            try {
                if (request == IMPORT) {
                    JSONArray imported = readLibrary(uri);
                    onUi(() -> {
                        for (int i = 0; i < imported.length(); i++) presets.put(imported.optJSONObject(i));
                        persist();
                        host.refreshPage();
                        toast(tr("Пресеты импортированы"));
                    });
                } else {
                    writeLibrary(uri, snapshot);
                    onUi(() -> toast(tr("Экспорт готов")));
                }
            } catch (IOException | JSONException | IllegalArgumentException | SecurityException error) {
                onUi(() -> toast(tr("Файл: ") + tr(error.getMessage() == null
                        ? "Ошибка" : error.getMessage())));
            }
        }, "g0-library-file").start();
    }

    private JSONArray readLibrary(Uri uri) throws IOException, JSONException {
        byte[] file;
        try (InputStream input = activity.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IOException("Ошибка");
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1;) {
                if (output.size() + count > MAX_IMPORT_BYTES) throw new IOException("Файл слишком большой");
                output.write(buffer, 0, count);
            }
            file = output.toByteArray();
        }
        JSONObject document = new JSONObject(new String(file, StandardCharsets.UTF_8));
        if (!FILE_FORMAT.equals(document.optString("format"))) {
            throw new JSONException("Не файл библиотеки G0 Studio");
        }
        JSONArray imported = document.getJSONArray(PRESETS);
        for (int i = 0; i < imported.length(); i++) {
            JSONObject entry = imported.getJSONObject(i);
            decode(entry);
            entry.put("id", UUID.randomUUID().toString());
        }
        return imported;
    }

    private void writeLibrary(Uri uri, String snapshot) throws IOException, JSONException {
        JSONObject document = new JSONObject().put("format", FILE_FORMAT)
                .put(PRESETS, new JSONArray(snapshot));
        byte[] bytes = document.toString(2).getBytes(StandardCharsets.UTF_8);
        try (OutputStream output = activity.getContentResolver().openOutputStream(uri, "wt")) {
            if (output == null) throw new IOException("Ошибка");
            output.write(bytes);
        }
    }

    private void onUi(Runnable action) {
        activity.runOnUiThread(() -> {
            if (!activity.isFinishing() && !activity.isDestroyed()) action.run();
        });
    }

    private View emptyLibrary() {
        LinearLayout empty = ui.panel();
        empty.setGravity(Gravity.CENTER_HORIZONTAL);
        empty.setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(20));
        empty.addView(new Glyph("preset", Ui.ACCENT), square(29));
        TextView title = ui.text(tr("Пока нет сохранённых пресетов"), 16, Ui.TEXT);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        ui.add(empty, title, 8);
        TextView hint = ui.text(tr("Подключи датчик, выбери звук и нажми «Из текущего»."), 13, Ui.MUTED);
        hint.setGravity(Gravity.CENTER);
        ui.add(empty, hint, 6);
        return empty;
    }

    private View settingRow(String icon, String title, String detail, int highlight,
                            boolean enabled, Runnable action) {
        LinearLayout row = ui.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(ui.dp(49));
        row.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
        row.setBackground(ui.pressable(Ui.SURFACE, 0, 14));
        row.setEnabled(enabled);
        row.setOnClickListener(enabled ? view -> action.run() : null);
        row.setFocusable(enabled);
        Glyph symbol = new Glyph(icon, enabled ? highlight : Ui.MUTED);
        row.addView(symbol, square(28));
        LinearLayout copy = ui.column();
        TextView primary = ui.text(title, 15, enabled ? Ui.TEXT : Ui.MUTED);
        primary.setMaxLines(2);
        primary.setEllipsize(TextUtils.TruncateAt.END);
        copy.addView(primary);
        if (detail != null && !detail.isEmpty()) copy.addView(ui.text(detail, 12, Ui.MUTED));
        LinearLayout.LayoutParams copySize = new LinearLayout.LayoutParams(0, -2, 1);
        copySize.setMarginStart(ui.dp(12));
        copySize.setMarginEnd(ui.dp(8));
        row.addView(copy, copySize);
        if (enabled) row.addView(new Glyph("arrow", Ui.MUTED), square(25));
        return row;
    }

    private LinearLayout content() {
        LinearLayout page = ui.column();
        page.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(20));
        return page;
    }

    private View scroll(LinearLayout child) {
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(child, new ScrollView.LayoutParams(-1, -2));
        return scroll;
    }

    private LinearLayout.LayoutParams flexible() {
        return new LinearLayout.LayoutParams(0, ui.dp(46), 1);
    }

    private LinearLayout.LayoutParams square(int dp) {
        return new LinearLayout.LayoutParams(ui.dp(dp), ui.dp(dp));
    }

    private void toast(String text) {
        Toast.makeText(activity, text == null || text.isEmpty() ? tr("Ошибка") : text,
                Toast.LENGTH_SHORT).show();
    }

    private final class Glyph extends View {
        private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final String icon;

        Glyph(String icon, int color) {
            super(activity);
            this.icon = icon;
            ink.setColor(color);
            ink.setStyle(Paint.Style.STROKE);
            ink.setStrokeWidth(1.7f);
            ink.setStrokeCap(Paint.Cap.ROUND);
            ink.setStrokeJoin(Paint.Join.ROUND);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float size = Math.min(ui.dp(24), Math.min(getWidth(), getHeight()));
            canvas.save();
            canvas.translate((getWidth() - size) / 2, (getHeight() - size) / 2);
            canvas.scale(size / 24, size / 24);
            path.reset();
            switch (icon) {
                case "language":
                    canvas.drawCircle(12, 12, 8.5f, ink);
                    canvas.drawOval(8, 3.5f, 16, 20.5f, ink);
                    canvas.drawLine(3.5f, 12, 20.5f, 12, ink);
                    break;
                case "connect":
                    path.moveTo(6, 5.5f);
                    path.lineTo(17, 17.5f);
                    path.lineTo(11.5f, 22);
                    path.lineTo(11.5f, 2);
                    path.lineTo(17, 6.5f);
                    path.lineTo(6, 18.5f);
                    canvas.drawPath(path, ink);
                    break;
                case "disconnect":
                    path.moveTo(10, 7);
                    path.lineTo(10, 4);
                    path.lineTo(20, 4);
                    path.lineTo(20, 20);
                    path.lineTo(10, 20);
                    path.lineTo(10, 17);
                    path.moveTo(7.3f, 8.5f);
                    path.lineTo(3.8f, 12);
                    path.lineTo(7.3f, 15.5f);
                    path.moveTo(4, 12);
                    path.lineTo(14, 12);
                    canvas.drawPath(path, ink);
                    break;
                case "reload":
                    canvas.drawArc(4.2f, 4.2f, 19.8f, 19.8f, 211, 132, false, ink);
                    canvas.drawArc(4.2f, 4.2f, 19.8f, 19.8f, 31, 132, false, ink);
                    path.moveTo(19.4f, 5);
                    path.lineTo(19.4f, 10);
                    path.lineTo(14.4f, 10);
                    path.moveTo(4.6f, 19);
                    path.lineTo(4.6f, 14);
                    path.lineTo(9.6f, 14);
                    canvas.drawPath(path, ink);
                    break;
                case "log":
                    ink.setStrokeWidth(1.4f);
                    canvas.drawRect(3.4f, 5.4f, 7.1f, 10.1f, ink);
                    canvas.drawRect(10.2f, 13.9f, 13.9f, 18.6f, ink);
                    canvas.drawRect(16.9f, 5.4f, 20.6f, 10.1f, ink);
                    path.moveTo(10.5f, 7);
                    path.lineTo(12.7f, 5.4f);
                    path.lineTo(12.7f, 10.1f);
                    path.moveTo(3.5f, 15.5f);
                    path.lineTo(5.7f, 13.9f);
                    path.lineTo(5.7f, 18.6f);
                    path.moveTo(17.7f, 15.5f);
                    path.lineTo(19.9f, 13.9f);
                    path.lineTo(19.9f, 18.6f);
                    canvas.drawPath(path, ink);
                    ink.setStrokeWidth(1.7f);
                    break;
                case "reset":
                    canvas.drawArc(4.5f, 4.5f, 19.5f, 19.5f, 210, 290, false, ink);
                    path.moveTo(4, 5.5f);
                    path.lineTo(4, 10);
                    path.lineTo(8.5f, 10);
                    canvas.drawPath(path, ink);
                    break;
                case "timing":
                    canvas.drawCircle(12, 13, 7.2f, ink);
                    canvas.drawLine(9, 2, 15, 2, ink);
                    canvas.drawLine(12, 2, 12, 6, ink);
                    canvas.drawLine(12, 9.5f, 12, 13, ink);
                    canvas.drawLine(12, 13, 15.2f, 14.5f, ink);
                    break;
                case "info":
                    canvas.drawCircle(12, 12, 8.5f, ink);
                    canvas.drawLine(12, 7.4f, 12, 13.5f, ink);
                    canvas.drawPoint(12, 16.4f, ink);
                    break;
                case "preset":
                    canvas.drawRoundRect(4, 5, 20, 19, 2, 2, ink);
                    for (int i = 0; i < 3; i++) {
                        float x = 8 + i * 4;
                        float y = 10.5f + i % 2 * 3;
                        canvas.drawLine(x, 8, x, 16, ink);
                        canvas.drawLine(x - 1, y, x + 1, y, ink);
                    }
                    break;
                default:
                    path.moveTo(9.5f, 7.5f);
                    path.lineTo(14, 12);
                    path.lineTo(9.5f, 16.5f);
                    canvas.drawPath(path, ink);
                    break;
            }
            canvas.restore();
        }
    }
}

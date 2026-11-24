package ru.g0studio;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import static ru.g0studio.Texts.tr;

final class ConsoleScreen {
    private static final int[] SLOT_COLORS = {0xffa6d3e8, 0xffaad06b, 0xffa355ae, 0xff5961ff};
    private static final int[] FADER_COLORS = {0xff48b8d0, 0xfff4d670, 0xffed806a, 0xffe856a4};
    private static final int MIXER_HEIGHT = 246;
    private static final int FADER_HEIGHT = 169;

    private final MainActivity activity;
    private final G0Session session;
    private final Ui ui;
    private final Fader[] faders = new Fader[2];
    private final TextView[] levels = new TextView[2];
    private final SourceIcon[] sourceIcons = new SourceIcon[2];
    private final Button[] curveTabs = new Button[3];
    private BatteryBadge battery;
    private Button effects;
    private Dial dial;
    private int chartChannel;

    ConsoleScreen(MainActivity activity, G0Session session) {
        this.activity = activity;
        this.session = session;
        ui = new Ui(activity);
    }

    View view() {
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout page = ui.column();
        page.setPadding(ui.dp(15), 0, ui.dp(15), ui.dp(12));
        scroll.addView(page);
        ui.add(page, statusBar(), 8);
        ui.add(page, activity.curve() ? curve() : mixer(), 6);
        ui.add(page, presets(), 5);
        updateLevels();
        return scroll;
    }

    private View statusBar() {
        LinearLayout bar = ui.row();
        bar.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        effects = ui.button(tr("Эффекты"), session::toggleGlobal);
        effects.setIncludeFontPadding(false);
        effects.setMaxLines(2);
        effects.setAutoSizeTextTypeUniformWithConfiguration(9, 11, 1, TypedValue.COMPLEX_UNIT_SP);
        effects.setPadding(ui.dp(3), 0, ui.dp(3), 0);
        effects.setBackground(new RippleDrawable(ColorStateList.valueOf(0x30ffffff),
                ui.round(Ui.RAISED, 8), null));
        bar.addView(effects, new LinearLayout.LayoutParams(ui.dp(84), ui.dp(44)));

        battery = new BatteryBadge();
        bar.addView(battery, new LinearLayout.LayoutParams(0, ui.dp(36), 1));
        battery.setVisibility(session.connected() ? View.VISIBLE : View.INVISIBLE);

        String modeName = modeName();
        TextView mode = ui.text(modeName, 12, session.mode >= 0 ? Ui.PINK : Ui.MUTED);
        mode.setContentDescription(session.mode == 1
                ? tr("Режим корпуса, Vibration") : tr("Режим датчика: ") + modeName);
        mode.setGravity(Gravity.CENTER);
        mode.setSingleLine();
        mode.setPadding(ui.dp(5), 0, ui.dp(5), 0);
        mode.setBackground(ui.round(Ui.RAISED, 8));
        bar.addView(mode, new LinearLayout.LayoutParams(ui.dp(84), ui.dp(36)));
        return bar;
    }

    private String modeName() {
        if (session.mode == 0 || session.mode == 2) return "STAGE";
        return session.mode == 1 ? "Vibration" : tr("Режим —");
    }

    private View mixer() {
        LinearLayout row = ui.row();
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        row.setGravity(Gravity.TOP);
        row.setMinimumHeight(ui.dp(MIXER_HEIGHT));
        View pickup = volumeColumn(0);
        View mic = volumeColumn(1);
        if (!session.connected()) {
            pickup.setVisibility(View.INVISIBLE);
            mic.setVisibility(View.INVISIBLE);
        }
        LinearLayout.LayoutParams sideSize = new LinearLayout.LayoutParams(0, ui.dp(MIXER_HEIGHT), 1);
        sideSize.topMargin = ui.dp(8);
        sideSize.height -= sideSize.topMargin;
        row.addView(pickup, sideSize);
        dial = new Dial();
        row.addView(dial, new LinearLayout.LayoutParams(0, ui.dp(210), 2.45f));
        row.addView(mic, new LinearLayout.LayoutParams(sideSize));
        return row;
    }

    private View curve() {
        LinearLayout content = ui.column();
        LinearLayout bar = ui.row();
        LinearLayout graphic = ui.column();
        Button back = ui.button("←", () -> activity.curve(false));
        back.setTextSize(23);
        back.setContentDescription(tr("Вернуться к громкости"));
        back.setPadding(0, 0, 0, 0);
        bar.addView(back, new LinearLayout.LayoutParams(ui.dp(41), ui.dp(43)));
        String[] channels = {"Гитара", "Микрофон", "Антисвист"};
        for (int index = 0; index < channels.length; index++) {
            final int channel = index;
            Button item = ui.button(tr(channels[index]), () -> {
                chartChannel = channel;
                showCurveGraphic(graphic);
            });
            item.setTextSize(11);
            item.setPadding(ui.dp(3), 0, ui.dp(3), 0);
            item.setEnabled(index != 1 || session.mic());
            curveTabs[index] = item;
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, ui.dp(43), 1);
            size.setMarginStart(ui.dp(5));
            bar.addView(item, size);
        }
        content.addView(bar);
        LinearLayout.LayoutParams chartSize = new LinearLayout.LayoutParams(-1, ui.dp(MIXER_HEIGHT - 49));
        chartSize.topMargin = ui.dp(6);
        content.addView(graphic, chartSize);
        showCurveGraphic(graphic);
        return content;
    }

    private void showCurveGraphic(LinearLayout area) {
        for (int index = 0; index < curveTabs.length; index++) {
            Button tab = curveTabs[index];
            boolean selected = index == chartChannel;
            tab.setSelected(selected);
            ui.styleButton(tab, false);
            if (selected) {
                GradientDrawable face = ui.round(Ui.RAISED, 13);
                face.setStroke(ui.dp(1), 0xff76546b);
                tab.setBackground(new RippleDrawable(ColorStateList.valueOf(0x30ffffff),
                        face, null));
                tab.setTextColor(Ui.PINK);
            }
        }
        area.removeAllViews();
        area.addView(EqScreen.preview(activity, activity.preset(), chartChannel),
                new LinearLayout.LayoutParams(-1, -1));
    }

    private View volumeColumn(int index) {
        boolean microphone = index == 1;
        String label = tr(microphone ? "Микрофон" : "Магнитный датчик");
        String description = tr(microphone ? "Громкость микрофона" : "Громкость магнитного датчика");
        LinearLayout column = ui.column();
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout cell = new FrameLayout(activity);
        cell.setClipChildren(false);
        Fader fader = new Fader(index, description);
        cell.addView(fader, new FrameLayout.LayoutParams(ui.dp(FADER_HEIGHT), ui.dp(48), Gravity.CENTER));
        column.addView(cell, new LinearLayout.LayoutParams(-1, ui.dp(FADER_HEIGHT)));
        SourceIcon icon = new SourceIcon(microphone);
        column.addView(icon, new LinearLayout.LayoutParams(-1, ui.dp(29)));
        TextView level = ui.text("—", 16, Ui.TEXT);
        level.setGravity(Gravity.CENTER);
        level.setOnClickListener(v -> editVolume(index, label));
        column.addView(level, new LinearLayout.LayoutParams(-1, ui.dp(39)));
        faders[index] = fader;
        levels[index] = level;
        sourceIcons[index] = icon;
        return column;
    }

    private void editVolume(int index, String label) {
        if (!session.volumeAvailable(index)) return;
        LinearLayout frame = ui.column();
        frame.setPadding(ui.dp(22), 0, ui.dp(22), 0);
        EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine();
        input.setText(Integer.toString(session.level(index)));
        input.setSelectAllOnFocus(true);
        frame.addView(input);
        new AlertDialog.Builder(activity).setTitle(label).setMessage(tr("Значение 0…100 (повышай постепенно)"))
                .setView(frame).setNegativeButton(tr("Отмена"), null)
                .setPositiveButton(tr("Применить"), (dialog, button) -> {
                    try {
                        int value = Integer.parseInt(input.getText().toString().trim());
                        if (value < 0 || value > 100) throw new NumberFormatException();
                        session.setVolume(index, value);
                    } catch (NumberFormatException invalid) {
                        activity.message(tr("Нужно целое число 0…100"));
                    }
                }).show();
    }

    private View presets() {
        LinearLayout region = ui.column();
        String subtitle = tr("Выбери устройство и пресет");
        if (activity.preset() != null) {
            boolean live = activity.preset() == session.preset;
            subtitle = activity.presetName() + (live ? tr(" · активен") : "");
            if (live && session.dirty) subtitle += " •";
        }
        TextView active = ui.text(subtitle, 17, Ui.TEXT);
        active.setSingleLine();
        active.setEllipsize(TextUtils.TruncateAt.END);
        active.setGravity(Gravity.CENTER_VERTICAL);
        active.setPadding(ui.dp(2), 0, 0, 0);
        region.addView(active, new LinearLayout.LayoutParams(-1, ui.dp(34)));

        LinearLayout tiles = new SquareTiles();
        for (int index = 0; index < 4; index++) {
            final int slot = index + 1;
            boolean selected = session.slot == slot && activity.preset() != null
                    && activity.preset() == session.preset;
            Button tile = ui.button(slot + "\n" + session.names[index],
                    () -> { if (session.connected()) activity.chooseSlot(slot); else activity.connect(); });
            tile.setTextSize(12);
            tile.setMaxLines(2);
            tile.setEllipsize(TextUtils.TruncateAt.END);
            tile.setPadding(ui.dp(3), ui.dp(7), ui.dp(3), ui.dp(6));
            tile.setTextColor(selected ? Ui.TEXT : Ui.MUTED);
            tile.setSelected(selected);
            GradientDrawable face = selected
                    ? new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                            new int[]{0xff13366c, 0xff105388})
                    : ui.round(Ui.SURFACE, 14);
            face.setCornerRadius(ui.dp(14));
            tile.setBackground(new RippleDrawable(ColorStateList.valueOf(0x30ffffff), face, null));
            GradientDrawable marker = ui.round(SLOT_COLORS[index], 4);
            marker.setBounds(0, 0, ui.dp(5), ui.dp(8));
            tile.setCompoundDrawables(null, marker, null, null);
            tile.setCompoundDrawablePadding(ui.dp(5));
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, 0);
            if (index != 0) size.leftMargin = ui.dp(5);
            tiles.addView(tile, size);
        }
        ui.add(region, tiles, 5);

        LinearLayout commands = ui.row();
        boolean hasPreset = activity.preset() != null;
        Button eq = ui.button(tr("Настроить EQ"), activity::openEq);
        eq.setEnabled(hasPreset);
        commands.addView(eq, new LinearLayout.LayoutParams(0, -2, 1));
        Button copy = ui.button(tr("Сохранить копию"), activity::saveLocalCopy);
        copy.setEnabled(hasPreset);
        LinearLayout.LayoutParams copySize = new LinearLayout.LayoutParams(0, -2, 1);
        copySize.setMarginStart(ui.dp(5));
        commands.addView(copy, copySize);
        ui.add(region, commands, 7);
        Button save = ui.button(tr("Сохранить пресет"), activity::savePreset);
        save.setEnabled(activity.canSave());
        ui.add(region, save, 7);
        return region;
    }

    void updateLevels() {
        if (battery != null) battery.refresh();
        if (effects != null) {
            boolean on = session.connected() && session.globalEffects;
            effects.setEnabled(session.connected());
            effects.setText(tr("Эффекты") + "\n" + tr(on ? "Вкл" : "Выкл"));
            effects.setTextColor(on ? Ui.PINK : Ui.MUTED);
            effects.setContentDescription(tr(on ? "Эффекты включены" : "Эффекты выключены"));
        }
        for (int index = 0; index < faders.length; index++) {
            Fader fader = faders[index];
            if (fader == null) continue;
            int level = session.level(index);
            boolean enabled = session.volumeAvailable(index);
            fader.setEnabled(enabled);
            if (!fader.dragging) fader.setProgress(Math.max(0, level));
            fader.invalidate();
            levels[index].setEnabled(enabled);
            levels[index].setText(enabled ? level + "%" : index == 1 && !session.mic() ? tr("Выкл.") : "—");
            levels[index].setTextColor(enabled ? Ui.TEXT : Ui.MUTED);
            sourceIcons[index].setAlpha(enabled ? 1f : .58f);
        }
        if (dial != null) dial.refresh();
    }

    private final class SquareTiles extends LinearLayout {
        SquareTiles() {
            super(activity);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setBaselineAligned(false);
            setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int available = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
            for (int i = 0; i < getChildCount(); i++) {
                LayoutParams size = (LayoutParams) getChildAt(i).getLayoutParams();
                available -= size.leftMargin + size.rightMargin;
            }
            int side = Math.min(ui.dp(118), Math.max(0, available / SLOT_COLORS.length));
            for (int i = 0; i < getChildCount(); i++) {
                LayoutParams size = (LayoutParams) getChildAt(i).getLayoutParams();
                size.width = side;
                size.height = side;
            }
            super.onMeasure(widthSpec, heightSpec);
        }
    }

    private final class Fader extends SeekBar {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private LinearGradient fill;
        private boolean dragging;

        Fader(int index, String description) {
            super(activity);
            setMax(100);
            setContentDescription(description);
            setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            setRotation(-90);
            setBackground(null);
            setPadding(ui.dp(11), 0, ui.dp(11), 0);
            setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                    if (user) session.setVolume(index, progress);
                }

                @Override public void onStartTrackingTouch(SeekBar bar) {
                    dragging = true;
                }

                @Override public void onStopTrackingTouch(SeekBar bar) {
                    dragging = false;
                    updateLevels();
                }
            });
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            if (width > getPaddingLeft() + getPaddingRight()) {
                fill = new LinearGradient(getPaddingLeft(), 0, width - getPaddingRight(), 0,
                        FADER_COLORS, null, Shader.TileMode.CLAMP);
            }
        }

        @Override protected synchronized void onDraw(Canvas canvas) {
            float x = getPaddingLeft(), end = getWidth() - getPaddingRight();
            float y = getHeight() / 2f, half = ui.dp(5.5f);
            float thumb = x + (end - x) * getProgress() / getMax();
            boolean enabled = isEnabled();
            paint.setColor(enabled ? 0xff292a33 : 0xff35353d);
            canvas.drawRoundRect(x, y - half, end, y + half, half, half, paint);
            if (enabled && getProgress() > 0) {
                paint.setShader(fill);
                canvas.drawRoundRect(x, y - half, thumb, y + half, half, half, paint);
                paint.setShader(null);
            }
            paint.setColor(enabled ? 0xff567d92 : 0xff4a515d);
            canvas.drawCircle(thumb, y, ui.dp(10), paint);
            paint.setColor(enabled ? 0xfff0f6fa : 0xff898993);
            canvas.drawCircle(thumb, y, ui.dp(6), paint);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (!isEnabled()) return false;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) getParent().requestDisallowInterceptTouchEvent(true);
            boolean handled = super.onTouchEvent(event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL || !handled) {
                getParent().requestDisallowInterceptTouchEvent(false);
            }
            return handled;
        }
    }

    private final class BatteryBadge extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        BatteryBadge() {
            super(activity);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        void refresh() {
            int charge = session.battery;
            setContentDescription(charge < 0 ? tr("Заряд датчика: ожидание данных")
                    : tr("Заряд датчика: ") + charge + "%");
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            int charge = session.battery;
            int color = charge < 0 ? Ui.MUTED : charge <= 20 ? 0xffff6975 : charge <= 55 ? 0xfff5ce53 : 0xff62d48e;
            String label = charge < 0 ? "—" : charge + "%";
            paint.setTextSize(ui.dp(12));
            float y = getHeight() / 2f;
            float x = (getWidth() - ui.dp(28) - paint.measureText(label)) / 2f;
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(ui.dp(1.2f));
            canvas.drawRoundRect(x, y - ui.dp(5), x + ui.dp(20), y + ui.dp(5),
                    ui.dp(2), ui.dp(2), paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(x + ui.dp(21), y - ui.dp(2), x + ui.dp(23), y + ui.dp(2),
                    ui.dp(1), ui.dp(1), paint);
            if (charge > 0) {
                canvas.drawRoundRect(x + ui.dp(2), y - ui.dp(3),
                        x + ui.dp(2) + ui.dp(16) * charge / 100f, y + ui.dp(3), ui.dp(1), ui.dp(1), paint);
            }
            canvas.drawText(label, x + ui.dp(28), y - (paint.ascent() + paint.descent()) / 2f, paint);
        }
    }

    private final class SourceIcon extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path line = new Path();
        private final boolean microphone;

        SourceIcon(boolean microphone) {
            super(activity);
            this.microphone = microphone;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas canvas) {
            canvas.save();
            canvas.translate(getWidth() / 2f, getHeight() / 2f);
            float unit = ui.dp(24) / 24f;
            canvas.scale(unit, unit);
            paint.setColor(Ui.MUTED);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.65f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            if (microphone) {
                canvas.drawRoundRect(-3.5f, -8, 3.5f, 3, 3.5f, 3.5f, paint);
                canvas.drawLine(-3, -3, -.5f, -3, paint);
                canvas.drawLine(-3, 0, -.5f, 0, paint);
                canvas.drawArc(-6, -4, 6, 6, 0, 180, false, paint);
                canvas.drawLine(0, 6, 0, 9, paint);
                canvas.drawLine(-3, 9, 3, 9, paint);
            } else {
                canvas.rotate(36);
                line.reset();
                line.moveTo(-1.4f, -3);
                line.cubicTo(-7, -4, -2.8f, -.7f, -5, 1.3f);
                line.cubicTo(-9.7f, 7, -3.6f, 10, 0, 10);
                line.cubicTo(3.6f, 10, 9.7f, 7, 5, 1.3f);
                line.cubicTo(2.8f, -.7f, 7, -4, 1.4f, -3);
                canvas.drawPath(line, paint);
                canvas.drawLine(0, 4.5f, 0, -7, paint);
                canvas.drawRoundRect(-1.6f, -10, 1.6f, -7, 1, 1, paint);
                canvas.drawCircle(0, 2.5f, 1.7f, paint);
                canvas.drawLine(-2, 6, 2, 6, paint);
            }
            canvas.restore();
        }
    }

    private final class Dial extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF ring = new RectF();
        private final RectF track = new RectF();
        private final Path glyph = new Path();
        private SweepGradient gradient;
        private boolean turning;
        private boolean pressed;

        Dial() {
            super(activity);
            setClickable(true);
            setFocusable(true);
            refresh();
        }

        void refresh() {
            int level = session.level(2);
            setContentDescription(!session.connected() ? tr("Подключить G0 Pro")
                    : level < 0 ? tr("Общая громкость. Ожидание данных")
                    : level == 0 ? tr("Звук выключен. Нажать, чтобы включить")
                    : tr("Общая громкость ") + level + tr("%. Нажать, чтобы выключить звук"));
            invalidate();
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            float radius = Math.max(0, Math.min(width, height) / 2f - ui.dp(7));
            float x = width / 2f, y = height / 2f;
            ring.set(x - radius, y - radius, x + radius, y + radius);
            track.set(ring);
            track.inset(ui.dp(3), ui.dp(3));
            gradient = new SweepGradient(x, y,
                    new int[]{0xff2a6baa, 0xff19818a, 0xff079248, 0xffddba4c, 0xff2a6baa},
                    new float[]{0, .25f, .53f, .75f, 1});
            Matrix rotation = new Matrix();
            rotation.setRotate(135, x, y);
            gradient.setLocalMatrix(rotation);
        }

        @Override protected void onDraw(Canvas canvas) {
            float x = ring.centerX(), y = ring.centerY(), radius = ring.width() / 2f;
            if (radius <= ui.dp(12)) return;
            boolean connected = session.connected();
            int level = session.level(2);
            paint.setColor(Ui.SURFACE);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(x, y, radius - ui.dp(9), paint);
            paint.setColor(Ui.LINE);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(ui.dp(connected ? 14 : 7));
            if (connected) canvas.drawArc(track, 135, 270, false, paint);
            else canvas.drawOval(ring, paint);

            if (connected && level >= 0) {
                float sweep = level * 2.7f;
                if (level > 0) {
                    paint.setShader(gradient);
                    canvas.drawArc(track, 135, sweep, false, paint);
                    paint.setShader(null);
                }
                double angle = Math.toRadians(135 + sweep);
                float distance = track.width() / 2f;
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xffbbbaca);
                canvas.drawCircle(x + distance * (float) Math.cos(angle),
                        y + distance * (float) Math.sin(angle), ui.dp(9), paint);
            }
            if (connected) {
                paint.setColor(level < 0 ? Ui.MUTED : Ui.TEXT);
                drawSpeaker(canvas, x, y - ui.dp(12), level == 0);
                caption(canvas, level < 0 ? "—" : level + "%", y + ui.dp(29), 26, paint.getColor());
            } else {
                drawPickup(canvas, x, y - ui.dp(13), radius);
                caption(canvas, tr("Подключить"), y + radius * .55f, 13, Ui.TEXT);
            }
        }

        private void caption(Canvas canvas, String title, float y, int size, int color) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            paint.setTextSize(ui.dp(size));
            canvas.drawText(title, ring.centerX(), y, paint);
        }

        private void drawSpeaker(Canvas canvas, float x, float y, boolean muted) {
            canvas.save();
            canvas.translate(x, y);
            float unit = ui.dp(3) / 2f;
            canvas.scale(unit, unit);
            paint.setStrokeWidth(1.8f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeCap(Paint.Cap.ROUND);
            glyph.reset();
            glyph.moveTo(-8, -3);
            glyph.lineTo(-4, -3);
            glyph.lineTo(1, -7);
            glyph.lineTo(1, 7);
            glyph.lineTo(-4, 3);
            glyph.lineTo(-8, 3);
            glyph.close();
            canvas.drawPath(glyph, paint);
            if (muted) {
                canvas.drawLine(4, -3, 10, 3, paint);
                canvas.drawLine(4, 3, 10, -3, paint);
            } else {
                canvas.drawArc(-4, -5, 7, 5, -54, 108, false, paint);
                canvas.drawArc(-6, -8, 11, 8, -49, 98, false, paint);
            }
            canvas.restore();
        }

        private void drawPickup(Canvas canvas, float x, float y, float radius) {
            canvas.save();
            canvas.translate(x, y);
            float unit = radius / 79f;
            canvas.scale(unit, unit);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xff373743);
            glyph.reset();
            glyph.moveTo(-45, -12);
            glyph.lineTo(45, -12);
            glyph.lineTo(29, 10);
            glyph.lineTo(23, 30);
            glyph.lineTo(0, 38);
            glyph.lineTo(-23, 30);
            glyph.lineTo(-29, 10);
            glyph.close();
            canvas.drawPath(glyph, paint);
            paint.setColor(0xff535563);
            canvas.drawRoundRect(-54, -24, 54, -8, 7, 7, paint);
            paint.setColor(0xff252632);
            canvas.drawCircle(-43, -16, 3, paint);
            canvas.drawCircle(43, -16, 3, paint);
            canvas.drawRoundRect(-8, -4, 8, 0, 2, 2, paint);
            canvas.drawCircle(0, 19, 10, paint);
            paint.setColor(Ui.ACCENT);
            canvas.drawCircle(0, 19, 7, paint);
            paint.setColor(0xff303440);
            canvas.drawCircle(0, 19, 5, paint);
            paint.setColor(0xff9193a2);
            canvas.drawRoundRect(-22, 12, -14, 14, 1, 1, paint);
            canvas.drawRoundRect(-21, 18, -13, 20, 1, 1, paint);
            canvas.drawRoundRect(14, 12, 22, 14, 1, 1, paint);
            canvas.drawRoundRect(13, 18, 21, 20, 1, 1, paint);
            canvas.restore();
        }

        @Override public boolean performClick() {
            super.performClick();
            if (session.connected()) session.mute();
            else activity.connect();
            return true;
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            float dx = event.getX() - ring.centerX();
            float dy = event.getY() - ring.centerY();
            double distance = Math.hypot(dx, dy);
            float radius = ring.width() / 2f;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (distance > radius + ui.dp(8)) return false;
                    if (!session.connected()) return super.onTouchEvent(event);
                    if (!session.volumeAvailable(2)) return false;
                    turning = distance >= radius * .69f;
                    pressed = !turning;
                    if (turning) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                        setAngle(dx, dy);
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!session.connected()) return super.onTouchEvent(event);
                    if (turning && distance >= radius * .4f) setAngle(dx, dy);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!session.connected()) return super.onTouchEvent(event);
                    if (turning && distance >= radius * .4f) setAngle(dx, dy);
                    else if (pressed && distance < radius * .69f) performClick();
                    finishTouch();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    finishTouch();
                    return super.onTouchEvent(event);
                default:
                    return super.onTouchEvent(event);
            }
        }

        private void finishTouch() {
            turning = false;
            pressed = false;
            getParent().requestDisallowInterceptTouchEvent(false);
        }

        private void setAngle(float dx, float dy) {
            double angle = (Math.toDegrees(Math.atan2(dy, dx)) + 225) % 360;
            if (angle > 270) angle = angle > 315 ? 0 : 270;
            session.setVolume(2, (int) Math.round(angle / 2.7));
        }
    }
}

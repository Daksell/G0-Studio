package ru.g0studio;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.text.method.DigitsKeyListener;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.math.BigDecimal;
import java.util.Arrays;

import static ru.g0studio.Texts.tr;

final class EqScreen {
    interface Host {
        Protocol.Preset preset();
        boolean micEnabled();
        void changeBand(int channel, int band, float gain, float q, float hz);
        void closeEq();
    }

    private static final String[] CHANNEL_LABELS = {"Гитара", "Микрофон", "Антисвист"};
    private static final int[] BAND_COLORS = {
        0xffe42a73, 0xffb5a2e4, 0xff5b63ce, 0xff32942d,
        0xff86a897, 0xffb8502d, 0xfff6b756, 0xffd3895c
    };
    private static final int TOTAL_CURVE = 0xffbed5fe;
    private static final int Q_COLOR = 0xff62d4ce;
    private static final float Q_MIN = .05f;
    private static final int Q_STEPS = 200;
    private final Context context;
    private final Host host;
    private final Ui ui;
    private final LinearLayout root;
    private final LinearLayout bandStrip;
    private final Button[] channelButtons = new Button[3];
    private final EditText frequency, gain, quality;
    private final SeekBar qSlider;
    private final Button apply;
    private final ResponsePlot plot;
    private Protocol.Preset source;
    private int channel, selected;
    private float[] draft;
    private float sliderCeiling = 20;

    EqScreen(Context context, Host host) {
        this.context = context;
        this.host = host;
        ui = new Ui(context);
        root = ui.column();
        root.setFocusableInTouchMode(true);
        root.setBackgroundColor(Ui.BACK);
        root.setPadding(ui.dp(2), 0, ui.dp(2), ui.dp(8));

        LinearLayout toolbar = ui.row();
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        Button close = ui.button(tr("‹ Звук"), () -> {
            hideKeyboard();
            host.closeEq();
        });
        styleToolbarButton(close);
        toolbar.addView(close, toolbarButtonSize());
        for (int i = 0; i < CHANNEL_LABELS.length; i++) {
            final int id = i;
            Button tab = ui.button(tr(CHANNEL_LABELS[i]), () -> chooseChannel(id));
            styleToolbarButton(tab);
            toolbar.addView(tab, toolbarButtonSize());
            channelButtons[i] = tab;
        }
        root.addView(toolbar);

        LinearLayout editor = ui.row();
        editor.setBaselineAligned(false);
        editor.setGravity(Gravity.TOP);
        root.addView(editor, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout graphPanel = ui.column();
        editor.addView(graphPanel, new LinearLayout.LayoutParams(0, -1, 3.2f));

        plot = new ResponsePlot(context, this);
        graphPanel.addView(plot, new LinearLayout.LayoutParams(-1, 0, 1));
        bandStrip = ui.row();
        graphPanel.addView(bandStrip, new LinearLayout.LayoutParams(-1, ui.dp(36)));

        ScrollView inspector = new ScrollView(context);
        inspector.setFillViewport(true);
        inspector.setVerticalScrollBarEnabled(false);
        LinearLayout.LayoutParams inspectorGeometry = new LinearLayout.LayoutParams(0, -1, 1);
        inspectorGeometry.setMarginStart(ui.dp(6));
        editor.addView(inspector, inspectorGeometry);

        LinearLayout controls = ui.column();
        controls.setPadding(ui.dp(5), ui.dp(3), ui.dp(5), ui.dp(4));
        inspector.addView(controls, new ScrollView.LayoutParams(-1, -2));
        frequency = addField(controls, "Частота, Гц", false);
        gain = addField(controls, "Усиление, дБ", true);
        quality = addField(controls, "Добротность Q", false);
        qSlider = new SeekBar(context);
        qSlider.setMax(Q_STEPS);
        qSlider.setProgressTintList(ColorStateList.valueOf(Q_COLOR));
        qSlider.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.LINE));
        qSlider.setThumbTintList(ColorStateList.valueOf(Q_COLOR));
        qSlider.setContentDescription(tr("Добротность Q выбранной полосы"));
        controls.addView(qSlider, new LinearLayout.LayoutParams(-1, ui.dp(34)));
        qSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private boolean tracking;

            @Override public void onProgressChanged(SeekBar bar, int progress, boolean byUser) {
                if (!byUser || source == null) return;
                float q = (float) (Q_MIN * Math.pow(sliderCeiling / Q_MIN, progress / (double) Q_STEPS));
                showQ(q);
                if (!tracking) acceptDraft();
            }

            @Override public void onStartTrackingTouch(SeekBar bar) { tracking = true; }

            @Override public void onStopTrackingTouch(SeekBar bar) {
                tracking = false;
                acceptDraft();
            }
        });
        apply = ui.button(tr("Применить"), this::applyFields);
        styleToolbarButton(apply);
        LinearLayout.LayoutParams applyGeometry = new LinearLayout.LayoutParams(-1, ui.dp(44));
        applyGeometry.setMargins(ui.dp(3), ui.dp(5), ui.dp(3), ui.dp(5));
        controls.addView(apply, applyGeometry);

        refresh();
        root.requestFocus();
    }

    private void styleToolbarButton(Button button) {
        button.setSingleLine(true);
        button.setTextColor(new ColorStateList(
            new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
            new int[] {Ui.MUTED, Ui.TEXT}));
        button.setPadding(ui.dp(5), 0, ui.dp(5), 0);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2479cdd5),
            ui.round(Ui.SURFACE, 14), null));
    }

    private LinearLayout.LayoutParams toolbarButtonSize() {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, ui.dp(47), 1);
        size.setMargins(ui.dp(3), ui.dp(4), ui.dp(3), ui.dp(4));
        return size;
    }

    View view() { return root; }

    static View preview(Context context, Protocol.Preset preset, int channel) {
        ResponsePlot preview = new ResponsePlot(context, null);
        preview.setPreset(preset, channel);
        return preview;
    }

    void refresh() {
        source = host.preset();
        if (channel == 1 && !host.micEnabled()) {
            channel = 0;
            selected = 0;
        }
        selected = Math.min(selected, bandCount(channel) - 1);
        draft = null;
        for (int i = 0; i < channelButtons.length; i++) {
            Button tab = channelButtons[i];
            tab.setEnabled(i != 1 || host.micEnabled());
            tab.setSelected(i == channel);
            tab.setText((i == channel ? "● " : "") + tr(CHANNEL_LABELS[i]));
        }
        reshapeBandStrip();
        plot.setPreset(source, channel);
        loadFields();
    }

    private void chooseChannel(int value) {
        if (value == channel || value == 1 && !host.micEnabled()) return;
        hideKeyboard();
        channel = value;
        selected = 0;
        refresh();
    }

    private void reshapeBandStrip() {
        bandStrip.removeAllViews();
        for (int i = 0; i < bandCount(channel); i++) {
            final int band = i;
            Button selector = ui.button(Integer.toString(i + 1), () -> chooseBand(band));
            selector.setContentDescription(tr("Полоса ") + (i + 1));
            selector.setTextSize(12);
            selector.setMinWidth(0);
            selector.setMinimumWidth(0);
            selector.setMinHeight(0);
            selector.setMinimumHeight(0);
            selector.setPadding(0, 0, 0, 0);
            selector.setEnabled(source != null);
            LinearLayout.LayoutParams item = new LinearLayout.LayoutParams(0, -1, 1);
            item.setMargins(ui.dp(2), 0, ui.dp(2), 0);
            bandStrip.addView(selector, item);
        }
        markBand();
    }

    private void markBand() {
        for (int i = 0; i < bandStrip.getChildCount(); i++) {
            Button button = (Button) bandStrip.getChildAt(i);
            boolean active = source != null && selected == i;
            button.setSelected(active);
            button.setTextColor(active ? Ui.BACK : source != null ? BAND_COLORS[i] : Ui.MUTED);
            button.setBackground(ui.round(active ? BAND_COLORS[i] : Ui.SURFACE, 14));
        }
    }

    private EditText addField(LinearLayout parent, String label, boolean signed) {
        TextView caption = ui.text(tr(label), 12, Ui.MUTED);
        LinearLayout.LayoutParams captionSize = new LinearLayout.LayoutParams(-1, -2);
        captionSize.topMargin = ui.dp(2);
        parent.addView(caption, captionSize);

        EditText input = new EditText(context);
        input.setId(View.generateViewId());
        caption.setLabelFor(input.getId());
        input.setTextSize(18);
        input.setTextColor(Ui.TEXT);
        input.setSingleLine(true);
        input.setSelectAllOnFocus(true);
        input.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(3));
        input.setBackgroundTintList(new ColorStateList(
            new int[][] {new int[] {android.R.attr.state_focused}, new int[] {}},
            new int[] {Q_COLOR, 0xffaaaaac}));
        input.setKeyListener(DigitsKeyListener.getInstance(signed ? "0123456789-+.,": "0123456789.,"));
        input.setRawInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
            | (signed ? InputType.TYPE_NUMBER_FLAG_SIGNED : 0));
        input.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            applyFields();
            return true;
        });
        LinearLayout.LayoutParams inputSize = new LinearLayout.LayoutParams(-1, ui.dp(36), 1);
        parent.addView(input, inputSize);
        return input;
    }

    private void loadFields() {
        boolean available = source != null;
        frequency.setEnabled(available);
        gain.setEnabled(available);
        quality.setEnabled(available);
        apply.setEnabled(available);
        qSlider.setEnabled(available);
        if (!available) {
            frequency.setText("");
            gain.setText("");
            quality.setText("");
            qSlider.setProgress(0);
            return;
        }
        float[] band = source.band(channel, selected);
        sliderCeiling = Math.max(20, band[1]);
        applyFieldsPreview(band, true);
    }

    private void chooseBand(int index) {
        if (source == null) return;
        hideKeyboard();
        if (draft != null) discardDraft();
        selected = index;
        markBand();
        loadFields();
        plot.invalidate();
    }

    private void hideKeyboard() {
        InputMethodManager keyboard = context.getSystemService(InputMethodManager.class);
        if (keyboard != null && root.getWindowToken() != null) {
            keyboard.hideSoftInputFromWindow(root.getWindowToken(), 0);
        }
        root.requestFocus();
    }

    private void showQ(float value) {
        ensureDraft();
        if (draft == null) return;
        draft[1] = Math.max(Q_MIN, Math.min(sliderCeiling, Math.round(value * 100) / 100f));
        applyFieldsPreview(draft, false);
        plot.setBand(selected, draft);
    }

    private void moveBand(float hz, float db) {
        ensureDraft();
        if (draft == null) return;
        draft[0] = Math.round(10 * clamp(db, -12, channel == 2 ? 0 : 12)) / 10f;
        draft[2] = Math.round(hz);
        applyFieldsPreview(draft, true);
        plot.setBand(selected, draft);
    }

    private void ensureDraft() {
        if (draft == null && source != null && host.preset() == source) {
            draft = source.band(channel, selected);
        }
    }

    private void applyFieldsPreview(float[] tuple, boolean updatePoint) {
        if (updatePoint) {
            frequency.setText(formatNumber(tuple[2]));
            gain.setText(formatNumber(tuple[0]));
        }
        quality.setText(formatNumber(tuple[1]));
        float progress = (float) (Math.log(Math.max(Q_MIN, tuple[1]) / Q_MIN) /
            Math.log(sliderCeiling / Q_MIN));
        qSlider.setProgress(Math.round(clamp(progress, 0, 1) * Q_STEPS));
    }

    private void discardDraft() {
        draft = null;
        if (source != null) {
            plot.setBand(selected, source.band(channel, selected));
            loadFields();
        }
    }

    private void applyFields() {
        try {
            if (commit(readNumber(gain), readNumber(quality), readNumber(frequency))) hideKeyboard();
        } catch (NumberFormatException e) {
            notifyError(tr("Введи число во всех трёх полях"));
        }
    }

    private void acceptDraft() {
        if (draft == null) return;
        float[] completed = draft;
        draft = null;
        commit(completed[0], completed[1], completed[2]);
    }

    private boolean commit(float db, float q, float hz) {
        if (source == null || host.preset() != source || channel == 1 && !host.micEnabled()) {
            refresh();
            return false;
        }
        try {
            if (!Float.isFinite(db) || !Float.isFinite(q) || !Float.isFinite(hz)
                || db < -12 || db > 12 || q <= 0 || hz < 20 || hz > 22000) {
                throw new IllegalArgumentException(tr("Нужны 20–22000 Гц, −12…+12 дБ и Q > 0"));
            }
            if (channel == 2 && db > 0) {
                throw new IllegalArgumentException(tr("Для антисвиста усиление должно быть не выше 0 дБ"));
            }
            float[] previous = source.band(channel, selected);
            if (previous[0] != db || previous[1] != q || previous[2] != hz) {
                host.changeBand(channel, selected, db, q, hz);
            }
            discardDraft();
            return true;
        } catch (IllegalArgumentException | IllegalStateException e) {
            discardDraft();
            notifyError(tr(e.getMessage()));
            return false;
        }
    }

    private void notifyError(String message) {
        Toast.makeText(context, message == null ? tr("Ошибка") : message, Toast.LENGTH_SHORT).show();
    }

    private static float readNumber(EditText input) {
        return Float.parseFloat(input.getText().toString().trim().replace(',', '.'));
    }

    private static String formatNumber(float value) {
        String number = Float.toString(value);
        if (number.endsWith(".0")) return number.substring(0, number.length() - 2);
        return number.indexOf('E') < 0 ? number : new BigDecimal(number).toPlainString();
    }

    private static int bandCount(int channel) { return channel == 2 ? 4 : 8; }

    private static float clamp(float value, float lower, float upper) {
        return Math.max(lower, Math.min(value, upper));
    }

    private static final class ResponsePlot extends View {
        private static final int SAMPLES = 288;
        private static final double LOG_RANGE = Math.log(1100);
        private static final int[] TICK_HZ = {20, 100, 1000, 10000, 22000};
        private static final String[] TICK_LABELS = {"20", "100", "1k", "10k", "22k"};
        private static final String[] GAIN_LABELS = {
            "-18", "-15", "-12", "-9", "-6", "-3", "0", "+3", "+6", "+9", "+12", "+15", "+18"
        };
        private static final String[] BAND_NUMBERS = {"1", "2", "3", "4", "5", "6", "7", "8"};
        private static final double[] COS = new double[SAMPLES + 1];
        private static final double[] SIN = new double[SAMPLES + 1];
        private static final double[] COS2 = new double[SAMPLES + 1];
        private static final double[] SIN2 = new double[SAMPLES + 1];

        static {
            for (int i = 0; i <= SAMPLES; i++) {
                double omega = 2 * Math.PI * frequency(i / (double) SAMPLES) / 48000;
                COS[i] = Math.cos(omega);
                SIN[i] = Math.sin(omega);
                COS2[i] = Math.cos(2 * omega);
                SIN2[i] = Math.sin(2 * omega);
            }
        }

        private final Ui ui;
        private final EqScreen editor;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path trace = new Path();
        private final Path shading = new Path();
        private final double[] total = new double[SAMPLES + 1];
        private final double[][] responses = new double[8][SAMPLES + 1];
        private final float[][] bands = new float[8][3];
        private final int touchSlop;
        private int visibleBands;
        private int channel;
        private int pointerId = -1, pinchOther = -1;
        private boolean moved, pinching;
        private float anchorX, anchorY, bandOffsetX, bandOffsetY, initialSpan, initialQ;

        ResponsePlot(Context context, EqScreen editor) {
            super(context);
            ui = new Ui(context);
            this.editor = editor;
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeCap(Paint.Cap.ROUND);
            if (editor != null) {
                setContentDescription(tr("График эквалайзера. Перетаскивай точки для изменения частоты и усиления."));
                setClickable(true);
            }
        }

        void setPreset(Protocol.Preset preset, int channel) {
            this.channel = channel;
            visibleBands = preset == null ? 0 : bandCount(channel);
            pointerId = -1;
            pinchOther = -1;
            pinching = false;
            Arrays.fill(total, 0);
            for (int i = 0; i < visibleBands; i++) {
                float[] band = preset.band(channel, i);
                System.arraycopy(band, 0, bands[i], 0, 3);
                response(i, band);
                for (int s = 0; s <= SAMPLES; s++) total[s] += responses[i][s];
            }
            invalidate();
        }

        void setBand(int index, float[] band) {
            if (index >= visibleBands || Arrays.equals(bands[index], band)) return;
            for (int s = 0; s <= SAMPLES; s++) total[s] -= responses[index][s];
            System.arraycopy(band, 0, bands[index], 0, 3);
            response(index, band);
            for (int s = 0; s <= SAMPLES; s++) total[s] += responses[index][s];
            invalidate();
        }

        private void response(int index, float[] band) {
            double[] result = responses[index];
            double gain = band[0], q = band[1], center = band[2];
            if (center <= 0 || Math.abs(gain) < .00001) {
                Arrays.fill(result, 0);
                return;
            }
            double omega = 2 * Math.PI * Math.min(22000, center) / 48000;
            double amplitude = Math.pow(10, gain / 40);
            double cos = Math.cos(omega);
            double alpha = Math.sin(omega) / (2 * Math.max(Q_MIN, q));
            double b0, b1, b2, a0, a1, a2;
            if (channel != 2 && index < 2) {
                boolean high = index == 1;
                double plus = amplitude + 1;
                double minus = amplitude - 1;
                double knee = 2 * Math.sqrt(amplitude) * alpha;
                double sign = high ? 1 : -1;
                b0 = amplitude * (plus + sign * minus * cos + knee);
                b1 = -sign * 2 * amplitude * (minus + sign * plus * cos);
                b2 = amplitude * (plus + sign * minus * cos - knee);
                a0 = plus - sign * minus * cos + knee;
                a1 = sign * 2 * (minus - sign * plus * cos);
                a2 = plus - sign * minus * cos - knee;
            } else {
                b0 = 1 + alpha * amplitude;
                b1 = -2 * cos;
                b2 = 1 - alpha * amplitude;
                a0 = 1 + alpha / amplitude;
                a1 = -2 * cos;
                a2 = 1 - alpha / amplitude;
            }
            for (int i = 0; i <= SAMPLES; i++) {
                double numeratorReal = b0 + b1 * COS[i] + b2 * COS2[i];
                double numeratorImag = b1 * SIN[i] + b2 * SIN2[i];
                double denominatorReal = a0 + a1 * COS[i] + a2 * COS2[i];
                double denominatorImag = a1 * SIN[i] + a2 * SIN2[i];
                double ratio = (numeratorReal * numeratorReal + numeratorImag * numeratorImag) /
                    (denominatorReal * denominatorReal + denominatorImag * denominatorImag);
                result[i] = 10 * Math.log10(Math.max(1e-20, ratio));
            }
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (right() <= left() || bottom() <= top()) return;
            drawGrid(canvas);
            if (visibleBands == 0) {
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setColor(Ui.MUTED);
                canvas.drawText(tr("Ожидание данных пресета"), (left() + right()) / 2,
                    (top() + bottom()) / 2, paint);
                return;
            }
            int saved = canvas.save();
            canvas.clipRect(left(), top(), right(), bottom());
            if (editor != null) {
                for (int i = 0; i < visibleBands; i++) {
                    if (i != editor.selected && bands[i][0] != 0) drawBand(canvas, i, false);
                }
                if (bands[editor.selected][0] != 0) drawBand(canvas, editor.selected, true);
            }
            buildTrace(total);
            drawTrace(canvas, TOTAL_CURVE, 2.5f, 255);
            canvas.restoreToCount(saved);
            if (editor != null) {
                for (int i = 0; i < visibleBands; i++) if (i != editor.selected) drawBandMarker(canvas, i, false);
                drawBandMarker(canvas, editor.selected, true);
            }
        }

        private void drawGrid(Canvas canvas) {
            paint.setStyle(Paint.Style.FILL);
            paint.setTextSize(ui.dp(10));
            paint.setStrokeWidth(ui.dp(1));
            paint.setTextAlign(Paint.Align.RIGHT);
            int step = bottom() - top() < ui.dp(170) ? 6 : 3;
            for (int db = -18; db <= 18; db += step) {
                paint.setColor(db == 0 ? 0xff4f657a : Ui.SURFACE);
                canvas.drawLine(left(), y(db), right(), y(db), paint);
                paint.setColor(Ui.MUTED);
                canvas.drawText(GAIN_LABELS[(db + 18) / 3], left() - ui.dp(11), y(db) + ui.dp(3), paint);
            }
            paint.setTextAlign(Paint.Align.CENTER);
            for (int i = 0; i < TICK_HZ.length; i++) {
                float x = x(TICK_HZ[i]);
                paint.setColor(Ui.SURFACE);
                canvas.drawLine(x, top(), x, bottom(), paint);
                paint.setColor(Ui.MUTED);
                canvas.drawText(TICK_LABELS[i], x, bottom() + ui.dp(16), paint);
            }
        }

        private void drawBand(Canvas canvas, int band, boolean selected) {
            buildTrace(responses[band]);
            shading.set(trace);
            shading.lineTo(right(), y(0));
            shading.lineTo(left(), y(0));
            shading.close();
            int color = BAND_COLORS[band];
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            paint.setAlpha(selected ? 80 : 53);
            canvas.drawPath(shading, paint);
            drawTrace(canvas, color, selected ? 1.5f : 1, selected ? 255 : 190);
        }

        private void buildTrace(double[] values) {
            trace.reset();
            trace.moveTo(left(), y(values[0]));
            float pixelStep = (right() - left()) / SAMPLES;
            for (int i = 1; i <= SAMPLES; i++) trace.lineTo(left() + i * pixelStep, y(values[i]));
        }

        private void drawTrace(Canvas canvas, int color, float width, int alpha) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(color);
            paint.setAlpha(alpha);
            paint.setStrokeWidth(ui.dp(width));
            canvas.drawPath(trace, paint);
        }

        private void drawBandMarker(Canvas canvas, int band, boolean selected) {
            float x = x(bands[band][2]), y = pointY(bands[band][0]);
            float radius = ui.dp(selected ? 12 : 9);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(BAND_COLORS[band]);
            canvas.drawCircle(x, y, radius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(selected ? Ui.TEXT : Ui.BACK);
            paint.setStrokeWidth(ui.dp(1));
            canvas.drawCircle(x, y, radius, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Ui.BACK);
            paint.setTextSize(ui.dp(selected ? 12 : 10));
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(BAND_NUMBERS[band], x, y - (paint.ascent() + paint.descent()) / 2, paint);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (editor == null || visibleBands == 0 || right() <= left() || bottom() <= top()) {
                return super.onTouchEvent(event);
            }
            if (pointerId == -1 && event.getActionMasked() != MotionEvent.ACTION_DOWN) return true;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    pointerId = event.getPointerId(0);
                    anchorX = event.getX();
                    anchorY = event.getY();
                    moved = false;
                    pinching = false;
                    pinchOther = -1;
                    int nearest = nearestBand(anchorX, anchorY);
                    bandOffsetX = Float.NaN;
                    if (nearest >= 0) {
                        editor.chooseBand(nearest);
                        bandOffsetX = x(bands[nearest][2]) - anchorX;
                        bandOffsetY = pointY(bands[nearest][0]) - anchorY;
                    }
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (event.getPointerCount() != 2 || pinching) return true;
                    editor.hideKeyboard();
                    editor.discardDraft();
                    pinching = true;
                    pinchOther = event.getPointerId(event.getActionIndex());
                    initialQ = bands[editor.selected][1];
                    initialSpan = Math.max(ui.dp(8), span(event));
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (pinching) {
                        float currentSpan = span(event);
                        if (currentSpan > 0 && (moved || Math.abs(currentSpan - initialSpan) > touchSlop)) {
                            moved = true;
                            editor.showQ(initialQ * currentSpan / initialSpan);
                        }
                    } else if (Float.isFinite(bandOffsetX)) {
                        float dx = event.getX() - anchorX, dy = event.getY() - anchorY;
                        if (moved || Math.hypot(dx, dy) >= touchSlop) {
                            moved = true;
                            float position = clamp((event.getX() + bandOffsetX - left()) / (right() - left()), 0, 1);
                            float db = 18 - 36 * (event.getY() + bandOffsetY - top()) / (bottom() - top());
                            editor.moveBand((float) frequency(position), db);
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    pointerId = -1;
                    editor.acceptDraft();
                    getParent().requestDisallowInterceptTouchEvent(false);
                    if (!moved && !pinching) performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    pointerId = -1;
                    editor.discardDraft();
                    getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                default:
                    return true;
            }
        }

        @Override public boolean performClick() {
            super.performClick();
            return true;
        }

        private int nearestBand(float x, float y) {
            int nearest = -1;
            double best = ui.dp(21);
            for (int i = 0; i < visibleBands; i++) {
                double distance = Math.hypot(x - x(bands[i][2]), y - pointY(bands[i][0]));
                if (distance < best || distance == best && i == editor.selected) {
                    nearest = i;
                    best = distance;
                }
            }
            return nearest;
        }

        private float span(MotionEvent event) {
            int first = event.findPointerIndex(pointerId), second = event.findPointerIndex(pinchOther);
            if (first < 0 || second < 0) return -1;
            return (float) Math.hypot(event.getX(first) - event.getX(second), event.getY(first) - event.getY(second));
        }

        private float left() { return ui.dp(34); }
        private float right() { return getWidth() - ui.dp(20); }
        private float top() { return ui.dp(16); }
        private float bottom() { return getHeight() - ui.dp(24); }
        private float x(float hz) {
            return left() + (right() - left()) * (float) (Math.log(clamp(hz, 20, 22000) / 20d) / LOG_RANGE);
        }
        private float y(double db) { return top() + (18 - (float) db) / 36 * (bottom() - top()); }
        private float pointY(float db) { return y(clamp(db, -18, 18)); }
        private static double frequency(double position) { return 20 * Math.exp(LOG_RANGE * position); }
    }
}

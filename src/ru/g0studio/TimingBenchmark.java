package ru.g0studio;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

final class TimingBenchmark {
    static final int EQ = 1;
    static final int PEDALS = 2;
    static final int SLOTS = 4;
    static final int ALL = EQ | PEDALS | SLOTS;

    interface Host {
        void send(byte[] request);
        void mark(String event);
        void done(boolean success, String result);
        boolean canSend();
        void restoreTiming();
    }

    private enum Phase { IDLE, BASELINE, TEST, RESTORE }

    private static final class Step {
        final byte[] request;
        final Consumer<byte[]> reply;
        final byte[] undo;
        final String marker;

        Step(byte[] request, Consumer<byte[]> reply, byte[] undo) {
            this(request, reply, undo, null);
        }

        Step(byte[] request, Consumer<byte[]> reply, byte[] undo, String marker) {
            this.request = request;
            this.reply = reply;
            this.undo = undo;
            this.marker = marker;
        }
    }

    private final Host host;
    private final ArrayDeque<Step> remaining = new ArrayDeque<>();
    private final Map<Integer, byte[]> pendingRestore = new LinkedHashMap<>();
    private final Protocol.Preset[] slotSnapshots = new Protocol.Preset[5];
    private Phase phase = Phase.IDLE;
    private Step inFlight;
    private Protocol.Preset supplied, baseline;
    private int[] switchTargets;
    private int slot, rounds, batchRounds, stages;
    private int expectedActive;
    private boolean micEnabled, sentSlotSwitch, recoveryPending;
    private String label, abortReason;
    private int eqWrites, pedalWrites, selects, reads, batches, verified, ackOnly;

    TimingBenchmark(Host host) {
        this.host = host;
    }

    boolean active() {
        return phase != Phase.IDLE;
    }

    boolean recoveryPending() {
        return recoveryPending;
    }

    void resumeRecovery() {
        if (!recoveryPending || active() || !host.canSend()) return;
        recoveryPending = false;
        restore();
    }

    void start(String label, Protocol.Preset original, int alternateSlot, int rounds, int batchRounds,
               boolean micEnabled) {
        start(label, original, alternateSlot, rounds, batchRounds, micEnabled, ALL);
    }

    void start(String label, Protocol.Preset original, int alternateSlot, int rounds, int batchRounds,
               boolean micEnabled, int stages) {
        if (active()) throw new IllegalStateException("Проверка уже запущена");
        if (recoveryPending) throw new IllegalStateException("Сначала восстановите исходный пресет");
        boolean switchSlots = rounds > 0 && (stages & SLOTS) != 0;
        if (original == null || !validSlot(original.slot()) || (stages & ~ALL) != 0
            || (switchSlots && (!validSlot(alternateSlot) || original.slot() == alternateSlot))
            || rounds < 0 || rounds > 64 || batchRounds < 0 || batchRounds > 16
            || (batchRounds == 0 && (rounds == 0 || stages == 0))) {
            throw new IllegalArgumentException("Некорректные параметры проверки профиля");
        }
        if (!host.canSend()) throw new IllegalStateException("Датчик не подключён");
        this.label = label;
        supplied = original.copy();
        slot = original.slot();
        expectedActive = slot;
        switchTargets = slotTargets(alternateSlot, switchSlots, stages == SLOTS);
        this.rounds = rounds;
        this.batchRounds = batchRounds;
        this.micEnabled = micEnabled;
        this.stages = stages;
        sentSlotSwitch = false;
        abortReason = null;
        phase = Phase.BASELINE;
        eqWrites = pedalWrites = selects = reads = batches = verified = ackOnly = 0;
        host.mark("Профиль " + label + " · чтение исходных данных");
        verifyActive(slot);
        readPreset(slot, preset -> {
            requireUnchanged(supplied, preset, "Текущий слот изменился до проверки");
            baseline = preset;
            baseline.micReverb = supplied.micReverb;
        });
        for (int target : switchTargets) {
            if (target != slot) readPreset(target, preset -> slotSnapshots[target] = preset);
        }
        verifyActive(slot);
        advance();
    }

    void cancel() {
        if (!active() || phase == Phase.RESTORE) return;
        requestAbort("Проверка остановлена");
    }

    void slotNotice(int reported) {
        if (!active() || !validSlot(reported) || expectedActive == reported) return;
        if (inFlight != null && isSelect(inFlight.request)) return;
        if (phase == Phase.RESTORE) {
            if (inFlight != null && isActiveRead(inFlight.request)) return;
            finish(false, abortReason + " · слот снова переключён во время восстановления");
            return;
        }
        expectedActive = reported;
        requestAbort("Активный слот изменился во время проверки");
    }

    void failed(String reason) {
        if (!active()) return;
        Step failed = inFlight;
        inFlight = null;
        if (phase == Phase.RESTORE) {
            if (!host.canSend()) deferRecovery();
            else if (failed != null && isSelect(failed.request)) {
                host.mark("Подтверждение переключения не получено, проверка активного слота · " + reason);
                advance();
            } else {
                finish(false, abortReason + " · не удалось подтвердить восстановление: " + reason);
            }
        } else {
            beginRestore(abortReason == null ? reason : abortReason);
        }
    }

    void acknowledged(byte[] request, byte[] packet) {
        Step completed = inFlight;
        if (completed == null || !Arrays.equals(completed.request, request)) return;
        inFlight = null;
        if (abortReason != null && phase != Phase.RESTORE) {
            beginRestore(abortReason);
            return;
        }
        try {
            completed.reply.accept(packet);
        } catch (IllegalArgumentException | IllegalStateException problem) {
            failed(problem.getMessage());
            return;
        }
        advance();
    }

    private void requestAbort(String reason) {
        abortReason = reason;
        if (inFlight == null) beginRestore(reason);
    }

    private void beginRestore(String reason) {
        abortReason = reason;
        remaining.clear();
        if (pendingRestore.isEmpty() && !sentSlotSwitch) {
            finish(false, reason);
            return;
        }
        restore();
    }

    private void restore() {
        if (!host.canSend()) {
            deferRecovery();
            return;
        }
        phase = Phase.RESTORE;
        host.restoreTiming();
        host.mark("Восстановление исходного слота " + slot);
        readActive(reported -> {
            expectedActive = reported;
            sentSlotSwitch = reported != slot;
            if (reported != slot) {
                selectSlot(slot);
                verifyActive(slot);
            }
            for (byte[] command : pendingRestore.values()) write(command, null);
            readPreset(slot, actual -> requireUnchanged(baseline, actual, "Исходные данные не восстановились"));
        });
        advance();
    }

    private void deferRecovery() {
        phase = Phase.IDLE;
        recoveryPending = true;
        remaining.clear();
        inFlight = null;
        host.done(false, "Профиль " + label + " · " + abortReason
            + " · связь потеряна, восстановление отложено до переподключения");
    }

    private void advance() {
        if (!active() || inFlight != null) return;
        if (remaining.isEmpty()) {
            if (phase == Phase.BASELINE) {
                try {
                    prepareTests();
                } catch (IllegalArgumentException | IllegalStateException problem) {
                    failed(problem.getMessage());
                    return;
                }
            } else if (phase == Phase.TEST) {
                String summary = "EQ " + eqWrites + " · педали " + pedalWrites
                    + " · переключения " + selects + " · чтения " + reads + " · загрузки " + batches
                    + " · проверено " + verified;
                if (ackOnly > 0) {
                    finish(true, summary + " · ACK без проверки применения " + ackOnly
                        + ", контрольное чтение вернуло исходные данные");
                } else {
                    finish(true, summary + " · исходное состояние восстановлено");
                }
                return;
            } else {
                finish(false, abortReason + " · исходные данные восстановлены и проверены");
                return;
            }
        }
        inFlight = remaining.removeFirst();
        if (inFlight.undo != null) {
            pendingRestore.putIfAbsent(key(inFlight.undo), inFlight.undo);
        }
        if (isSelect(inFlight.request)) {
            expectedActive = inFlight.request[6] & 255;
            sentSlotSwitch = true;
        }
        if (inFlight.marker != null) host.mark(inFlight.marker);
        host.send(inFlight.request);
    }

    private void prepareTests() {
        if (baseline == null) throw new IllegalStateException("Нет исходных данных");
        phase = Phase.TEST;
        if (batchRounds > 0) validateFullLoad();
        host.mark("Профиль " + label + " · запись, чтение и восстановление");
        for (int i = 0; i < rounds; i++) {
            if ((stages & EQ) != 0) testEq(i);
            if ((stages & PEDALS) != 0) testPedal(i);
            if ((stages & SLOTS) != 0) testSlots(i == 0 ? "Проверка слотов" : null);
        }
        for (int i = 0; i < batchRounds; i++) fullLoad(i);
        if (remaining.isEmpty()) throw new IllegalStateException("Нет команд для проверки");
    }

    private void testEq(int round) {
        int channel = micEnabled ? round % 3 : round % 2 == 0 ? 0 : 2;
        int band = findBand(channel, round);
        Protocol.Preset altered = alteredBand(channel, band);
        edit(altered.bandFrame(channel, band), baseline.bandFrame(channel, band), altered,
            round == 0 ? "Проверка EQ" : null);
    }

    private void testPedal(int round) {
        int effect = chooseEffect(round);
        Protocol.Preset adjusted = baseline.copy();
        float parameter = adjusted.effect(effect, 0);
        adjusted.effect(effect, 0, parameter <= .95f ? parameter + .025f : parameter - .025f);
        edit(adjusted.effectFrame(effect), baseline.effectFrame(effect), adjusted,
            round == 0 ? "Проверка педалей" : null);
    }

    private void testSlots(String marker) {
        for (int target : switchTargets) {
            Protocol.Preset snapshot = target == slot ? baseline : slotSnapshots[target];
            selectSlot(target, marker);
            readPreset(target, actual -> requireUnchanged(snapshot, actual, "Изменились данные слота " + target));
            verifyActive(target);
            marker = null;
        }
    }

    private void edit(byte[] command, byte[] restore, Protocol.Preset expected, String marker) {
        write(command, restore, marker);
        verifyEdit(expected);
        write(restore, null);
        verifyRestored(restore);
    }

    private void fullLoad(int round) {
        int band = findBand(0, round);
        Protocol.Preset expected = alteredBand(0, band);
        byte[] undo = baseline.bandFrame(0, band);
        for (int channel = 0; channel < 3; channel++) {
            if (channel == 1 && !micEnabled) continue;
            for (int index = 0; index < (channel == 2 ? 4 : 8); index++) {
                String marker = channel == 0 && index == 0 ? "Загрузка " + (round + 1) + " · начало" : null;
                write(expected.bandFrame(channel, index), baseline.bandFrame(channel, index), marker);
            }
        }
        write(expected.orderFrame(), baseline.orderFrame());
        for (int effect = 0; effect < 4; effect++) {
            write(expected.effectFrame(effect), baseline.effectFrame(effect));
        }
        if (micEnabled && expected.micReverb != null && expected.micReverb.length == 13) {
            byte[] reverb = Protocol.frame(14, 0, expected.micReverb);
            write(reverb, reverb);
        }
        readPreset(slot, actual -> {
            checkEdit(expected, actual);
            host.mark("Загрузка " + (round + 1) + " · контрольный ответ");
            batches++;
        });
        write(undo, null);
        readPreset(slot, actual -> {
            requireUnchanged(baseline, actual, "После загрузки исходные байты не восстановились");
            pendingRestore.clear();
        });
    }

    private Protocol.Preset alteredBand(int channel, int band) {
        Protocol.Preset altered = baseline.copy();
        float[] value = altered.band(channel, band);
        float ceiling = channel == 2 ? 0 : 12;
        float change = value[0] > ceiling - .25f ? -.25f : .25f;
        altered.band(channel, band, value[0] + change, value[1], value[2]);
        return altered;
    }

    private int findBand(int channel, int offset) {
        int count = channel == 2 ? 4 : 8;
        for (int i = 0; i < count; i++) {
            int index = (i + offset) % count;
            float[] values = baseline.band(channel, index);
            if (safeBand(channel, values)) return index;
        }
        throw new IllegalStateException("В канале " + channel + " нет полос, пригодных для временной записи");
    }

    private boolean safeBand(int channel, float[] values) {
        return values[0] >= -12 && values[0] <= (channel == 2 ? 0 : 12)
            && values[1] > 0 && values[2] >= 20 && values[2] <= 22000;
    }

    private int chooseEffect(int offset) {
        for (int i = 0; i < 8; i++) {
            int effect = (offset + i) % 4;
            boolean bypassed = (baseline.mask & (1 << effect)) == 0;
            if ((bypassed || i >= 4) && safeEffect(effect)) return effect;
        }
        throw new IllegalStateException("Нет педалей, пригодных для временной записи");
    }

    private boolean safeEffect(int effect) {
        for (int parameter = 0; parameter < 3; parameter++) {
            if (!Protocol.validEffectParameter(effect, parameter, baseline.effect(effect, parameter))) return false;
        }
        return true;
    }

    private void validateFullLoad() {
        for (int channel = 0; channel < 3; channel++) {
            if (channel == 1 && !micEnabled) continue;
            for (int index = 0; index < (channel == 2 ? 4 : 8); index++) {
                if (!safeBand(channel, baseline.band(channel, index))) {
                    throw new IllegalStateException("Исходный EQ содержит значения вне диапазона безопасной загрузки");
                }
            }
        }
        for (int effect = 0; effect < 4; effect++) {
            if (!safeEffect(effect)) {
                throw new IllegalStateException("Параметры педали вне диапазона безопасной загрузки");
            }
        }
        int[] order = baseline.order();
        Arrays.sort(order);
        if (!Arrays.equals(order, new int[] {1, 2, 3, 4})) {
            throw new IllegalStateException("У датчика нестандартный порядок педалей");
        }
    }

    private void verifyEdit(Protocol.Preset expected) {
        readPreset(slot, actual -> checkEdit(expected, actual));
    }

    private void checkEdit(Protocol.Preset expected, Protocol.Preset actual) {
        if (samePreset(expected, actual)) verified++;
        else if (samePreset(baseline, actual)) ackOnly++;
        else throw new IllegalStateException("Чтение вернуло неожиданные байты пресета");
    }

    private void verifyRestored(byte[] restore) {
        readPreset(slot, actual -> {
            requireUnchanged(baseline, actual, "После восстановления байты пресета отличаются");
            pendingRestore.remove(key(restore));
        });
    }

    private void selectSlot(int target) {
        selectSlot(target, null);
    }

    private void selectSlot(int target, String marker) {
        remaining.add(new Step(Protocol.frame(10, 1, new byte[] {(byte) target}), packet -> {
            requireSuccessfulAck(packet);
            if (phase == Phase.TEST) selects++;
        }, null, marker));
    }

    private void verifyActive(int target) {
        readActive(reported -> {
            if (reported != target) {
                throw new IllegalStateException("Датчик сообщил другой активный слот");
            }
            sentSlotSwitch = target != slot;
        });
    }

    private void readActive(Consumer<Integer> consume) {
        remaining.add(new Step(Protocol.frame(10, 6, new byte[0]), packet -> {
            if (packet.length != 9 || !validSlot(packet[6] & 255)) {
                throw new IllegalStateException("Датчик не сообщил активный слот");
            }
            consume.accept(packet[6] & 255);
        }, null));
    }

    private void readPreset(int target, Consumer<Protocol.Preset> consume) {
        remaining.add(new Step(Protocol.frame(10, 2, new byte[] {(byte) target}), packet -> {
            if (packet.length != 333 || (packet[6] & 255) != target) {
                throw new IllegalStateException("Не получен полный ответ нужного слота");
            }
            if (phase == Phase.TEST) reads++;
            consume.accept(new Protocol.Preset(Arrays.copyOfRange(packet, 6, 331)));
        }, null));
    }

    private void write(byte[] command, byte[] undo) {
        write(command, undo, null);
    }

    private void write(byte[] command, byte[] undo, String marker) {
        remaining.add(new Step(command, packet -> {
            requireSuccessfulAck(packet);
            if (phase != Phase.TEST || !Protocol.isPresetEdit(command)) return;
            if ((command[2] & 255) <= 3) eqWrites++;
            else pedalWrites++;
        }, undo, marker));
    }

    private static void requireSuccessfulAck(byte[] packet) {
        if (packet.length != 9 || packet[6] != 0) {
            throw new IllegalStateException("Датчик не подтвердил запись");
        }
    }

    private static boolean samePreset(Protocol.Preset expected, Protocol.Preset actual) {
        return expected != null && Arrays.equals(expected.data, actual.data) && expected.mask == actual.mask;
    }

    private static void requireUnchanged(Protocol.Preset expected, Protocol.Preset actual, String error) {
        if (samePreset(expected, actual)) return;
        if (expected != null) {
            for (int i = 0; i < expected.data.length; i++) {
                if (expected.data[i] != actual.data[i]) {
                    throw new IllegalStateException(error + " · позиция байта " + i);
                }
            }
        }
        throw new IllegalStateException(error);
    }

    private static boolean isSelect(byte[] request) {
        return request[2] == 10 && request[3] == 1;
    }

    private static boolean isActiveRead(byte[] request) {
        return request[2] == 10 && request[3] == 6;
    }

    private static boolean validSlot(int index) {
        return index >= 1 && index <= 4;
    }

    private int[] slotTargets(int alternateSlot, boolean switchSlots, boolean allSlots) {
        if (!switchSlots) return new int[0];
        if (!allSlots) return new int[] {alternateSlot, slot};
        int[] targets = new int[4];
        int index = 0;
        for (int offset = 0; offset < 4; offset++) {
            int target = (alternateSlot - 1 + offset) % 4 + 1;
            if (target != slot) targets[index++] = target;
        }
        targets[3] = slot;
        return targets;
    }

    private static int key(byte[] request) {
        return (request[2] & 255) << 8 | request[3] & 255;
    }

    private void finish(boolean success, String result) {
        phase = Phase.IDLE;
        recoveryPending = false;
        remaining.clear();
        pendingRestore.clear();
        inFlight = null;
        supplied = baseline = null;
        Arrays.fill(slotSnapshots, null);
        switchTargets = null;
        host.done(success, "Профиль " + label + " · " + result);
    }
}

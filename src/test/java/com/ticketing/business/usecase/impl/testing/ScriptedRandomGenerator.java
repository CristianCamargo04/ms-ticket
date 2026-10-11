package com.ticketing.business.usecase.impl.testing;

import java.util.random.RandomGenerator;

/**
 * Deterministic {@link RandomGenerator} replaying a script of values: {@link #nextInt(int)} returns the next scripted
 * value reduced modulo the bound (the script cycles), so tests can pin the random start ticket and the random sample.
 */
public final class ScriptedRandomGenerator implements RandomGenerator {

    private final int[] script;
    private int position;

    public ScriptedRandomGenerator(int... script) {
        if (script.length == 0) {
            throw new IllegalArgumentException("script must not be empty");
        }
        this.script = script.clone();
    }

    @Override
    public synchronized int nextInt(int bound) {
        int value = script[position % script.length];
        position++;
        return Math.floorMod(value, bound);
    }

    @Override
    public long nextLong() {
        return nextInt(Integer.MAX_VALUE);
    }
}

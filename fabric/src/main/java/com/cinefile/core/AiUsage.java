package com.cinefile.core;

/** Token counts for one run and their approximate cost (DeepSeek prices, USD per million tokens). */
public final class AiUsage {
    static final double PRICE_IN = 0.15;
    static final double PRICE_OUT = 0.60;
    private int in;
    private int out;

    public synchronized void add(int in, int out) {
        this.in += in;
        this.out += out;
    }

    public synchronized int in() { return in; }

    public synchronized int out() { return out; }

    public synchronized double estimateUsd() {
        return in / 1_000_000.0 * PRICE_IN + out / 1_000_000.0 * PRICE_OUT;
    }
}

package com.lionclient.feature.module.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ClickPatternStore {
    private static final List<Integer> delays = new ArrayList<Integer>();

    private ClickPatternStore() {
    }

    public static void clear() {
        delays.clear();
    }

    public static void addDelay(int delay) {
        delays.add(Integer.valueOf(Math.max(0, delay)));
    }

    public static boolean isEmpty() {
        return delays.isEmpty();
    }

    public static int size() {
        return delays.size();
    }

    public static List<Integer> getDelays() {
        return Collections.unmodifiableList(new ArrayList<Integer>(delays));
    }
}

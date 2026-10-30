package com.moveinsync.config.core.model;

public enum ScopeLevel {
    GLOBAL(1),
    CLIENT(2),
    OFFICE(3),
    SHIFT(4);

    private final int specificity;

    ScopeLevel(int specificity) {
        this.specificity = specificity;
    }

    public int getSpecificity() {
        return specificity;
    }
}

package com.ticketing.enums;

public enum RuntimeModeEnum {
    ALL,
    API,
    WORKER;

    public boolean apiEnabled() {
        return switch (this) {
            case ALL, API -> true;
            case WORKER -> false;
        };
    }

    public boolean workerEnabled() {
        return switch (this) {
            case ALL, WORKER -> true;
            case API -> false;
        };
    }
}

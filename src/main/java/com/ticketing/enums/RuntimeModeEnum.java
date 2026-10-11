package com.ticketing.enums;

/**
 * Runtime mode of a deployed instance: the same image can serve the API, run the workers, or both.
 */
public enum RuntimeModeEnum {
    ALL,
    API,
    WORKER;

    /**
     * Whether the HTTP API must be served.
     *
     * @return {@code true} for {@link #ALL} and {@link #API}
     */
    public boolean apiEnabled() {
        return switch (this) {
            case ALL, API -> true;
            case WORKER -> false;
        };
    }

    /**
     * Whether the SQS consumers and the sweepers must run.
     *
     * @return {@code true} for {@link #ALL} and {@link #WORKER}
     */
    public boolean workerEnabled() {
        return switch (this) {
            case ALL, WORKER -> true;
            case API -> false;
        };
    }
}

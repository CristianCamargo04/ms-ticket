package com.ticketing.domain.data;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

public record PageData<T>(List<T> items, String nextCursor) {
    public PageData {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }

    public static <T> PageData<T> empty() {
        return new PageData<>(List.of(), null);
    }

    public boolean hasMore() {
        return nextCursor != null;
    }

    public <R> PageData<R> map(Function<? super T, ? extends R> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        return new PageData<>(items.stream().<R>map(mapper).toList(), nextCursor);
    }
}

package com.ticketing.domain.data;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * One page of a cursor-paginated listing.
 *
 * @param items      the items of the page (defensively copied, never {@code null})
 * @param nextCursor opaque cursor of the next page, {@code null} on the last page
 * @param <T>        item type
 */
public record PageData<T>(List<T> items, String nextCursor) {

    /**
     * Copies {@code items} defensively.
     */
    public PageData {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }

    /**
     * An empty last page.
     *
     * @param <T> item type
     * @return a page without items nor next cursor
     */
    public static <T> PageData<T> empty() {
        return new PageData<>(List.of(), null);
    }

    /**
     * Whether another page can be requested.
     *
     * @return {@code true} when {@link #nextCursor()} is not {@code null}
     */
    public boolean hasMore() {
        return nextCursor != null;
    }

    /**
     * Converts the items while keeping the cursor.
     *
     * @param mapper item conversion
     * @param <R>    target item type
     * @return the converted page
     */
    public <R> PageData<R> map(Function<? super T, ? extends R> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        return new PageData<>(items.stream().<R>map(mapper).toList(), nextCursor);
    }
}

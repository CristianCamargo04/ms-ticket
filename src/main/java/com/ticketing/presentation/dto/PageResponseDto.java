package com.ticketing.presentation.dto;

import java.util.List;

/**
 * One page of a cursor-paginated listing.
 *
 * @param items      the items of the page
 * @param nextCursor opaque cursor of the next page, {@code null} on the last page
 * @param <T>        item type
 */
public record PageResponseDto<T>(List<T> items, String nextCursor) {
}

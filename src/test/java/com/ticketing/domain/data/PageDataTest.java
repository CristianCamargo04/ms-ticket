package com.ticketing.domain.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PageDataTest {
    @Test
    void shouldHaveMoreWhenNextCursorIsPresent() {
        assertThat(new PageData<>(List.of("a"), "cursor").hasMore()).isTrue();
        assertThat(new PageData<>(List.of("a"), null).hasMore()).isFalse();
    }

    @Test
    void shouldCreateEmptyLastPage() {
        PageData<String> page = PageData.empty();

        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void shouldCopyItemsDefensively() {
        List<String> items = new ArrayList<>(List.of("a", "b"));

        PageData<String> page = new PageData<>(items, null);
        items.add("c");

        assertThat(page.items()).containsExactly("a", "b");
        assertThatThrownBy(() -> page.items().add("d")).isInstanceOf(UnsupportedOperationException.class);
        assertThatNullPointerException().isThrownBy(() -> new PageData<String>(null, null));
    }

    @Test
    void shouldMapItemsAndKeepCursor() {
        PageData<Integer> lengths = new PageData<>(List.of("a", "bbb"), "next").map(String::length);

        assertThat(lengths.items()).containsExactly(1, 3);
        assertThat(lengths.nextCursor()).isEqualTo("next");
        assertThatNullPointerException().isThrownBy(() -> lengths.map(null));
    }
}

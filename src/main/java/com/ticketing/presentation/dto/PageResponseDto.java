package com.ticketing.presentation.dto;

import java.util.List;

public record PageResponseDto<T>(List<T> items, String nextCursor) {
}

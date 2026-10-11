package com.ticketing.presentation.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record IssueComplimentaryRequestDto(@NotBlank @Size(max = 128) String beneficiaryId,
                                           @Min(1) @Max(40) int quantity) {
}

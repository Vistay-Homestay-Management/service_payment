package com.vistay.payment.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RecordUatEvidenceCommand(
        @NotBlank @Size(max = 180) String testReference,
        String notes
) {
}


package com.vistay.payment.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RecordUatEvidenceCommand(
        @NotBlank(message = "UAT test reference cannot be blank")
        @Size(max = 180)
        String testReference,

        String notes
) {
}


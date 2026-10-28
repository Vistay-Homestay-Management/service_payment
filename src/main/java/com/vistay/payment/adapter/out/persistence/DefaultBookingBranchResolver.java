package com.vistay.payment.adapter.out.persistence;

import com.vistay.payment.application.BookingBranchResolver;
import com.vistay.payment.domain.BookingCharge;
import com.vistay.payment.domain.BookingChargeRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
@ConditionalOnMissingBean(BookingBranchResolver.class)
public class DefaultBookingBranchResolver implements BookingBranchResolver {

    private final BookingChargeRepository chargeRepository;

    public DefaultBookingBranchResolver(BookingChargeRepository chargeRepository) {
        this.chargeRepository = chargeRepository;
    }

    @Override
    public Optional<UUID> resolveBranchId(UUID bookingId) {
        if (bookingId == null) {
            return Optional.empty();
        }
        List<BookingCharge> charges = chargeRepository.findByBookingId(bookingId);
        return charges.stream()
                .map(BookingCharge::getBranchId)
                .filter(Objects::nonNull)
                .findFirst();
    }
}


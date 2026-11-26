package com.buildhive.common.dto;

import java.time.Instant;

/** New expiry of a currently owned job attempt. */
public record LeaseRenewalDto(Instant leaseExpiresAt) {}

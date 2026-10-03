package org.octavio.paymentreconciliationsim.model;

public record CreationResult<T>(T value, boolean created) {}

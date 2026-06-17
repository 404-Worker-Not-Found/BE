package com.workernotfound.job.domain.job.port;

public interface BusinessValidator {

    void validateOwnership(Long businessId, Long ownerId);
}

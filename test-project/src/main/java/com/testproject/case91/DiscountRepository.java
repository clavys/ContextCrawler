package com.testproject.case91;

import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DiscountRepository {
    List<DiscountEntity> findAllActive();
}

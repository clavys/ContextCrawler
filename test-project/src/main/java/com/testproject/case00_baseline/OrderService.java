package com.testproject.case00_baseline;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    @Autowired
    private OrderRepository repository;

    // @TestTarget
    public OrderDTO findOrder(String reference) {
        OrderEntity entity = repository.findByReference(reference);
        if (entity == null) {
            return null;
        }
        return new OrderDTO(entity.getId(), entity.getReference(), entity.getAmount());
    }
}

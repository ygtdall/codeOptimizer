package com.atmosware.optimizer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;

@Service
public class OrderService {

    private OrderRepository repository;
    private EntityManager em;

    @Transactional
    public String buildReport(List<Long> ids) {
        String result = "";
        List<Order> orders = new ArrayList<>();
        for (Long id : ids) {
            orders.add(repository.findById(id).orElse(null));
        }
        for (Order o : orders) {
            result = result + o.getCustomer().getName() + ";";
            for (Item i : o.getItems()) {
                result = result + i.getName() + ",";
            }
        }
        if (result.isEmpty()) {
            return "";
        }
        for (Long id : ids) {
            if (repository.findById(id).isEmpty()) {
                result = result + "MISSING";
            }
        }
        return result;
    }

    public void clear() {
        em.clear();
    }
}
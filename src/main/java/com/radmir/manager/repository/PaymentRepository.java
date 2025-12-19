package com.radmir.manager.repository;

import com.radmir.manager.model.Payment;
import org.springframework.data.repository.CrudRepository;
import java.util.List;

public interface PaymentRepository extends CrudRepository<Payment, Long> {
    List<Payment> findAllByChatId(Long chatId);
}

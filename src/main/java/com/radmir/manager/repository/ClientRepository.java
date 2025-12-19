package com.radmir.manager.repository;

import com.radmir.manager.model.ClientRecord;
import org.springframework.data.repository.CrudRepository;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

public interface ClientRepository extends CrudRepository<ClientRecord, Long> {
    List<ClientRecord> findAllByChatId(Long chatId);

    @Transactional
    void deleteAllByChatId(Long chatId);
}
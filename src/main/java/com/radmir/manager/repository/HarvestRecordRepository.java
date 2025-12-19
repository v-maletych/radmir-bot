package com.radmir.manager.repository;

import com.radmir.manager.model.HarvestRecord;
import org.springframework.data.repository.CrudRepository;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

public interface HarvestRecordRepository extends CrudRepository<HarvestRecord, Long> {
    List<HarvestRecord> findAllByChatId(Long chatId);

    @Transactional
    void deleteAllByChatId(Long chatId);
}
package com.radmir.manager.repository;

import com.radmir.manager.model.Ogorod;
import org.springframework.data.repository.CrudRepository;
import java.util.List;

public interface OgorodRepository extends CrudRepository<Ogorod, Long> {
    List<Ogorod> findAllByChatId(Long chatId);
}
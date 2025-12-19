package com.radmir.manager.repository;

import com.radmir.manager.model.User;
import org.springframework.data.repository.CrudRepository;

public interface UserRepository extends CrudRepository<User, Long> {
}

package com.radmir.manager.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Data
@Table(name = "users")
public class User {
    @Id
    private Long chatId;
    private String firstName;
    private String userName;
    private String phoneNumber;
    private boolean registered;
}

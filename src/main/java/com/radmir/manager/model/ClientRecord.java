package com.radmir.manager.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "clients")
@Data
public class ClientRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long chatId;
    private String nickname;
    private String ogorodName;
    private String contact;

    private Double price; // Підсумкова ціна (може змінитися при достроковому завершенні)

    private LocalDateTime startDate;
    private Integer duration;
    private String durationUnit; // "год" або "дн"

    private LocalDateTime endDate;

    private boolean notificationSent;

    // НОВЕ ПОЛЕ: Причина завершення
    // null (ще йде), "EXPIRED" (час вийшов сам), "CLIENT_EARLY", "OWNER_EARLY", "OGOROD_DELETED"
    private String terminationReason;
}
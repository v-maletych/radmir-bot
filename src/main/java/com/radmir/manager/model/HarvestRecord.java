package com.radmir.manager.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "harvest_records")
@Data
public class HarvestRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long chatId;
    private Long ogorodId;
    private String ogorodName;
    private Double amount; // Заробіток
    private LocalDateTime harvestedAt; // Дата збору
}
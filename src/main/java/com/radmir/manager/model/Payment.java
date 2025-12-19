package com.radmir.manager.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;

@Entity
@Data
@Table(name = "payments")
public class Payment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long chatId;
    private String name;
    private Double price;

    private LocalDate purchaseDate; // Дата покупки (історія)
    private LocalDate paidUntil;    // Таймер (коли закінчиться)

    private Integer daysPaid;       // Додаємо назад: На скільки днів була оплата (інформативно)
}
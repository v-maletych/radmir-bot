package com.radmir.manager.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "ogorods")
public class Ogorod {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long chatId;
    private String title;
    private Double price; // Ціна покупки самого огороду

    private LocalDate purchaseDate;
    private LocalDate paidUntil;

    private Integer daysPaid;

    // --- НОВІ ПОЛЯ ДЛЯ УРОЖАЮ ---

    // Налаштування (Параметри)
    private Integer growthTimeMinutes;   // Загальний час росту (наприклад 210 хв = 3:30)
    private Integer wateringIntervalMinutes; // Інтервал поливу (наприклад 30 хв)
    private Double harvestProfit;        // Скільки грошей дає один збір

    // Стан
    // "IDLE" (пусто), "GROWING" (росте), "WAITING_WATER" (чекає поливу), "READY" (готово)
    private String harvestState;

    // Таймери
    private LocalDateTime growthStartTime; // Коли посадили
    private LocalDateTime lastWateringTime; // Коли останній раз полили (або посадили)
    private Integer accumulatedGrowthMinutes; // Скільки хвилин вже "наросло" (для пауз)
}
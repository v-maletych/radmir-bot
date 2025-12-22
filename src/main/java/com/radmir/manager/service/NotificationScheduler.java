package com.radmir.manager.service;

import com.radmir.manager.bot.RadmirBot;
import com.radmir.manager.model.ClientRecord;
import com.radmir.manager.model.Ogorod;
import com.radmir.manager.model.Payment;
import com.radmir.manager.repository.ClientRepository;
import com.radmir.manager.repository.OgorodRepository;
import com.radmir.manager.repository.PaymentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class NotificationScheduler {

    @Autowired private PaymentRepository paymentRepository;
    @Autowired private OgorodRepository ogorodRepository;
    @Autowired private ClientRepository clientRepository;
    @Autowired private RadmirBot bot;

    private static final ZoneId KYIV_ZONE = ZoneId.of("Europe/Kiev");

    // --- НОВА ЛОГІКА: Перевірка оплат (Записи + Огороди) ---
    // Запускається щодня о 09:00 ранку
    @Scheduled(cron = "0 0 9 * * *", zone = "Europe/Kiev")
    public void checkDailyPayments() {
        LocalDate today = LocalDate.now(KYIV_ZONE);

        // 1. ПЕРЕВІРКА ЗАПИСІВ (Payments)
        List<Payment> payments = (List<Payment>) paymentRepository.findAll();
        for (Payment p : payments) {
            if (p.getPaidUntil() != null) {
                long daysLeft = ChronoUnit.DAYS.between(today, p.getPaidUntil());

                if (daysLeft > 0 && daysLeft <= 3) {
                    // Нагадування за 3, 2, 1 дні
                    bot.sendNotification(p.getChatId(),
                            "⚠️ <b>Напоминание!</b>\nЗаканчивается оплата: '" + p.getName() + "'.\n⏳ Осталось дней: <b>" + daysLeft + "</b>",
                            p.getId());
                } else if (daysLeft == 0) {
                    // День 0 (Сьогодні)
                    bot.sendNotification(p.getChatId(),
                            "🔥 <b>ВНИМАНИЕ!</b>\nЗапись: '" + p.getName() + "'.\nЗавтра будет -1 день оплаты - <b>оплати сегодня!!!</b>",
                            p.getId());
                } else if (daysLeft < 0) {
                    // Прострочено
                    bot.sendNotification(p.getChatId(),
                            "❌ <b>ПРОСРОЧЕНО!</b>\nЗапись: '" + p.getName() + "'.\nДней долга: " + Math.abs(daysLeft),
                            p.getId());
                }
            }
        }

        // 2. ПЕРЕВІРКА ОГОРОДІВ (Ogorods)
        List<Ogorod> ogorods = (List<Ogorod>) ogorodRepository.findAll();
        for (Ogorod o : ogorods) {
            if (o.getPaidUntil() != null) {
                long daysLeft = ChronoUnit.DAYS.between(today, o.getPaidUntil());

                if (daysLeft > 0 && daysLeft <= 3) {
                    // Нагадування за 3, 2, 1 дні
                    bot.sendOgorodNotification(o.getChatId(),
                            "⚠️ <b>Огород!</b>\nЗаканчивается оплата: '" + o.getTitle() + "'.\n⏳ Осталось дней: <b>" + daysLeft + "</b>",
                            o.getId());
                } else if (daysLeft == 0) {
                    // День 0 (Сьогодні)
                    bot.sendOgorodNotification(o.getChatId(),
                            "🔥 <b>ВНИМАНИЕ!</b>\nОгород: '" + o.getTitle() + "'.\nЗавтра будет -1 день оплаты - <b>оплати сегодня!!!</b>",
                            o.getId());
                } else if (daysLeft < 0) {
                    // Прострочено
                    bot.sendOgorodNotification(o.getChatId(),
                            "❌ <b>ОГОРОД ПРОСРОЧЕН!</b>\n'" + o.getTitle() + "'.\nМожет слететь! Дней долга: " + Math.abs(daysLeft),
                            o.getId());
                }
            }
        }
    }

    // --- СТАРИЙ МЕТОД: КЛІЄНТИ (Залишив без змін, як у тебе було) ---
    @Scheduled(cron = "0 0 * * * *")
    public void checkClientExpirations() {
        List<ClientRecord> clients = (List<ClientRecord>) clientRepository.findAll();
        LocalDateTime now = LocalDateTime.now(KYIV_ZONE);
        for (ClientRecord c : clients) {
            if (c.isNotificationSent()) continue;
            LocalDateTime end = c.getEndDate();
            if (end == null) continue;
            long hoursLeft = Duration.between(now, end).toHours();
            if (hoursLeft >= 23 && hoursLeft <= 24) {
                bot.sendMessage(c.getChatId(), "⏰ <b>КЛИЕНТ ИСТЕКАЕТ ЧЕРЕЗ 24 ЧАСА</b>\n👤 " + c.getNickname() + "\n🏡 " + c.getOgorodName());
                c.setNotificationSent(true);
                clientRepository.save(c);
            }
        }
    }

    // --- ВАЖЛИВИЙ МЕТОД: УРОЖАЙ (Залишив повністю без змін) ---
    @Scheduled(cron = "0 * * * * *")
    public void checkHarvestCycles() {
        List<Ogorod> ogorods = (List<Ogorod>) ogorodRepository.findAll();
        LocalDateTime now = LocalDateTime.now(KYIV_ZONE);

        for (Ogorod o : ogorods) {
            // Перевіряємо лише ті, що ростуть
            if ("GROWING".equals(o.getHarvestState())) {

                // 1. Скільки часу пройшло з останнього "руху"
                long minutesSinceLastWater = ChronoUnit.MINUTES.between(o.getLastWateringTime(), now);

                // 2. Поточний прогрес
                long currentTotalProgress = (o.getAccumulatedGrowthMinutes() == null ? 0 : o.getAccumulatedGrowthMinutes()) + minutesSinceLastWater;

                // Перевірка 1: Чи виріс урожай повністю?
                if (currentTotalProgress >= o.getGrowthTimeMinutes()) {
                    o.setHarvestState("READY");
                    o.setAccumulatedGrowthMinutes((int) currentTotalProgress);
                    ogorodRepository.save(o);
                    bot.sendMessage(o.getChatId(), "🌽 <b>УРОЖАЙ ГОТОВ!</b>\n🏡 Огород: <b>" + o.getTitle() + "</b>\n💰 Жмите «Собрать» в меню!");
                    continue;
                }

                // Перевірка 2: Чи пора поливати?
                if (minutesSinceLastWater >= o.getWateringIntervalMinutes()) {
                    // Ставимо на ПАУЗУ
                    o.setHarvestState("WAITING_WATER");
                    o.setAccumulatedGrowthMinutes((int) ((o.getAccumulatedGrowthMinutes() == null ? 0 : o.getAccumulatedGrowthMinutes()) + minutesSinceLastWater));
                    ogorodRepository.save(o);
                    bot.sendMessage(o.getChatId(), "💧 <b>НУЖЕН ПОЛИВ!</b>\n🏡 Огород: <b>" + o.getTitle() + "</b>\n⏸ Рост остановлен пока не польете.");
                }
            }
        }
    }
}
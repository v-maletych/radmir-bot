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

    // --- СТАРІ МЕТОДИ (Оплата) ---
    @Scheduled(cron = "0 0 7 * * *")
    @Scheduled(cron = "0 30 23 * * *")
    public void checkPaymentExpirations() {
        List<Payment> allPayments = (List<Payment>) paymentRepository.findAll();
        LocalDate today = LocalDate.now(KYIV_ZONE);
        for (Payment p : allPayments) {
            if (p.getPaidUntil() == null) continue;
            long daysLeft = ChronoUnit.DAYS.between(today, p.getPaidUntil());
            if (daysLeft == 5) bot.sendNotification(p.getChatId(), "⚠️ <b>НАПОМИНАНИЕ</b>\nЗапись: <b>" + p.getName() + "</b>\nИстекает через 5 дней", p.getId());
            if (daysLeft == 1) bot.sendNotification(p.getChatId(), "🚨 <b>ВНИМАНИЕ!</b>\nЗапись: <b>" + p.getName() + "</b>\nИстекает ЗАВТРА", p.getId());
        }
    }

    @Scheduled(cron = "0 0 7 * * *")
    @Scheduled(cron = "0 30 23 * * *")
    public void checkOgorodExpirations() {
        List<Ogorod> allOgorods = (List<Ogorod>) ogorodRepository.findAll();
        LocalDate today = LocalDate.now(KYIV_ZONE);
        for (Ogorod o : allOgorods) {
            if (o.getPaidUntil() == null) continue;
            long daysLeft = ChronoUnit.DAYS.between(today, o.getPaidUntil());
            if (daysLeft <= 3 && daysLeft > 1) bot.sendOgorodNotification(o.getChatId(), "⚠️ <b>ОГОРОД: ОПЛАТА</b>\nName: <b>" + o.getTitle() + "</b>\nИстекает через " + daysLeft + " дн.", o.getId());
            if (daysLeft == 1) bot.sendOgorodNotification(o.getChatId(), "🚨 <b>ОГОРОД: ЗАВТРА КОНЕЦ ОПЛАТЫ</b>\nName: <b>" + o.getTitle() + "</b>", o.getId());
        }
    }

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

    // --- НОВИЙ МЕТОД: УРОЖАЙ (Кожну хвилину) ---
    @Scheduled(cron = "0 * * * * *")
    public void checkHarvestCycles() {
        List<Ogorod> ogorods = (List<Ogorod>) ogorodRepository.findAll();
        LocalDateTime now = LocalDateTime.now(KYIV_ZONE);

        for (Ogorod o : ogorods) {
            // Перевіряємо лише ті, що ростуть
            if ("GROWING".equals(o.getHarvestState())) {

                // 1. Скільки часу пройшло з останнього "руху" (посадки або поливу)
                long minutesSinceLastWater = ChronoUnit.MINUTES.between(o.getLastWateringTime(), now);

                // 2. Поточний прогрес = Накопичено раніше + Те, що пройшло зараз
                long currentTotalProgress = (o.getAccumulatedGrowthMinutes() == null ? 0 : o.getAccumulatedGrowthMinutes()) + minutesSinceLastWater;

                // Перевірка 1: Чи виріс урожай повністю?
                if (currentTotalProgress >= o.getGrowthTimeMinutes()) {
                    o.setHarvestState("READY");
                    o.setAccumulatedGrowthMinutes((int) currentTotalProgress); // Фіксуємо фінал
                    ogorodRepository.save(o);
                    bot.sendMessage(o.getChatId(), "🌽 <b>УРОЖАЙ ГОТОВ!</b>\n🏡 Огород: <b>" + o.getTitle() + "</b>\n💰 Жмите «Собрать» в меню!");
                    continue; // Переходимо до наступного, цей вже все
                }

                // Перевірка 2: Чи пора поливати?
                if (minutesSinceLastWater >= o.getWateringIntervalMinutes()) {
                    // Ставимо на ПАУЗУ
                    o.setHarvestState("WAITING_WATER");
                    // Зберігаємо прогрес, який встиг нарости до цього моменту
                    o.setAccumulatedGrowthMinutes((int) ((o.getAccumulatedGrowthMinutes() == null ? 0 : o.getAccumulatedGrowthMinutes()) + minutesSinceLastWater));
                    // Час lastWateringTime не оновлюємо тут, він оновиться коли юзер натисне "Полив"
                    ogorodRepository.save(o);
                    bot.sendMessage(o.getChatId(), "💧 <b>НУЖЕН ПОЛИВ!</b>\n🏡 Огород: <b>" + o.getTitle() + "</b>\n⏸ Рост остановлен пока не польете.");
                }
            }
        }
    }
}
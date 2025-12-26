package com.radmir.manager.bot;

import com.radmir.manager.model.*;
import com.radmir.manager.repository.*;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class RadmirBot extends TelegramLongPollingBot {

    @Autowired private BotConfig config;
    @Autowired private UserRepository userRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private OgorodRepository ogorodRepository;
    @Autowired private ClientRepository clientRepository;
    @Autowired private HarvestRecordRepository harvestRecordRepository;

    private Map<Long, UserState> userStateMap = new HashMap<>();

    // Drafts
    private Map<Long, Payment> paymentDraftMap = new HashMap<>();
    private Map<Long, Ogorod> ogorodDraftMap = new HashMap<>();
    private Map<Long, ClientRecord> clientDraftMap = new HashMap<>();

    // Temp
    private Map<Long, Long> extensionPaymentIdMap = new HashMap<>();
    private Map<Long, Long> extensionOgorodIdMap = new HashMap<>();
    private Map<Long, Long> editPaymentIdMap = new HashMap<>();
    private Map<Long, String> editFieldMap = new HashMap<>();
    private Map<Long, Long> editClientIdMap = new HashMap<>();
    private Map<Long, Long> editOgorodIdMap = new HashMap<>();
    private Map<Long, Long> terminateClientIdMap = new HashMap<>();

    // Harvest Temp
    private Map<Long, Long> harvestParamOgorodId = new HashMap<>();

    // Calc & Stats
    private Map<Long, String> calcModeMap = new HashMap<>();
    private Map<Long, Integer> calcAmountMap = new HashMap<>();
    private Map<Long, String> statsPeriodMap = new HashMap<>();
    private Map<Long, String> statsTypeMap = new HashMap<>();

    private static final ZoneId KYIV_ZONE = ZoneId.of("Europe/Kiev");

    @Override
    public String getBotUsername() { return config.getBotName(); }
    @Override
    public String getBotToken() { return config.getToken(); }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasCallbackQuery()) {
            handleCallback(update.getCallbackQuery());
            return;
        }
        if (update.hasMessage()) {
            handleMessage(update.getMessage());
        }
    }

    private void handleMessage(Message message) {
        Long chatId = message.getChatId();
        if (message.hasContact()) { handleRegistration(message); return; }
        if (message.hasText()) {
            String text = message.getText();
            if (!isRegistered(chatId)) { handleRegistration(message); return; }

            if (text.equals("🔙 Отмена")) {
                UserState currentState = userStateMap.getOrDefault(chatId, UserState.DEFAULT);
                resetUserState(chatId);
                String stateName = currentState.name();
                if (stateName.startsWith("AWAITING_OGOROD")) showOgorodSubMenu(chatId);
                else if (stateName.startsWith("AWAITING_CLIENT")) showClientSubMenu(chatId);
                else if (stateName.startsWith("AWAITING_HARVEST")) showHarvestMenu(chatId);
                else if (stateName.startsWith("AWAITING_CALC") || stateName.startsWith("AWAITING_STATS")) showOgorodManagerMenu(chatId);
                else showMainMenu(chatId, "🚫 Действие отменено.");
                return;
            }

            if (text.equals("⏭ Пропустить контакт")) {
                if (userStateMap.get(chatId) == UserState.AWAITING_CLIENT_CONTACT) {
                    processClientContact(chatId, "Нет контакта");
                    return;
                }
            }

            UserState state = userStateMap.getOrDefault(chatId, UserState.DEFAULT);
            if (state == UserState.DEFAULT) {
                switch (text) {
                    case "/start": showMainMenu(chatId, "👋 Привет!"); break;

                    case "📝 Новая запись": startAddingPayment(chatId); break;
                    case "📋 Мои записи": showPayments(chatId); break;
                    case "✏️ Редактировать": startEditingPayment(chatId); break;
                    case "❌ Удалить запись": startDeletingPayment(chatId); break;

                    case "🥬 Менеджер огородов": showOgorodManagerMenu(chatId); break;
                    case "🔙 Главное меню": showMainMenu(chatId, "Главное меню"); break;
                    case "🔙 Менеджер": showOgorodManagerMenu(chatId); break;

                    case "🏡 Огороды": showOgorodSubMenu(chatId); break;
                    case "👥 Клиенты": showClientSubMenu(chatId); break;
                    case "🌽 Мой урожай": showHarvestMenu(chatId); break;
                    case "🧮 Калькулятор": startCalculator(chatId); break;
                    case "📊 Статистика": startStatistics(chatId); break;

                    case "📜 Список огородов": showOgorodList(chatId); break;
                    case "➕ Добавить огород": startAddingOgorod(chatId); break;
                    case "✏️ Ред. огород": startEditingOgorod(chatId); break;
                    case "❌ Удалить огород": startDeletingOgorod(chatId); break;

                    // Harvest
                    case "🌱 Посадил": startHarvestCycle(chatId); break;
                    case "💧 Полил": performWatering(chatId); break;
                    case "🚜 Собрал": collectHarvest(chatId); break;
                    case "⏱ Состояние": showHarvestStatus(chatId); break;
                    case "🔄 Сброс": showHarvestResetMenu(chatId); break;
                    case "⚙️ Параметры": setupHarvestParams(chatId); break;
                    case "📈 Статистика урожая": startHarvestStatistics(chatId); break;

                    // Clients
                    case "📜 Список клиентов": showClientsList(chatId); break;
                    case "➕ Добавить клиента": startAddingClient(chatId); break;
                    case "🔍 Поиск клиента": startSearchingClient(chatId); break;
                    case "📥 Скачать Excel": generateClientsExcel(chatId, null); break;
                    case "✏️ Ред. клиента": startEditingClientInput(chatId); break;
                    case "❌ Удалить клиента": startDeletingClient(chatId); break;
                    case "🛑 Завершить досрочно": startTerminatingClient(chatId); break;

                    default: showMainMenu(chatId, "Выберите действие из меню.");
                }
            } else {
                processInput(chatId, text, state);
            }
        }
    }

    private void handleCallback(CallbackQuery q) {
        String data = q.getData();
        Long chatId = q.getMessage().getChatId();
        Integer msgId = q.getMessage().getMessageId();

        // 1. Обробка натискання на кнопки значень (02:50, 35, 193.950)
        if (data.startsWith("val_")) {
            String value = data.replace("val_", "");
            // Передаємо значення так, ніби користувач ввів його вручну
            processInput(chatId, value, userStateMap.get(chatId));
        }

        // 2. Harvest Params (Start)
        else if (data.startsWith("h_param_")) {
            Long oid = Long.parseLong(data.split("_")[2]);
            harvestParamOgorodId.put(chatId, oid);
            userStateMap.put(chatId, UserState.AWAITING_HARVEST_GROWTH_TIME);

            // КНОПКА "02:50" (Inline)
            SendMessage m = new SendMessage();
            m.setChatId(chatId);
            m.setText("Введите время роста (ЧЧ:ММ), например 3:30\nИли выберите:");

            InlineKeyboardMarkup mk = new InlineKeyboardMarkup();
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            List<InlineKeyboardButton> row = new ArrayList<>();
            row.add(createBtn("02:50", "val_02:50"));
            rows.add(row);
            mk.setKeyboard(rows);
            m.setReplyMarkup(mk);

            // Залишаємо кнопку "Отмена" знизу
            sendMessageWithCancel(chatId, ""); // Пустий текст, просто щоб оновити нижню клаву
            try { execute(m); } catch (Exception e) {}
        }

        else if (data.startsWith("h_plant_")) processPlanting(chatId, Long.parseLong(data.split("_")[2]));
        else if (data.startsWith("h_water_")) processWateringConfirm(chatId, Long.parseLong(data.split("_")[2]));
        else if (data.startsWith("h_collect_")) processCollectingConfirm(chatId, Long.parseLong(data.split("_")[2]));

            // Harvest Reset
        else if (data.equals("h_reset_all")) processHarvestReset(chatId, -1L);
        else if (data.startsWith("h_reset_")) processHarvestReset(chatId, Long.parseLong(data.split("_")[2]));

            // Stats Reset
        else if (data.equals("stats_reset_harvest")) askResetHarvestStats(chatId);
        else if (data.equals("stats_reset_clients")) askResetClientStats(chatId);
        else if (data.equals("confirm_reset_h_yes")) processResetHarvestStats(chatId);
        else if (data.equals("confirm_reset_c_yes")) processResetClientStats(chatId);
        else if (data.startsWith("confirm_reset_")) sendMessage(chatId, "🚫 Сброс отменен.");

            // Clients Actions
        else if (data.startsWith("client_pick_ogorod_")) checkOgorodAvailability(chatId, Long.parseLong(data.split("_")[3]), msgId);
        else if (data.equals("client_confirm_override_yes")) sendClientUnitChoice(chatId);
        else if (data.equals("client_confirm_override_no")) { sendMessage(chatId, "🚫 Отмена."); showOgorodPickerForClient(chatId); }
        else if (data.equals("client_unit_hours")) processClientUnit(chatId, "год");
        else if (data.equals("client_unit_days")) processClientUnit(chatId, "дн");
        else if (data.equals("client_start_now")) finishAddingClient(chatId, LocalDateTime.now(KYIV_ZONE));
        else if (data.equals("client_start_custom")) askClientCustomDate(chatId);
            // Client Termination
        else if (data.equals("term_client")) processTermination(chatId, "CLIENT_EARLY");
        else if (data.equals("term_owner")) processTermination(chatId, "OWNER_EARLY");

            // Buttons
        else if (data.equals("btn_date_today_payment")) {
            paymentDraftMap.get(chatId).setPurchaseDate(LocalDate.now(KYIV_ZONE));
            userStateMap.put(chatId, UserState.AWAITING_PRICE);
            askForPaymentPrice(chatId);
        }
        else if (data.equals("btn_skip_date_payment")) {
            paymentDraftMap.get(chatId).setPurchaseDate(null);
            userStateMap.put(chatId, UserState.AWAITING_PRICE);
            askForPaymentPrice(chatId);
        }
        else if (data.equals("btn_date_today_ogorod")) {
            ogorodDraftMap.get(chatId).setPurchaseDate(LocalDate.now(KYIV_ZONE));
            userStateMap.put(chatId, UserState.AWAITING_OGOROD_PRICE);
            askForOgorodPrice(chatId);
        }
        else if (data.equals("btn_skip_date_ogorod")) {
            ogorodDraftMap.get(chatId).setPurchaseDate(null);
            userStateMap.put(chatId, UserState.AWAITING_OGOROD_PRICE);
            askForOgorodPrice(chatId);
        }
        else if (data.equals("btn_skip_price_payment")) {
            paymentDraftMap.get(chatId).setPrice(null);
            userStateMap.put(chatId, UserState.AWAITING_DAYS);
            sendMessageWithCancel(chatId, "Введите количество дней оплаты:");
        }
        else if (data.equals("btn_skip_price_ogorod")) {
            ogorodDraftMap.get(chatId).setPrice(null);
            userStateMap.put(chatId, UserState.AWAITING_OGOROD_DAYS);
            sendMessageWithCancel(chatId, "На сколько дней оплачено? (Введите число):");
        }
        else if (data.equals("btn_skip_contact")) {
            processClientContact(chatId, "Нет контакта");
        }

        // Edits & Logic
        else if (data.startsWith("cedit_")) handleClientEditField(chatId, data);
        else if (data.startsWith("oedit_")) handleOgorodEditField(chatId, data);
        else if (data.startsWith("ogorod_extend_")) startOgorodExtension(chatId, data, msgId, q.getMessage().getText());
        else if (data.equals("calc_mode_hours")) processCalcMode(chatId, "hours");
        else if (data.equals("calc_mode_days")) processCalcMode(chatId, "days");
        else if (data.startsWith("stats_p_")) processStatsPeriod(chatId, data.split("_")[2]);
        else if (data.startsWith("stats_o_")) processStatsOgorod(chatId, data.split("_")[2]);
        else if (data.startsWith("extend_")) startExtension(chatId, data, msgId, q.getMessage().getText());
        else if (data.startsWith("edit_")) handleEditFieldChoice(chatId, data, msgId);

        try { AnswerCallbackQuery answer = new AnswerCallbackQuery(); answer.setCallbackQueryId(q.getId()); execute(answer); } catch (Exception e) {}
    }

    private void processInput(Long chatId, String text, UserState state) {
        try {
            switch (state) {
                // Harvest Params
                case AWAITING_HARVEST_GROWTH_TIME:
                    int growthMins = parseTime(text);
                    if (growthMins == -1) { sendMessageWithCancel(chatId, "❌ Неверный формат. Введите ЧЧ:ММ (напр. 3:30):"); return; }
                    Ogorod oH1 = ogorodRepository.findById(harvestParamOgorodId.get(chatId)).get();
                    oH1.setGrowthTimeMinutes(growthMins);
                    ogorodRepository.save(oH1);
                    userStateMap.put(chatId, UserState.AWAITING_HARVEST_WATER_TIME);

                    // КНОПКА "35" (Inline)
                    SendMessage mWater = new SendMessage();
                    mWater.setChatId(chatId);
                    mWater.setText("Введите интервал полива в минутах (целое число):");

                    InlineKeyboardMarkup mkWater = new InlineKeyboardMarkup();
                    List<List<InlineKeyboardButton>> rowsWater = new ArrayList<>();
                    List<InlineKeyboardButton> rowWater = new ArrayList<>();
                    rowWater.add(createBtn("35", "val_35"));
                    rowsWater.add(rowWater);
                    mkWater.setKeyboard(rowsWater);
                    mWater.setReplyMarkup(mkWater);

                    try { execute(mWater); } catch (Exception e) {}
                    break;

                case AWAITING_HARVEST_WATER_TIME:
                    Ogorod oH2 = ogorodRepository.findById(harvestParamOgorodId.get(chatId)).get();
                    oH2.setWateringIntervalMinutes(Integer.parseInt(text));
                    ogorodRepository.save(oH2);
                    userStateMap.put(chatId, UserState.AWAITING_HARVEST_PRICE);

                    // КНОПКА "193.950" (Inline)
                    SendMessage mPrice = new SendMessage();
                    mPrice.setChatId(chatId);
                    mPrice.setText("Введите прибыль за один сбор урожая:");

                    InlineKeyboardMarkup mkPrice = new InlineKeyboardMarkup();
                    List<List<InlineKeyboardButton>> rowsPrice = new ArrayList<>();
                    List<InlineKeyboardButton> rowPrice = new ArrayList<>();
                    rowPrice.add(createBtn("193.950", "val_193.950"));
                    rowsPrice.add(rowPrice);
                    mkPrice.setKeyboard(rowsPrice);
                    mPrice.setReplyMarkup(mkPrice);

                    try { execute(mPrice); } catch (Exception e) {}
                    break;

                case AWAITING_HARVEST_PRICE:
                    Ogorod oH3 = ogorodRepository.findById(harvestParamOgorodId.get(chatId)).get();
                    oH3.setHarvestProfit(parsePrice(text));
                    if (oH3.getHarvestState() == null) oH3.setHarvestState("IDLE");
                    ogorodRepository.save(oH3);
                    resetUserState(chatId);
                    sendMessage(chatId, "✅ Параметры для '" + oH3.getTitle() + "' сохранены!");
                    showHarvestMenu(chatId);
                    break;

                // Payments
                case AWAITING_NAME: paymentDraftMap.get(chatId).setName(text); paymentDraftMap.get(chatId).setChatId(chatId); userStateMap.put(chatId, UserState.AWAITING_DATE); askForPaymentDate(chatId); break;
                case AWAITING_DATE: paymentDraftMap.get(chatId).setPurchaseDate(LocalDate.parse(text)); userStateMap.put(chatId, UserState.AWAITING_PRICE); askForPaymentPrice(chatId); break;
                case AWAITING_PRICE: paymentDraftMap.get(chatId).setPrice(parsePrice(text)); userStateMap.put(chatId, UserState.AWAITING_DAYS); sendMessageWithCancel(chatId, "Введите количество дней оплаты:"); break;
                case AWAITING_DAYS:
                    Payment p = paymentDraftMap.get(chatId); int days = Integer.parseInt(text);
                    p.setDaysPaid(days); p.setPaidUntil(LocalDate.now(KYIV_ZONE).plusDays(days));
                    paymentRepository.save(p); resetUserState(chatId); sendMessage(chatId, "✅ Запись создана!"); showMainMenu(chatId, "Главное меню:"); break;

                // Ogorods
                case AWAITING_OGOROD_NAME: ogorodDraftMap.get(chatId).setTitle(text); ogorodDraftMap.get(chatId).setChatId(chatId); userStateMap.put(chatId, UserState.AWAITING_OGOROD_DATE); askForOgorodDate(chatId); break;
                case AWAITING_OGOROD_DATE: ogorodDraftMap.get(chatId).setPurchaseDate(LocalDate.parse(text)); userStateMap.put(chatId, UserState.AWAITING_OGOROD_PRICE); askForOgorodPrice(chatId); break;
                case AWAITING_OGOROD_PRICE: ogorodDraftMap.get(chatId).setPrice(parsePrice(text)); userStateMap.put(chatId, UserState.AWAITING_OGOROD_DAYS); sendMessageWithCancel(chatId, "На сколько дней оплачено? (Введите число):"); break;
                case AWAITING_OGOROD_DAYS:
                    Ogorod og = ogorodDraftMap.get(chatId); int ogDays = Integer.parseInt(text);
                    og.setDaysPaid(ogDays); og.setPaidUntil(LocalDate.now(KYIV_ZONE).plusDays(ogDays)); og.setHarvestState("IDLE");
                    ogorodRepository.save(og); resetUserState(chatId); sendMessage(chatId, "✅ Огород добавлен!"); showOgorodSubMenu(chatId); break;

                // Ogorod Edit/Del
                case AWAITING_OGOROD_DELETE_ID:
                    long deleteOgId = Long.parseLong(text); Optional<Ogorod> ogDel = ogorodRepository.findById(deleteOgId);
                    if (ogDel.isEmpty() || !ogDel.get().getChatId().equals(chatId)) { sendMessageWithCancel(chatId, "❌ Огород не найден. ID:"); return; }

                    List<ClientRecord> clientsOnOgorod = clientRepository.findAllByChatId(chatId).stream()
                            .filter(c -> c.getOgorodName().equals(ogDel.get().getTitle()) && c.getEndDate().isAfter(LocalDateTime.now(KYIV_ZONE)))
                            .collect(Collectors.toList());
                    for(ClientRecord c : clientsOnOgorod) {
                        c.setEndDate(LocalDateTime.now(KYIV_ZONE));
                        c.setTerminationReason("OGOROD_DELETED");
                        clientRepository.save(c);
                        sendMessage(chatId, "ℹ️ Аренда клиента <b>" + c.getNickname() + "</b> завершена (огород удален).");
                    }
                    ogorodRepository.deleteById(deleteOgId); resetUserState(chatId); sendMessage(chatId, "✅ Огород удален."); showOgorodSubMenu(chatId); break;

                case AWAITING_OGOROD_EDIT_ID:
                    long editOgId = Long.parseLong(text); Optional<Ogorod> ogEdit = ogorodRepository.findById(editOgId);
                    if (ogEdit.isEmpty() || !ogEdit.get().getChatId().equals(chatId)) { sendMessageWithCancel(chatId, "❌ Не найдено. ID:"); return; }
                    sendOgorodEditOptions(chatId, editOgId); break;
                case AWAITING_OGOROD_EDIT_VALUE: processOgorodEditValue(chatId, text); break;
                case AWAITING_OGOROD_EXTEND_DAYS:
                    Ogorod o = ogorodRepository.findById(extensionOgorodIdMap.get(chatId)).get(); int ogExtDays = Integer.parseInt(text);
                    o.setDaysPaid(ogExtDays); o.setPaidUntil(LocalDate.now(KYIV_ZONE).plusDays(ogExtDays));
                    ogorodRepository.save(o); resetUserState(chatId); sendMessage(chatId, "✅ Продлено до: " + o.getPaidUntil().format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))); showOgorodSubMenu(chatId); break;

                // Clients
                case AWAITING_CLIENT_NICKNAME: clientDraftMap.get(chatId).setNickname(text); clientDraftMap.get(chatId).setChatId(chatId); showOgorodPickerForClient(chatId); break;
                case AWAITING_CLIENT_DURATION: clientDraftMap.get(chatId).setDuration(Integer.parseInt(text)); userStateMap.put(chatId, UserState.AWAITING_CLIENT_PRICE); sendMessageWithCancel(chatId, "Введите общую цену аренды:"); break;
                case AWAITING_CLIENT_PRICE: clientDraftMap.get(chatId).setPrice(parsePrice(text)); userStateMap.put(chatId, UserState.AWAITING_CLIENT_CONTACT); askForClientContact(chatId); break;
                case AWAITING_CLIENT_CONTACT: processClientContact(chatId, text); break;
                case AWAITING_CLIENT_START_DATE: processClientCustomDate(chatId, text); break;
                case AWAITING_CLIENT_DELETE_ID: clientRepository.deleteById(Long.parseLong(text)); resetUserState(chatId); sendMessage(chatId, "🗑 Удалено."); showClientSubMenu(chatId); break;
                case AWAITING_CLIENT_EDIT_ID: sendClientEditOptions(chatId, Long.parseLong(text)); break;
                case AWAITING_CLIENT_EDIT_VALUE: processClientEditValue(chatId, text); break;
                case AWAITING_CLIENT_SEARCH: performClientSearch(chatId, text); break;
                case AWAITING_CLIENT_TERMINATE_ID: askTerminationReason(chatId, Long.parseLong(text)); break;

                // Calc & Payment Edit
                case AWAITING_CALC_AMOUNT: calcAmountMap.put(chatId, Integer.parseInt(text)); userStateMap.put(chatId, UserState.AWAITING_CALC_PRICE); sendMessageWithCancel(chatId, "Введите цену за 1 час:"); break;
                case AWAITING_CALC_PRICE: double pricePerH = parsePrice(text); int amt = calcAmountMap.get(chatId); String m = calcModeMap.get(chatId); double tot = m.equals("hours") ? amt * pricePerH : (amt * 24) * pricePerH; sendMessage(chatId, "🧮 <b>Результат:</b> " + formatPrice(tot)); resetUserState(chatId); showOgorodManagerMenu(chatId); break;

                case AWAITING_DELETE_ID:
                    long delId = Long.parseLong(text); Optional<Payment> pDel = paymentRepository.findById(delId);
                    if (pDel.isEmpty() || !pDel.get().getChatId().equals(chatId)) { sendMessageWithCancel(chatId, "❌ Не найдено. ID:"); return; }
                    paymentRepository.deleteById(delId); resetUserState(chatId); sendMessage(chatId, "✅ Удалено."); showMainMenu(chatId, "Меню:"); break;
                case AWAITING_EXTENSION_DAYS:
                    Payment pExt = paymentRepository.findById(extensionPaymentIdMap.get(chatId)).get(); int pDays = Integer.parseInt(text);
                    pExt.setDaysPaid(pDays); pExt.setPaidUntil(LocalDate.now(KYIV_ZONE).plusDays(pDays));
                    paymentRepository.save(pExt); resetUserState(chatId); sendMessage(chatId, "✅ Продлено до: " + pExt.getPaidUntil().format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))); showMainMenu(chatId, "Меню:"); break;
                case AWAITING_EDIT_ID:
                    long editId = Long.parseLong(text); Optional<Payment> pEdit = paymentRepository.findById(editId);
                    if (pEdit.isEmpty() || !pEdit.get().getChatId().equals(chatId)) { sendMessageWithCancel(chatId, "❌ Не найдено. ID:"); return; }
                    sendEditOptions(chatId, editId); break;
                case AWAITING_EDIT_VALUE: processEditValue(chatId, text); break;
            }
        } catch (Exception e) { sendMessageWithCancel(chatId, "❌ Ошибка. Попробуйте еще раз:"); }
    }

    // ================== METHODS ==================

    private void showMainMenu(Long chatId, String text) {
        SendMessage message = new SendMessage(); message.setChatId(chatId); message.setText(text);
        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup(); markup.setResizeKeyboard(true);
        List<KeyboardRow> keyboard = new ArrayList<>();
        KeyboardRow r1 = new KeyboardRow(); r1.add("📝 Новая запись"); r1.add("📋 Мои записи");
        KeyboardRow r2 = new KeyboardRow(); r2.add("✏️ Редактировать"); r2.add("❌ Удалить запись");
        KeyboardRow r3 = new KeyboardRow(); r3.add("🥬 Менеджер огородов");
        keyboard.add(r1); keyboard.add(r2); keyboard.add(r3); markup.setKeyboard(keyboard); message.setReplyMarkup(markup);
        try { execute(message); } catch (Exception e) {}
    }

    private void showOgorodManagerMenu(Long chatId) {
        SendMessage msg = new SendMessage(); msg.setChatId(chatId); msg.setText("🥬 <b>Менеджер огородов</b>"); msg.setParseMode("HTML");
        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup(); markup.setResizeKeyboard(true);
        List<KeyboardRow> keyboard = new ArrayList<>();
        KeyboardRow r0 = new KeyboardRow(); r0.add("🌽 Мой урожай");
        KeyboardRow r1 = new KeyboardRow(); r1.add("👥 Клиенты"); r1.add("🏡 Огороды");
        KeyboardRow r2 = new KeyboardRow(); r2.add("🧮 Калькулятор"); r2.add("📊 Статистика");
        KeyboardRow r3 = new KeyboardRow(); r3.add("🔙 Главное меню");
        keyboard.add(r0); keyboard.add(r1); keyboard.add(r2); keyboard.add(r3); markup.setKeyboard(keyboard); msg.setReplyMarkup(markup);
        try { execute(msg); } catch (Exception e) {}
    }

    private void showOgorodSubMenu(Long chatId) {
        SendMessage msg = new SendMessage(); msg.setChatId(chatId); msg.setText("🏡 <b>Меню Огородов</b>"); msg.setParseMode("HTML");
        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup(); markup.setResizeKeyboard(true);
        List<KeyboardRow> keyboard = new ArrayList<>();
        KeyboardRow r1 = new KeyboardRow(); r1.add("📜 Список огородов"); r1.add("➕ Добавить огород");
        KeyboardRow r2 = new KeyboardRow(); r2.add("✏️ Ред. огород"); r2.add("❌ Удалить огород");
        KeyboardRow r3 = new KeyboardRow(); r3.add("🔙 Менеджер");
        keyboard.add(r1); keyboard.add(r2); keyboard.add(r3); markup.setKeyboard(keyboard); msg.setReplyMarkup(markup);
        try { execute(msg); } catch (Exception e) {}
    }

    private void showClientSubMenu(Long chatId) {
        SendMessage msg = new SendMessage(); msg.setChatId(chatId); msg.setText("👥 <b>Меню Клиентов</b>"); msg.setParseMode("HTML");
        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup(); markup.setResizeKeyboard(true);
        List<KeyboardRow> keyboard = new ArrayList<>();
        KeyboardRow r1 = new KeyboardRow(); r1.add("📜 Список клиентов"); r1.add("➕ Добавить клиента");
        KeyboardRow r2 = new KeyboardRow(); r2.add("🔍 Поиск клиента"); r2.add("📥 Скачать Excel");
        KeyboardRow r3 = new KeyboardRow(); r3.add("✏️ Ред. клиента"); r3.add("❌ Удалить клиента");
        KeyboardRow r4 = new KeyboardRow(); r4.add("🛑 Завершить досрочно");
        KeyboardRow r5 = new KeyboardRow(); r5.add("🔙 Менеджер");
        keyboard.add(r1); keyboard.add(r2); keyboard.add(r3); keyboard.add(r4); keyboard.add(r5); markup.setKeyboard(keyboard); msg.setReplyMarkup(markup);
        try { execute(msg); } catch (Exception e) {}
    }

    private void showHarvestMenu(Long chatId) {
        SendMessage msg = new SendMessage(); msg.setChatId(chatId); msg.setText("🌽 <b>Мой урожай</b>\nУправление циклом роста."); msg.setParseMode("HTML");
        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup(); markup.setResizeKeyboard(true);
        List<KeyboardRow> keyboard = new ArrayList<>();
        KeyboardRow r1 = new KeyboardRow(); r1.add("🌱 Посадил"); r1.add("💧 Полил"); r1.add("🚜 Собрал");
        KeyboardRow r2 = new KeyboardRow(); r2.add("⏱ Состояние"); r2.add("🔄 Сброс");
        KeyboardRow r3 = new KeyboardRow(); r3.add("⚙️ Параметры"); r3.add("📈 Статистика урожая");
        KeyboardRow r4 = new KeyboardRow(); r4.add("🔙 Менеджер");
        keyboard.add(r1); keyboard.add(r2); keyboard.add(r3); keyboard.add(r4); markup.setKeyboard(keyboard); msg.setReplyMarkup(markup);
        try { execute(msg); } catch (Exception e) {}
    }

    private void showHarvestResetMenu(Long chatId) {
        List<Ogorod> ogorods = ogorodRepository.findAllByChatId(chatId);
        List<Ogorod> active = ogorods.stream().filter(o -> o.getHarvestState() != null && !o.getHarvestState().equals("IDLE")).collect(Collectors.toList());
        if(active.isEmpty()) { sendMessage(chatId, "🤷‍♂️ Все таймеры по нулям."); return; }
        SendMessage msg = new SendMessage(); msg.setChatId(chatId); msg.setText("Какой таймер сбросить (обнулить)?");
        InlineKeyboardMarkup mk = new InlineKeyboardMarkup(); List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> rAll = new ArrayList<>(); rAll.add(createBtn("🔥 Сбросить ВСЕ", "h_reset_all")); rows.add(rAll);
        for (Ogorod o : active) { List<InlineKeyboardButton> r = new ArrayList<>(); r.add(createBtn(o.getTitle(), "h_reset_" + o.getId())); rows.add(r); }
        mk.setKeyboard(rows); msg.setReplyMarkup(mk); try { execute(msg); } catch(Exception e) {}
    }

    private void processHarvestReset(Long chatId, Long ogId) {
        if (ogId == -1) {
            List<Ogorod> all = ogorodRepository.findAllByChatId(chatId);
            for(Ogorod o : all) { o.setHarvestState("IDLE"); o.setGrowthStartTime(null); o.setLastWateringTime(null); o.setAccumulatedGrowthMinutes(0); ogorodRepository.save(o); }
            sendMessage(chatId, "✅ Все таймеры сброшены.");
        } else {
            Ogorod o = ogorodRepository.findById(ogId).get();
            o.setHarvestState("IDLE"); o.setGrowthStartTime(null); o.setLastWateringTime(null); o.setAccumulatedGrowthMinutes(0);
            ogorodRepository.save(o); sendMessage(chatId, "✅ Таймер для <b>" + o.getTitle() + "</b> сброшен.");
        }
    }

    private void showHarvestStatus(Long chatId) {
        List<Ogorod> ogorods = ogorodRepository.findAllByChatId(chatId);
        List<Ogorod> growing = ogorods.stream().filter(o -> o.getHarvestState() != null && (o.getHarvestState().equals("GROWING") || o.getHarvestState().equals("WAITING_WATER") || o.getHarvestState().equals("READY"))).collect(Collectors.toList());
        if (growing.isEmpty()) { sendMessage(chatId, "🌱 Нет активных посадок."); return; }
        StringBuilder sb = new StringBuilder("🌽 <b>Состояние урожая:</b>\n\n");
        LocalDateTime now = LocalDateTime.now(KYIV_ZONE);
        for (Ogorod o : growing) {
            sb.append("🏡 <b>").append(o.getTitle()).append("</b>\n");
            if ("READY".equals(o.getHarvestState())) { sb.append("✅ <b>ГОТОВО К СБОРУ!</b>\n"); }
            else if ("WAITING_WATER".equals(o.getHarvestState())) { sb.append("💧 <b>ЖДЕТ ПОЛИВА!</b> (Таймер на паузе)\n"); }
            else if ("GROWING".equals(o.getHarvestState())) {
                long accumulated = (o.getAccumulatedGrowthMinutes() != null) ? o.getAccumulatedGrowthMinutes() : 0;
                long currentSession = ChronoUnit.MINUTES.between(o.getLastWateringTime(), now);
                long totalProgress = accumulated + currentSession;
                long totalNeeded = o.getGrowthTimeMinutes();
                long left = totalNeeded - totalProgress;
                if (left <= 0) { sb.append("✅ <b>ГОТОВО!</b> (Подождите минуту или нажмите Полил для обновления)\n"); }
                else {
                    long hours = left / 60; long mins = left % 60;
                    sb.append("⏳ Рост: осталось <b>").append(hours).append("ч ").append(mins).append("мин</b>\n");
                    long waterInterval = o.getWateringIntervalMinutes();
                    long nextWaterIn = waterInterval - currentSession;
                    if (nextWaterIn <= 0) sb.append("⚠️ <b>Пора поливать!</b>\n"); else sb.append("💧 Полив через: ").append(nextWaterIn).append(" мин\n");
                }
            }
            sb.append("--------------------\n");
        }
        sendMessage(chatId, sb.toString());
    }

    private void startAddingClient(Long chatId) { userStateMap.put(chatId, UserState.AWAITING_CLIENT_NICKNAME); clientDraftMap.put(chatId, new ClientRecord()); sendMessageWithCancel(chatId, "Введите Никнейм клиента:"); }

    private void showClientsList(Long chatId) {
        List<ClientRecord> allClients = clientRepository.findAllByChatId(chatId);
        if (allClients.isEmpty()) { sendMessage(chatId, "Список пуст."); return; }
        allClients.sort(Comparator.comparing(ClientRecord::getId).reversed());
        List<ClientRecord> lastSeven = allClients.stream().limit(7).collect(Collectors.toList());
        StringBuilder sb = new StringBuilder("👥 <b>Последние 7 клиентов</b>:\n\n");
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
        for (ClientRecord c : lastSeven) {
            double pricePerHour = "дн".equals(c.getDurationUnit()) ? c.getPrice() / (c.getDuration() * 24.0) : c.getPrice() / c.getDuration();
            String status = "🟢 Активен";
            if (c.getTerminationReason() != null) {
                if (c.getTerminationReason().equals("CLIENT_EARLY")) status = "🔴 Досрочно (Клиент)";
                else if (c.getTerminationReason().equals("OWNER_EARLY")) status = "🔴 Досрочно (Вы)";
                else if (c.getTerminationReason().equals("OGOROD_DELETED")) status = "🔴 Огород удален";
            } else if (LocalDateTime.now(KYIV_ZONE).isAfter(c.getEndDate())) { status = "🔴 Истек"; }
            sb.append("🆔 ID: <b>").append(c.getId()).append("</b>\n").append("👤 <b>").append(c.getNickname()).append("</b> (").append(c.getOgorodName()).append(")\n").append("📞 ").append(c.getContact()).append("\n").append("💰 ").append(formatPrice(c.getPrice())).append(" (").append(formatPrice(pricePerHour)).append("/ч)\n").append("📅 С: ").append(c.getStartDate().format(fmt)).append("\n").append("🏁 До: ").append(c.getEndDate().format(fmt)).append(" (").append(status).append(")\n--------------------------\n");
        }
        sendMessage(chatId, sb.toString());
    }

    private void startTerminatingClient(Long chatId) { sendMessageWithCancel(chatId, "Введите ID клиента для завершения:"); userStateMap.put(chatId, UserState.AWAITING_CLIENT_TERMINATE_ID); }

    private void askTerminationReason(Long chatId, Long clientId) {
        Optional<ClientRecord> cOpt = clientRepository.findById(clientId);
        if (cOpt.isEmpty() || !cOpt.get().getChatId().equals(chatId)) { sendMessageWithCancel(chatId, "❌ Клиент не найден."); return; }
        if(LocalDateTime.now(KYIV_ZONE).isAfter(cOpt.get().getEndDate())) { sendMessage(chatId, "⚠️ Этот клиент уже истек."); showClientSubMenu(chatId); resetUserState(chatId); return; }
        terminateClientIdMap.put(chatId, clientId);
        SendMessage msg = new SendMessage(); msg.setChatId(chatId); msg.setText("Кто завершил аренду?");
        InlineKeyboardMarkup mk = new InlineKeyboardMarkup(); List<List<InlineKeyboardButton>> rows = new ArrayList<>(); List<InlineKeyboardButton> r = new ArrayList<>();
        r.add(createBtn("👤 Клиент", "term_client")); r.add(createBtn("🙋‍♂️ Я (Владелец)", "term_owner"));
        rows.add(r); mk.setKeyboard(rows); msg.setReplyMarkup(mk); try { execute(msg); } catch(Exception e) {}
    }

    private void processTermination(Long chatId, String reason) {
        ClientRecord c = clientRepository.findById(terminateClientIdMap.get(chatId)).get();
        LocalDateTime now = LocalDateTime.now(KYIV_ZONE);
        c.setEndDate(now); c.setTerminationReason(reason);
        if ("OWNER_EARLY".equals(reason)) {
            long totalMinutesPlanned;
            if("дн".equals(c.getDurationUnit())) totalMinutesPlanned = (long)c.getDuration() * 24 * 60; else totalMinutesPlanned = (long)c.getDuration() * 60;
            long minutesUsed = Duration.between(c.getStartDate(), now).toMinutes();
            if (minutesUsed < 0) minutesUsed = 0;
            if (totalMinutesPlanned > 0) {
                double ratio = (double) minutesUsed / totalMinutesPlanned;
                if (ratio > 1.0) ratio = 1.0;
                double newPrice = c.getPrice() * ratio;
                double refund = c.getPrice() - newPrice;
                sendMessage(chatId, "💸 <b>Возврат клиенту:</b> " + formatPrice(refund) + "\n(Вычтено из статистики)");
                c.setPrice(newPrice);
            }
        } else { sendMessage(chatId, "✅ Завершено. Полная сумма ("+formatPrice(c.getPrice())+") сохранена в статистике."); }
        clientRepository.save(c); resetUserState(chatId); showClientSubMenu(chatId);
    }

    private void startSearchingClient(Long chatId) { userStateMap.put(chatId, UserState.AWAITING_CLIENT_SEARCH); sendMessageWithCancel(chatId, "Введите часть никнейма для поиска:"); }
    private void generateClientsExcel(Long chatId, String query) { List<ClientRecord> clients = clientRepository.findAllByChatId(chatId); if(clients.isEmpty()) { sendMessage(chatId, "Нет данных."); return; } sendExcelReport(chatId, clients, "clients.xlsx"); }
    private void startEditingClientInput(Long chatId) { sendMessageWithCancel(chatId, "Введите ID клиента для редактирования:"); userStateMap.put(chatId, UserState.AWAITING_CLIENT_EDIT_ID); }
    private void startDeletingClient(Long chatId) { sendMessageWithCancel(chatId, "Введите ID клиента для удаления:"); userStateMap.put(chatId, UserState.AWAITING_CLIENT_DELETE_ID); }

    private void performClientSearch(Long chatId, String query) {
        List<ClientRecord> all = clientRepository.findAllByChatId(chatId);
        List<ClientRecord> found = all.stream().filter(c -> c.getNickname().toLowerCase().contains(query.toLowerCase())).collect(Collectors.toList());
        if(found.isEmpty()) { sendMessage(chatId, "Ничего не найдено."); }
        else {
            StringBuilder sb = new StringBuilder("🔍 Результаты поиска:\n\n");
            for(ClientRecord c : found) sb.append("ID: ").append(c.getId()).append(" | ").append(c.getNickname()).append("\n");
            sendMessage(chatId, sb.toString());
            sendExcelReport(chatId, found, "Result_" + query + ".xlsx");
        }
        resetUserState(chatId); showClientSubMenu(chatId);
    }

    private void showOgorodPickerForClient(Long chatId) {
        List<Ogorod> list = ogorodRepository.findAllByChatId(chatId);
        if (list.isEmpty()) { sendMessage(chatId, "❌ Нет огородов! Сначала добавьте их."); resetUserState(chatId); return; }
        SendMessage msg = new SendMessage(); msg.setChatId(chatId); msg.setText("Выберите огород:");
        InlineKeyboardMarkup mk = new InlineKeyboardMarkup(); List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Ogorod o : list) { List<InlineKeyboardButton> row = new ArrayList<>(); row.add(createBtn(o.getTitle(), "client_pick_ogorod_" + o.getId())); rows.add(row); }
        mk.setKeyboard(rows); msg.setReplyMarkup(mk); try { execute(msg); } catch (Exception e) {}
    }

    private void checkOgorodAvailability(Long chatId, Long ogId, Integer msgId) {
        Ogorod o = ogorodRepository.findById(ogId).get();
        boolean isOccupied = clientRepository.findAllByChatId(chatId).stream().anyMatch(c -> c.getOgorodName().equals(o.getTitle()) && c.getEndDate().isAfter(LocalDateTime.now(KYIV_ZONE)));
        if (isOccupied) {
            SendMessage msg = new SendMessage(); msg.setChatId(chatId); msg.setText("⚠️ <b>ВНИМАНИЕ!</b>\nОгород '" + o.getTitle() + "' занят. Подвязать?"); msg.setParseMode("HTML");
            InlineKeyboardMarkup mk = new InlineKeyboardMarkup(); List<List<InlineKeyboardButton>> rows = new ArrayList<>(); List<InlineKeyboardButton> r = new ArrayList<>(); r.add(createBtn("✅ Да", "client_confirm_override_yes")); r.add(createBtn("⛔️ Нет", "client_confirm_override_no")); rows.add(r); mk.setKeyboard(rows); msg.setReplyMarkup(mk);
            clientDraftMap.get(chatId).setOgorodName(o.getTitle()); try { execute(msg); } catch (Exception e) {}
        } else { clientDraftMap.get(chatId).setOgorodName(o.getTitle()); sendClientUnitChoice(chatId); }
    }

    private void sendClientUnitChoice(Long chatId) {
        SendMessage msg = new SendMessage(); msg.setChatId(chatId); msg.setText("Выберите единицы измерения:");
        InlineKeyboardMarkup mk = new InlineKeyboardMarkup(); List<List<InlineKeyboardButton>> rows = new ArrayList<>(); List<InlineKeyboardButton> r = new ArrayList<>(); r.add(createBtn("Часы", "client_unit_hours")); r.add(createBtn("Дни", "client_unit_days")); rows.add(r); mk.setKeyboard(rows); msg.setReplyMarkup(mk); try { execute(msg); } catch (Exception e) {}
    }

    private void processClientUnit(Long chatId, String unit) { clientDraftMap.get(chatId).setDurationUnit(unit); userStateMap.put(chatId, UserState.AWAITING_CLIENT_DURATION); sendMessageWithCancel(chatId, "Введите длительность (число):"); }
    private void processClientContact(Long chatId, String contact) { clientDraftMap.get(chatId).setContact(contact); sendClientStartModeChoice(chatId); }
    private void sendClientStartModeChoice(Long chatId) {
        SendMessage msg = new SendMessage(); msg.setChatId(chatId); msg.setText("Когда началась аренда?");
        InlineKeyboardMarkup mk = new InlineKeyboardMarkup(); List<List<InlineKeyboardButton>> rows = new ArrayList<>(); List<InlineKeyboardButton> r = new ArrayList<>(); r.add(createBtn("🟢 Сейчас", "client_start_now")); r.add(createBtn("📅 Указать дату", "client_start_custom")); rows.add(r); mk.setKeyboard(rows); msg.setReplyMarkup(mk); userStateMap.put(chatId, UserState.AWAITING_CLIENT_START_MODE); try { execute(msg); } catch (Exception e) {}
    }
    private void finishAddingClient(Long chatId, LocalDateTime startDate) {
        ClientRecord c = clientDraftMap.get(chatId); c.setStartDate(startDate);
        LocalDateTime end = "дн".equals(c.getDurationUnit()) ? startDate.plusDays(c.getDuration()) : startDate.plusHours(c.getDuration());
        c.setEndDate(end); c.setNotificationSent(false);
        clientRepository.save(c); resetUserState(chatId); sendMessage(chatId, "✅ Клиент добавлен! Начало: " + startDate.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))); showClientSubMenu(chatId);
    }
    private void askClientCustomDate(Long chatId) { userStateMap.put(chatId, UserState.AWAITING_CLIENT_START_DATE); sendMessageWithCancel(chatId, "Введите дату и время начала (ГГГГ-ММ-ДД ЧЧ:ММ):"); }
    private void processClientCustomDate(Long chatId, String text) { try { LocalDateTime customStart = LocalDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")); finishAddingClient(chatId, customStart); } catch (Exception e) { sendMessageWithCancel(chatId, "❌ Неверный формат."); } }
    private void handleClientEditField(Long chatId, String data) { editFieldMap.put(chatId, data.replace("cedit_", "")); userStateMap.put(chatId, UserState.AWAITING_CLIENT_EDIT_VALUE); if (data.equals("cedit_start")) sendMessageWithCancel(chatId, "Введите новую дату (ГГГГ-ММ-ДД ЧЧ:ММ):"); else sendMessageWithCancel(chatId, "Введите новое значение:"); }
    private void handleOgorodEditField(Long chatId, String data) { editFieldMap.put(chatId, data.replace("oedit_", "")); userStateMap.put(chatId, UserState.AWAITING_OGOROD_EDIT_VALUE); if(data.endsWith("date")) sendMessageWithCancel(chatId, "Введите дату покупки (ГГГГ-ММ-ДД):"); else sendMessageWithCancel(chatId, "Введите новое значение:"); }
    private void startOgorodExtension(Long chatId, String data, Integer msgId, String text) { extensionOgorodIdMap.put(chatId, Long.parseLong(data.split("_")[2])); userStateMap.put(chatId, UserState.AWAITING_OGOROD_EXTEND_DAYS); sendMessageWithCancel(chatId, "Введите новое значение (на сколько щас оплачено?):"); }
    private void sendOgorodEditOptions(Long chatId, long id) { editOgorodIdMap.put(chatId, id); SendMessage m = new SendMessage(); m.setChatId(chatId); m.setText("Что изменить?"); InlineKeyboardMarkup mk = new InlineKeyboardMarkup(); List<List<InlineKeyboardButton>> rows = new ArrayList<>(); List<InlineKeyboardButton> r1 = new ArrayList<>(); r1.add(createBtn("Название", "oedit_title")); r1.add(createBtn("Цена", "oedit_price")); List<InlineKeyboardButton> r2 = new ArrayList<>(); r2.add(createBtn("Дни оплаты", "oedit_days")); r2.add(createBtn("📅 Дата покупки", "oedit_date")); rows.add(r1); rows.add(r2); mk.setKeyboard(rows); m.setReplyMarkup(mk); try { execute(m); } catch (Exception e) {} }
    private void processOgorodEditValue(Long chatId, String text) { Ogorod o = ogorodRepository.findById(editOgorodIdMap.get(chatId)).get(); String f = editFieldMap.get(chatId); try { if(f.equals("title")) o.setTitle(text); if(f.equals("price")) o.setPrice(parsePrice(text)); if(f.equals("date")) o.setPurchaseDate(LocalDate.parse(text)); if(f.equals("days")) { int d = Integer.parseInt(text); o.setDaysPaid(d); o.setPaidUntil(LocalDate.now(KYIV_ZONE).plusDays(d)); } ogorodRepository.save(o); resetUserState(chatId); sendMessage(chatId, "✅ Огород обновлен."); showOgorodSubMenu(chatId); } catch(Exception e) { sendMessageWithCancel(chatId, "❌ Ошибка."); } }
    private void sendClientEditOptions(Long chatId, long id) { editClientIdMap.put(chatId, id); SendMessage msg = new SendMessage(); msg.setChatId(chatId); msg.setText("Что изменить?"); InlineKeyboardMarkup mk = new InlineKeyboardMarkup(); List<List<InlineKeyboardButton>> rows = new ArrayList<>(); List<InlineKeyboardButton> r1 = new ArrayList<>(); r1.add(createBtn("Никнейм", "cedit_nick")); r1.add(createBtn("Цена", "cedit_price")); List<InlineKeyboardButton> r2 = new ArrayList<>(); r2.add(createBtn("Контакт", "cedit_contact")); r2.add(createBtn("Длительность", "cedit_dur")); List<InlineKeyboardButton> r3 = new ArrayList<>(); r3.add(createBtn("📅 Дата начала", "cedit_start")); rows.add(r1); rows.add(r2); rows.add(r3); mk.setKeyboard(rows); msg.setReplyMarkup(mk); try { execute(msg); } catch (Exception e) {} }
    private void processClientEditValue(Long chatId, String text) {
        ClientRecord c = clientRepository.findById(editClientIdMap.get(chatId)).get(); String f = editFieldMap.get(chatId);
        try { if(f.equals("nick")) c.setNickname(text); if(f.equals("price")) c.setPrice(parsePrice(text)); if(f.equals("contact")) c.setContact(text); if(f.equals("dur")) c.setDuration(Integer.parseInt(text)); if(f.equals("start")) c.setStartDate(LocalDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
            if(f.equals("dur") || f.equals("start")) { LocalDateTime end = "дн".equals(c.getDurationUnit()) ? c.getStartDate().plusDays(c.getDuration()) : c.getStartDate().plusHours(c.getDuration()); c.setEndDate(end); }
            clientRepository.save(c); resetUserState(chatId); sendMessage(chatId, "✅ Обновлено."); showClientSubMenu(chatId);
        } catch(Exception e) { sendMessageWithCancel(chatId, "❌ Ошибка формата."); }
    }

    private void askForPaymentDate(Long chatId) { SendMessage msg = new SendMessage(); msg.setChatId(chatId); msg.setText("Введите дату покупки (ГГГГ-ММ-ДД):"); InlineKeyboardMarkup mk = new InlineKeyboardMarkup(); List<List<InlineKeyboardButton>> rows = new ArrayList<>(); List<InlineKeyboardButton> r = new ArrayList<>(); r.add(createBtn("📅 Сегодня", "btn_date_today_payment")); r.add(createBtn("⏭ Пропустить", "btn_skip_date_payment")); rows.add(r); mk.setKeyboard(rows); msg.setReplyMarkup(mk); ReplyKeyboardMarkup kb = new ReplyKeyboardMarkup(); kb.setResizeKeyboard(true); KeyboardRow row = new KeyboardRow(); row.add("🔙 Отмена"); kb.setKeyboard(Collections.singletonList(row)); try { execute(msg); } catch(Exception e) {} }
    private void askForPaymentPrice(Long chatId) { SendMessage msg = new SendMessage(); msg.setChatId(chatId); msg.setText("Введите цену покупки:"); InlineKeyboardMarkup mk = new InlineKeyboardMarkup(); List<List<InlineKeyboardButton>> rows = new ArrayList<>(); List<InlineKeyboardButton> r = new ArrayList<>(); r.add(createBtn("⏭ Пропустить", "btn_skip_price_payment")); rows.add(r); mk.setKeyboard(rows); msg.setReplyMarkup(mk); try { execute(msg); } catch(Exception e) {} }
    private void askForOgorodDate(Long chatId) { SendMessage msg = new SendMessage(); msg.setChatId(chatId); msg.setText("Введите дату покупки (ГГГГ-ММ-ДД):"); InlineKeyboardMarkup mk = new InlineKeyboardMarkup(); List<List<InlineKeyboardButton>> rows = new ArrayList<>(); List<InlineKeyboardButton> r = new ArrayList<>(); r.add(createBtn("📅 Сегодня", "btn_date_today_ogorod")); r.add(createBtn("⏭ Пропустить", "btn_skip_date_ogorod")); rows.add(r); mk.setKeyboard(rows); msg.setReplyMarkup(mk); try { execute(msg); } catch(Exception e) {} }
    private void askForOgorodPrice(Long chatId) { SendMessage msg = new SendMessage(); msg.setChatId(chatId); msg.setText("Введите цену покупки:"); InlineKeyboardMarkup mk = new InlineKeyboardMarkup(); List<List<InlineKeyboardButton>> rows = new ArrayList<>(); List<InlineKeyboardButton> r = new ArrayList<>(); r.add(createBtn("⏭ Пропустить", "btn_skip_price_ogorod")); rows.add(r); mk.setKeyboard(rows); msg.setReplyMarkup(mk); try { execute(msg); } catch(Exception e) {} }
    private void askForClientContact(Long chatId) { SendMessage msg = new SendMessage(); msg.setChatId(chatId); msg.setText("Введите контакт клиента:"); InlineKeyboardMarkup mk = new InlineKeyboardMarkup(); List<List<InlineKeyboardButton>> rows = new ArrayList<>(); List<InlineKeyboardButton> r = new ArrayList<>(); r.add(createBtn("⏭ Пропустить", "btn_skip_contact")); rows.add(r); mk.setKeyboard(rows); msg.setReplyMarkup(mk); try { execute(msg); } catch(Exception e) {} }

    private int parseTime(String text) { try { String[] parts = text.split(":"); return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]); } catch (Exception e) { return -1; } }

    private void setupHarvestParams(Long chatId) {
        List<Ogorod> ogorods = ogorodRepository.findAllByChatId(chatId);
        if (ogorods.isEmpty()) { sendMessage(chatId, "❌ Сначала добавьте огород."); return; }
        SendMessage msg = new SendMessage(); msg.setChatId(chatId); msg.setText("Выберите огород для настройки:");
        InlineKeyboardMarkup mk = new InlineKeyboardMarkup(); List<List<InlineKeyboardButton>> rows = new ArrayList<>(); for (Ogorod o : ogorods) { List<InlineKeyboardButton> r = new ArrayList<>(); r.add(createBtn(o.getTitle(), "h_param_" + o.getId())); rows.add(r); } mk.setKeyboard(rows); msg.setReplyMarkup(mk); try { execute(msg); } catch (Exception e) {}
    }

    private void startHarvestCycle(Long chatId) {
        List<Ogorod> ogorods = ogorodRepository.findAllByChatId(chatId);
        List<Ogorod> available = ogorods.stream()
                .filter(o -> o.getHarvestState() == null || o.getHarvestState().equals("IDLE"))
                .collect(Collectors.toList());

        if (available.isEmpty()) {
            sendMessage(chatId, "❌ Нет свободных огородов (или урожай еще не собран).");
            return;
        }

        SendMessage msg = new SendMessage(); msg.setChatId(chatId); msg.setText("Какой огород посадили?");
        InlineKeyboardMarkup mk = new InlineKeyboardMarkup(); List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Ogorod o : available) {
            List<InlineKeyboardButton> r = new ArrayList<>();
            r.add(createBtn(o.getTitle(), "h_plant_" + o.getId()));
            rows.add(r);
        }
        mk.setKeyboard(rows); msg.setReplyMarkup(mk); try { execute(msg); } catch (Exception e) {}
    }
    private void processPlanting(Long chatId, Long ogId) {
        Ogorod o = ogorodRepository.findById(ogId).get();
        if (o.getGrowthTimeMinutes() == null || o.getWateringIntervalMinutes() == null) { sendMessage(chatId, "❌ Сначала настройте таймеры в меню «⚙️ Параметры»!"); return; }
        o.setHarvestState("GROWING"); o.setGrowthStartTime(LocalDateTime.now(KYIV_ZONE)); o.setLastWateringTime(LocalDateTime.now(KYIV_ZONE)); o.setAccumulatedGrowthMinutes(0);
        ogorodRepository.save(o); sendMessage(chatId, "🌱 Огород <b>" + o.getTitle() + "</b> посажен!");
    }
    private void performWatering(Long chatId) {
        List<Ogorod> ogorods = ogorodRepository.findAllByChatId(chatId);
        List<Ogorod> waiting = ogorods.stream().filter(o -> "WAITING_WATER".equals(o.getHarvestState())).collect(Collectors.toList());
        if (waiting.isEmpty()) { sendMessage(chatId, "🤷‍♂️ Нет огородов для полива."); return; }
        SendMessage msg = new SendMessage(); msg.setChatId(chatId); msg.setText("Какой огород полить?");
        InlineKeyboardMarkup mk = new InlineKeyboardMarkup(); List<List<InlineKeyboardButton>> rows = new ArrayList<>(); for (Ogorod o : waiting) { List<InlineKeyboardButton> r = new ArrayList<>(); r.add(createBtn(o.getTitle(), "h_water_" + o.getId())); rows.add(r); } mk.setKeyboard(rows); msg.setReplyMarkup(mk); try { execute(msg); } catch (Exception e) {}
    }
    private void processWateringConfirm(Long chatId, Long ogId) {
        Ogorod o = ogorodRepository.findById(ogId).get();
        o.setHarvestState("GROWING"); o.setLastWateringTime(LocalDateTime.now(KYIV_ZONE)); ogorodRepository.save(o); sendMessage(chatId, "💧 Огород <b>" + o.getTitle() + "</b> полит!");
    }
    private void collectHarvest(Long chatId) {
        List<Ogorod> ogorods = ogorodRepository.findAllByChatId(chatId);
        List<Ogorod> ready = ogorods.stream().filter(o -> "READY".equals(o.getHarvestState())).collect(Collectors.toList());
        if (ready.isEmpty()) { sendMessage(chatId, "🤷‍♂️ Нет готового урожая."); return; }
        SendMessage msg = new SendMessage(); msg.setChatId(chatId); msg.setText("Где собрать урожай?");
        InlineKeyboardMarkup mk = new InlineKeyboardMarkup(); List<List<InlineKeyboardButton>> rows = new ArrayList<>(); for (Ogorod o : ready) { List<InlineKeyboardButton> r = new ArrayList<>(); r.add(createBtn(o.getTitle(), "h_collect_" + o.getId())); rows.add(r); } mk.setKeyboard(rows); msg.setReplyMarkup(mk); try { execute(msg); } catch (Exception e) {}
    }
    private void processCollectingConfirm(Long chatId, Long ogId) {
        Ogorod o = ogorodRepository.findById(ogId).get();
        HarvestRecord rec = new HarvestRecord(); rec.setChatId(chatId); rec.setOgorodId(ogId); rec.setOgorodName(o.getTitle()); rec.setAmount(o.getHarvestProfit() != null ? o.getHarvestProfit() : 0.0); rec.setHarvestedAt(LocalDateTime.now(KYIV_ZONE)); harvestRecordRepository.save(rec);
        o.setHarvestState("IDLE"); o.setGrowthStartTime(null); o.setLastWateringTime(null); o.setAccumulatedGrowthMinutes(0); ogorodRepository.save(o);
        sendMessage(chatId, "🚜 Урожай собран! +" + formatPrice(rec.getAmount()));
    }
    private void startHarvestStatistics(Long chatId) { statsTypeMap.put(chatId, "harvest"); startStatistics(chatId); }

    // --- STATISTICS & COMMON HELPERS ---
    private void startStatistics(Long chatId) {
        if(!statsTypeMap.containsKey(chatId)) statsTypeMap.put(chatId, "clients");
        String label = statsTypeMap.get(chatId).equals("harvest") ? "урожая" : "клиентов";
        SendMessage msg = new SendMessage(); msg.setChatId(chatId); msg.setText("📊 <b>Статистика " + label + "</b>\nВыберите период:"); msg.setParseMode("HTML");
        InlineKeyboardMarkup mk = new InlineKeyboardMarkup(); List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> r1 = new ArrayList<>(); r1.add(createBtn("♾ За все время", "stats_p_all")); r1.add(createBtn("🗓 За год", "stats_p_year"));
        List<InlineKeyboardButton> r2 = new ArrayList<>(); r2.add(createBtn("📅 За месяц", "stats_p_month")); r2.add(createBtn("📅 За неделю", "stats_p_week"));
        List<InlineKeyboardButton> r3 = new ArrayList<>(); String resetCallback = statsTypeMap.get(chatId).equals("harvest") ? "stats_reset_harvest" : "stats_reset_clients"; r3.add(createBtn("🗑 Сбросить статистику", resetCallback));
        rows.add(r1); rows.add(r2); rows.add(r3); mk.setKeyboard(rows); msg.setReplyMarkup(mk); try { execute(msg); } catch (Exception e) {}
    }

    private void askResetHarvestStats(Long chatId) { SendMessage msg = new SendMessage(); msg.setChatId(chatId); msg.setText("⚠️ <b>Вы уверены?</b>\nВся история сбора урожая будет удалена безвозвратно."); msg.setParseMode("HTML"); InlineKeyboardMarkup mk = new InlineKeyboardMarkup(); List<List<InlineKeyboardButton>> rows = new ArrayList<>(); List<InlineKeyboardButton> r = new ArrayList<>(); r.add(createBtn("✅ Да", "confirm_reset_h_yes")); r.add(createBtn("🚫 Нет", "confirm_reset_h_no")); rows.add(r); mk.setKeyboard(rows); msg.setReplyMarkup(mk); try { execute(msg); } catch (Exception e) {} }
    private void processResetHarvestStats(Long chatId) { harvestRecordRepository.deleteAllByChatId(chatId); sendMessage(chatId, "✅ История урожая очищена."); showHarvestMenu(chatId); }
    private void askResetClientStats(Long chatId) { SendMessage msg = new SendMessage(); msg.setChatId(chatId); msg.setText("⚠️ <b>Вы уверены?</b>\nЭто удалит ВСЕХ клиентов (и активных, и историю)."); msg.setParseMode("HTML"); InlineKeyboardMarkup mk = new InlineKeyboardMarkup(); List<List<InlineKeyboardButton>> rows = new ArrayList<>(); List<InlineKeyboardButton> r = new ArrayList<>(); r.add(createBtn("✅ Да", "confirm_reset_c_yes")); r.add(createBtn("🚫 Нет", "confirm_reset_c_no")); rows.add(r); mk.setKeyboard(rows); msg.setReplyMarkup(mk); try { execute(msg); } catch (Exception e) {} }
    private void processResetClientStats(Long chatId) { clientRepository.deleteAllByChatId(chatId); sendMessage(chatId, "✅ База клиентов очищена."); showClientSubMenu(chatId); }

    private void processStatsPeriod(Long chatId, String period) {
        statsPeriodMap.put(chatId, period); List<Ogorod> list = ogorodRepository.findAllByChatId(chatId);
        SendMessage msg = new SendMessage(); msg.setChatId(chatId); msg.setText("Выберите огород:");
        InlineKeyboardMarkup mk = new InlineKeyboardMarkup(); List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> rAll = new ArrayList<>(); rAll.add(createBtn("Все огороды", "stats_o_all")); rows.add(rAll);
        for(Ogorod o : list) { List<InlineKeyboardButton> r = new ArrayList<>(); r.add(createBtn(o.getTitle(), "stats_o_" + o.getId())); rows.add(r); }
        mk.setKeyboard(rows); msg.setReplyMarkup(mk); try { execute(msg); } catch (Exception e) {}
    }

    private void processStatsOgorod(Long chatId, String ogorodIdStr) {
        String period = statsPeriodMap.get(chatId); String type = statsTypeMap.getOrDefault(chatId, "clients");
        LocalDateTime now = LocalDateTime.now(KYIV_ZONE); LocalDateTime calculatedCutoff;
        if (period.equals("month")) calculatedCutoff = now.minusMonths(1); else if (period.equals("week")) calculatedCutoff = now.minusWeeks(1); else if (period.equals("year")) calculatedCutoff = now.minusYears(1); else calculatedCutoff = LocalDateTime.MIN;
        final LocalDateTime finalCutoff = calculatedCutoff;
        double total = 0;
        if (type.equals("clients")) {
            List<ClientRecord> all = clientRepository.findAllByChatId(chatId);
            if(!ogorodIdStr.equals("all")) { String name = ogorodRepository.findById(Long.parseLong(ogorodIdStr)).get().getTitle(); all = all.stream().filter(c -> c.getOgorodName().equals(name)).collect(Collectors.toList()); }
            total = all.stream().filter(c -> c.getStartDate().isAfter(finalCutoff)).mapToDouble(ClientRecord::getPrice).sum();
        } else {
            List<HarvestRecord> all = harvestRecordRepository.findAllByChatId(chatId);
            if(!ogorodIdStr.equals("all")) { Long oid = Long.parseLong(ogorodIdStr); all = all.stream().filter(r -> r.getOgorodId().equals(oid)).collect(Collectors.toList()); }
            total = all.stream().filter(r -> r.getHarvestedAt().isAfter(finalCutoff)).mapToDouble(HarvestRecord::getAmount).sum();
        }
        sendMessage(chatId, "💰 Доход (" + type + " / " + period + "): <b>" + formatPrice(total) + "</b>"); statsTypeMap.remove(chatId);
    }

    public void showPayments(Long chatId) {
        List<Payment> payments = paymentRepository.findAllByChatId(chatId);
        StringBuilder sb = new StringBuilder("<b>📋 Ваши записи:</b>\n\n");
        if (payments.isEmpty()) { sb.append("Список пуст."); } else {
            payments.sort(Comparator.comparing(Payment::getId)); DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy");
            for (Payment p : payments) {
                String dateStr = (p.getPurchaseDate() != null) ? p.getPurchaseDate().format(fmt) : "Не указана"; String paidUntilStr = (p.getPaidUntil() != null) ? p.getPaidUntil().format(fmt) : "---"; String priceStr = (p.getPrice() != null) ? formatPrice(p.getPrice()) : "---"; long daysLeft = (p.getPaidUntil() != null) ? ChronoUnit.DAYS.between(LocalDate.now(KYIV_ZONE), p.getPaidUntil()) : 0;
                sb.append("🆔 ID: <b>").append(p.getId()).append("</b>\n").append("🔹 <b>").append(p.getName()).append("</b>\n").append("📅 Куплено: ").append(dateStr).append("\n").append("🗓 Оплачено на: ").append(p.getDaysPaid() != null ? p.getDaysPaid() : "0").append(" дн.\n").append("⏳ Осталось дней: ").append(daysLeft).append("\n").append("🏁 Оплачен до: ").append(paidUntilStr).append("\n").append("💰 Цена покупки: ").append(priceStr).append("\n------------------------------\n");
            }
        }
        sendMessage(chatId, sb.toString());
    }

    private void showOgorodList(Long chatId) {
        List<Ogorod> ogorods = ogorodRepository.findAllByChatId(chatId); ogorods.sort(Comparator.comparing(Ogorod::getId));
        Set<String> occupied = clientRepository.findAllByChatId(chatId).stream().filter(c -> c.getEndDate().isAfter(LocalDateTime.now(KYIV_ZONE))).map(ClientRecord::getOgorodName).collect(Collectors.toSet());
        StringBuilder sb = new StringBuilder("🏡 <b>Список ваших огородов:</b>\n\n");
        if (ogorods.isEmpty()) { sb.append("Список пуст."); } else {
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy");
            for (Ogorod o : ogorods) {
                String status = occupied.contains(o.getTitle()) ? "🔴 В аренде" : "🟢 Свободен"; String dateStr = (o.getPurchaseDate() != null) ? o.getPurchaseDate().format(fmt) : "Не указана"; String paidUntilStr = (o.getPaidUntil() != null) ? o.getPaidUntil().format(fmt) : "---"; String priceStr = (o.getPrice() != null) ? formatPrice(o.getPrice()) : "---"; long daysLeft = (o.getPaidUntil() != null) ? ChronoUnit.DAYS.between(LocalDate.now(KYIV_ZONE), o.getPaidUntil()) : 0;
                sb.append("🆔 ID: <b>").append(o.getId()).append("</b>\n").append("🏡 <b>").append(o.getTitle()).append("</b>\n").append("📅 Куплен: ").append(dateStr).append("\n").append("🗓 Оплачено на: ").append(o.getDaysPaid() != null ? o.getDaysPaid() : "0").append(" дн.\n").append("⏳ Осталось дней: ").append(daysLeft).append("\n").append("🏁 Оплачен до: ").append(paidUntilStr).append("\n").append("💰 Цена: ").append(priceStr).append("\n").append("📊 Статус: ").append(status).append("\n--------------------------\n");
            }
        }
        sendMessage(chatId, sb.toString());
    }

    private void startAddingOgorod(Long chatId) { userStateMap.put(chatId, UserState.AWAITING_OGOROD_NAME); ogorodDraftMap.put(chatId, new Ogorod()); sendMessageWithCancel(chatId, "Введите название огорода:"); }
    private void startDeletingOgorod(Long chatId) { sendMessageWithCancel(chatId, "Введите ID огорода для удаления (см. в Списке):"); userStateMap.put(chatId, UserState.AWAITING_OGOROD_DELETE_ID); }
    private void startEditingOgorod(Long chatId) { sendMessageWithCancel(chatId, "Введите ID огорода для редактирования:"); userStateMap.put(chatId, UserState.AWAITING_OGOROD_EDIT_ID); }

    private void startAddingPayment(Long chatId) { paymentDraftMap.put(chatId, new Payment()); userStateMap.put(chatId, UserState.AWAITING_NAME); sendMessageWithCancel(chatId, "Введите название (например: Дом):"); }
    private void startDeletingPayment(Long chatId) { sendMessageWithCancel(chatId, "Введите ID записи для удаления:"); userStateMap.put(chatId, UserState.AWAITING_DELETE_ID); }
    private void startEditingPayment(Long chatId) { sendMessageWithCancel(chatId, "Введите ID записи для редактирования:"); userStateMap.put(chatId, UserState.AWAITING_EDIT_ID); }
    private void startExtension(Long chatId, String data, Integer msgId, String text) { extensionPaymentIdMap.put(chatId, Long.parseLong(data.split("_")[1])); userStateMap.put(chatId, UserState.AWAITING_EXTENSION_DAYS); sendMessageWithCancel(chatId, "Введите новое значение (на сколько щас оплачено?):"); }
    private void handleEditFieldChoice(Long chatId, String data, Integer msgId) { String f = data.split("_")[1]; editFieldMap.put(chatId, f); editPaymentIdMap.put(chatId, Long.parseLong(data.split("_")[2])); userStateMap.put(chatId, UserState.AWAITING_EDIT_VALUE); if(f.equals("date")) sendMessageWithCancel(chatId, "Введите дату покупки (ГГГГ-ММ-ДД):"); else sendMessageWithCancel(chatId, "Введите новое значение:"); }
    private void processEditValue(Long chatId, String text) { Payment p = paymentRepository.findById(editPaymentIdMap.get(chatId)).get(); String f = editFieldMap.get(chatId); if(f.equals("name")) p.setName(text); if(f.equals("price")) p.setPrice(parsePrice(text)); if(f.equals("days")) { int d = Integer.parseInt(text); p.setDaysPaid(d); p.setPaidUntil(LocalDate.now(KYIV_ZONE).plusDays(d)); } if(f.equals("date")) p.setPurchaseDate(LocalDate.parse(text)); paymentRepository.save(p); resetUserState(chatId); sendMessage(chatId, "✅ Запись обновлена."); showMainMenu(chatId, "Меню:"); }
    private void sendEditOptions(Long chatId, Long id) { editPaymentIdMap.put(chatId, id); SendMessage m = new SendMessage(); m.setChatId(chatId); m.setText("Что менять?"); InlineKeyboardMarkup mk = new InlineKeyboardMarkup(); List<List<InlineKeyboardButton>> r = new ArrayList<>(); List<InlineKeyboardButton> r1 = new ArrayList<>(); r1.add(createBtn("Название", "edit_name_"+id)); r1.add(createBtn("Дата покупки", "edit_date_"+id)); List<InlineKeyboardButton> r2 = new ArrayList<>(); r2.add(createBtn("Цена", "edit_price_"+id)); r2.add(createBtn("Дни оплаты", "edit_days_"+id)); r.add(r1); r.add(r2); mk.setKeyboard(r); m.setReplyMarkup(mk); try { execute(m); } catch (Exception e) {} }

    private void startCalculator(Long chatId) { SendMessage m = new SendMessage(); m.setChatId(chatId); m.setText("🧮 <b>Калькулятор</b>\nВыберите режим:"); m.setParseMode("HTML"); InlineKeyboardMarkup mk = new InlineKeyboardMarkup(); List<List<InlineKeyboardButton>> r = new ArrayList<>(); List<InlineKeyboardButton> row = new ArrayList<>(); row.add(createBtn("⏱ По часам", "calc_mode_hours")); row.add(createBtn("📅 По дням", "calc_mode_days")); r.add(row); mk.setKeyboard(r); m.setReplyMarkup(mk); try { execute(m); } catch (Exception e) {} }
    private void processCalcMode(Long chatId, String mode) { calcModeMap.put(chatId, mode); userStateMap.put(chatId, UserState.AWAITING_CALC_AMOUNT); String l = mode.equals("hours") ? "часов" : "дней"; sendMessageWithCancel(chatId, "Введите количество " + l + " (целое число):"); }

    public void sendNotification(Long chatId, String text, Long paymentId) { SendMessage m = new SendMessage(); m.setChatId(chatId); m.setText(text); m.setParseMode("HTML"); InlineKeyboardMarkup mk = new InlineKeyboardMarkup(); List<List<InlineKeyboardButton>> rows = new ArrayList<>(); List<InlineKeyboardButton> r = new ArrayList<>(); r.add(createBtn("🔄 Продлить", "extend_" + paymentId)); rows.add(r); mk.setKeyboard(rows); m.setReplyMarkup(mk); try { execute(m); } catch (Exception e) {} }
    public void sendOgorodNotification(Long chatId, String text, Long ogorodId) { SendMessage m = new SendMessage(); m.setChatId(chatId); m.setText(text); m.setParseMode("HTML"); InlineKeyboardMarkup mk = new InlineKeyboardMarkup(); List<List<InlineKeyboardButton>> rows = new ArrayList<>(); List<InlineKeyboardButton> r = new ArrayList<>(); r.add(createBtn("🔄 Продлить", "ogorod_extend_" + ogorodId)); rows.add(r); mk.setKeyboard(rows); m.setReplyMarkup(mk); try { execute(m); } catch (Exception e) {} }
    public void sendMessage(Long chatId, String text) { SendMessage m = new SendMessage(); m.setChatId(chatId); m.setText(text); m.setParseMode("HTML"); try { execute(m); } catch (Exception e) {} }
    private void sendMessageWithCancel(Long chatId, String text) { SendMessage m = new SendMessage(); m.setChatId(chatId); m.setText(text); ReplyKeyboardMarkup mk = new ReplyKeyboardMarkup(); mk.setResizeKeyboard(true); List<KeyboardRow> kb = new ArrayList<>(); KeyboardRow r = new KeyboardRow(); r.add("🔙 Отмена"); kb.add(r); mk.setKeyboard(kb); m.setReplyMarkup(mk); try { execute(m); } catch (Exception e) {} }
    private void sendExcelReport(Long chatId, List<ClientRecord> data, String filename) { try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) { Sheet sheet = workbook.createSheet("Clients"); Row header = sheet.createRow(0); header.createCell(0).setCellValue("ID"); header.createCell(1).setCellValue("Никнейм"); header.createCell(2).setCellValue("Огород"); header.createCell(3).setCellValue("Цена"); header.createCell(4).setCellValue("Цена/Час"); header.createCell(5).setCellValue("Контакт"); header.createCell(6).setCellValue("Начало"); header.createCell(7).setCellValue("Конец"); int i=1; DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"); for(ClientRecord c : data) { Row r = sheet.createRow(i++); double pricePerHour = "дн".equals(c.getDurationUnit()) ? c.getPrice() / (c.getDuration() * 24.0) : c.getPrice() / c.getDuration(); r.createCell(0).setCellValue(c.getId()); r.createCell(1).setCellValue(c.getNickname()); r.createCell(2).setCellValue(c.getOgorodName()); r.createCell(3).setCellValue(formatPrice(c.getPrice())); r.createCell(4).setCellValue(formatPrice(pricePerHour)); r.createCell(5).setCellValue(c.getContact()); r.createCell(6).setCellValue(c.getStartDate().format(fmt)); r.createCell(7).setCellValue(c.getEndDate().format(fmt)); } for(int col=0; col<8; col++) sheet.autoSizeColumn(col); workbook.write(out); SendDocument doc = new SendDocument(); doc.setChatId(chatId); doc.setDocument(new InputFile(new ByteArrayInputStream(out.toByteArray()), filename)); doc.setCaption("📊 Отчет: " + filename); execute(doc); } catch(Exception e) { sendMessage(chatId, "❌ Ошибка создания Excel."); } }

    private boolean isRegistered(Long chatId) { return userRepository.existsById(chatId) && userRepository.findById(chatId).get().isRegistered(); }
    private void handleRegistration(Message message) { Long chatId = message.getChatId(); if (message.hasContact()) { User user = new User(); user.setChatId(chatId); user.setFirstName(message.getFrom().getFirstName()); user.setUserName(message.getFrom().getUserName()); user.setPhoneNumber(message.getContact().getPhoneNumber()); user.setRegistered(true); userRepository.save(user); sendMessage(chatId, "✅ Регистрация успешна!"); showMainMenu(chatId, "Меню:"); return; } SendMessage msg = new SendMessage(); msg.setChatId(chatId); msg.setText("Авторизация:"); ReplyKeyboardMarkup kb = new ReplyKeyboardMarkup(); kb.setResizeKeyboard(true); KeyboardRow row = new KeyboardRow(); KeyboardButton btn = new KeyboardButton("📞 Отправить номер"); btn.setRequestContact(true); row.add(btn); kb.setKeyboard(Collections.singletonList(row)); msg.setReplyMarkup(kb); try { execute(msg); } catch (Exception e) {} }
    private void resetUserState(Long chatId) { userStateMap.put(chatId, UserState.DEFAULT); paymentDraftMap.remove(chatId); ogorodDraftMap.remove(chatId); clientDraftMap.remove(chatId); extensionPaymentIdMap.remove(chatId); editPaymentIdMap.remove(chatId); editFieldMap.remove(chatId); editClientIdMap.remove(chatId); editOgorodIdMap.remove(chatId); extensionOgorodIdMap.remove(chatId); harvestParamOgorodId.remove(chatId); }
    private Double parsePrice(String text) { return Double.parseDouble(text.replace(",", ".").replace(" ", "")); }
    private String formatPrice(Double price) { if (price == null) return "---"; DecimalFormatSymbols s = new DecimalFormatSymbols(Locale.US); s.setGroupingSeparator('.'); DecimalFormat df = new DecimalFormat("#,###.##", s); return df.format(price); }
    private InlineKeyboardButton createBtn(String text, String callbackData) { InlineKeyboardButton btn = new InlineKeyboardButton(); btn.setText(text); btn.setCallbackData(callbackData); return btn; }
}
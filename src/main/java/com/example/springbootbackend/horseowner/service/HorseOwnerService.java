package com.example.springbootbackend.horseowner.service;

import com.example.springbootbackend.horseowner.repository.HorseOwnerRepository;
import com.example.springbootbackend.veterinarian.support.VetTime;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class HorseOwnerService {
    private final HorseOwnerRepository repository;
    private final VetTime time;

    public HorseOwnerService(HorseOwnerRepository repository, VetTime time) {
        this.repository = repository;
        this.time = time;
    }

    public Map<String, Object> horses(String email) {
        long ownerId = requireOwner(email);
        List<Map<String, Object>> items = repository.findHorses(ownerId);
        return Map.of("items", items, "total", items.size());
    }

    public Map<String, Object> dashboard(String email, UUID horseId, Integer requestedYear) {
        long ownerId = requireOwner(email);
        int year = requestedYear == null ? time.today().getYear() : requestedYear;
        if (year < 2000 || year > 2100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Năm báo cáo phải nằm trong khoảng 2000–2100");

        String horse = horseId.toString();
        Map<String, Object> horseProfile = repository.findOwnedHorse(horse, ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy ngựa thuộc sở hữu của bạn"));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("horse", horseProfile);
        response.put("health_exams", repository.findHealthExams(horse));
        response.put("training_metrics", repository.findTrainingMetrics(horse));
        response.put("training_schedules", repository.findTrainingSchedules(horse));
        response.put("training_videos", repository.findTrainingVideos(horse));
        response.put("race_history", repository.findRaceHistory(horse));
        response.put("financial_report", financialReport(horse, year));
        return response;
    }

    private Map<String, Object> financialReport(String horse, int year) {
        List<Map<String, Object>> transactions = repository.findFinancialTransactions(horse, year);
        BigDecimal care = BigDecimal.ZERO;
        BigDecimal medical = BigDecimal.ZERO;
        BigDecimal prize = BigDecimal.ZERO;
        BigDecimal other = BigDecimal.ZERO;
        Map<String, Map<String, Object>> monthly = new TreeMap<>();

        for (Map<String, Object> transaction : transactions) {
            String category = financialCategory(transaction.get("transaction_type"));
            transaction.put("category", category);
            BigDecimal amount = transaction.get("amount") instanceof BigDecimal value ? value : BigDecimal.ZERO;
            String period = String.valueOf(transaction.get("billing_period"));
            Map<String, Object> month = monthly.computeIfAbsent(period, HorseOwnerService::newMonth);

            switch (category) {
                case "CARE" -> {
                    care = care.add(amount);
                    addToMonth(month, "care_cost", amount);
                }
                case "MEDICAL" -> {
                    medical = medical.add(amount);
                    addToMonth(month, "medical_cost", amount);
                }
                case "PRIZE" -> {
                    prize = prize.add(amount);
                    addToMonth(month, "prize_income", amount);
                }
                default -> {
                    other = other.add(amount);
                    addToMonth(month, "other_amount", amount);
                }
            }
        }

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("care_cost", care);
        totals.put("medical_cost", medical);
        totals.put("total_expenses", care.add(medical));
        totals.put("prize_income", prize);
        totals.put("other_amount", other);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("year", year);
        report.put("totals", totals);
        report.put("monthly", new ArrayList<>(monthly.values()));
        report.put("transactions", transactions);
        return report;
    }

    private static Map<String, Object> newMonth(String period) {
        Map<String, Object> month = new LinkedHashMap<>();
        month.put("billing_period", period);
        month.put("care_cost", BigDecimal.ZERO);
        month.put("medical_cost", BigDecimal.ZERO);
        month.put("prize_income", BigDecimal.ZERO);
        month.put("other_amount", BigDecimal.ZERO);
        return month;
    }

    private static void addToMonth(Map<String, Object> month, String field, BigDecimal amount) {
        month.put(field, ((BigDecimal) month.get(field)).add(amount));
    }

    private static String financialCategory(Object rawType) {
        String type = rawType == null ? "" : rawType.toString().toLowerCase(Locale.ROOT);
        if (containsAny(type, "prize", "reward", "bonus", "award", "winning", "thuong", "thưởng")) return "PRIZE";
        if (containsAny(type, "medical", "health", "vet", "medicine", "pharmacy", "doctor", "y_te", "y tế")) return "MEDICAL";
        if (containsAny(type, "care", "feed", "food", "hay", "nutrition", "boarding", "stable", "upkeep", "maintenance", "nuoi", "nuôi")) return "CARE";
        return "OTHER";
    }

    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }

    private long requireOwner(String email) {
        Long ownerId = repository.findApprovedOwnerId(email);
        if (ownerId == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chỉ Horse Owner đã được duyệt mới có quyền truy cập");
        return ownerId;
    }
}

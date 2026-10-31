package com.example.springbootbackend.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.springbootbackend.entity.InventoryLot;
import com.example.springbootbackend.entity.InventoryTransaction;
import com.example.springbootbackend.entity.SupplyRequest;
import com.example.springbootbackend.exception.VetException;
import com.example.springbootbackend.repository.InventoryLotRepository;
import com.example.springbootbackend.repository.InventoryTransactionRepository;
import com.example.springbootbackend.repository.SupplyRequestRepository;
import com.fasterxml.jackson.databind.JsonNode;

@Service
@Transactional(readOnly = true)
public class InventoryService {
    private static final BigDecimal MAX_QUANTITY = new BigDecimal("99999999.99");
    private final VetData db;
    private final VetAccess access;
    private final VetAudit audit;
    private final VetTime time;
    private final NotificationService notifications;
    private final InventoryLotRepository lotRepository;
    private final InventoryTransactionRepository transactionRepository;
    private final SupplyRequestRepository supplyRequestRepository;

    public InventoryService(
            VetData db,
            VetAccess access,
            VetAudit audit,
            VetTime time,
            NotificationService notifications,
            InventoryLotRepository lotRepository,
            InventoryTransactionRepository transactionRepository,
            SupplyRequestRepository supplyRequestRepository) {
        this.db = db;
        this.access = access;
        this.audit = audit;
        this.time = time;
        this.notifications = notifications;
        this.lotRepository = lotRepository;
        this.transactionRepository = transactionRepository;
        this.supplyRequestRepository = supplyRequestRepository;
    }

    public Map<String, Object> managerItems(String email) {
        access.requireRole(email, "CLUB_MANAGER");
        return itemList(false);
    }

    public Map<String, Object> managerItemDetail(String email, UUID itemId) {
        access.requireRole(email, "CLUB_MANAGER");
        String id = itemId.toString();
        Map<String, Object> result = item(id);
        result.put("lots", db.query("SELECT id,lot_number,expiry_date,quantity_received,quantity_remaining,received_at "
                + "FROM inventory_lots WHERE item_id=? ORDER BY CASE WHEN expiry_date IS NULL THEN 1 ELSE 0 END,expiry_date,received_at DESC",
                VetRows.MAPPER, id));
        return result;
    }

    public Map<String, Object> groomItems(String email) {
        access.requireRole(email, "GROOM");
        return itemList(true);
    }

    public Map<String, Object> groomItems(String email, String category, boolean lowStockOnly) {
        access.requireRole(email, "GROOM");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) itemList(true).get("data");
        if (category != null && !category.isBlank()) data.removeIf(row -> !category.equalsIgnoreCase(String.valueOf(row.get("category"))));
        if (lowStockOnly) data.removeIf(row -> !Boolean.TRUE.equals(row.get("is_low_stock")));
        return Map.of("data", data);
    }

    private Map<String, Object> itemList(boolean activeOnly) {
        String where = activeOnly ? " WHERE i.is_active=TRUE" : "";
        LocalDate today = time.today();
        var items = db.query("SELECT i.id,i.item_code,i.item_name,i.category,i.unit,i.quantity_in_stock,"
                + "i.reorder_threshold,i.description,i.is_active,i.updated_at,"
                + "(SELECT MIN(l.expiry_date) FROM inventory_lots l WHERE l.item_id=i.id AND l.quantity_remaining>0) AS nearest_expiry_date,"
                + "COALESCE((SELECT SUM(l.quantity_remaining) FROM inventory_lots l WHERE l.item_id=i.id "
                + "AND (l.expiry_date IS NULL OR l.expiry_date>=?)),0) AS usable_quantity,"
                + "COALESCE((SELECT SUM(l.quantity_remaining) FROM inventory_lots l WHERE l.item_id=i.id "
                + "AND l.expiry_date<?),0) AS expired_quantity "
                + "FROM inventory_items i" + where + " ORDER BY i.category,i.item_name,i.id", VetRows.MAPPER,
                today, today);
        int low = 0, expiring = 0, expired = 0, out = 0, active = 0;
        for (Map<String, Object> item : items) {
            boolean isActive = Boolean.TRUE.equals(item.get("is_active"));
            BigDecimal quantity = decimal(item.get("quantity_in_stock"));
            BigDecimal usable = decimal(item.get("usable_quantity"));
            BigDecimal expiredQuantity = decimal(item.get("expired_quantity"));
            BigDecimal available = quantity.subtract(expiredQuantity).max(BigDecimal.ZERO);
            BigDecimal threshold = decimal(item.get("reorder_threshold"));
            LocalDate nearest = item.get("nearest_expiry_date") instanceof LocalDate value ? value : null;
            String status = stockStatus(isActive, quantity, usable, expiredQuantity, threshold, nearest, today);
            item.put("total_quantity_in_stock", quantity);
            item.put("quantity_in_stock", available);
            item.put("quantity_on_hand", available);
            item.put("status", status);
            item.put("is_low_stock", isActive && available.compareTo(threshold) <= 0);
            item.put("is_expiring", isActive && nearest != null && !nearest.isBefore(today) && !nearest.isAfter(today.plusDays(30)));
            if (isActive) {
                active++;
                if (available.compareTo(threshold) <= 0) low++;
                if (status.equals("OUT_OF_STOCK")) out++;
                if (status.equals("EXPIRED")) expired++;
                if (status.equals("EXPIRING")) expiring++;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_items", active);
        summary.put("low_stock", low);
        summary.put("expiring_soon", expiring);
        summary.put("expired", expired);
        summary.put("out_of_stock", out);
        if (activeOnly) return Map.of("data", items);
        long pending = supplyRequestRepository.countByStatus("Pending");
        summary.put("pending_requests", pending);
        return Map.of("data", items, "summary", summary);
    }

    private String stockStatus(boolean active, BigDecimal quantity, BigDecimal usable, BigDecimal expired,
            BigDecimal threshold, LocalDate nearestExpiry, LocalDate today) {
        if (!active) return "INACTIVE";
        if (quantity.signum() <= 0) return "OUT_OF_STOCK";
        if (expired.signum() > 0 && usable.compareTo(quantity) < 0) return "EXPIRED";
        if (nearestExpiry != null && !nearestExpiry.isBefore(today) && !nearestExpiry.isAfter(today.plusDays(30))) return "EXPIRING";
        if (quantity.compareTo(threshold) <= 0) return "LOW_STOCK";
        return "SUFFICIENT";
    }

    @Transactional
    public Map<String, Object> createItem(String email, String ip, JsonNode body) {
        long actor = access.requireRole(email, "CLUB_MANAGER");
        Map<String, Object> fields = VetInput.parse(body,
                "item_name:100", "category:30", "unit:20", "quantity_in_stock:number",
                "reorder_threshold:number", "description:2000", "initial_lot_number:80", "initial_expiry_date:date");
        VetInput.required(fields, "item_name", "category", "unit", "reorder_threshold");
        String name = text(fields, "item_name");
        String category = text(fields, "category");
        String unit = text(fields, "unit");
        if (name.isBlank() || category.isBlank() || unit.isBlank()) throw VetException.invalid("item_name", "Không được để trống");
        if (unit.matches("^[0-9]+(\\.[0-9]+)?$")) throw VetException.invalid("unit", "Đơn vị tính không thể là số");
        BigDecimal quantity = quantity(fields.getOrDefault("quantity_in_stock", BigDecimal.ZERO), "quantity_in_stock", true);
        BigDecimal minimum = quantity(fields.get("reorder_threshold"), "reorder_threshold", true);
        validateBatch(category, quantity, fields.get("initial_lot_number"), fields.get("initial_expiry_date"));
        if (db.queryForObject("SELECT count(*) FROM inventory_items WHERE lower(item_name)=lower(?)", Long.class, name) > 0)
            throw new VetException(409, "ITEM_NAME_EXISTS", "Tên vật tư đã tồn tại");

        String id = UUID.randomUUID().toString();
        String code = "MAT-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        LocalDateTime now = time.utcNow();
        db.update("INSERT INTO inventory_items(id,item_code,item_name,category,unit,quantity_in_stock,reorder_threshold,description,is_active,updated_by,updated_at) "
                + "VALUES (?,?,?,?,?,?,?,?,TRUE,?,?)", id, code, name, category, unit, quantity, minimum,
                fields.get("description"), actor, now);
        if (quantity.signum() > 0) {
            String lotId = insertLot(id, initialLotNumber(fields, "OPENING-" + code), date(fields.get("initial_expiry_date")), quantity, actor, now);
            transaction(id, lotId, actor, "OPENING", quantity, BigDecimal.ZERO, quantity, "Tồn ban đầu", now);
        }
        audit.record(actor, ip, "CREATE_INVENTORY_ITEM:" + id);
        return item(id);
    }

    @Transactional
    public Map<String, Object> updateItem(String email, String ip, UUID itemId, JsonNode body) {
        long actor = access.requireRole(email, "CLUB_MANAGER");
        String id = itemId.toString();
        lockedItem(id);
        Map<String, Object> fields = VetInput.parse(body,
                "item_name:100", "category:30", "unit:20", "reorder_threshold:number", "description:2000", "is_active:bool");
        if (fields.isEmpty()) throw new VetException(400, "VALIDATION_ERROR", "Cần ít nhất một trường để cập nhật");
        if (fields.containsKey("item_name")) {
            String name = text(fields, "item_name");
            if (name.isBlank()) throw VetException.invalid("item_name", "Bắt buộc");
            if (db.queryForObject("SELECT count(*) FROM inventory_items WHERE lower(item_name)=lower(?) AND id<>?", Long.class, name, id) > 0)
                throw new VetException(409, "ITEM_NAME_EXISTS", "Tên vật tư đã tồn tại");
            fields.put("item_name", name);
        }
        for (String column : List.of("category", "unit")) {
            if (fields.containsKey(column)) {
                String value = text(fields, column);
                if (value.isBlank()) throw VetException.invalid(column, "Bắt buộc");
                if ("unit".equals(column) && value.matches("^[0-9]+(\\.[0-9]+)?$"))
                    throw VetException.invalid("unit", "Đơn vị tính không thể là số");
                fields.put(column, value);
            }
        }
        if (fields.containsKey("reorder_threshold")) fields.put("reorder_threshold", quantity(fields.get("reorder_threshold"), "reorder_threshold", true));
        var assignments = new ArrayList<String>();
        var args = new ArrayList<Object>();
        for (String field : List.of("item_name", "category", "unit", "reorder_threshold", "description", "is_active")) {
            if (fields.containsKey(field)) { assignments.add(field + "=?"); args.add(fields.get(field)); }
        }
        assignments.add("updated_by=?");
        args.add(actor);
        assignments.add("updated_at=?");
        args.add(time.utcNow());
        args.add(id);
        db.update("UPDATE inventory_items SET " + String.join(",", assignments) + " WHERE id=?", args.toArray());
        audit.record(actor, ip, "UPDATE_INVENTORY_ITEM:" + id);
        return item(id);
    }

    @Transactional
    public Map<String, Object> receive(String email, String ip, UUID itemId, JsonNode body) {
        long actor = access.requireRole(email, "CLUB_MANAGER");
        Map<String, Object> item = lockedItem(itemId.toString());
        ensureActive(item);
        Map<String, Object> fields = VetInput.parse(body, "quantity:number", "received_date:date", "lot_number:80", "expiry_date:date", "reason:500");
        VetInput.required(fields, "quantity");
        BigDecimal amount = positive(fields.get("quantity"), "quantity");
        LocalDate receivedDate = fields.get("received_date") == null ? time.today() : date(fields.get("received_date"));
        if (receivedDate.isAfter(time.today())) throw VetException.invalid("received_date", "Ngày nhận không được ở tương lai");
        LocalDate expiry = date(fields.get("expiry_date"));
        String lotNumber = text(fields, "lot_number");
        validateBatch(text(item, "category"), amount, lotNumber, expiry);
        if (expiry != null && expiry.isBefore(receivedDate)) throw VetException.invalid("expiry_date", "Hạn dùng phải bằng hoặc sau ngày nhận");
        LocalDateTime now = time.utcNow();
        BigDecimal before = decimal(item.get("quantity_in_stock"));
        BigDecimal after = before.add(amount);
        ensureWithinLimit(after, "quantity");
        String lotId = insertLot(itemId.toString(), lotNumber.isBlank() ? "LOT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT) : lotNumber,
                expiry, amount, actor, time.utcStartOf(receivedDate));
        db.update("UPDATE inventory_items SET quantity_in_stock=?,updated_by=?,updated_at=? WHERE id=?", after, actor, now, itemId.toString());
        transaction(itemId.toString(), lotId, actor, "RECEIPT", amount, before, after, text(fields, "reason"), now);
        audit.record(actor, ip, "INVENTORY_RECEIPT:" + itemId + ":" + amount);
        return item(itemId.toString());
    }

    @Transactional
    public Map<String, Object> adjust(String email, String ip, UUID itemId, JsonNode body) {
        long actor = access.requireRole(email, "CLUB_MANAGER");
        Map<String, Object> item = lockedItem(itemId.toString());
        Map<String, Object> fields = VetInput.parse(body, "quantity_change:number", "reason:500", "lot_number:80", "expiry_date:date");
        VetInput.required(fields, "quantity_change", "reason");
        BigDecimal change = signedQuantity(fields.get("quantity_change"), "quantity_change");
        String reason = text(fields, "reason");
        if (change.signum() == 0) throw VetException.invalid("quantity_change", "Mức điều chỉnh không được bằng 0");
        if (reason.isBlank()) throw VetException.invalid("reason", "Bắt buộc ghi lý do điều chỉnh");
        BigDecimal before = decimal(item.get("quantity_in_stock"));
        BigDecimal after = before.add(change);
        ensureWithinLimit(after, "quantity_change");
        if (after.signum() < 0) throw new VetException(409, "INSUFFICIENT_STOCK", "Điều chỉnh làm tồn kho âm");
        LocalDateTime now = time.utcNow();
        String lotId = null;
        if (change.signum() > 0) {
            LocalDate expiry = date(fields.get("expiry_date"));
            String lotNumber = text(fields, "lot_number");
            validateBatch(text(item, "category"), change, lotNumber, expiry);
            if (expiry != null && expiry.isBefore(time.today())) throw VetException.invalid("expiry_date", "Hạn dùng không được trước ngày hiện tại");
            lotId = insertLot(itemId.toString(), lotNumber.isBlank() ? "ADJUST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT) : lotNumber,
                    expiry, change, actor, now);
        } else {
            depleteLots(itemId.toString(), change.abs(), false, true, now, actor, "ADJUSTMENT", reason, before);
        }
        db.update("UPDATE inventory_items SET quantity_in_stock=?,updated_by=?,updated_at=? WHERE id=?", after, actor, now, itemId.toString());
        if (change.signum() > 0) transaction(itemId.toString(), lotId, actor, "ADJUSTMENT", change, before, after, reason, now);
        audit.record(actor, ip, "INVENTORY_ADJUSTMENT:" + itemId + ":" + change + ":" + reason);
        return item(itemId.toString());
    }

    @Transactional
    public Map<String, Object> use(String email, String ip, JsonNode body) {
        long actor = access.requireRole(email, "GROOM");
        Map<String, Object> fields = VetInput.parse(body, "item_id:uuid", "quantity:number", "reason:500");
        VetInput.required(fields, "item_id", "quantity");
        String itemId = fields.get("item_id").toString();
        Map<String, Object> item = lockedItem(itemId);
        ensureActive(item);
        BigDecimal amount = positive(fields.get("quantity"), "quantity");
        BigDecimal before = decimal(item.get("quantity_in_stock"));
        BigDecimal usable = db.queryForObject("SELECT COALESCE(SUM(quantity_remaining),0) FROM inventory_lots WHERE item_id=? AND (expiry_date IS NULL OR expiry_date>=?)",
                BigDecimal.class, itemId, time.today());
        if (usable.compareTo(amount) < 0) throw new VetException(409, "INSUFFICIENT_USABLE_STOCK", "Tồn khả dụng không đủ hoặc số lô còn lại đã hết hạn");
        LocalDateTime now = time.utcNow();
        depleteLots(itemId, amount, true, false, now, actor, "USAGE", text(fields, "reason"), before);
        BigDecimal after = before.subtract(amount);
        db.update("UPDATE inventory_items SET quantity_in_stock=?,updated_by=?,updated_at=? WHERE id=?", after, actor, now, itemId);
        audit.record(actor, ip, "INVENTORY_USAGE:" + itemId + ":" + amount);
        for (long manager : activeManagers()) notifications.schedule(manager, "Inventory_Transactions", itemId,
                "Groom recorded use of " + amount.stripTrailingZeros().toPlainString() + " " + item.get("unit") + " " + item.get("item_name") + ".", time.now());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("item_id", itemId);
        result.put("quantity_change", amount.negate());
        result.put("quantity_before", before);
        result.put("quantity_after", after);
        result.put("status", stockStatus(true, after, usable.subtract(amount), BigDecimal.ZERO,
                decimal(item.get("reorder_threshold")), null, time.today()));
        return result;
    }

    private void depleteLots(String itemId, BigDecimal amount, boolean excludeExpired, boolean includeExpired,
            LocalDateTime now, long actor, String type, String reason, BigDecimal stockBefore) {
        String eligibility = excludeExpired ? " AND (expiry_date IS NULL OR expiry_date>=?)" : "";
        Object[] queryArgs = excludeExpired ? new Object[] { itemId, time.today() } : new Object[] { itemId };
        var lots = db.query("SELECT id,quantity_remaining FROM inventory_lots WHERE item_id=? AND quantity_remaining>0"
                + eligibility + " ORDER BY CASE WHEN expiry_date IS NULL THEN 1 ELSE 0 END,expiry_date,received_at,id FOR UPDATE",
                VetRows.MAPPER, queryArgs);
        BigDecimal remaining = amount;
        BigDecimal runningStock = stockBefore;
        for (Map<String, Object> lot : lots) {
            if (remaining.signum() <= 0) break;
            BigDecimal inLot = decimal(lot.get("quantity_remaining"));
            BigDecimal taken = inLot.min(remaining);
            BigDecimal nextLot = inLot.subtract(taken);
            db.update("UPDATE inventory_lots SET quantity_remaining=? WHERE id=?", nextLot, lot.get("id"));
            BigDecimal nextStock = runningStock.subtract(taken);
            transaction(itemId, lot.get("id").toString(), actor, type, taken.negate(), runningStock, nextStock, reason, now);
            runningStock = nextStock;
            remaining = remaining.subtract(taken);
        }
        if (remaining.signum() > 0) throw new VetException(409, "INSUFFICIENT_STOCK", "Số lượng lô khả dụng không đủ");
    }

    public Map<String, Object> transactions(String email, boolean manager, UUID itemId, int limit) {
        long actor = access.requireRole(email, manager ? "CLUB_MANAGER" : "GROOM");
        if (limit < 1 || limit > 200) throw VetException.invalid("limit", "Phải từ 1 đến 200");
        String where = " WHERE 1=1";
        var args = new ArrayList<Object>();
        if (!manager) { where += " AND t.actor_id=?"; args.add(actor); }
        if (itemId != null) { where += " AND t.item_id=?"; args.add(itemId.toString()); }
        args.add(limit);
        var rows = db.query("SELECT t.id,t.item_id,i.item_code,i.item_name,t.lot_id,l.lot_number,l.expiry_date,"
                + "t.actor_id,u.full_name AS actor_name,t.transaction_type,t.quantity_change,t.quantity_before,"
                + "t.quantity_after,t.reason,t.created_at FROM inventory_transactions t "
                + "JOIN inventory_items i ON i.id=t.item_id LEFT JOIN inventory_lots l ON l.id=t.lot_id "
                + "JOIN users u ON u.user_id=t.actor_id" + where + " ORDER BY t.created_at DESC,t.id DESC LIMIT ?",
                VetRows.MAPPER, args.toArray());
        return Map.of("data", rows);
    }

    @Transactional
    public Map<String, Object> createSupplyRequest(String email, String ip, JsonNode body) {
        long groom = access.requireRole(email, "GROOM");
        Map<String, Object> fields = VetInput.parse(body, "item_id:uuid", "quantity_requested:number", "reason:2000");
        VetInput.required(fields, "item_id", "quantity_requested");
        BigDecimal quantity = positive(fields.get("quantity_requested"), "quantity_requested");
        String itemId = fields.get("item_id").toString();
        Map<String, Object> item = lockedItem(itemId);
        ensureActive(item);
        boolean duplicatePending = supplyRequestRepository.existsByRequestedByAndItemIdAndStatus(groom, itemId, "Pending");
        String id = UUID.randomUUID().toString();
        LocalDateTime now = time.utcNow();

        SupplyRequest request = new SupplyRequest();
        request.setId(id);
        request.setRequestedBy(groom);
        request.setItemId(itemId);
        request.setQuantityRequested(quantity);
        request.setReason(fields.get("reason") != null ? fields.get("reason").toString() : null);
        request.setStatus("Pending");
        request.setCreatedAt(now);
        supplyRequestRepository.saveAndFlush(request);

        audit.record(groom, ip, "CREATE_SUPPLY_REQUEST:" + id);
        for (long manager : activeManagers()) notifications.schedule(manager, "Supply_Requests", id,
                "New supply request for " + item.get("item_name") + " (quantity " + quantity.stripTrailingZeros().toPlainString() + ")", time.now());
        Map<String, Object> result = supplyRequest(id);
        result.put("warnings", duplicatePending
                ? List.of(Map.of("code", "DUPLICATE_PENDING_REQUEST", "message", "You already have a pending request for this item"))
                : List.of());
        return result;
    }

    public Map<String, Object> supplyRequests(String email, boolean manager, String status) {
        access.requireRole(email, manager ? "CLUB_MANAGER" : "GROOM");
        String selected = status == null || status.isBlank() ? (manager ? "Pending" : "All") : status.trim();
        if (!List.of("Pending", "Approved", "PartiallyApproved", "Rejected", "All").contains(selected))
            throw VetException.invalid("status", "Trạng thái yêu cầu không hợp lệ");
        String where = " WHERE 1=1";
        var args = new ArrayList<Object>();
        if (!manager) { where += " AND r.requested_by=?"; args.add(access.requireRole(email, "GROOM")); }
        if (!"All".equals(selected)) { where += " AND r.status=?"; args.add(selected); }
        var rows = db.query("SELECT r.id,r.requested_by,u.full_name AS groom_name,r.item_id,i.item_name,i.item_code,"
                + "r.quantity_requested,r.quantity_approved,r.reason,r.review_note,r.status,r.reviewed_by,"
                + "r.created_at,r.reviewed_at FROM supply_requests r JOIN inventory_items i ON i.id=r.item_id "
                + "LEFT JOIN users u ON u.user_id=r.requested_by" + where + " ORDER BY r.created_at DESC,r.id DESC",
                VetRows.MAPPER, args.toArray());
        return Map.of("data", rows, "status", selected);
    }

    @Transactional
    public Map<String, Object> reviewSupplyRequest(String email, String ip, UUID requestId, JsonNode body) {
        long manager = access.requireRole(email, "CLUB_MANAGER");
        String id = requestId.toString();
        var requestRows = db.query("SELECT id,requested_by,item_id,quantity_requested,status FROM supply_requests WHERE id=? FOR UPDATE",
                VetRows.MAPPER, id);
        if (requestRows.isEmpty()) throw new VetException(404, "SUPPLY_REQUEST_NOT_FOUND", "Không tìm thấy yêu cầu bổ sung");
        Map<String, Object> request = requestRows.getFirst();
        if (!"Pending".equals(request.get("status"))) throw new VetException(409, "SUPPLY_REQUEST_REVIEWED", "Yêu cầu đã được xử lý");
        Map<String, Object> fields = VetInput.parse(body, "action:20", "quantity_approved:number", "review_note:1000");
        VetInput.required(fields, "action");
        String action = text(fields, "action").toUpperCase(Locale.ROOT);
        BigDecimal requested = decimal(request.get("quantity_requested"));
        BigDecimal approved = BigDecimal.ZERO;
        String status;
        if (action.equals("REJECT")) status = "Rejected";
        else if (action.equals("APPROVE")) {
            approved = fields.containsKey("quantity_approved") ? quantity(fields.get("quantity_approved"), "quantity_approved", true) : requested;
            if (approved.signum() <= 0 || approved.compareTo(requested) > 0) throw VetException.invalid("quantity_approved", "Phải lớn hơn 0 và không vượt quá số lượng yêu cầu");
            status = approved.compareTo(requested) == 0 ? "Approved" : "PartiallyApproved";
        } else throw VetException.invalid("action", "Giá trị hợp lệ: APPROVE hoặc REJECT");
        LocalDateTime now = time.utcNow();
        db.update("UPDATE supply_requests SET status=?,quantity_approved=?,review_note=?,reviewed_by=?,reviewed_at=? WHERE id=?",
                status, approved.signum() == 0 ? null : approved, fields.get("review_note"), manager, now, id);
        long requester = ((Number) request.get("requested_by")).longValue();
        String message = status.equals("Rejected") ? "Yêu cầu bổ sung vật tư đã bị từ chối."
                : "Yêu cầu bổ sung vật tư đã được " + (status.equals("PartiallyApproved") ? "duyệt một phần (" + approved.stripTrailingZeros().toPlainString() + ")." : "duyệt.");
        notifications.schedule(requester, "Supply_Requests", id, message, time.now());
        audit.record(manager, ip, "REVIEW_SUPPLY_REQUEST:" + id + ":" + status);
        return supplyRequest(id);
    }

    @Transactional
    public Map<String, Object> deactivate(String email, String ip, UUID itemId) {
        long actor = access.requireRole(email, "CLUB_MANAGER");
        String id = itemId.toString();
        lockedItem(id);
        db.update("UPDATE inventory_items SET is_active=FALSE,updated_by=?,updated_at=? WHERE id=?", actor, time.utcNow(), id);
        audit.record(actor, ip, "DEACTIVATE_INVENTORY_ITEM:" + id);
        return item(id);
    }

    private Map<String, Object> item(String id) {
        LocalDate today = time.today();
        Map<String, Object> row = db.query("SELECT i.id,i.item_code,i.item_name,i.category,i.unit,i.quantity_in_stock,"
                + "i.reorder_threshold,i.description,i.is_active,i.updated_at,"
                + "COALESCE((SELECT SUM(l.quantity_remaining) FROM inventory_lots l WHERE l.item_id=i.id "
                + "AND (l.expiry_date IS NULL OR l.expiry_date>=?)),0) AS usable_quantity,"
                + "COALESCE((SELECT SUM(l.quantity_remaining) FROM inventory_lots l WHERE l.item_id=i.id "
                + "AND l.expiry_date<?),0) AS expired_quantity "
                + "FROM inventory_items i WHERE i.id=?", VetRows.MAPPER, today, today, id).getFirst();
        BigDecimal quantity = decimal(row.get("quantity_in_stock"));
        BigDecimal expiredQuantity = decimal(row.get("expired_quantity"));
        BigDecimal available = quantity.subtract(expiredQuantity).max(BigDecimal.ZERO);
        row.put("total_quantity_in_stock", quantity);
        row.put("quantity_in_stock", available);
        row.put("quantity_on_hand", available);
        return row;
    }

    private Map<String, Object> lockedItem(String id) {
        var rows = db.query("SELECT id,item_code,item_name,category,unit,quantity_in_stock,reorder_threshold,description,is_active,updated_at "
                + "FROM inventory_items WHERE id=? FOR UPDATE", VetRows.MAPPER, id);
        if (rows.isEmpty()) throw new VetException(404, "ITEM_NOT_FOUND", "Không tìm thấy vật tư");
        return rows.getFirst();
    }

    private void ensureActive(Map<String, Object> item) {
        if (!Boolean.TRUE.equals(item.get("is_active"))) throw new VetException(409, "ITEM_INACTIVE", "Vật tư đã ngừng sử dụng");
    }

    private String insertLot(String itemId, String lotNumber, LocalDate expiry, BigDecimal quantity, long actor, LocalDateTime receivedAt) {
        String lotId = UUID.randomUUID().toString();
        LocalDateTime now = time.utcNow();
        InventoryLot lot = new InventoryLot();
        lot.setId(lotId);
        lot.setItemId(itemId);
        lot.setLotNumber(lotNumber);
        lot.setExpiryDate(expiry);
        lot.setQuantityReceived(quantity);
        lot.setQuantityRemaining(quantity);
        lot.setReceivedAt(receivedAt);
        lot.setReceivedBy(actor);
        lot.setCreatedAt(now);
        lotRepository.saveAndFlush(lot);
        return lotId;
    }

    private void transaction(String itemId, String lotId, long actor, String type, BigDecimal change,
            BigDecimal before, BigDecimal after, String reason, LocalDateTime createdAt) {
        InventoryTransaction tx = new InventoryTransaction();
        tx.setId(UUID.randomUUID().toString());
        tx.setItemId(itemId);
        tx.setLotId(lotId);
        tx.setActorId(actor);
        tx.setTransactionType(type);
        tx.setQuantityChange(change);
        tx.setQuantityBefore(before);
        tx.setQuantityAfter(after);
        tx.setReason(reason);
        tx.setCreatedAt(createdAt);
        transactionRepository.saveAndFlush(tx);
    }

    private Map<String, Object> supplyRequest(String id) {
        return db.query("SELECT r.id,r.requested_by,u.full_name AS groom_name,r.item_id,i.item_name,i.item_code,"
                + "r.quantity_requested,r.quantity_approved,r.reason,r.review_note,r.status,r.reviewed_by,r.created_at,r.reviewed_at "
                + "FROM supply_requests r JOIN inventory_items i ON i.id=r.item_id LEFT JOIN users u ON u.user_id=r.requested_by WHERE r.id=?",
                VetRows.MAPPER, id).getFirst();
    }

    private List<Long> activeManagers() {
        return db.queryForList("SELECT u.user_id FROM users u JOIN roles r ON r.role_id=u.role_id "
                + "WHERE r.role_name='CLUB_MANAGER' AND u.status='APPROVED' AND u.deleted_at IS NULL "
                + "AND u.must_change_password=FALSE", Long.class);
    }

    private void validateBatch(String category, BigDecimal quantity, Object lotNumber, Object expiry) {
        boolean expiringCategory = List.of("MEDICINE", "VETERINARY MEDICINE", "THUỐC THÚ Y")
                .contains(category.trim().toUpperCase(Locale.ROOT));
        if (quantity.signum() > 0 && expiringCategory && (lotNumber == null || lotNumber.toString().isBlank() || expiry == null))
            throw VetException.invalid("lot_number", "Thuốc thú y cần có số lô và hạn sử dụng");
        if (expiry != null && (lotNumber == null || lotNumber.toString().isBlank()))
            throw VetException.invalid("lot_number", "Cần nhập số lô khi có hạn sử dụng");
    }

    private String initialLotNumber(Map<String, Object> fields, String fallback) {
        String given = text(fields, "initial_lot_number");
        return given.isBlank() ? fallback : given;
    }

    private BigDecimal positive(Object value, String field) {
        BigDecimal result = quantity(value, field, true);
        if (result.signum() <= 0) throw VetException.invalid(field, "Phải là số dương");
        return result;
    }

    private BigDecimal quantity(Object value, String field, boolean allowZero) {
        if (!(value instanceof BigDecimal amount) || amount.signum() < 0
                || amount.signum() == 0 && !allowZero || amount.stripTrailingZeros().scale() > 2 || amount.compareTo(MAX_QUANTITY) > 0)
            throw VetException.invalid(field, "Giá trị phải trong giới hạn 99999999.99 và tối đa hai chữ số thập phân");
        return amount;
    }

    private BigDecimal signedQuantity(Object value, String field) {
        if (!(value instanceof BigDecimal amount) || amount.signum() == 0
                || amount.stripTrailingZeros().scale() > 2 || amount.abs().compareTo(MAX_QUANTITY) > 0)
            throw VetException.invalid(field, "Giá trị điều chỉnh phải khác 0, tối đa hai chữ số thập phân");
        return amount;
    }

    private void ensureWithinLimit(BigDecimal amount, String field) {
        if (amount.compareTo(MAX_QUANTITY) > 0) throw VetException.invalid(field, "Vượt quá giới hạn tồn kho");
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal amount) return amount;
        if (value instanceof Number number) return new BigDecimal(number.toString());
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }

    private String text(Map<String, Object> fields, String field) {
        Object value = fields.get(field);
        return value == null ? "" : value.toString().trim();
    }

    private LocalDate date(Object value) {
        return value instanceof LocalDate date ? date : null;
    }
}

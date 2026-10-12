package com.example.springbootbackend.veterinarian.support;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Typed allowlists used by partial updates; unknown and protected fields are ignored. */
public final class VetInput {
    private VetInput() {}
    public static Map<String,Object> parse(JsonNode body, String... fields) {
        if (body == null || !body.isObject()) throw VetException.invalid("body", "Phải là JSON object");
        Map<String,Object> result = new LinkedHashMap<>();
        for (String spec : fields) {
            String[] parts = spec.split(":"); String field=parts[0];
            if (!body.has(field)) continue;
            JsonNode n=body.get(field);
            if (n.isNull()) { result.put(field,null); continue; }
            try {
                Object value;
                switch(parts[1]) {
                    case "date" -> { if(!n.isTextual()) throw new IllegalArgumentException(); value=LocalDate.parse(n.textValue()); }
                    case "time" -> { if(!n.isTextual() || !n.textValue().matches("\\d{2}:\\d{2}")) throw new IllegalArgumentException(); value=LocalTime.parse(n.textValue()); }
                    case "uuid" -> { if(!n.isTextual()) throw new IllegalArgumentException(); value=UUID.fromString(n.textValue()).toString(); }
                    case "int" -> { if(!n.isNumber()) throw new IllegalArgumentException(); value=n.decimalValue().intValueExact(); }
                    case "long" -> { if(!n.isNumber()) throw new IllegalArgumentException(); value=n.decimalValue().longValueExact(); }
                    case "number" -> { if(!n.isNumber()) throw new IllegalArgumentException(); value=n.decimalValue(); }
                    case "bool" -> { if(!n.isBoolean()) throw new IllegalArgumentException(); value=n.booleanValue(); }
                    default -> {
                        if(!n.isTextual()) throw new IllegalArgumentException();
                        String s=n.textValue().trim();
                        int max=Integer.parseInt(parts[1]);
                        if(max>0 && s.codePointCount(0,s.length())>max) throw new IllegalArgumentException();
                        value=s;
                    }
                }
                result.put(field,value);
            } catch (RuntimeException ex) { throw VetException.invalid(field,"Sai kiểu, định dạng hoặc vượt độ dài cho phép ("+parts[1]+")"); }
        }
        return result;
    }
    public static void required(Map<String,Object> data,String... fields) {
        for(String f:fields) if(data.get(f)==null || data.get(f).toString().isBlank()) throw VetException.invalid(f,"Bắt buộc");
    }
    public static void choice(Map<String,Object> data,String field,String... choices) {
        required(data,field);
        if(!List.of(choices).contains(data.get(field))) throw VetException.invalid(field,"Giá trị không hợp lệ");
    }
    public static void dates(Map<String,Object> data,String start,String end) {
        required(data,start);
        if(data.get(end)!=null && ((LocalDate)data.get(end)).isBefore((LocalDate)data.get(start)))
            throw VetException.invalid(end,"Phải >= "+start);
    }
    public static void number(Map<String,Object> data,String field,String min,String max,int scale) {
        Object value=data.get(field); if(value==null) return;
        BigDecimal n=new BigDecimal(value.toString());
        if(n.compareTo(new BigDecimal(min))<0 || n.compareTo(new BigDecimal(max))>0 || n.stripTrailingZeros().scale()>scale)
            throw VetException.invalid(field,"Khoảng hợp lệ: "+min+" đến "+max+", tối đa "+scale+" số thập phân");
    }
}

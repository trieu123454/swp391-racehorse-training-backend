package com.example.springbootbackend.veterinarian.support;

public record ApiPage(int page, int limit) {
    public ApiPage {
        if (page < 1) throw VetException.invalid("page", "Phải >= 1");
        if (limit < 1 || limit > 100) throw VetException.invalid("limit", "Phải nằm trong khoảng 1 đến 100");
    }
    public long offset() { return (long) (page - 1) * limit; }
}

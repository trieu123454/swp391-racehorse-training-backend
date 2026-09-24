package com.example.springbootbackend.auth.service;

import com.example.springbootbackend.auth.entity.Role;
import com.example.springbootbackend.auth.repository.RoleRepository;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class RoleSeeder implements CommandLineRunner {

    private final RoleRepository roleRepository;

    public RoleSeeder(RoleRepository roleRepository) {
        this.roleRepository = roleRepository;
    }

    @Override
    public void run(String... args) {
        seed("HEAD_TRAINER", "Huan luyen vien truong");
        seed("VETERINARIAN", "Bac si thu y");
        seed("GROOM", "Nhan vien cham soc chuong trai");
        seed("HORSE_OWNER", "Chu so huu ngua");
        seed("CLUB_MANAGER", "Quan ly cau lac bo");
    }

    private void seed(String name, String description) {
        if (!roleRepository.existsByName(name)) {
            roleRepository.save(new Role(name, description));
        }
    }
}

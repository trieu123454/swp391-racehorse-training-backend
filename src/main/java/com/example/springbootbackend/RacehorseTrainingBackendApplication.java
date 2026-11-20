package com.example.springbootbackend;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class RacehorseTrainingBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(RacehorseTrainingBackendApplication.class, args);
    }

    // Switch Supabase Session Mode (:5432, max 15 clients) to Transaction Pooler (:6543) for concurrent team runs.
    @Bean
    static BeanPostProcessor supabasePoolerPortNormalizer() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
                if (bean instanceof HikariDataSource hikari) {
                    String url = hikari.getJdbcUrl();
                    if (url != null && url.contains(".pooler.supabase.com:5432/")) {
                        hikari.setJdbcUrl(url.replace(".pooler.supabase.com:5432/", ".pooler.supabase.com:6543/"));
                    }
                }
                return bean;
            }
        };
    }
}

package com.banking.accountservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/*
 * @EnableFeignClients scans the package for interfaces annotated with
 * @FeignClient and generates proxy implementations at startup.
 * Without this annotation, @FeignClient interfaces are ignored entirely —
 * Spring will not create beans for them and injection will fail.
 */
@SpringBootApplication
@EnableFeignClients
public class AccountServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AccountServiceApplication.class, args);
    }
}

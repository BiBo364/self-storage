package vn.lemar.selfstorage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Điểm khởi động của hệ thống self-storage.
 *
 * <p>Kiến trúc modular monolith: mỗi flow nghiệp vụ là một package con
 * trực tiếp của package này và được Spring Modulith coi là một module độc lập.
 */
@SpringBootApplication
public class SelfStorageApplication {

    public static void main(String[] args) {
        SpringApplication.run(SelfStorageApplication.class, args);
    }
}

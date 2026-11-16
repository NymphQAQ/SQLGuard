package io.sqlguard.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@SpringBootApplication
@EnableScheduling
public class SqlGuardDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(SqlGuardDemoApplication.class, args);
    }

    @Controller
    static class DashboardRedirect {
        @GetMapping({"/", "/sqlguard", "/sqlguard/"})
        String redirect() {
            return "redirect:/sqlguard/index.html";
        }
    }
}

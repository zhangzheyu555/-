package com.storeprofit.system;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class StoreProfitApplication {
  public static void main(String[] args) {
    SpringApplication.run(StoreProfitApplication.class, args);
  }
}

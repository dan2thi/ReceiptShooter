package com.dan.receiptshooter.config;

import com.dan.receiptshooter.service.AccountService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class AdministratorBootstrap implements ApplicationRunner {
    private final AccountService accounts;
    private final String username;
    private final String password;

    AdministratorBootstrap(AccountService accounts, @Value("${receipt.bootstrap.username:}") String username,
                           @Value("${receipt.bootstrap.password:}") String password) {
        this.accounts = accounts; this.username = username; this.password = password;
    }
    @Override public void run(ApplicationArguments args) { accounts.bootstrap(username, password); }
}

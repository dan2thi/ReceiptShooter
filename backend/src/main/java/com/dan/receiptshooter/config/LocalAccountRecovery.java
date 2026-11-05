package com.dan.receiptshooter.config;

import com.dan.receiptshooter.service.AccountService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class LocalAccountRecovery implements ApplicationRunner {
    private final AccountService accounts;
    private final ConfigurableApplicationContext context;
    private final boolean enabled;
    private final String username;
    private final String password;
    private final String address;
    private final int port;

    LocalAccountRecovery(AccountService accounts, ConfigurableApplicationContext context,
            @Value("${receipt.recovery.enabled:false}") boolean enabled,
            @Value("${receipt.recovery.username:}") String username,
            @Value("${receipt.recovery.password:}") String password,
            @Value("${server.address:127.0.0.1}") String address,
            @Value("${server.port:8080}") int port) {
        this.accounts = accounts; this.context = context; this.enabled = enabled;
        this.username = username; this.password = password; this.address = address; this.port = port;
    }

    @Override public void run(ApplicationArguments args) {
        if (!enabled) return;
        if (!address.equals("127.0.0.1") || port != 0) {
            throw new IllegalStateException("Account recovery requires --server.address=127.0.0.1 --server.port=0.");
        }
        accounts.recoverAdministrator(username, password);
        context.close();
    }
}

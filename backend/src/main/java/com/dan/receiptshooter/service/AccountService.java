package com.dan.receiptshooter.service;

import com.dan.receiptshooter.domain.AccountRole;
import com.dan.receiptshooter.service.model.AccountView;
import java.util.List;

/** Account lifecycle operations; implementations enforce the account invariants. */
public interface AccountService {
    void bootstrap(String username, String initialPassword);

    List<AccountView> list();

    AccountView current(String username);

    AccountView create(String username, String temporaryPassword, AccountRole role);

    AccountView setEnabled(long id, boolean enabled);

    AccountView resetPassword(long id, String temporaryPassword);

    void changePassword(String username, String currentPassword, String newPassword);

    void recoverAdministrator(String username, String temporaryPassword);
}

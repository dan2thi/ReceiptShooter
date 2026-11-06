package com.dan.receiptshooter.repository;

import com.dan.receiptshooter.domain.AccountRole;
import com.dan.receiptshooter.entity.UserAccount;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {
    Optional<UserAccount> findByUsername(String username);
    long countByRole(AccountRole role);
    long countByRoleAndEnabledTrue(AccountRole role);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserAccount u where u.role = :role and u.enabled = true order by u.id")
    List<UserAccount> lockActiveAdministrators(AccountRole role);
}

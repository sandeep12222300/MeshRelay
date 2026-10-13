package io.meshrelay.prototype.model;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Database operations for simulated accounts, including locks used during settlement.
 */
public interface AccountRepository extends JpaRepository<Account, String> {

    /**
     * Loads the requested accounts in a deterministic order while holding write locks.
     * Stable ordering reduces deadlock risk when opposite-direction transfers overlap.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.vpa in :vpas order by a.vpa")
    List<Account> findAllForSettlement(@Param("vpas") List<String> vpas);
}

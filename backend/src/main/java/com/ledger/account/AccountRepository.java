package com.ledger.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, String> {

    List<Account> findByArchivedFalseOrderByNameAsc();

    List<Account> findByTypeAndArchivedFalse(AccountType type);

    /**
     * Resolves a parsed instrument hint (a card tail, an account tail, a UPI
     * fragment) to an account.
     *
     * <p>Matching is case-insensitive and ignores the masking characters banks
     * put in front of the digits, so {@code XXXX4567}, {@code xx4567} and
     * {@code 4567} all find the account whose hint is {@code 4567}.
     */
    @Query("""
            select a from Account a
            where a.archived = false
              and a.instrumentHint is not null
              and lower(a.instrumentHint) = lower(:hint)
            """)
    List<Account> findByInstrumentHintIgnoreCase(@Param("hint") String hint);

    Optional<Account> findByNameIgnoreCase(String name);
}

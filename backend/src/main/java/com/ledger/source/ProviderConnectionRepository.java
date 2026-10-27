package com.ledger.source;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProviderConnectionRepository extends JpaRepository<ProviderConnection, String> {

    Optional<ProviderConnection> findByProvider(ProviderType provider);

    /** Connections the scheduler should poll. */
    @Query("""
            select p from ProviderConnection p
            where p.syncEnabled = true and p.status = :connected
            """)
    List<ProviderConnection> findSyncable(@Param("connected") ProviderConnection.Status connected);

    default List<ProviderConnection> findSyncable() {
        return findSyncable(ProviderConnection.Status.CONNECTED);
    }

    List<ProviderConnection> findByStatus(ProviderConnection.Status status);
}

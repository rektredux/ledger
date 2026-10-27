package com.ledger.categorize;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MerchantAliasRepository extends JpaRepository<MerchantAlias, String> {

    List<MerchantAlias> findAllByOrderByPatternAsc();

    Optional<MerchantAlias> findByPatternAndMatchType(String pattern, MatchType matchType);
}

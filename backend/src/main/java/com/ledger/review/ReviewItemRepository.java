package com.ledger.review;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ReviewItemRepository extends JpaRepository<ReviewItem, String> {

    Page<ReviewItem> findByStatusOrderByCreatedAtDesc(ReviewItem.Status status, Pageable pageable);

    Page<ReviewItem> findByStatusAndKindOrderByCreatedAtDesc(
            ReviewItem.Status status, ReviewItem.Kind kind, Pageable pageable);

    long countByStatus(ReviewItem.Status status);

    long countByStatusAndKind(ReviewItem.Status status, ReviewItem.Kind kind);

    List<ReviewItem> findBySourceRecordId(String sourceRecordId);

    Optional<ReviewItem> findByCanonicalTransactionIdAndStatus(
            String canonicalTransactionId, ReviewItem.Status status);

    /**
     * Open item counts grouped by kind, for the badge on the review screen and
     * the dashboard's "needs attention" indicator.
     *
     * <p>The status is a bound parameter rather than an inline enum literal:
     * referring to a nested enum constant inside HQL requires {@code $}
     * notation whose support has varied across Hibernate versions. Callers pass
     * {@link ReviewItem.Status#OPEN}.
     */
    @Query("""
            select r.kind as kind, count(r) as itemCount
            from ReviewItem r
            where r.status = :status
            group by r.kind
            """)
    List<KindCount> countByKind(@Param("status") ReviewItem.Status status);

    /**
     * Prevents duplicate review items for the same question: if a source record
     * already has an open item of this kind, do not raise a second one.
     */
    @Query("""
            select count(r) > 0 from ReviewItem r
            where r.sourceRecordId = :sourceRecordId
              and r.kind = :kind
              and r.status = :status
            """)
    boolean hasItem(@Param("sourceRecordId") String sourceRecordId,
                    @Param("kind") ReviewItem.Kind kind,
                    @Param("status") ReviewItem.Status status);

    /** Convenience wrapper for the common "is this already queued?" check. */
    default boolean hasOpenItem(String sourceRecordId, ReviewItem.Kind kind) {
        return hasItem(sourceRecordId, kind, ReviewItem.Status.OPEN);
    }

    default List<KindCount> countOpenByKind() {
        return countByKind(ReviewItem.Status.OPEN);
    }

    interface KindCount {
        ReviewItem.Kind getKind();

        long getItemCount();
    }
}

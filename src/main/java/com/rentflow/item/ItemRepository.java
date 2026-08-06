package com.rentflow.item;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Data access for items. JpaRepository gives save/findById/findAll/etc.
 * findByOwnerId is a derived query (Spring writes the SQL from the name).
 */
public interface ItemRepository extends JpaRepository<Item, Long> {

    List<Item> findByOwnerId(Long ownerId);

    /**
     * Load an item with a PESSIMISTIC_WRITE lock — this emits {@code SELECT ... FOR UPDATE},
     * so concurrent transactions touching the same item queue up in the database until this
     * one commits.
     *
     * This is the second of the three defences against double-booking. The Redis lock is
     * faster and gives a nicer error, but it lives outside the database: if Redis is down,
     * restarted, or an instance somehow skips it, this row lock still serialises writers.
     * Must be called inside an active transaction — a row lock is released at commit.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Item i WHERE i.id = :id")
    Optional<Item> findByIdForUpdate(@Param("id") Long id);
}

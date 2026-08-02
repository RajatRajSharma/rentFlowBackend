package com.rentflow.item;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Data access for items. JpaRepository gives save/findById/findAll/etc.
 * findByOwnerId is a derived query (Spring writes the SQL from the name).
 */
public interface ItemRepository extends JpaRepository<Item, Long> {

    List<Item> findByOwnerId(Long ownerId);
}

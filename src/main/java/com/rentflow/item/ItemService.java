package com.rentflow.item;

import com.rentflow.common.exception.NotFoundException;
import com.rentflow.item.dto.CreateItemRequest;
import com.rentflow.item.dto.UpdateItemRequest;
import com.rentflow.security.OwnershipGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Business logic for listings. The update path enforces ownership via OwnershipGuard,
 * so a user can only edit items they own.
 */
@Service
public class ItemService {

    private final ItemRepository itemRepository;
    private final OwnershipGuard ownershipGuard;

    public ItemService(ItemRepository itemRepository, OwnershipGuard ownershipGuard) {
        this.itemRepository = itemRepository;
        this.ownershipGuard = ownershipGuard;
    }

    @Transactional(readOnly = true)
    public List<Item> findAll() {
        return itemRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Item get(Long id) {
        return itemRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Item", id));
    }

    /**
     * Same as {@link #get}, but takes a row-level write lock (SELECT ... FOR UPDATE) so
     * concurrent bookings for this item are serialised by the database.
     *
     * MANDATORY propagation: a row lock only lives until commit, so calling this without an
     * enclosing transaction would silently release it immediately. Rather than let that be a
     * subtle bug, we fail loudly.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Item getForUpdate(Long id) {
        return itemRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Item", id));
    }

    @Transactional
    public Item create(CreateItemRequest request, Long ownerId) {
        Item item = new Item(
                ownerId,
                request.title(),
                request.description(),
                request.dailyRate(),
                request.depositAmount()
        );
        return itemRepository.save(item);
    }

    @Transactional
    public Item update(Long id, UpdateItemRequest request, Long currentUserId) {
        Item item = get(id);
        ownershipGuard.requireOwner(item.getOwnerId(), currentUserId);  // 403 if not the owner

        item.setTitle(request.title());
        item.setDescription(request.description());
        item.setDailyRate(request.dailyRate());
        item.setDepositAmount(request.depositAmount());
        return itemRepository.save(item);
    }
}

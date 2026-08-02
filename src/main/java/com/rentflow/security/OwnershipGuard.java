package com.rentflow.security;

import com.rentflow.common.exception.ForbiddenException;
import org.springframework.stereotype.Component;

/**
 * Resource-level authorization: "is this resource yours?"
 *
 * Role tells you *what kind* of user (USER/ADMIN); ownership tells you *whose*
 * resource it is. You need both. This guard centralises the ownership check so it's
 * done the same way everywhere (edit item, cancel booking, etc.).
 */
@Component
public class OwnershipGuard {

    public void requireOwner(Long resourceOwnerId, Long currentUserId) {
        if (!resourceOwnerId.equals(currentUserId)) {
            throw new ForbiddenException("You do not own this resource");
        }
    }
}

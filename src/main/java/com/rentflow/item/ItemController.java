package com.rentflow.item;

import com.rentflow.item.dto.CreateItemRequest;
import com.rentflow.item.dto.ItemResponse;
import com.rentflow.item.dto.UpdateItemRequest;
import com.rentflow.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Item endpoints. GET routes are public (browsing); POST/PUT require a valid JWT.
 * @AuthenticationPrincipal injects the caller (from the token) so we know who owns what.
 */
@RestController
@RequestMapping("/items")
public class ItemController {

    private final ItemService itemService;

    public ItemController(ItemService itemService) {
        this.itemService = itemService;
    }

    @GetMapping
    public List<ItemResponse> list() {
        return itemService.findAll().stream().map(ItemResponse::from).toList();
    }

    @GetMapping("/{id}")
    public ItemResponse get(@PathVariable Long id) {
        return ItemResponse.from(itemService.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ItemResponse create(@Valid @RequestBody CreateItemRequest request,
                               @AuthenticationPrincipal AuthenticatedUser user) {
        return ItemResponse.from(itemService.create(request, user.id()));
    }

    @PutMapping("/{id}")
    public ItemResponse update(@PathVariable Long id,
                               @Valid @RequestBody UpdateItemRequest request,
                               @AuthenticationPrincipal AuthenticatedUser user) {
        return ItemResponse.from(itemService.update(id, request, user.id()));
    }
}

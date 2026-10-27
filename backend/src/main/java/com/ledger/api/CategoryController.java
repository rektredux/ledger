package com.ledger.api;

import com.ledger.categorize.CategorizationEngine;
import com.ledger.categorize.CategoryRule;
import com.ledger.category.Category;
import com.ledger.category.CategoryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryService categoryService;
    private final CategorizationEngine categorizer;

    public CategoryController(CategoryService categoryService, CategorizationEngine categorizer) {
        this.categoryService = categoryService;
        this.categorizer = categorizer;
    }

    @GetMapping
    public List<ApiDtos.CategoryDto> tree() {
        return categoryService.tree().stream().map(node -> {
            List<ApiDtos.CategoryDto> children = node.children().stream()
                    .map(child -> ApiDtos.toCategoryDto(child, categoryService, List.of()))
                    .toList();
            return ApiDtos.toCategoryDto(node.category(), categoryService, children);
        }).toList();
    }

    @GetMapping("/flat")
    public List<ApiDtos.CategoryDto> flat() {
        return categoryService.findAll().stream()
                .map(c -> ApiDtos.toCategoryDto(c, categoryService, List.of()))
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiDtos.CategoryDto create(@Valid @RequestBody ApiDtos.CategoryRequest request) {
        Category created = categoryService.create(request.name(), request.parentId(),
                request.excludedFromSpending());
        return ApiDtos.toCategoryDto(created, categoryService, List.of());
    }

    @PutMapping("/{id}/name")
    public ApiDtos.CategoryDto rename(@PathVariable String id,
                                      @RequestParam String name) {
        Category updated = categoryService.rename(id, name);
        return ApiDtos.toCategoryDto(updated, categoryService, List.of());
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        categoryService.delete(id);
    }

    // --- Rules -----------------------------------------------------------

    @GetMapping("/rules")
    public List<ApiDtos.CategoryRuleDto> rules() {
        return categorizer.allRules().stream()
                .map(rule -> ApiDtos.toRuleDto(rule, categoryService))
                .toList();
    }

    @PostMapping("/rules")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiDtos.CategoryRuleDto createRule(@Valid @RequestBody ApiDtos.CategoryRuleRequest request) {
        CategoryRule created = categorizer.createUserRule(
                request.pattern(),
                ApiDtos.parseMatchType(request.matchType()),
                request.categoryId(),
                request.confidence() == null ? 0.9 : request.confidence());
        return ApiDtos.toRuleDto(created, categoryService);
    }

    @PutMapping("/rules/{id}/enabled")
    public ApiDtos.CategoryRuleDto setEnabled(@PathVariable String id,
                                               @RequestParam boolean enabled) {
        CategoryRule updated = categorizer.setEnabled(id, enabled);
        return ApiDtos.toRuleDto(updated, categoryService);
    }

    @DeleteMapping("/rules/{id}")
    public void deleteRule(@PathVariable String id) {
        categorizer.deleteRule(id);
    }
}

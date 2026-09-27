package com.logiplatform.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Stable pagination contract for AAL REST APIs.
 *
 * Keeps the existing frontend-compatible top-level pagination fields while
 * preventing Spring Data from serializing PageImpl directly.
 */
public record PageResponse<T>(
        List<T> content,
        int number,
        int size,
        int numberOfElements,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last,
        boolean empty) {
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getNumberOfElements(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast(),
                page.isEmpty());
    }
}

package com.bookscreen.model;

public record EditionRecord(
        String openLibraryEditionId,
        String isbn13,
        Integer pageCount,
        Integer publishYear
) {}
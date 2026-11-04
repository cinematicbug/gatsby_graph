package com.bookscreen.model;

public record AuthorRecord(
        String openLibraryAuthorKey,
        String fullName,
        int authorOrder
) {}
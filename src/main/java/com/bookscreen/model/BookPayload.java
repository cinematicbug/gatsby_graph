package com.bookscreen.model;

import java.util.List;

public record BookPayload(
        String openLibraryWorkId,
        String title,
        Integer firstPublishYear,
        Integer earliestEditionYear,
        List<String> authorNames,
        String description,
        Integer coverId
) {
    public Integer effectivePublishYear() {
        return firstPublishYear != null ? firstPublishYear : earliestEditionYear;
    }
}

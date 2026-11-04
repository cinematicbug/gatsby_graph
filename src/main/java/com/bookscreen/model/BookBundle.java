package com.bookscreen.model;

import com.bookscreen.model.Enums.RatingSource;
import java.math.BigDecimal;
import java.util.List;

public record BookBundle(
        String openLibraryWorkId,
        String title,
        Integer firstPublishYear,
        List<AuthorRecord> authors,
        List<EditionRecord> editions,
        BigDecimal avgRating5,
        int ratingsCount,
        RatingSource ratingSource,
        BookPayload payload
) {}
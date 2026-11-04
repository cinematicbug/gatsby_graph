package com.bookscreen.model;

import java.util.List;

public record FilmPayload(
        long tmdbId,
        String title,
        int releaseYear,
        String overview,
        List<CrewCredit> writingCredits,
        List<String> keywords,
        String posterPath,
        String director,
        Long collectionId,
        String collectionName
) {
    public record CrewCredit(
            String name,
            String job,
            String department
    ) {}
}

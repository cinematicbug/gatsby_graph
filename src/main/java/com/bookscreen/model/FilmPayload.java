package com.bookscreen.model;

import java.util.List;

public record FilmPayload(
        long tmdbId,
        String title,
        int releaseYear,
        String overview,
        List<CrewCredit> writingCredits,
        List<String> keywords
) {
    public record CrewCredit(
            String name,
            String job,
            String department
    ) {}
}
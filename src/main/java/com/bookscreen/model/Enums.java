package com.bookscreen.model;

public final class Enums {
    private Enums() {}

    public enum RatingSource {
        OPENLIBRARY, GOOGLE_BOOKS
    }

    public enum LinkStatus {
        ACCEPTED, REVIEW, REJECTED
    }

    public enum StatusSource {
        MATCHER, HUMAN
    }

    public enum Domain {
        BOOK, FILM
    }

    public enum BookReliability {
        LOW, OK
    }

    public enum DivergenceCategory {
        BOOK_OUTRANKS_FILM,
        FILM_OUTRANKS_BOOK,
        ALIGNED,
        INSUFFICIENT_DATA
    }

    public enum InsufficientReason {
        NO_BASELINE,
        BOOK_UNQUALIFIED,
        FILM_UNQUALIFIED,
        SMALL_PARTITION,
        NOT_CALIBRATED
    }
}
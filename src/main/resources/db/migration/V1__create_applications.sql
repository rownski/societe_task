CREATE SEQUENCE application_publication_number_seq AS BIGINT START WITH 1 NO CYCLE;

CREATE TABLE applications (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL CHECK (name ~ '\S'),
    body TEXT NOT NULL CHECK (body ~ '\S'),
    state TEXT NOT NULL DEFAULT 'CREATED',
    publication_number BIGINT UNIQUE CHECK (publication_number > 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    rejection_reason TEXT CHECK (rejection_reason ~ '\S'),
    rejected_at TIMESTAMPTZ,
    deletion_reason TEXT CHECK (deletion_reason IN ('DUPLICATE', 'CREATED_BY_MISTAKE', 'NO_LONGER_NEEDED')),
    deleted_at TIMESTAMPTZ,
    CHECK ((rejection_reason IS NULL) = (rejected_at IS NULL)),
    CHECK ((deletion_reason IS NULL) = (deleted_at IS NULL)),
    CONSTRAINT applications_state_valid
        CHECK (state IN ('CREATED', 'VERIFIED', 'ACCEPTED', 'PUBLISHED', 'DELETED', 'REJECTED')),
    CONSTRAINT applications_rejection_matches_state
        CHECK ((state = 'REJECTED') = (rejected_at IS NOT NULL)),
    CONSTRAINT applications_deletion_matches_state
        CHECK ((state = 'DELETED') = (deleted_at IS NOT NULL)),
    CONSTRAINT applications_publication_matches_state
        CHECK ((state = 'PUBLISHED') = (publication_number IS NOT NULL))
);

ALTER SEQUENCE application_publication_number_seq OWNED BY applications.publication_number;

CREATE TABLE applications (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL CHECK (name ~ '\S'),
    body TEXT NOT NULL CHECK (body ~ '\S'),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    rejection_reason TEXT CHECK (rejection_reason ~ '\S'),
    rejected_at TIMESTAMPTZ,
    deletion_reason TEXT CHECK (deletion_reason IN ('DUPLICATE', 'CREATED_BY_MISTAKE', 'NO_LONGER_NEEDED')),
    deleted_at TIMESTAMPTZ,
    CHECK ((rejection_reason IS NULL) = (rejected_at IS NULL)),
    CHECK ((deletion_reason IS NULL) = (deleted_at IS NULL))
);

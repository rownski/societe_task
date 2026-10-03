CREATE TABLE application_state_history (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES applications(id),
    previous_state TEXT CHECK (previous_state IN ('CREATED', 'VERIFIED', 'ACCEPTED', 'PUBLISHED', 'DELETED', 'REJECTED')),
    new_state TEXT NOT NULL CHECK (new_state IN ('CREATED', 'VERIFIED', 'ACCEPTED', 'PUBLISHED', 'DELETED', 'REJECTED')),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reason TEXT CHECK (reason ~ '\S'),
    CHECK ((previous_state IS NULL) = (new_state = 'CREATED')),
    CHECK (previous_state <> new_state),
    CHECK ((new_state IN ('REJECTED', 'DELETED')) = (reason IS NOT NULL)),
    CHECK (new_state <> 'DELETED' OR reason IN ('DUPLICATE', 'CREATED_BY_MISTAKE', 'NO_LONGER_NEEDED'))
);

CREATE INDEX idx_application_state_history_application
    ON application_state_history (application_id, id);

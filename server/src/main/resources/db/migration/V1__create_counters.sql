CREATE TABLE counters (
    name VARCHAR(255) PRIMARY KEY,
    value INTEGER NOT NULL,
    CONSTRAINT counters_name_not_empty CHECK (length(name) > 0)
);

CREATE TABLE outbox
(
    id                  uuid        PRIMARY KEY,
    sekvensnummer       bigint      GENERATED ALWAYS AS IDENTITY UNIQUE,
    melding             jsonb       NOT NULL,
    fødselsnummer       varchar     NOT NULL,
    opprettet           timestamptz NOT NULL DEFAULT now(),
    publisert_tidspunkt timestamptz NULL
);

CREATE INDEX outbox_upublisert_idx ON outbox (sekvensnummer) WHERE publisert_tidspunkt IS NULL;

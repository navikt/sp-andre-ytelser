-- Begrunnelsen lagres nå på hver dag i data_json. Eksisterende hendelser er testdata uten begrunnelse per dag.
DELETE FROM graderte_andre_ytelser_events;

ALTER TABLE graderte_andre_ytelser_events DROP COLUMN notat_til_beslutter;

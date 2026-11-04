DELETE FROM graderte_andre_ytelser_events
WHERE EXISTS (
    SELECT 1
    FROM jsonb_array_elements(data_json -> 'dager') AS dag
    WHERE NOT dag ? 'sykdomsgradFørFordeling'
);

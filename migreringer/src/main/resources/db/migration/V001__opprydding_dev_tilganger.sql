DO
$$
    BEGIN
        IF EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'sp-andre-ytelser-opprydding-dev')
        THEN
            GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO "sp-andre-ytelser-opprydding-dev";
            GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO "sp-andre-ytelser-opprydding-dev";
            ALTER DEFAULT PRIVILEGES FOR USER "sp-andre-ytelser" IN SCHEMA public GRANT ALL PRIVILEGES ON SEQUENCES TO "sp-andre-ytelser-opprydding-dev";
            ALTER DEFAULT PRIVILEGES FOR USER "sp-andre-ytelser" IN SCHEMA public GRANT ALL PRIVILEGES ON TABLES TO "sp-andre-ytelser-opprydding-dev";
        END IF;
    END
$$;

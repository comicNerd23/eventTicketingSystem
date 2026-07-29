#!/bin/bash
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE DATABASE ticketing_events;
    CREATE DATABASE ticketing_payments;
    CREATE DATABASE ticketing_notifications;
EOSQL

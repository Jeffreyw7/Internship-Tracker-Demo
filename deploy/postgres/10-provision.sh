#!/bin/sh
set -eu
psql -X --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set ON_ERROR_STOP=1 --file /opt/campus/postgres/provision.sql

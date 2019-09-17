-- Script to create an user for the nomulus application.
--
-- Usage:
-- psql -f ./create_nomulus_user.sql --variable=username='username' --variable=password='password'

CREATE USER :username ENCRYPTED PASSWORD :'password';
GRANT CONNECT ON DATABASE postgres TO :username;
GRANT USAGE ON SCHEMA public TO :username;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO :username;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO :username;

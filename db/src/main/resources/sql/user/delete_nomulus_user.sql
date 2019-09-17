-- Script to delete the user for the nomulus application.
--
-- Usage:
-- psql -f ./delete_nomulus_user.sql --variable=username='username'

REVOKE ALL PRIVILEGES ON DATABASE postgres FROM :username;
REVOKE ALL PRIVILEGES ON SCHEMA public FROM :username;
REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM :username;
REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public FROM :username;
REVOKE ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA public FROM :username;
DROP USER :username;

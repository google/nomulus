--
-- PostgreSQL database dump
--

-- Dumped from database version 11.5 (Debian 11.5-3.pgdg90+1)
-- Dumped by pg_dump version 11.5 (Debian 11.5-3.pgdg90+1)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: hstore; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS hstore WITH SCHEMA public;


--
-- Name: EXTENSION hstore; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION hstore IS 'data type for storing sets of (key, value) pairs';


SET default_tablespace = '';

SET default_with_oids = false;

--
-- Name: ClaimsEntry; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."ClaimsEntry" (
    revision_id bigint NOT NULL,
    claim_key text NOT NULL,
    domain_label text NOT NULL
);


--
-- Name: ClaimsList; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."ClaimsList" (
    revision_id bigint NOT NULL,
    creation_timestamp timestamp with time zone NOT NULL,
    tmdb_generation_time timestamp with time zone NOT NULL
);


--
-- Name: ClaimsList_revision_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."ClaimsList_revision_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ClaimsList_revision_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public."ClaimsList_revision_id_seq" OWNED BY public."ClaimsList".revision_id;


--
-- Name: Contact; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Contact" (
    repo_id text NOT NULL,
    creation_client_id text NOT NULL,
    creation_time timestamp with time zone NOT NULL,
    current_sponsor_client_id text NOT NULL,
    deletion_time timestamp with time zone,
    last_epp_update_client_id text,
    last_epp_update_time timestamp with time zone,
    statuses text[],
    auth_info_repo_id text,
    auth_info_value text,
    contact_id text,
    disclose_types_addr text[],
    disclose_show_email boolean,
    disclose_show_fax boolean,
    disclose_mode_flag boolean,
    disclose_types_name text[],
    disclose_types_org text[],
    disclose_show_voice boolean,
    email text,
    fax_phone_extension text,
    fax_phone_number text,
    addr_i18n_city text,
    addr_i18n_country_code text,
    addr_i18n_state text,
    addr_i18n_street_line1 text,
    addr_i18n_street_line2 text,
    addr_i18n_street_line3 text,
    addr_i18n_zip text,
    addr_i18n_name text,
    addr_i18n_org text,
    addr_i18n_type text,
    last_transfer_time timestamp with time zone,
    addr_local_city text,
    addr_local_country_code text,
    addr_local_state text,
    addr_local_street_line1 text,
    addr_local_street_line2 text,
    addr_local_street_line3 text,
    addr_local_zip text,
    addr_local_name text,
    addr_local_org text,
    addr_local_type text,
    search_name text,
    voice_phone_extension text,
    voice_phone_number text
);


--
-- Name: Cursor; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Cursor" (
    scope text NOT NULL,
    type text NOT NULL,
    cursor_time timestamp with time zone NOT NULL,
    last_update_time timestamp with time zone NOT NULL
);


--
-- Name: Domain; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Domain" (
    repo_id text NOT NULL,
    creation_client_id text NOT NULL,
    creation_time timestamp with time zone NOT NULL,
    current_sponsor_client_id text NOT NULL,
    deletion_time timestamp with time zone,
    last_epp_update_client_id text,
    last_epp_update_time timestamp with time zone,
    statuses text[],
    auth_info_repo_id text,
    auth_info_value text,
    fully_qualified_domain_name text,
    idn_table_name text,
    last_transfer_time timestamp with time zone,
    launch_notice_accepted_time timestamp with time zone,
    launch_notice_expiration_time timestamp with time zone,
    launch_notice_tcn_id text,
    launch_notice_validator_id text,
    registration_expiration_time timestamp with time zone,
    smd_id text,
    subordinate_hosts text[],
    tld text,
    admin_contact text,
    billing_contact text,
    registrant_contact text,
    tech_contact text
);


--
-- Name: DomainHost; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."DomainHost" (
    domain_repo_id text NOT NULL,
    ns_host_v_keys text
);


--
-- Name: HostHistory; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."HostHistory" (
    revision_id bigint NOT NULL,
    by_superuser boolean NOT NULL,
    creation_time timestamp with time zone NOT NULL,
    other_registrar_id text,
    reason text,
    registrar_id text NOT NULL,
    requested_by_registrar boolean,
    client_transaction_id text,
    server_transaction_id text,
    type integer NOT NULL,
    xml_bytes bytea,
    fully_qualified_host_name text,
    last_superordinate_change timestamp with time zone,
    last_transfer_time timestamp with time zone,
    referenced_entity text,
    superordinate_domain text
);


--
-- Name: HostHistory_domainTransactionRecords; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."HostHistory_domainTransactionRecords" (
    host_history_revision_id bigint NOT NULL,
    report_amount integer NOT NULL,
    report_field integer NOT NULL,
    reporting_time timestamp with time zone NOT NULL,
    tld text NOT NULL
);


--
-- Name: HostHistory_inetAddresses; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."HostHistory_inetAddresses" (
    host_history_revision_id bigint NOT NULL,
    inet_addresses bytea
);


--
-- Name: HostHistory_revision_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."HostHistory_revision_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: HostHistory_revision_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public."HostHistory_revision_id_seq" OWNED BY public."HostHistory".revision_id;


--
-- Name: HostResource; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."HostResource" (
    repo_id text NOT NULL,
    creation_client_id text,
    creation_time timestamp with time zone,
    current_sponsor_client_id text,
    deletion_time timestamp with time zone,
    last_epp_update_client_id text,
    last_epp_update_time timestamp with time zone,
    statuses text[],
    fully_qualified_host_name text,
    last_superordinate_change timestamp with time zone,
    last_transfer_time timestamp with time zone,
    superordinate_domain bytea
);


--
-- Name: HostResource_inetAddresses; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."HostResource_inetAddresses" (
    host_resource_repo_id text NOT NULL,
    inet_addresses bytea
);


--
-- Name: Lock; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Lock" (
    resource_name text NOT NULL,
    tld text NOT NULL,
    acquired_time timestamp with time zone NOT NULL,
    expiration_time timestamp with time zone NOT NULL,
    request_log_id text NOT NULL
);


--
-- Name: PremiumEntry; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."PremiumEntry" (
    revision_id bigint NOT NULL,
    price numeric(19,2) NOT NULL,
    domain_label text NOT NULL
);


--
-- Name: PremiumList; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."PremiumList" (
    revision_id bigint NOT NULL,
    creation_timestamp timestamp with time zone NOT NULL,
    name text NOT NULL,
    bloom_filter bytea NOT NULL,
    currency text NOT NULL
);


--
-- Name: PremiumList_revision_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."PremiumList_revision_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: PremiumList_revision_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public."PremiumList_revision_id_seq" OWNED BY public."PremiumList".revision_id;


--
-- Name: Registrar; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Registrar" (
    client_id text NOT NULL,
    allowed_tlds text[],
    billing_account_map public.hstore,
    billing_identifier bigint,
    block_premium_names boolean NOT NULL,
    client_certificate text,
    client_certificate_hash text,
    contacts_require_syncing boolean NOT NULL,
    creation_time timestamp with time zone,
    drive_folder_id text,
    email_address text,
    failover_client_certificate text,
    failover_client_certificate_hash text,
    fax_number text,
    iana_identifier bigint,
    icann_referral_email text,
    i18n_address_city text,
    i18n_address_country_code text,
    i18n_address_state text,
    i18n_address_street_line1 text,
    i18n_address_street_line2 text,
    i18n_address_street_line3 text,
    i18n_address_zip text,
    ip_address_whitelist text[],
    last_certificate_update_time timestamp with time zone,
    last_update_time timestamp with time zone,
    localized_address_city text,
    localized_address_country_code text,
    localized_address_state text,
    localized_address_street_line1 text,
    localized_address_street_line2 text,
    localized_address_street_line3 text,
    localized_address_zip text,
    password_hash text,
    phone_number text,
    phone_passcode text,
    po_number text,
    rdap_base_urls text[],
    registrar_name text NOT NULL,
    registry_lock_allowed boolean NOT NULL,
    password_salt text,
    state text,
    type text NOT NULL,
    url text,
    whois_server text
);


--
-- Name: RegistrarPoc; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."RegistrarPoc" (
    email_address text NOT NULL,
    allowed_to_set_registry_lock_password boolean NOT NULL,
    fax_number text,
    gae_user_id text,
    name text,
    phone_number text,
    registry_lock_password_hash text,
    registry_lock_password_salt text,
    types text[],
    visible_in_domain_whois_as_abuse boolean NOT NULL,
    visible_in_whois_as_admin boolean NOT NULL,
    visible_in_whois_as_tech boolean NOT NULL,
    registry_lock_email_address text
);


--
-- Name: RegistryLock; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."RegistryLock" (
    revision_id bigint NOT NULL,
    lock_completion_timestamp timestamp with time zone,
    lock_request_timestamp timestamp with time zone NOT NULL,
    domain_name text NOT NULL,
    is_superuser boolean NOT NULL,
    registrar_id text NOT NULL,
    registrar_poc_id text,
    repo_id text NOT NULL,
    verification_code text NOT NULL,
    unlock_request_timestamp timestamp with time zone,
    unlock_completion_timestamp timestamp with time zone,
    last_update_timestamp timestamp with time zone,
    relock_revision_id bigint,
    relock_duration bigint
);


--
-- Name: RegistryLock_revision_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."RegistryLock_revision_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: RegistryLock_revision_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public."RegistryLock_revision_id_seq" OWNED BY public."RegistryLock".revision_id;


--
-- Name: ReservedEntry; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."ReservedEntry" (
    revision_id bigint NOT NULL,
    comment text,
    reservation_type integer NOT NULL,
    domain_label text NOT NULL
);


--
-- Name: ReservedList; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."ReservedList" (
    revision_id bigint NOT NULL,
    creation_timestamp timestamp with time zone NOT NULL,
    name text NOT NULL,
    should_publish boolean NOT NULL
);


--
-- Name: ReservedList_revision_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."ReservedList_revision_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ReservedList_revision_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public."ReservedList_revision_id_seq" OWNED BY public."ReservedList".revision_id;


--
-- Name: ClaimsList revision_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ClaimsList" ALTER COLUMN revision_id SET DEFAULT nextval('public."ClaimsList_revision_id_seq"'::regclass);


--
-- Name: HostHistory revision_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."HostHistory" ALTER COLUMN revision_id SET DEFAULT nextval('public."HostHistory_revision_id_seq"'::regclass);


--
-- Name: PremiumList revision_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PremiumList" ALTER COLUMN revision_id SET DEFAULT nextval('public."PremiumList_revision_id_seq"'::regclass);


--
-- Name: RegistryLock revision_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."RegistryLock" ALTER COLUMN revision_id SET DEFAULT nextval('public."RegistryLock_revision_id_seq"'::regclass);


--
-- Name: ReservedList revision_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ReservedList" ALTER COLUMN revision_id SET DEFAULT nextval('public."ReservedList_revision_id_seq"'::regclass);


--
-- Name: ClaimsEntry ClaimsEntry_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ClaimsEntry"
    ADD CONSTRAINT "ClaimsEntry_pkey" PRIMARY KEY (revision_id, domain_label);


--
-- Name: ClaimsList ClaimsList_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ClaimsList"
    ADD CONSTRAINT "ClaimsList_pkey" PRIMARY KEY (revision_id);


--
-- Name: Contact Contact_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Contact"
    ADD CONSTRAINT "Contact_pkey" PRIMARY KEY (repo_id);


--
-- Name: Cursor Cursor_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Cursor"
    ADD CONSTRAINT "Cursor_pkey" PRIMARY KEY (scope, type);


--
-- Name: Domain Domain_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain"
    ADD CONSTRAINT "Domain_pkey" PRIMARY KEY (repo_id);


--
-- Name: HostHistory_domainTransactionRecords HostHistory_domainTransactionRecords_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."HostHistory_domainTransactionRecords"
    ADD CONSTRAINT "HostHistory_domainTransactionRecords_pkey" PRIMARY KEY (host_history_revision_id, report_amount, report_field, reporting_time, tld);


--
-- Name: HostHistory HostHistory_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."HostHistory"
    ADD CONSTRAINT "HostHistory_pkey" PRIMARY KEY (revision_id);


--
-- Name: HostResource HostResource_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."HostResource"
    ADD CONSTRAINT "HostResource_pkey" PRIMARY KEY (repo_id);


--
-- Name: Lock Lock_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Lock"
    ADD CONSTRAINT "Lock_pkey" PRIMARY KEY (resource_name, tld);


--
-- Name: PremiumEntry PremiumEntry_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PremiumEntry"
    ADD CONSTRAINT "PremiumEntry_pkey" PRIMARY KEY (revision_id, domain_label);


--
-- Name: PremiumList PremiumList_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PremiumList"
    ADD CONSTRAINT "PremiumList_pkey" PRIMARY KEY (revision_id);


--
-- Name: RegistrarPoc RegistrarPoc_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."RegistrarPoc"
    ADD CONSTRAINT "RegistrarPoc_pkey" PRIMARY KEY (email_address);


--
-- Name: Registrar Registrar_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Registrar"
    ADD CONSTRAINT "Registrar_pkey" PRIMARY KEY (client_id);


--
-- Name: RegistryLock RegistryLock_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."RegistryLock"
    ADD CONSTRAINT "RegistryLock_pkey" PRIMARY KEY (revision_id);


--
-- Name: ReservedEntry ReservedEntry_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ReservedEntry"
    ADD CONSTRAINT "ReservedEntry_pkey" PRIMARY KEY (revision_id, domain_label);


--
-- Name: ReservedList ReservedList_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ReservedList"
    ADD CONSTRAINT "ReservedList_pkey" PRIMARY KEY (revision_id);


--
-- Name: RegistryLock idx_registry_lock_repo_id_revision_id; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."RegistryLock"
    ADD CONSTRAINT idx_registry_lock_repo_id_revision_id UNIQUE (repo_id, revision_id);


--
-- Name: Contact ukoqd7n4hbx86hvlgkilq75olas; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Contact"
    ADD CONSTRAINT ukoqd7n4hbx86hvlgkilq75olas UNIQUE (contact_id);


--
-- Name: idx1p3esngcwwu6hstyua6itn6ff; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx1p3esngcwwu6hstyua6itn6ff ON public."Contact" USING btree (search_name);


--
-- Name: idx1rcgkdd777bpvj0r94sltwd5y; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx1rcgkdd777bpvj0r94sltwd5y ON public."Domain" USING btree (fully_qualified_domain_name);


--
-- Name: idx3y752kr9uh4kh6uig54vemx0l; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx3y752kr9uh4kh6uig54vemx0l ON public."Contact" USING btree (creation_time);


--
-- Name: idx5mnf0wn20tno4b9do88j61klr; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx5mnf0wn20tno4b9do88j61klr ON public."Domain" USING btree (deletion_time);


--
-- Name: idx8nr0ke9mrrx4ewj6pd2ag4rmr; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx8nr0ke9mrrx4ewj6pd2ag4rmr ON public."Domain" USING btree (creation_time);


--
-- Name: idx_registry_lock_registrar_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_registry_lock_registrar_id ON public."RegistryLock" USING btree (registrar_id);


--
-- Name: idx_registry_lock_verification_code; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_registry_lock_verification_code ON public."RegistryLock" USING btree (verification_code);


--
-- Name: idxbn8t4wp85fgxjl8q4ctlscx55; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxbn8t4wp85fgxjl8q4ctlscx55 ON public."Contact" USING btree (current_sponsor_client_id);


--
-- Name: idxfg2nnjlujxo6cb9fha971bq2n; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxfg2nnjlujxo6cb9fha971bq2n ON public."HostHistory" USING btree (creation_time);


--
-- Name: idxkjt9yaq92876dstimd93hwckh; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxkjt9yaq92876dstimd93hwckh ON public."Domain" USING btree (current_sponsor_client_id);


--
-- Name: idxn1f711wicdnooa2mqb7g1m55o; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxn1f711wicdnooa2mqb7g1m55o ON public."Contact" USING btree (deletion_time);


--
-- Name: idxnxei34hfrt20dyxtphh6j25mo; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxnxei34hfrt20dyxtphh6j25mo ON public."HostHistory" USING btree (registrar_id);


--
-- Name: idxrwl38wwkli1j7gkvtywi9jokq; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxrwl38wwkli1j7gkvtywi9jokq ON public."Domain" USING btree (tld);


--
-- Name: premiumlist_name_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX premiumlist_name_idx ON public."PremiumList" USING btree (name);


--
-- Name: registrar_iana_identifier_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX registrar_iana_identifier_idx ON public."Registrar" USING btree (iana_identifier);


--
-- Name: registrar_name_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX registrar_name_idx ON public."Registrar" USING btree (registrar_name);


--
-- Name: registrarpoc_gae_user_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX registrarpoc_gae_user_id_idx ON public."RegistrarPoc" USING btree (gae_user_id);


--
-- Name: reservedlist_name_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX reservedlist_name_idx ON public."ReservedList" USING btree (name);


--
-- Name: Contact fk1sfyj7o7954prbn1exk7lpnoe; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Contact"
    ADD CONSTRAINT fk1sfyj7o7954prbn1exk7lpnoe FOREIGN KEY (creation_client_id) REFERENCES public."Registrar"(client_id);


--
-- Name: Domain fk2jc69qyg2tv9hhnmif6oa1cx1; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain"
    ADD CONSTRAINT fk2jc69qyg2tv9hhnmif6oa1cx1 FOREIGN KEY (creation_client_id) REFERENCES public."Registrar"(client_id);


--
-- Name: RegistryLock fk2lhcwpxlnqijr96irylrh1707; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."RegistryLock"
    ADD CONSTRAINT fk2lhcwpxlnqijr96irylrh1707 FOREIGN KEY (relock_revision_id) REFERENCES public."RegistryLock"(revision_id);


--
-- Name: Domain fk2u3srsfbei272093m3b3xwj23; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain"
    ADD CONSTRAINT fk2u3srsfbei272093m3b3xwj23 FOREIGN KEY (current_sponsor_client_id) REFERENCES public."Registrar"(client_id);


--
-- Name: ClaimsEntry fk6sc6at5hedffc0nhdcab6ivuq; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ClaimsEntry"
    ADD CONSTRAINT fk6sc6at5hedffc0nhdcab6ivuq FOREIGN KEY (revision_id) REFERENCES public."ClaimsList"(revision_id);


--
-- Name: HostResource_inetAddresses fk6unwhfkcu3oq6q347fxvpagv; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."HostResource_inetAddresses"
    ADD CONSTRAINT fk6unwhfkcu3oq6q347fxvpagv FOREIGN KEY (host_resource_repo_id) REFERENCES public."HostResource"(repo_id);


--
-- Name: Contact fk93c185fx7chn68uv7nl6uv2s0; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Contact"
    ADD CONSTRAINT fk93c185fx7chn68uv7nl6uv2s0 FOREIGN KEY (current_sponsor_client_id) REFERENCES public."Registrar"(client_id);


--
-- Name: Domain fk_domain_admin_contact; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain"
    ADD CONSTRAINT fk_domain_admin_contact FOREIGN KEY (admin_contact) REFERENCES public."Contact"(repo_id);


--
-- Name: Domain fk_domain_billing_contact; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain"
    ADD CONSTRAINT fk_domain_billing_contact FOREIGN KEY (billing_contact) REFERENCES public."Contact"(repo_id);


--
-- Name: Domain fk_domain_registrant_contact; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain"
    ADD CONSTRAINT fk_domain_registrant_contact FOREIGN KEY (registrant_contact) REFERENCES public."Contact"(repo_id);


--
-- Name: Domain fk_domain_tech_contact; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain"
    ADD CONSTRAINT fk_domain_tech_contact FOREIGN KEY (tech_contact) REFERENCES public."Contact"(repo_id);


--
-- Name: DomainHost fk_domainhost_host_valid; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."DomainHost"
    ADD CONSTRAINT fk_domainhost_host_valid FOREIGN KEY (ns_host_v_keys) REFERENCES public."HostResource"(repo_id);


--
-- Name: HostHistory_inetAddresses fkdxdtvupo2b6xlqsq4og4nlo7k; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."HostHistory_inetAddresses"
    ADD CONSTRAINT fkdxdtvupo2b6xlqsq4og4nlo7k FOREIGN KEY (host_history_revision_id) REFERENCES public."HostHistory"(revision_id);


--
-- Name: DomainHost fkfmi7bdink53swivs390m2btxg; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."DomainHost"
    ADD CONSTRAINT fkfmi7bdink53swivs390m2btxg FOREIGN KEY (domain_repo_id) REFERENCES public."Domain"(repo_id);


--
-- Name: ReservedEntry fkgq03rk0bt1hb915dnyvd3vnfc; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ReservedEntry"
    ADD CONSTRAINT fkgq03rk0bt1hb915dnyvd3vnfc FOREIGN KEY (revision_id) REFERENCES public."ReservedList"(revision_id);


--
-- Name: Domain fkjc0r9r5y1lfbt4gpbqw4wsuvq; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain"
    ADD CONSTRAINT fkjc0r9r5y1lfbt4gpbqw4wsuvq FOREIGN KEY (last_epp_update_client_id) REFERENCES public."Registrar"(client_id);


--
-- Name: Contact fkmb7tdiv85863134w1wogtxrb2; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Contact"
    ADD CONSTRAINT fkmb7tdiv85863134w1wogtxrb2 FOREIGN KEY (last_epp_update_client_id) REFERENCES public."Registrar"(client_id);


--
-- Name: PremiumEntry fko0gw90lpo1tuee56l0nb6y6g5; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PremiumEntry"
    ADD CONSTRAINT fko0gw90lpo1tuee56l0nb6y6g5 FOREIGN KEY (revision_id) REFERENCES public."PremiumList"(revision_id);


--
-- Name: HostHistory_domainTransactionRecords fkofp2dm4xd9my12aex1456rn7d; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."HostHistory_domainTransactionRecords"
    ADD CONSTRAINT fkofp2dm4xd9my12aex1456rn7d FOREIGN KEY (host_history_revision_id) REFERENCES public."HostHistory"(revision_id);


--
-- PostgreSQL database dump complete
--


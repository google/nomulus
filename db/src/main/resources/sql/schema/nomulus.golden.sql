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
-- Name: Cursor; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Cursor" (
    scope text NOT NULL,
    type text NOT NULL,
    cursor_time timestamp with time zone NOT NULL,
    last_update_time timestamp with time zone NOT NULL
);


--
-- Name: DelegationSignerData; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."DelegationSignerData" (
    key_tag integer NOT NULL,
    algorithm integer NOT NULL,
    digest bytea,
    digest_type integer NOT NULL
);


--
-- Name: Domain; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Domain" (
    repo_id text NOT NULL,
    creation_client_id text,
    creation_time timestamp with time zone,
    current_sponsor_client_id text,
    deletion_time timestamp with time zone,
    last_epp_update_client_id text,
    last_epp_update_time timestamp with time zone,
    revisions bytea,
    auth_info_repo_id text,
    auth_info_value text,
    autorenew_billing_event bytea,
    autorenew_poll_message bytea,
    delete_poll_message bytea,
    fully_qualified_domain_name text,
    idn_table_name text,
    last_transfer_time timestamp with time zone,
    launch_notice_accepted_time timestamp with time zone,
    launch_notice_expiration_time timestamp with time zone,
    launch_notice_tcn_id text,
    launch_notice_validator_id text,
    registration_expiration_time timestamp with time zone,
    smd_id text,
    tld text,
    transfer_data_server_approve_autorenrew_event bytea,
    transfer_data_server_approve_autorenrew_poll_message bytea,
    transfer_data_server_approve_billing_event bytea,
    unit integer,
    value integer,
    client_transaction_id text,
    server_transaction_id text,
    transfer_data_registration_expiration_time timestamp with time zone,
    gaining_client_id text,
    losing_client_id text,
    pending_transfer_expiration_time timestamp with time zone,
    transfer_request_time timestamp with time zone,
    transfer_status integer
);


--
-- Name: DomainBase_nsHosts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."DomainBase_nsHosts" (
    domain_base_repo_id text NOT NULL,
    ns_hosts bytea
);


--
-- Name: DomainBase_serverApproveEntities; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."DomainBase_serverApproveEntities" (
    domain_base_repo_id text NOT NULL,
    transfer_data_server_approve_entities bytea
);


--
-- Name: DomainBase_subordinateHosts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."DomainBase_subordinateHosts" (
    domain_base_repo_id text NOT NULL,
    subordinate_hosts text
);


--
-- Name: Domain_DelegationSignerData; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Domain_DelegationSignerData" (
    domain_base_repo_id text NOT NULL,
    ds_data_key_tag integer NOT NULL
);


--
-- Name: Domain_GracePeriod; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Domain_GracePeriod" (
    domain_base_repo_id text NOT NULL,
    grace_periods_id bigint NOT NULL
);


--
-- Name: Domain_allContacts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Domain_allContacts" (
    domain text NOT NULL,
    contact bytea NOT NULL,
    type integer
);


--
-- Name: GracePeriod; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."GracePeriod" (
    id bigint NOT NULL,
    billing_event_one_time bytea,
    billing_event_recurring bytea,
    client_id text,
    expiration_time timestamp with time zone,
    type integer
);


--
-- Name: GracePeriod_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."GracePeriod_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: GracePeriod_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public."GracePeriod_id_seq" OWNED BY public."GracePeriod".id;


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
    last_update_timestamp timestamp with time zone
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
-- Name: GracePeriod id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."GracePeriod" ALTER COLUMN id SET DEFAULT nextval('public."GracePeriod_id_seq"'::regclass);


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
-- Name: Cursor Cursor_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Cursor"
    ADD CONSTRAINT "Cursor_pkey" PRIMARY KEY (scope, type);


--
-- Name: DelegationSignerData DelegationSignerData_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."DelegationSignerData"
    ADD CONSTRAINT "DelegationSignerData_pkey" PRIMARY KEY (key_tag);


--
-- Name: Domain_DelegationSignerData Domain_DelegationSignerData_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain_DelegationSignerData"
    ADD CONSTRAINT "Domain_DelegationSignerData_pkey" PRIMARY KEY (domain_base_repo_id, ds_data_key_tag);


--
-- Name: Domain_GracePeriod Domain_GracePeriod_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain_GracePeriod"
    ADD CONSTRAINT "Domain_GracePeriod_pkey" PRIMARY KEY (domain_base_repo_id, grace_periods_id);


--
-- Name: Domain Domain_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain"
    ADD CONSTRAINT "Domain_pkey" PRIMARY KEY (repo_id);


--
-- Name: GracePeriod GracePeriod_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."GracePeriod"
    ADD CONSTRAINT "GracePeriod_pkey" PRIMARY KEY (id);


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
-- Name: Domain_GracePeriod uk_4ps2u4y8i5r91wu2n1x2xea28; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain_GracePeriod"
    ADD CONSTRAINT uk_4ps2u4y8i5r91wu2n1x2xea28 UNIQUE (grace_periods_id);


--
-- Name: idx_registry_lock_registrar_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_registry_lock_registrar_id ON public."RegistryLock" USING btree (registrar_id);


--
-- Name: idx_registry_lock_verification_code; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_registry_lock_verification_code ON public."RegistryLock" USING btree (verification_code);


--
-- Name: premiumlist_name_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX premiumlist_name_idx ON public."PremiumList" USING btree (name);


--
-- Name: reservedlist_name_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX reservedlist_name_idx ON public."ReservedList" USING btree (name);


--
-- Name: Domain_DelegationSignerData fk2nvqbovvy5wasa8arhyhy8mge; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain_DelegationSignerData"
    ADD CONSTRAINT fk2nvqbovvy5wasa8arhyhy8mge FOREIGN KEY (domain_base_repo_id) REFERENCES public."Domain"(repo_id);


--
-- Name: ClaimsEntry fk6sc6at5hedffc0nhdcab6ivuq; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ClaimsEntry"
    ADD CONSTRAINT fk6sc6at5hedffc0nhdcab6ivuq FOREIGN KEY (revision_id) REFERENCES public."ClaimsList"(revision_id);


--
-- Name: DomainBase_serverApproveEntities fk7vuyqcsmcfvpv5648femoxien; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."DomainBase_serverApproveEntities"
    ADD CONSTRAINT fk7vuyqcsmcfvpv5648femoxien FOREIGN KEY (domain_base_repo_id) REFERENCES public."Domain"(repo_id);


--
-- Name: Domain_allContacts fkbh7x0hikqyo6jr50pj02tt6bu; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain_allContacts"
    ADD CONSTRAINT fkbh7x0hikqyo6jr50pj02tt6bu FOREIGN KEY (domain) REFERENCES public."Domain"(repo_id);


--
-- Name: ReservedEntry fkgq03rk0bt1hb915dnyvd3vnfc; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ReservedEntry"
    ADD CONSTRAINT fkgq03rk0bt1hb915dnyvd3vnfc FOREIGN KEY (revision_id) REFERENCES public."ReservedList"(revision_id);


--
-- Name: Domain_DelegationSignerData fkho8wxowo3f4e688ehdl4wpni5; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain_DelegationSignerData"
    ADD CONSTRAINT fkho8wxowo3f4e688ehdl4wpni5 FOREIGN KEY (ds_data_key_tag) REFERENCES public."DelegationSignerData"(key_tag);


--
-- Name: Domain_GracePeriod fkkpor7amcdp7gwe0hp3obng6do; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain_GracePeriod"
    ADD CONSTRAINT fkkpor7amcdp7gwe0hp3obng6do FOREIGN KEY (domain_base_repo_id) REFERENCES public."Domain"(repo_id);


--
-- Name: DomainBase_subordinateHosts fkkva2lb57ri8qf39hthcej538k; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."DomainBase_subordinateHosts"
    ADD CONSTRAINT fkkva2lb57ri8qf39hthcej538k FOREIGN KEY (domain_base_repo_id) REFERENCES public."Domain"(repo_id);


--
-- Name: Domain_GracePeriod fkny62h7k1nd3910rp56gdo5pfi; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain_GracePeriod"
    ADD CONSTRAINT fkny62h7k1nd3910rp56gdo5pfi FOREIGN KEY (grace_periods_id) REFERENCES public."GracePeriod"(id);


--
-- Name: PremiumEntry fko0gw90lpo1tuee56l0nb6y6g5; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PremiumEntry"
    ADD CONSTRAINT fko0gw90lpo1tuee56l0nb6y6g5 FOREIGN KEY (revision_id) REFERENCES public."PremiumList"(revision_id);


--
-- Name: DomainBase_nsHosts fkow28763fcl1ilx8unxrfjtbja; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."DomainBase_nsHosts"
    ADD CONSTRAINT fkow28763fcl1ilx8unxrfjtbja FOREIGN KEY (domain_base_repo_id) REFERENCES public."Domain"(repo_id);


--
-- PostgreSQL database dump complete
--


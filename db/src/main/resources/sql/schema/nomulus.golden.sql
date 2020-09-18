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
-- Name: AllocationToken; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."AllocationToken" (
    token text NOT NULL,
    update_timestamp timestamp with time zone,
    allowed_registrar_ids text[],
    allowed_tlds text[],
    creation_time timestamp with time zone NOT NULL,
    discount_fraction double precision NOT NULL,
    discount_premiums boolean NOT NULL,
    discount_years integer NOT NULL,
    domain_name text,
    redemption_history_entry text,
    token_status_transitions public.hstore,
    token_type text
);


--
-- Name: BillingCancellation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."BillingCancellation" (
    billing_cancellation_id bigint NOT NULL,
    registrar_id text NOT NULL,
    domain_history_revision_id bigint NOT NULL,
    domain_repo_id text NOT NULL,
    event_time timestamp with time zone NOT NULL,
    flags text[],
    reason text NOT NULL,
    domain_name text NOT NULL,
    billing_time timestamp with time zone,
    billing_event_id bigint,
    billing_recurrence_id bigint
);


--
-- Name: BillingCancellation_billing_cancellation_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."BillingCancellation_billing_cancellation_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: BillingCancellation_billing_cancellation_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public."BillingCancellation_billing_cancellation_id_seq" OWNED BY public."BillingCancellation".billing_cancellation_id;


--
-- Name: BillingEvent; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."BillingEvent" (
    billing_event_id bigint NOT NULL,
    registrar_id text NOT NULL,
    domain_history_revision_id bigint NOT NULL,
    domain_repo_id text NOT NULL,
    event_time timestamp with time zone NOT NULL,
    flags text[],
    reason text NOT NULL,
    domain_name text NOT NULL,
    allocation_token_id text,
    billing_time timestamp with time zone,
    cancellation_matching_billing_recurrence_id bigint,
    cost_amount numeric(19,2),
    cost_currency text,
    period_years integer,
    synthetic_creation_time timestamp with time zone
);


--
-- Name: BillingEvent_billing_event_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."BillingEvent_billing_event_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: BillingEvent_billing_event_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public."BillingEvent_billing_event_id_seq" OWNED BY public."BillingEvent".billing_event_id;


--
-- Name: BillingRecurrence; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."BillingRecurrence" (
    billing_recurrence_id bigint NOT NULL,
    registrar_id text NOT NULL,
    domain_history_revision_id bigint NOT NULL,
    domain_repo_id text NOT NULL,
    event_time timestamp with time zone NOT NULL,
    flags text[],
    reason text NOT NULL,
    domain_name text NOT NULL,
    recurrence_end_time timestamp with time zone,
    recurrence_time_of_year text
);


--
-- Name: BillingRecurrence_billing_recurrence_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."BillingRecurrence_billing_recurrence_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: BillingRecurrence_billing_recurrence_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public."BillingRecurrence_billing_recurrence_id_seq" OWNED BY public."BillingRecurrence".billing_recurrence_id;


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
    creation_registrar_id text NOT NULL,
    creation_time timestamp with time zone NOT NULL,
    current_sponsor_registrar_id text NOT NULL,
    deletion_time timestamp with time zone,
    last_epp_update_registrar_id text,
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
    voice_phone_number text,
    transfer_gaining_poll_message_id bigint,
    transfer_losing_poll_message_id bigint,
    transfer_client_txn_id text,
    transfer_server_txn_id text,
    transfer_gaining_registrar_id text,
    transfer_losing_registrar_id text,
    transfer_pending_expiration_time timestamp with time zone,
    transfer_request_time timestamp with time zone,
    transfer_status text,
    update_timestamp timestamp with time zone
);


--
-- Name: ContactHistory; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."ContactHistory" (
    history_revision_id bigint NOT NULL,
    history_by_superuser boolean NOT NULL,
    history_registrar_id text,
    history_modification_time timestamp with time zone NOT NULL,
    history_reason text,
    history_requested_by_registrar boolean NOT NULL,
    history_client_transaction_id text,
    history_server_transaction_id text,
    history_type text NOT NULL,
    history_xml_bytes bytea NOT NULL,
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
    transfer_gaining_poll_message_id bigint,
    transfer_losing_poll_message_id bigint,
    transfer_client_txn_id text,
    transfer_server_txn_id text,
    transfer_gaining_registrar_id text,
    transfer_losing_registrar_id text,
    transfer_pending_expiration_time timestamp with time zone,
    transfer_request_time timestamp with time zone,
    transfer_status text,
    voice_phone_extension text,
    voice_phone_number text,
    creation_registrar_id text,
    creation_time timestamp with time zone,
    current_sponsor_registrar_id text,
    deletion_time timestamp with time zone,
    last_epp_update_registrar_id text,
    last_epp_update_time timestamp with time zone,
    statuses text[],
    contact_repo_id text NOT NULL,
    update_timestamp timestamp with time zone
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
    creation_registrar_id text NOT NULL,
    creation_time timestamp with time zone NOT NULL,
    current_sponsor_registrar_id text NOT NULL,
    deletion_time timestamp with time zone,
    last_epp_update_registrar_id text,
    last_epp_update_time timestamp with time zone,
    statuses text[],
    auth_info_repo_id text,
    auth_info_value text,
    domain_name text,
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
    tech_contact text,
    transfer_gaining_poll_message_id bigint,
    transfer_losing_poll_message_id bigint,
    transfer_billing_cancellation_id bigint,
    transfer_billing_event_id bigint,
    transfer_billing_recurrence_id bigint,
    transfer_autorenew_poll_message_id bigint,
    transfer_renew_period_unit text,
    transfer_renew_period_value integer,
    transfer_client_txn_id text,
    transfer_server_txn_id text,
    transfer_registration_expiration_time timestamp with time zone,
    transfer_gaining_registrar_id text,
    transfer_losing_registrar_id text,
    transfer_pending_expiration_time timestamp with time zone,
    transfer_request_time timestamp with time zone,
    transfer_status text,
    update_timestamp timestamp with time zone,
    billing_recurrence_id bigint,
    autorenew_poll_message_id bigint,
    deletion_poll_message_id bigint,
    autorenew_end_time timestamp with time zone
);


--
-- Name: DomainHistory; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."DomainHistory" (
    history_revision_id bigint NOT NULL,
    history_by_superuser boolean NOT NULL,
    history_registrar_id text,
    history_modification_time timestamp with time zone NOT NULL,
    history_reason text,
    history_requested_by_registrar boolean NOT NULL,
    history_client_transaction_id text,
    history_server_transaction_id text,
    history_type text NOT NULL,
    history_xml_bytes bytea NOT NULL,
    admin_contact text,
    auth_info_repo_id text,
    auth_info_value text,
    billing_recurrence_id bigint,
    autorenew_poll_message_id bigint,
    billing_contact text,
    deletion_poll_message_id bigint,
    domain_name text,
    idn_table_name text,
    last_transfer_time timestamp with time zone,
    launch_notice_accepted_time timestamp with time zone,
    launch_notice_expiration_time timestamp with time zone,
    launch_notice_tcn_id text,
    launch_notice_validator_id text,
    registrant_contact text,
    registration_expiration_time timestamp with time zone,
    smd_id text,
    subordinate_hosts text[],
    tech_contact text,
    tld text,
    transfer_billing_cancellation_id bigint,
    transfer_billing_recurrence_id bigint,
    transfer_autorenew_poll_message_id bigint,
    transfer_billing_event_id bigint,
    transfer_renew_period_unit text,
    transfer_renew_period_value integer,
    transfer_registration_expiration_time timestamp with time zone,
    transfer_gaining_poll_message_id bigint,
    transfer_losing_poll_message_id bigint,
    transfer_client_txn_id text,
    transfer_server_txn_id text,
    transfer_gaining_registrar_id text,
    transfer_losing_registrar_id text,
    transfer_pending_expiration_time timestamp with time zone,
    transfer_request_time timestamp with time zone,
    transfer_status text,
    creation_registrar_id text,
    creation_time timestamp with time zone,
    current_sponsor_registrar_id text,
    deletion_time timestamp with time zone,
    last_epp_update_registrar_id text,
    last_epp_update_time timestamp with time zone,
    statuses text[],
    update_timestamp timestamp with time zone,
    domain_repo_id text NOT NULL,
    autorenew_end_time timestamp with time zone,
    history_other_registrar_id text,
    history_period_unit text,
    history_period_value integer
);


--
-- Name: DomainHistoryHost; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."DomainHistoryHost" (
    domain_history_history_revision_id bigint NOT NULL,
    host_repo_id text,
    domain_history_domain_repo_id text NOT NULL
);


--
-- Name: DomainHost; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."DomainHost" (
    domain_repo_id text NOT NULL,
    host_repo_id text
);


--
-- Name: DomainTransactionRecord; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."DomainTransactionRecord" (
    id bigint NOT NULL,
    report_amount integer NOT NULL,
    report_field text NOT NULL,
    reporting_time timestamp with time zone NOT NULL,
    tld text NOT NULL,
    domain_repo_id text,
    history_revision_id bigint
);


--
-- Name: DomainTransactionRecord_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."DomainTransactionRecord_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: DomainTransactionRecord_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public."DomainTransactionRecord_id_seq" OWNED BY public."DomainTransactionRecord".id;


--
-- Name: GracePeriod; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."GracePeriod" (
    id bigint NOT NULL,
    billing_event_id bigint,
    billing_recurrence_id bigint,
    registrar_id text NOT NULL,
    domain_repo_id text NOT NULL,
    expiration_time timestamp with time zone NOT NULL,
    type text NOT NULL
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
-- Name: HostHistory; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."HostHistory" (
    history_revision_id bigint NOT NULL,
    history_by_superuser boolean NOT NULL,
    history_registrar_id text NOT NULL,
    history_modification_time timestamp with time zone NOT NULL,
    history_reason text,
    history_requested_by_registrar boolean NOT NULL,
    history_client_transaction_id text,
    history_server_transaction_id text,
    history_type text NOT NULL,
    history_xml_bytes bytea NOT NULL,
    host_name text,
    inet_addresses text[],
    last_superordinate_change timestamp with time zone,
    last_transfer_time timestamp with time zone,
    superordinate_domain text,
    creation_registrar_id text,
    creation_time timestamp with time zone,
    current_sponsor_registrar_id text,
    deletion_time timestamp with time zone,
    last_epp_update_registrar_id text,
    last_epp_update_time timestamp with time zone,
    statuses text[],
    host_repo_id text NOT NULL,
    update_timestamp timestamp with time zone
);


--
-- Name: HostResource; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."HostResource" (
    repo_id text NOT NULL,
    creation_registrar_id text,
    creation_time timestamp with time zone,
    current_sponsor_registrar_id text,
    deletion_time timestamp with time zone,
    last_epp_update_registrar_id text,
    last_epp_update_time timestamp with time zone,
    statuses text[],
    host_name text,
    last_superordinate_change timestamp with time zone,
    last_transfer_time timestamp with time zone,
    superordinate_domain text,
    inet_addresses text[],
    update_timestamp timestamp with time zone
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
-- Name: PollMessage; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."PollMessage" (
    type text NOT NULL,
    poll_message_id bigint NOT NULL,
    registrar_id text NOT NULL,
    contact_repo_id text,
    contact_revision_id bigint,
    domain_repo_id text,
    domain_revision_id bigint,
    event_time timestamp with time zone NOT NULL,
    host_repo_id text,
    host_revision_id bigint,
    message text,
    transfer_response_contact_id text,
    transfer_response_domain_expiration_time timestamp with time zone,
    transfer_response_domain_name text,
    pending_action_response_action_result boolean,
    pending_action_response_name_or_id text,
    pending_action_response_processed_date timestamp with time zone,
    pending_action_response_client_txn_id text,
    pending_action_response_server_txn_id text,
    transfer_response_gaining_registrar_id text,
    transfer_response_losing_registrar_id text,
    transfer_response_pending_transfer_expiration_time timestamp with time zone,
    transfer_response_transfer_request_time timestamp with time zone,
    transfer_response_transfer_status text,
    autorenew_end_time timestamp with time zone,
    autorenew_domain_name text
);


--
-- Name: PollMessage_poll_message_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."PollMessage_poll_message_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: PollMessage_poll_message_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public."PollMessage_poll_message_id_seq" OWNED BY public."PollMessage".poll_message_id;


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
    registrar_id text NOT NULL,
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
    ip_address_allow_list text[],
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
    registry_lock_email_address text,
    registrar_id text NOT NULL
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
    relock_duration interval
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
-- Name: Spec11ThreatMatch; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Spec11ThreatMatch" (
    id bigint NOT NULL,
    check_date text NOT NULL,
    domain_name text NOT NULL,
    domain_repo_id text NOT NULL,
    registrar_id text NOT NULL,
    threat_types text[] NOT NULL,
    tld text NOT NULL
);


--
-- Name: SafeBrowsingThreat_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."SafeBrowsingThreat_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: SafeBrowsingThreat_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public."SafeBrowsingThreat_id_seq" OWNED BY public."Spec11ThreatMatch".id;


--
-- Name: Tld; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Tld" (
    tld_name text NOT NULL,
    add_grace_period_length interval NOT NULL,
    allowed_fully_qualified_host_names text[],
    allowed_registrant_contact_ids text[],
    anchor_tenant_add_grace_period_length interval NOT NULL,
    auto_renew_grace_period_length interval NOT NULL,
    automatic_transfer_length interval NOT NULL,
    claims_period_end timestamp with time zone NOT NULL,
    create_billing_cost_amount numeric(19,2),
    create_billing_cost_currency text,
    creation_time timestamp with time zone NOT NULL,
    currency text NOT NULL,
    dns_paused boolean NOT NULL,
    dns_writers text[] NOT NULL,
    drive_folder_id text,
    eap_fee_schedule public.hstore NOT NULL,
    escrow_enabled boolean NOT NULL,
    invoicing_enabled boolean NOT NULL,
    lordn_username text,
    num_dns_publish_locks integer NOT NULL,
    pending_delete_length interval NOT NULL,
    premium_list_name text,
    pricing_engine_class_name text,
    redemption_grace_period_length interval NOT NULL,
    registry_lock_or_unlock_cost_amount numeric(19,2),
    registry_lock_or_unlock_cost_currency text,
    renew_billing_cost_transitions public.hstore NOT NULL,
    renew_grace_period_length interval NOT NULL,
    reserved_list_names text[] NOT NULL,
    restore_billing_cost_amount numeric(19,2),
    restore_billing_cost_currency text,
    roid_suffix text,
    server_status_change_billing_cost_amount numeric(19,2),
    server_status_change_billing_cost_currency text,
    tld_state_transitions public.hstore NOT NULL,
    tld_type text NOT NULL,
    tld_unicode text NOT NULL,
    transfer_grace_period_length interval NOT NULL
);


--
-- Name: Transaction; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Transaction" (
    id bigint NOT NULL,
    contents bytea
);


--
-- Name: Transaction_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."Transaction_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: Transaction_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public."Transaction_id_seq" OWNED BY public."Transaction".id;


--
-- Name: history_id_sequence; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.history_id_sequence
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: temp_history_id_sequence; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.temp_history_id_sequence
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: BillingCancellation billing_cancellation_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."BillingCancellation" ALTER COLUMN billing_cancellation_id SET DEFAULT nextval('public."BillingCancellation_billing_cancellation_id_seq"'::regclass);


--
-- Name: BillingEvent billing_event_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."BillingEvent" ALTER COLUMN billing_event_id SET DEFAULT nextval('public."BillingEvent_billing_event_id_seq"'::regclass);


--
-- Name: BillingRecurrence billing_recurrence_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."BillingRecurrence" ALTER COLUMN billing_recurrence_id SET DEFAULT nextval('public."BillingRecurrence_billing_recurrence_id_seq"'::regclass);


--
-- Name: ClaimsList revision_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ClaimsList" ALTER COLUMN revision_id SET DEFAULT nextval('public."ClaimsList_revision_id_seq"'::regclass);


--
-- Name: DomainTransactionRecord id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."DomainTransactionRecord" ALTER COLUMN id SET DEFAULT nextval('public."DomainTransactionRecord_id_seq"'::regclass);


--
-- Name: GracePeriod id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."GracePeriod" ALTER COLUMN id SET DEFAULT nextval('public."GracePeriod_id_seq"'::regclass);


--
-- Name: PollMessage poll_message_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PollMessage" ALTER COLUMN poll_message_id SET DEFAULT nextval('public."PollMessage_poll_message_id_seq"'::regclass);


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
-- Name: Spec11ThreatMatch id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Spec11ThreatMatch" ALTER COLUMN id SET DEFAULT nextval('public."SafeBrowsingThreat_id_seq"'::regclass);


--
-- Name: Transaction id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Transaction" ALTER COLUMN id SET DEFAULT nextval('public."Transaction_id_seq"'::regclass);


--
-- Name: AllocationToken AllocationToken_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."AllocationToken"
    ADD CONSTRAINT "AllocationToken_pkey" PRIMARY KEY (token);


--
-- Name: BillingCancellation BillingCancellation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."BillingCancellation"
    ADD CONSTRAINT "BillingCancellation_pkey" PRIMARY KEY (billing_cancellation_id);


--
-- Name: BillingEvent BillingEvent_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."BillingEvent"
    ADD CONSTRAINT "BillingEvent_pkey" PRIMARY KEY (billing_event_id);


--
-- Name: BillingRecurrence BillingRecurrence_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."BillingRecurrence"
    ADD CONSTRAINT "BillingRecurrence_pkey" PRIMARY KEY (billing_recurrence_id);


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
-- Name: ContactHistory ContactHistory_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ContactHistory"
    ADD CONSTRAINT "ContactHistory_pkey" PRIMARY KEY (history_revision_id);


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
-- Name: DomainHistory DomainHistory_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."DomainHistory"
    ADD CONSTRAINT "DomainHistory_pkey" PRIMARY KEY (domain_repo_id, history_revision_id);


--
-- Name: DomainTransactionRecord DomainTransactionRecord_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."DomainTransactionRecord"
    ADD CONSTRAINT "DomainTransactionRecord_pkey" PRIMARY KEY (id);


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
-- Name: HostHistory HostHistory_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."HostHistory"
    ADD CONSTRAINT "HostHistory_pkey" PRIMARY KEY (history_revision_id);


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
-- Name: PollMessage PollMessage_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PollMessage"
    ADD CONSTRAINT "PollMessage_pkey" PRIMARY KEY (poll_message_id);


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
    ADD CONSTRAINT "RegistrarPoc_pkey" PRIMARY KEY (registrar_id, email_address);


--
-- Name: Registrar Registrar_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Registrar"
    ADD CONSTRAINT "Registrar_pkey" PRIMARY KEY (registrar_id);


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
-- Name: Spec11ThreatMatch SafeBrowsingThreat_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Spec11ThreatMatch"
    ADD CONSTRAINT "SafeBrowsingThreat_pkey" PRIMARY KEY (id);


--
-- Name: Tld Tld_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Tld"
    ADD CONSTRAINT "Tld_pkey" PRIMARY KEY (tld_name);


--
-- Name: Transaction Transaction_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Transaction"
    ADD CONSTRAINT "Transaction_pkey" PRIMARY KEY (id);


--
-- Name: RegistryLock idx_registry_lock_repo_id_revision_id; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."RegistryLock"
    ADD CONSTRAINT idx_registry_lock_repo_id_revision_id UNIQUE (repo_id, revision_id);


--
-- Name: allocation_token_domain_name_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX allocation_token_domain_name_idx ON public."AllocationToken" USING btree (domain_name);


--
-- Name: idx1iy7njgb7wjmj9piml4l2g0qi; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx1iy7njgb7wjmj9piml4l2g0qi ON public."HostHistory" USING btree (history_registrar_id);


--
-- Name: idx1p3esngcwwu6hstyua6itn6ff; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx1p3esngcwwu6hstyua6itn6ff ON public."Contact" USING btree (search_name);


--
-- Name: idx1rcgkdd777bpvj0r94sltwd5y; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx1rcgkdd777bpvj0r94sltwd5y ON public."Domain" USING btree (domain_name);


--
-- Name: idx2exdfbx6oiiwnhr8j6gjpqt2j; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx2exdfbx6oiiwnhr8j6gjpqt2j ON public."BillingCancellation" USING btree (event_time);


--
-- Name: idx3y752kr9uh4kh6uig54vemx0l; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx3y752kr9uh4kh6uig54vemx0l ON public."Contact" USING btree (creation_time);


--
-- Name: idx5mnf0wn20tno4b9do88j61klr; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx5mnf0wn20tno4b9do88j61klr ON public."Domain" USING btree (deletion_time);


--
-- Name: idx5yfbr88439pxw0v3j86c74fp8; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx5yfbr88439pxw0v3j86c74fp8 ON public."BillingEvent" USING btree (event_time);


--
-- Name: idx67qwkjtlq5q8dv6egtrtnhqi7; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx67qwkjtlq5q8dv6egtrtnhqi7 ON public."HostHistory" USING btree (history_modification_time);


--
-- Name: idx6py6ocrab0ivr76srcd2okpnq; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx6py6ocrab0ivr76srcd2okpnq ON public."BillingEvent" USING btree (billing_time);


--
-- Name: idx6syykou4nkc7hqa5p8r92cpch; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx6syykou4nkc7hqa5p8r92cpch ON public."BillingRecurrence" USING btree (event_time);


--
-- Name: idx6w3qbtgce93cal2orjg1tw7b7; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx6w3qbtgce93cal2orjg1tw7b7 ON public."DomainHistory" USING btree (history_modification_time);


--
-- Name: idx73l103vc5900ig3p4odf0cngt; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx73l103vc5900ig3p4odf0cngt ON public."BillingEvent" USING btree (registrar_id);


--
-- Name: idx8nr0ke9mrrx4ewj6pd2ag4rmr; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx8nr0ke9mrrx4ewj6pd2ag4rmr ON public."Domain" USING btree (creation_time);


--
-- Name: idx9q53px6r302ftgisqifmc6put; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx9q53px6r302ftgisqifmc6put ON public."ContactHistory" USING btree (history_type);


--
-- Name: idx_registry_lock_registrar_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_registry_lock_registrar_id ON public."RegistryLock" USING btree (registrar_id);


--
-- Name: idx_registry_lock_verification_code; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_registry_lock_verification_code ON public."RegistryLock" USING btree (verification_code);


--
-- Name: idxaro1omfuaxjwmotk3vo00trwm; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxaro1omfuaxjwmotk3vo00trwm ON public."DomainHistory" USING btree (history_registrar_id);


--
-- Name: idxaydgox62uno9qx8cjlj5lauye; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxaydgox62uno9qx8cjlj5lauye ON public."PollMessage" USING btree (event_time);


--
-- Name: idxbn8t4wp85fgxjl8q4ctlscx55; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxbn8t4wp85fgxjl8q4ctlscx55 ON public."Contact" USING btree (current_sponsor_registrar_id);


--
-- Name: idxe7wu46c7wpvfmfnj4565abibp; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxe7wu46c7wpvfmfnj4565abibp ON public."PollMessage" USING btree (registrar_id);


--
-- Name: idxeokttmxtpq2hohcioe5t2242b; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxeokttmxtpq2hohcioe5t2242b ON public."BillingCancellation" USING btree (registrar_id);


--
-- Name: idxfg2nnjlujxo6cb9fha971bq2n; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxfg2nnjlujxo6cb9fha971bq2n ON public."HostHistory" USING btree (creation_time);


--
-- Name: idxhmv411mdqo5ibn4vy7ykxpmlv; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxhmv411mdqo5ibn4vy7ykxpmlv ON public."BillingEvent" USING btree (allocation_token_id);


--
-- Name: idxhp33wybmb6tbpr1bq7ttwk8je; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxhp33wybmb6tbpr1bq7ttwk8je ON public."ContactHistory" USING btree (history_registrar_id);


--
-- Name: idxj1mtx98ndgbtb1bkekahms18w; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxj1mtx98ndgbtb1bkekahms18w ON public."GracePeriod" USING btree (domain_repo_id);


--
-- Name: idxj77pfwhui9f0i7wjq6lmibovj; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxj77pfwhui9f0i7wjq6lmibovj ON public."HostHistory" USING btree (host_name);


--
-- Name: idxjny8wuot75b5e6p38r47wdawu; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxjny8wuot75b5e6p38r47wdawu ON public."BillingRecurrence" USING btree (recurrence_time_of_year);


--
-- Name: idxkjt9yaq92876dstimd93hwckh; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxkjt9yaq92876dstimd93hwckh ON public."Domain" USING btree (current_sponsor_registrar_id);


--
-- Name: idxknk8gmj7s47q56cwpa6rmpt5l; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxknk8gmj7s47q56cwpa6rmpt5l ON public."HostHistory" USING btree (history_type);


--
-- Name: idxlrq7v63pc21uoh3auq6eybyhl; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxlrq7v63pc21uoh3auq6eybyhl ON public."Domain" USING btree (autorenew_end_time);


--
-- Name: idxn1f711wicdnooa2mqb7g1m55o; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxn1f711wicdnooa2mqb7g1m55o ON public."Contact" USING btree (deletion_time);


--
-- Name: idxn898pb9mwcg359cdwvolb11ck; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxn898pb9mwcg359cdwvolb11ck ON public."BillingRecurrence" USING btree (registrar_id);


--
-- Name: idxo1xdtpij2yryh0skxe9v91sep; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxo1xdtpij2yryh0skxe9v91sep ON public."ContactHistory" USING btree (creation_time);


--
-- Name: idxoqd7n4hbx86hvlgkilq75olas; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxoqd7n4hbx86hvlgkilq75olas ON public."Contact" USING btree (contact_id);


--
-- Name: idxp3usbtvk0v1m14i5tdp4xnxgc; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxp3usbtvk0v1m14i5tdp4xnxgc ON public."BillingRecurrence" USING btree (recurrence_end_time);


--
-- Name: idxplxf9v56p0wg8ws6qsvd082hk; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxplxf9v56p0wg8ws6qsvd082hk ON public."BillingEvent" USING btree (synthetic_creation_time);


--
-- Name: idxqa3g92jc17e8dtiaviy4fet4x; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxqa3g92jc17e8dtiaviy4fet4x ON public."BillingCancellation" USING btree (billing_time);


--
-- Name: idxrh4xmrot9bd63o382ow9ltfig; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxrh4xmrot9bd63o382ow9ltfig ON public."DomainHistory" USING btree (creation_time);


--
-- Name: idxrwl38wwkli1j7gkvtywi9jokq; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxrwl38wwkli1j7gkvtywi9jokq ON public."Domain" USING btree (tld);


--
-- Name: idxsu1nam10cjes9keobapn5jvxj; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxsu1nam10cjes9keobapn5jvxj ON public."DomainHistory" USING btree (history_type);


--
-- Name: idxsudwswtwqnfnx2o1hx4s0k0g5; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxsudwswtwqnfnx2o1hx4s0k0g5 ON public."ContactHistory" USING btree (history_modification_time);


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
-- Name: spec11threatmatch_check_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX spec11threatmatch_check_date_idx ON public."Spec11ThreatMatch" USING btree (check_date);


--
-- Name: spec11threatmatch_registrar_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX spec11threatmatch_registrar_id_idx ON public."Spec11ThreatMatch" USING btree (registrar_id);


--
-- Name: spec11threatmatch_tld_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX spec11threatmatch_tld_idx ON public."Spec11ThreatMatch" USING btree (tld);


--
-- Name: Contact fk1sfyj7o7954prbn1exk7lpnoe; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Contact"
    ADD CONSTRAINT fk1sfyj7o7954prbn1exk7lpnoe FOREIGN KEY (creation_registrar_id) REFERENCES public."Registrar"(registrar_id);


--
-- Name: Domain fk2jc69qyg2tv9hhnmif6oa1cx1; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain"
    ADD CONSTRAINT fk2jc69qyg2tv9hhnmif6oa1cx1 FOREIGN KEY (creation_registrar_id) REFERENCES public."Registrar"(registrar_id);


--
-- Name: RegistryLock fk2lhcwpxlnqijr96irylrh1707; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."RegistryLock"
    ADD CONSTRAINT fk2lhcwpxlnqijr96irylrh1707 FOREIGN KEY (relock_revision_id) REFERENCES public."RegistryLock"(revision_id);


--
-- Name: GracePeriod fk2mys4hojm6ev2g9tmy5aq6m7g; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."GracePeriod"
    ADD CONSTRAINT fk2mys4hojm6ev2g9tmy5aq6m7g FOREIGN KEY (domain_repo_id) REFERENCES public."Domain"(repo_id);


--
-- Name: Domain fk2u3srsfbei272093m3b3xwj23; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain"
    ADD CONSTRAINT fk2u3srsfbei272093m3b3xwj23 FOREIGN KEY (current_sponsor_registrar_id) REFERENCES public."Registrar"(registrar_id);


--
-- Name: HostHistory fk3d09knnmxrt6iniwnp8j2ykga; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."HostHistory"
    ADD CONSTRAINT fk3d09knnmxrt6iniwnp8j2ykga FOREIGN KEY (history_registrar_id) REFERENCES public."Registrar"(registrar_id);


--
-- Name: ClaimsEntry fk6sc6at5hedffc0nhdcab6ivuq; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ClaimsEntry"
    ADD CONSTRAINT fk6sc6at5hedffc0nhdcab6ivuq FOREIGN KEY (revision_id) REFERENCES public."ClaimsList"(revision_id);


--
-- Name: Contact fk93c185fx7chn68uv7nl6uv2s0; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Contact"
    ADD CONSTRAINT fk93c185fx7chn68uv7nl6uv2s0 FOREIGN KEY (current_sponsor_registrar_id) REFERENCES public."Registrar"(registrar_id);


--
-- Name: BillingCancellation fk_billing_cancellation_billing_event_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."BillingCancellation"
    ADD CONSTRAINT fk_billing_cancellation_billing_event_id FOREIGN KEY (billing_event_id) REFERENCES public."BillingEvent"(billing_event_id);


--
-- Name: BillingCancellation fk_billing_cancellation_billing_recurrence_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."BillingCancellation"
    ADD CONSTRAINT fk_billing_cancellation_billing_recurrence_id FOREIGN KEY (billing_recurrence_id) REFERENCES public."BillingRecurrence"(billing_recurrence_id);


--
-- Name: BillingCancellation fk_billing_cancellation_registrar_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."BillingCancellation"
    ADD CONSTRAINT fk_billing_cancellation_registrar_id FOREIGN KEY (registrar_id) REFERENCES public."Registrar"(registrar_id);


--
-- Name: BillingEvent fk_billing_event_cancellation_matching_billing_recurrence_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."BillingEvent"
    ADD CONSTRAINT fk_billing_event_cancellation_matching_billing_recurrence_id FOREIGN KEY (cancellation_matching_billing_recurrence_id) REFERENCES public."BillingRecurrence"(billing_recurrence_id);


--
-- Name: BillingEvent fk_billing_event_registrar_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."BillingEvent"
    ADD CONSTRAINT fk_billing_event_registrar_id FOREIGN KEY (registrar_id) REFERENCES public."Registrar"(registrar_id);


--
-- Name: BillingRecurrence fk_billing_recurrence_registrar_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."BillingRecurrence"
    ADD CONSTRAINT fk_billing_recurrence_registrar_id FOREIGN KEY (registrar_id) REFERENCES public."Registrar"(registrar_id);


--
-- Name: ContactHistory fk_contact_history_contact_repo_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ContactHistory"
    ADD CONSTRAINT fk_contact_history_contact_repo_id FOREIGN KEY (contact_repo_id) REFERENCES public."Contact"(repo_id);


--
-- Name: ContactHistory fk_contact_history_registrar_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ContactHistory"
    ADD CONSTRAINT fk_contact_history_registrar_id FOREIGN KEY (history_registrar_id) REFERENCES public."Registrar"(registrar_id);


--
-- Name: Contact fk_contact_transfer_gaining_registrar_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Contact"
    ADD CONSTRAINT fk_contact_transfer_gaining_registrar_id FOREIGN KEY (transfer_gaining_registrar_id) REFERENCES public."Registrar"(registrar_id);


--
-- Name: Contact fk_contact_transfer_losing_registrar_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Contact"
    ADD CONSTRAINT fk_contact_transfer_losing_registrar_id FOREIGN KEY (transfer_losing_registrar_id) REFERENCES public."Registrar"(registrar_id);


--
-- Name: Domain fk_domain_admin_contact; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain"
    ADD CONSTRAINT fk_domain_admin_contact FOREIGN KEY (admin_contact) REFERENCES public."Contact"(repo_id);


--
-- Name: Domain fk_domain_autorenew_poll_message_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain"
    ADD CONSTRAINT fk_domain_autorenew_poll_message_id FOREIGN KEY (autorenew_poll_message_id) REFERENCES public."PollMessage"(poll_message_id);


--
-- Name: Domain fk_domain_billing_contact; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain"
    ADD CONSTRAINT fk_domain_billing_contact FOREIGN KEY (billing_contact) REFERENCES public."Contact"(repo_id);


--
-- Name: Domain fk_domain_billing_recurrence_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain"
    ADD CONSTRAINT fk_domain_billing_recurrence_id FOREIGN KEY (billing_recurrence_id) REFERENCES public."BillingRecurrence"(billing_recurrence_id);


--
-- Name: Domain fk_domain_deletion_poll_message_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain"
    ADD CONSTRAINT fk_domain_deletion_poll_message_id FOREIGN KEY (deletion_poll_message_id) REFERENCES public."PollMessage"(poll_message_id);


--
-- Name: DomainHistory fk_domain_history_domain_repo_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."DomainHistory"
    ADD CONSTRAINT fk_domain_history_domain_repo_id FOREIGN KEY (domain_repo_id) REFERENCES public."Domain"(repo_id);


--
-- Name: DomainHistory fk_domain_history_registrar_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."DomainHistory"
    ADD CONSTRAINT fk_domain_history_registrar_id FOREIGN KEY (history_registrar_id) REFERENCES public."Registrar"(registrar_id);


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
-- Name: Domain fk_domain_transfer_billing_cancellation_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain"
    ADD CONSTRAINT fk_domain_transfer_billing_cancellation_id FOREIGN KEY (transfer_billing_cancellation_id) REFERENCES public."BillingCancellation"(billing_cancellation_id);


--
-- Name: Domain fk_domain_transfer_billing_event_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain"
    ADD CONSTRAINT fk_domain_transfer_billing_event_id FOREIGN KEY (transfer_billing_event_id) REFERENCES public."BillingEvent"(billing_event_id);


--
-- Name: Domain fk_domain_transfer_billing_recurrence_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain"
    ADD CONSTRAINT fk_domain_transfer_billing_recurrence_id FOREIGN KEY (transfer_billing_recurrence_id) REFERENCES public."BillingRecurrence"(billing_recurrence_id);


--
-- Name: Domain fk_domain_transfer_gaining_registrar_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain"
    ADD CONSTRAINT fk_domain_transfer_gaining_registrar_id FOREIGN KEY (transfer_gaining_registrar_id) REFERENCES public."Registrar"(registrar_id);


--
-- Name: Domain fk_domain_transfer_losing_registrar_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain"
    ADD CONSTRAINT fk_domain_transfer_losing_registrar_id FOREIGN KEY (transfer_losing_registrar_id) REFERENCES public."Registrar"(registrar_id);


--
-- Name: DomainHost fk_domainhost_host_valid; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."DomainHost"
    ADD CONSTRAINT fk_domainhost_host_valid FOREIGN KEY (host_repo_id) REFERENCES public."HostResource"(repo_id);


--
-- Name: GracePeriod fk_grace_period_billing_event_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."GracePeriod"
    ADD CONSTRAINT fk_grace_period_billing_event_id FOREIGN KEY (billing_event_id) REFERENCES public."BillingEvent"(billing_event_id);


--
-- Name: GracePeriod fk_grace_period_billing_recurrence_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."GracePeriod"
    ADD CONSTRAINT fk_grace_period_billing_recurrence_id FOREIGN KEY (billing_recurrence_id) REFERENCES public."BillingRecurrence"(billing_recurrence_id);


--
-- Name: HostResource fk_host_resource_superordinate_domain; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."HostResource"
    ADD CONSTRAINT fk_host_resource_superordinate_domain FOREIGN KEY (superordinate_domain) REFERENCES public."Domain"(repo_id);


--
-- Name: HostHistory fk_hosthistory_hostresource; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."HostHistory"
    ADD CONSTRAINT fk_hosthistory_hostresource FOREIGN KEY (host_repo_id) REFERENCES public."HostResource"(repo_id);


--
-- Name: PollMessage fk_poll_message_contact_repo_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PollMessage"
    ADD CONSTRAINT fk_poll_message_contact_repo_id FOREIGN KEY (contact_repo_id) REFERENCES public."Contact"(repo_id);


--
-- Name: PollMessage fk_poll_message_domain_repo_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PollMessage"
    ADD CONSTRAINT fk_poll_message_domain_repo_id FOREIGN KEY (domain_repo_id) REFERENCES public."Domain"(repo_id);


--
-- Name: PollMessage fk_poll_message_host_repo_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PollMessage"
    ADD CONSTRAINT fk_poll_message_host_repo_id FOREIGN KEY (host_repo_id) REFERENCES public."HostResource"(repo_id);


--
-- Name: PollMessage fk_poll_message_registrar_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PollMessage"
    ADD CONSTRAINT fk_poll_message_registrar_id FOREIGN KEY (registrar_id) REFERENCES public."Registrar"(registrar_id);


--
-- Name: PollMessage fk_poll_message_transfer_response_gaining_registrar_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PollMessage"
    ADD CONSTRAINT fk_poll_message_transfer_response_gaining_registrar_id FOREIGN KEY (transfer_response_gaining_registrar_id) REFERENCES public."Registrar"(registrar_id);


--
-- Name: PollMessage fk_poll_message_transfer_response_losing_registrar_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PollMessage"
    ADD CONSTRAINT fk_poll_message_transfer_response_losing_registrar_id FOREIGN KEY (transfer_response_losing_registrar_id) REFERENCES public."Registrar"(registrar_id);


--
-- Name: DomainHistoryHost fka9woh3hu8gx5x0vly6bai327n; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."DomainHistoryHost"
    ADD CONSTRAINT fka9woh3hu8gx5x0vly6bai327n FOREIGN KEY (domain_history_domain_repo_id, domain_history_history_revision_id) REFERENCES public."DomainHistory"(domain_repo_id, history_revision_id);


--
-- Name: DomainTransactionRecord fkcjqe54u72kha71vkibvxhjye7; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."DomainTransactionRecord"
    ADD CONSTRAINT fkcjqe54u72kha71vkibvxhjye7 FOREIGN KEY (domain_repo_id, history_revision_id) REFERENCES public."DomainHistory"(domain_repo_id, history_revision_id);


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
    ADD CONSTRAINT fkjc0r9r5y1lfbt4gpbqw4wsuvq FOREIGN KEY (last_epp_update_registrar_id) REFERENCES public."Registrar"(registrar_id);


--
-- Name: Contact fkmb7tdiv85863134w1wogtxrb2; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Contact"
    ADD CONSTRAINT fkmb7tdiv85863134w1wogtxrb2 FOREIGN KEY (last_epp_update_registrar_id) REFERENCES public."Registrar"(registrar_id);


--
-- Name: PremiumEntry fko0gw90lpo1tuee56l0nb6y6g5; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PremiumEntry"
    ADD CONSTRAINT fko0gw90lpo1tuee56l0nb6y6g5 FOREIGN KEY (revision_id) REFERENCES public."PremiumList"(revision_id);


--
-- PostgreSQL database dump complete
--


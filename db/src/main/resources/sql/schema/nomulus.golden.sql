
    create table "ClaimsEntry" (
       revision_id int8 not null,
        claim_key text not null,
        domain_label text not null,
        primary key (revision_id, domain_label)
    );

    create table "ClaimsList" (
       revision_id  bigserial not null,
        creation_timestamp timestamptz not null,
        primary key (revision_id)
    );

    alter table "ClaimsEntry"
       add constraint FKlugn0q07ayrtar87dqi3vs3c8 
       foreign key (revision_id) 
       references "ClaimsList";

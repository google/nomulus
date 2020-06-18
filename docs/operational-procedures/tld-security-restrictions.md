# TLD security restrictions

Nomulus has several security features that allow registries to impose additional
restrictions on which domains are allowed on a TLD and what
registrant/nameservers they can have. The restrictions can be applied to an
entire TLD or on a per-domain basis. These restrictions are intended for use on
closed TLDs that need to allow external registrars, and prevent undesired domain
registrations or updates from occurring, e.g. if a registrar makes an error or
is compromised. For closed TLDs that do not need external registrars, a simpler
solution is to not grant any registrars access to the TLD.

This document outlines the various restrictions available, their use cases, and
how to apply them.

## TLD-wide nameserver/registrant restrictions

Nomulus allows registry administrators to set registrant contact and/or
nameserver restrictions on a TLD. This is typically desired for brand TLDs on
which all domains are either self-hosted or restricted to a small set of
webhosts.

To configure allowed nameservers on a TLD, use the
`--allowed_nameservers`, `--add_allowed_nameservers`, and
`--remove_allowed_nameservers` parameters on the `update_tld` command as
follows:

```shell
$ nomulus -e {ENVIRONMENT} update_tld --allowed_nameservers {NS1,NS2,...} {TLD}
```

Note that `--allowed_nameservers` can also be used with the `create_tld` command
when the TLD is initially created.

To set the allowed registrants, use the analogous `--allowed_registrants`,
`--add_allowed_registrants`, and `--remove_allowed_registrants` parameters:

```shell
$ nomulus -e {ENVIRONMENT} update_tld \
    --allowed_registrants {CONTACTID1,CONTACTID2,...} {TLD}
```

When nameserver or registrant restrictions are set on a TLD, any domain mutation
flow under that TLD will verify that the supplied nameservers or registrants
are not empty and that they are a strict subset of the allowed nameservers and
registrants on the TLD. If no restrictions are set, domains can be created or
updated without nameservers, but registrant is still always required.

## Per-domain nameserver restrictions

Registries can also elect to impose per-domain nameserver restrictions. This
restriction is orthogonal to the TLD-wide nameserver restriction detailed above.
Any domain mutation must pass both validations (if applicable). In practice, it
is recommended to maintain consistency between the two types of lists by making
the per-domain allowed nameserver list a subset of the TLD-wide one, because any
nameservers that are not included in both lists are effectively disallowed.

The per-domain allowed nameserver lists are configured in [reserved
list](./reserved-list-management.md) entries with the reservation type
`NAMESERVER_RESTRICTED`. The final element in the entry is the colon-delimited
list of nameservers, e.g.:

```
restrictedsld,NAMESERVER_RESTRICTED,ns1.mycompany.tld:ns2.mycompany.tld
```

Note that multiple reserved lists can be applied to a TLD. If different reserved
lists contain nameserver restrictions for the same label, then the resulting
restriction set is the set intersection of all allowed nameserver lists for that
label.

## Domain create restriction on closed TLDs

Nomulus offers the ability to "lock-down" a TLD so that domain registration is
forbidden except for allow-listed domain names. This is achieved by setting the
"domain create restricted" option on the TLD using the `nomulus` tool. Domains
are allow-listed for registration by adding them to reserved lists with entries
of type `NAMESERVER_RESTRICTED`. Each domain will thus also need to have
explicitly allowed nameservers configured in its reserved list entry, per the
previous section.

To apply domain create restriction when creating/updating a TLD, use the
`--domain_create_restricted` parameter as follows:

```shell
$ nomulus -e {ENVIRONMENT} [create_tld | update_tld] \
    --domain_create_restricted [true | false] {TLD}
```

Note that you do **not** have to set a TLD-wide allowed nameservers list with
this option, because it operates independently from the per-domain nameservers
restriction that `NAMESERVER_RESTRICTED` reservation imposes.

In addition to disabling registration of non-allow-listed domains, setting a TLD
as domain create restricted also applies the `SERVER_UPDATE_PROHIBITED` and
`SERVER_TRANSFER_PROHIBITED` statuses to domains upon creation. Any domains on a
domain create restricted TLD are therefore virtually immutable, and must be
unlocked by the registry operator before each change can be made. For more
information on these EPP statuses, see [RFC
5731](https://tools.ietf.org/html/rfc5731#section-2.3).

To an unlock a locked domain so that a registrar can make changes, the registry
operator must remove the status using a `nomulus` tool command as follows:

```shell
$ nomulus -e {ENVIRONMENT} update_server_locks \
    --remove SERVER_UPDATE_PROHIBITED,SERVER_TRANSFER_PROHIBITED \
    --client {REGISTRAR_CLIENT_ID}
    --n {DOMAIN}
```

Note that these statuses will be reapplied immediately after any transfer/update
so long as the TLD is still set to domain create restricted.

Since the domain create restricted facility is intended for use on closed TLDs,
validation/server lock does not happen in domain application and allocate flows.
Most closed TLDs do not have a sunrise period, so this is fine, but for the
unanticipated occasion that a sunrise period is necessary, it suffices to
manually ensure that all domains are correct immediately after entering general
availability, after which no additional disallowed changes can be made.

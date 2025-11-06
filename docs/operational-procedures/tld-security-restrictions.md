# TLD security restrictions

Nomulus has several security features that allow registries to impose additional
restrictions on which domains are allowed on a TLD and what nameservers they can
have. The restrictions can be applied to an entire TLD or on a per-domain basis.
These restrictions are intended for use on closed TLDs that need to allow
external registrars, and prevent undesired domain registrations or updates from
occurring, e.g. if a registrar makes an error or is compromised. For closed TLDs
that do not need external registrars, a simpler solution is to not grant any
registrars access to the TLD.

This document outlines the various restrictions available, their use cases, and
how to apply them.

## TLD-wide nameserver/registrant restrictions

Nomulus allows registry administrators to set nameserver restrictions on a TLD.
This is typically desired for brand TLDs on which all domains are either
self-hosted or restricted to a small set of webhosts.

To [configure allowed nameservers on a TLD](modifying-tlds.md), use the
`allowedFullyQualifiedHostNames` field in the TLD YAML file:

```
addGracePeriodLength: "PT432000S"
allowedFullyQualifiedHostNames:
- "ns1.test.goog"
- "ns2.test.goog"
- "ns3.test.goog"
```

When nameserver restrictions are set on a TLD, any domain mutation flow under
that TLD will verify that the supplied nameservers are not empty and that they
are a strict subset of the allowed nameservers and registrants on the TLD. If no
restrictions are set, domains can be created or updated without nameservers.

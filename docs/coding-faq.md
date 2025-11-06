# Coding FAQ

## Do you support RDAP?

We provide an implementation of the Registry Data Access Protocol (RDAP) which
provides similar data to the outdated WHOIS protocol, but in a structured
format. The standard is defined in STD 95 and its RFCs:

*   [RFC 7480: HTTP Usage in the Registration Data Access Protocol (RDAP)](https://tools.ietf.org/html/rfc7480)
*   [RFC 7481: Security Services for the Registration Data Access Protocol
    (RDAP)](https://tools.ietf.org/html/rfc7481)
*   [RFC 9082: Registration Data Access Protocol (RDAP) Query Format](https://tools.ietf.org/html/rfc9082)
*   [RFC 9083: JSON Responses for the Registration Data Access Protocol (RDAP)](https://tools.ietf.org/html/rfc9083)
*   [RFC 9224: Finding the Authoritative Registration Data (RDAP) Service](https://tools.ietf.org/html/rfc9224)

If you access this endpoint on a running Nomulus system:

`https://pubapi.{SERVER_URL}/rdap/domains?name=ex*`

it should search for all domains that start with "ex", returning the results in
JSON format. Request paths which ought to work:

```
/rdap/domain/abc.tld
/rdap/nameserver/ns1.abc.tld
/rdap/entity/registrar-iana-identifier
/rdap/domains?name=abc.tld
/rdap/domains?name=abc*
/rdap/domains?name=abc*.tld
/rdap/domains?nsLdhName=ns1.abc.tld
/rdap/domains?nsLdhName=ns*
/rdap/domains?nsIp=1.2.3.4
/rdap/nameservers?name=ns*.abc.tld
/rdap/nameservers?ip=1.2.3.4
/rdap/entities?handle=registrar-iana-identifier
```

The wildcard searches allow only trailing wildcards, with the exception that you
can specify a TLD after the domain name wildcard (e.g. abc*.tld), and you can
specify .domain.tld after the nameserver wildcard (e.g. ns*.domain.tld). But you
can't do anything else, like searching for nameservers with ns*.tld. When using
a wildcard, we currently require a prefix of at least two characters, to avoid
having someone search for *.

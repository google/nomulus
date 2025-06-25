# Coding FAQ

This file is a motley assortment of informational emails generated in response
to questions from development partners.

## How do I mock Google Cloud Storage in tests?

AppEngine's GCS client automatically switches over to a local implementation,
[`GcsServiceFactory`](https://github.com/GoogleCloudPlatform/appengine-gcs-client/blob/master/java/src/main/java/com/google/appengine/tools/cloudstorage/GcsServiceFactory.java#L61),
for tests.

So rather than mocking GCS-related stuff at all, just use the fake local
implementation. This is what our tests should be doing; see
[`ExportCommitLogDiffActionTest`](https://github.com/google/nomulus/blob/master/javatests/google/registry/backup/ExportCommitLogDiffActionTest.java#L70).

Very rarely there have been cases where we've needed something beyond that (e.g.
to test against GCS being eventually consistent). In that case, rather than
mocking GcsUtils, you'd need to create a real instance of it but pass in a
mocked-out GcsService instance. Those are a bit of a pain to make since
GcsServiceImpl itself is also final (and not code we control), but you could
roll your own implementation by hand, or cheat and use a reflective proxy, as we
do in
[`GcsDiffFileListerTest`](https://github.com/google/domain-registry/blob/master/javatests/google/registry/backup/GcsDiffFileListerTest.java#L112).

## How do I test authentication on the SDK Development Server?

*Can someone explain how `GaeUserIdConverter.convertEmailAddressToGaeUserId()`
actually does the conversion? I see it's doing a save/load(/delete) of a
`GaeUserIdConverter`, which contains a `User`. Does Objectify do some magic on
load to look up the real GAE user ID from the email address? In trying to get
the registry to run in the SDK Development Server, I am seeing the wrong user ID
when adding a new RegistryContact using the command line tool.*

The [App Engine development
server](https://cloud.google.com/appengine/docs/python/tools/using-local-server)
is not particularly robust; it appears that it always returns userId
185804764220139124118 for any authenticated user, as per [this StackOverflow
thread](http://stackoverflow.com/questions/30524328/what-user-is-provided-by-app-engine-devserver).

For testing purposes, it might suffice to just create a RegistrarContact with
that userId by hand somehow, so that you can log in. In the longer term, if we
switch to Google Sign-In, this specific problem would go away, but based on the
above, it looks like OAuthService doesn't really work on the dev server either.

So for testing actual "real" authentication, you'd want to use an alpha instance
rather than the development server. We don't use the development server very
much internally for this reason.

## Do you support RDAP?

We provide an implementation of the Registry Data Access Protocol (RDAP)  which provides 
similar data to the outdated WHOIS protocol, but in a structured format. The 
standard is defined in STD 95 and its RFCs:

*   [RFC 7480: HTTP Usage in the Registration Data Access Protocol
    (RDAP)](https://tools.ietf.org/html/rfc7480)
*   [RFC 7481: Security Services for the Registration Data Access Protocol
    (RDAP)](https://tools.ietf.org/html/rfc7481)
*   [RFC 9082: Registration Data Access Protocol (RDAP) Query
    Format](https://tools.ietf.org/html/rfc9082)
*   [RFC 9083: JSON Responses for the Registration Data Access Protocol
    (RDAP)](https://tools.ietf.org/html/rfc9083)
*   [RFC 9224: Finding the Authoritative Registration Data (RDAP)
    Service](https://tools.ietf.org/html/rfc9224)

If you access this endpoint on a running Nomulus system:

`https://{PROJECT-ID}.appspot.com/rdap/domains?name=ex*`

it should search for all domains that start with "ex", returning the results in
JSON format. This functionality is still under development, so it is quite
possible that the format of returned data will change over time, but the basic
structure should be the same, as defined by RFCs 7480 through 7484. Request
paths which ought to mostly work (though no guarantees yet):

```
/rdap/domain/abc.tld
/rdap/nameserver/ns1.abc.tld
/rdap/entity/ROID
/rdap/entity/registrar-iana-identifier
/rdap/domains?name=abc.tld
/rdap/domains?name=abc*
/rdap/domains?name=abc*.tld
/rdap/domains?nsLdhName=ns1.abc.tld
/rdap/domains?nsLdhName=ns*
/rdap/domains?nsIp=1.2.3.4
/rdap/nameservers?name=ns*.abc.tld
/rdap/nameservers?ip=1.2.3.4
/rdap/entities?fn=John*
/rdap/entities?handle=ROI*
/rdap/entities?handle=registrar-iana-identifier
```

The wildcard searches allow only trailing wildcards, with the exception that you
can specify a TLD after the domain name wildcard (e.g. abc*.tld), and you can
specify .domain.tld after the nameserver wildcard (e.g. ns*.domain.tld). But you
can't do anything else, like searching for nameservers with ns*.tld. When using
a wildcard, we currently require a prefix of at least two characters, to avoid
having someone search for *.

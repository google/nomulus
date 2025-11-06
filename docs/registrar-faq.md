# FAQ for talking to registrars

This document contains some of the questions that registrars are likely to ask
about the system. In most cases, the question can be answered generally for
Nomulus systems. In some cases, the details depend on things outside the scope
of Nomulus, such as EPP proxies and DNS systems.

## Technical onboarding

**1.1 Do you provide documentation for OT&E?**

Yes, a document titled, “OT&E Instructions” is included as part of the
onboarding package. It explains what the test cases are, how to start a test,
and how to conduct a test.

**1.2 Do you offer a second OT&E account available to test domain transfers?**

Yes, we will be offering sandbox credentials for a test TLD that can be used to
test domain transfers.

**1.3 Do you provide a web based interface for registrars to manage domain names
(URL)?**

There is no web based interface to manage domains at this time, registrars must
be EPP integrated.

There is, however, a support console available only to registrars with
production EPP access (who have passed OT&E) which provide registrars with basic
self-service configuration management of their account with Google Registry.

**1.4 Will a Registrar Tool Kit be made available? If yes, what is included in
the Tool Kit?**

Google Registry has provided this technical FAQ documentation on how to use the
core EPP protocols and supported extensions. Google Registry will not provide an
EPP SDK. Registrar can develop EPP client using any existing EPP client
libraries (e.g. Verisign, Afilias, or any Open Source variants).

**1.5 Do I have to complete OT&E again for each new TLD launched by Google
Registry?**

You will only need to complete OT&E once. Any new functionality released with
new TLD launches will be communicated by email, and updated technical material
will be provided. We highly recommend that you test any new functionality in our
Sandbox. If you want to conduct a full test, or only test specific sections
related to new features, please contact technical support.

**1.6 Do you provide reports about the domains under management?**

Yes, Google Registry will provide a detailed activity report accompanying every
monthly invoice via Google Drive. For reports about domains under management,
please request through our technical support.

**1.7 How long is the absolute timeout for a registry connection?**

An idle connection will be disconnected after 60 minutes.

**1.8 Will the registry provide a secure certificate? Will multiple
certifications be required across gTLDs?**

*[ The answer to this question depends on the details of your EPP proxy
implementation. Here is how we answer it: ]*

The registry will not provide a secure certificate. Registrars must provide
their own certificate during onboarding, which will be allowlisted for the
connection. A single certificate can be used for multiple TLDs.

**1.9 Locks and statuses: will lock and status rules follow RFC
specifications?**

Yes, lock and status rules will follow RFC specifications.

**1.10 How can we receive the registry zone file (SFTP, web-based download)?**

The zone file will be available by requesting it through ICANN’s
[Centralized Zone Data Service](https://czds.icann.org).

## Domain names

**2.1 Are second level IDNs supported?**

All open TLDs launched by Google Registry support Latin script (which includes
all Spanish alphabet characters) at the second level. The following TLDs also
support the Kanji, Hiragana, and Katakana scripts: .app, .boo, .channel, .day,
.dev, .how, .new, .page, .rsvp, .soy, and .みんな . Please refer to the IDN tables
on the [IANA website](https://www.iana.org/domains/idn-tables).

**2.2 Are variants of the domain name allowed?**

For IDNs that contain variants, we will block them. However, the two tables that
we currently support (Japanese and Latin) do not have variants.

**2.3 Will the life cycle for IDNs v. ASCII differ?**

No, the restrictions for an IDN domain are the same as those for an ASCII
domain.

**2.4 Are domains on ICANN’s collision list available for application or
registration under Sunrise, Landrush, and General Launch?**

The controlled interruption period for our available open TLDs have ended .
Collision names for .app, .HOW, .MINNA and .SOY are currently available for
registration.

**2.5 Is NDN (for example, 1234.tld) allowed for registration?**

Yes. Numeric Domain Names are allowed.

**2.6 What are the restrictions on the length of a domain?**

A second level domain can be any length up to 15 characters if it contains any
Japanese codepoints, or 63 characters if it only contains ASCII or Latin script
codepoints.

**2.7 Is Google Registry MIIT Accredited?**

Google Registry does not have, nor intend to pursue at this time, MIIT
accreditation.

## DNSSEC

**3.1 What version of DNSSEC is being implemented? Will it be supported at
launch?**

EPP DNSSEC extension version 1.1 is supported at launch.

**3.2 What is the specification for transmitting DNSSEC data to the registry?**

RFC 5910 defines the specification for sending DNSSEC data to the registry.
Specifically, Registry accepts dsData.

**3.3 Are DNSSEC nameservers pre-checked for DNSSEC data?**

No, DNSSEC nameservers are not be pre-checked for DNSSEC data.

**3.4 What are the valid algorithm values for a DS record?**

Algorithm values 0-3, 5-8, 10, 12-16, and 252-253 are supported. Digest values 1
(SHA-1), 2 (SHA-256), and 4 (SHA-384) are supported.

**3.5 Is the maxSigLife attribute enabled?**

No, the maxSigLife attribute is not supported.

**3.6 What is the maximum number of DNSSEC records allowed per domain?**

Eight is the maximum number of DNSSEC records allowed per domain.

## Nameservers

**4.1 Do you support host objects or host attributes for the domain:ns tag?**

Google Registry supports host objects. Host attributes are not supported.

**4.2 Are there any restrictions on the length of the host?**

We do not impose any additional restrictions, except those required by DNS.

**4.3 What number of nameservers are required in order for a domain to
resolve?**

One nameserver is required for a domain name to resolve.

**4.4 Does the registry support renaming of host objects?**

Yes, Registry supports the renaming of host objects.

**4.5 Can hosts be updated by their owner when the host is linked to another
domain name?**

Yes, hosts can be updated when the host is linked to another domain name.

**4.6 Can domains be deleted when a subordinate host object of the domain is
being used as a nameserver by another domain?**

No, domains cannot be deleted while they still contain subordinate host objects.
However, you can delete the hosts, or rename them so they are no longer
subordinate, and then the domain can be deleted.

**4.7 Is it required to have a zone configuration verified by the registry prior
to the registration of a domain name or a domain update?**

No, we do not require verification of zone configurations.

**4.8 Are IPv6 addresses supported?**

Yes, IPv6 addresses are supported.

**4.9 How many IP addresses can be listed on an in-bailiwick host object?**

Ten IP addresses can be listed on an in-bailiwick host object. This limit is for
both IPv4 and IPv6 addresses combined.

**4.10 Can duplicate IP addresses be listed within a host object?**

While duplicate IP addresses can be set on a host object, they will be de-duped
prior to persisting, so there is no point in listing duplicate IP addresses.

**4.11 Can duplicate IP addresses be listed between different host objects for
the same domain?**

Yes, duplicate IP addresses can be listed between different host objects for the
same domain.

**4.12 Will the registration of nameservers be charged?**

No, there is no charge for the registration of nameservers.

**4.13 Will the update of nameservers be charged?**

No, nameservers can be updated free of charge.

**4.14 How many nameservers can be set on a domain?**

Thirteen nameservers can be set on a domain.

**4.15 Do you allow the usage of glue nameservers?**

We require IP address information for nameservers subordinate to a TLD, and do
not allow it otherwise.

**4.16 What is your procedure for the handling of Orphan Glue Records?**

We do not have a procedure for the handling of Orphan Glue records because we
will not have Orphan Glue Records. When a host object is no longer referred to
by any domain, its glue is removed.

**4.17 What are the authoritative domain name servers for Registry TLDs?**

*[ The answer to this question will depend on your DNS implementation. ]*

## Contacts

As of the beginning of 2026, the registry has transitioned to the Minimum Data
Set, also known as a "thin registry". This means that contact information is not
stored, including registrant information.

## WHOIS / RDAP

**6.1 Is the WHOIS protocol supported?**

No. We have discontinued support for the legacy WHOIS protocol (Port 43) in
favor of RDAP (Registration Data Access Protocol). Please update your systems to
query our RDAP service.

**6.2: What is the base URL for RDAP requests?**

*[ The answer to this question will depend on your implementation.Here is ours:
]*

The base URL for RDAP requests is https://pubapi.registry.google/rdap/.

**6.3: Is any authentication required for RDAP requests?**

No, there is no authentication process currently implemented for our RDAP
server.

**6.4: What will be the output of RDAP requests?**

Responses from the RDAP server will be in JSON format, conforming to STD 95.

**6.5 How often is RDAP information updated?**

RDAP is updated in near real time.

## EPP - General

**7.1 What is your EPP server address?**

*[ The answer to this question will depend on your EPP proxy implementation. ]*

**7.2 What version of EPP is supported?**

The registry supports EPP version 1.0.

**7.3 Will you provide a standard Pool and Batch account (e.g. as VeriSign)?**

No, we will not provide a standard Pool and Batch account.

**7.4 Does the registry support thick domain registrations?**

No, we have moved to the Minimum Data Set allowed by ICANN’s Registration Data
Policy and no longer store any contact information.

**7.5 What EPP commands exist to retrieve the account balance of the
registrar?**

None at the current time.

**7.6 Are there any restrictions on the volume of commands allowed during a
session?**

No, there are no restriction on the volume of commands allowed during a session.

**7.7 Are you using special extensions for your EPP API?**

[Launch Phase Mapping](http://tools.ietf.org/html/draft-ietf-eppext-launchphase-01)
and Registry Fee Extension (version 1.0, as described below) are supported.

**7.8 Which domain status are allowed for the domains?**

Standard EPP statuses are allowed.

**7.9 Do you use a real-time or non real-time registration system?**

Registry uses a real-time registration system.

**7.10 What are your connection policies to the registry system (e.g., how many
connections are allowed)?**

*[ The answer to this question will depend on your deployment configuration. ]*

**7.11 Are you using a Shared Registry System or a Dedicated Registry System
(separate EPP servers for each TLD)?**

We have a shared registry system for EPP, with a shared namespace across all
supported TLDs.

**7.12 If using a DRS, are login credentials, IP allow listing, etc. configured
separately or will these be the same for all TLDs in your system?**

These will be the same for all TLDs.

**7.13 What types of signed marks do you accept for sunrise applications?**

We only accept encoded signed marks that are signed by the TMCH. Non-encoded
signed marks are not supported.

**7.14 Where do I get encoded signed marks to use during the OT&E process?**

You can use any of the active test SMDs provided by ICANN, which can be found by
following the link in
[this ICANN PDF document](http://newgtlds.icann.org/en/about/trademark-clearinghouse/smd-test-repository-02oct13-en.pdf).

**7.15 How do I specify the EPP launch phases (i.e. Sunrise and Landrush)?**

For applications during the Sunrise phase, the launch phase should be specified
as follows:

`<launch:phase>sunrise</launch:phase>`

For applications during the Landrush phase, the launch phase should be specified
as follows:

`<launch:phase>landrush</launch:phase>`

**7.16 Do you accept domain labels with upper-case characters?**

No, the registry will reject domain labels with any uppercase characters. While
DNS labels are inherently case-insensitive
([RFC4343](http://tools.ietf.org/html/rfc4343)), we ask registrars to
canonicalize to lowercase to avoid confusion matching up labels on EPP responses
and poll messages.

**7.17 When should I run a claims check?**

Claims checks are required during the General Availability and Landrush periods
for domains matching a label existing in the TMCH claims database. Otherwise,
the registration/application will be rejected. Claims checks are not allowed
during Sunrise.

**7.18 How can I get the claims notice from TMDB using the TCNID?**

Section 3.1 of
[this ICANN PDF document](http://newgtlds.icann.org/en/about/trademark-clearinghouse/scsvcs/db-registrar-06dec13-en.pdf)
provides an overview of how you can access the TMDB system.

**7.19 What happens if I send an explicit renew command during the auto-renew
grace period?**

If an explicit renewal command is sent through during the auto-renew grace
period, it will be in addition to the auto-renew and further extend the
expiration date (not overrule the auto-renew).

Note however that this is not the case for transfers; a transfer during the
auto-renew grace period overrules the auto-renew.

**7.20 What testing environments are available to registrars on sandbox?**

In addition to the existing registrar-specific OTE EPP accounts and TLDs, we
provide a production-like development environment. All registrars are able to
use their OTE GA accounts to access the development versions of the TLDs they
have been onboarded for.

These development TLDs can be accessed at:

*   EPP hostname: *[ this depends on your EPP proxy implementation ]*
*   Using OTE EPP accounts: `<registrarid>-3` and `<registrarid>-4`

These TLDs will be shared across all onboarded registrars and configured with
similar settings to the respective TLDs in the production environment to enable
registrars to perform any required tests.

**7.21 Do you support in-band registrar password update (updating passwords
within an EPP command)?**

No, the registrar will need to contact the registry directly to update a
password.

**7.22 What happens to a deleted domain’s expiration date when it is restored?**

Successful restore requests reinstate the domain’s original expiration date,
unless the domain has already expired. Expired domain restores include a
mandatory 1 year renewal (billed separately) applied to the original expiration
date.

**7.23 What is the maximum number of objects that can be checked with a single
<domain:check> command?**

There is a maximum of 50 objects that can be checked per <domain:check> command.

## EPP - Fee extension

**8.1 Which version of the Fee Extension do you support?**

We currently support the Fee Extension version 1.0:

[urn:ietf:params:xml:ns:epp:fee-1.0](https://datatracker.ietf.org/doc/rfc8748/)

**8.2 Where is the Fee Extension supported?**

Fee extension is supported in the OT&E sandbox and in the production
environment.

**8.3 How do I use the Fee Extension?**

*   Declare the fee extension in the `<login>` command:

    `<extURI>urn:ietf:params:xml:ns:epp:fee-1.0</extURI>`

*   Use of the fee extension is optional for EPP query commands. If the client
    request includes the fee extension, fee information will be included in the
    server response. By policy, the registry will return an error from `<check>`
    if you attempt to check the fee of a domain that you are not also checking
    the availability of.

*   On non-premium domains, the fee extension is not required to perform EPP
    transform commands (e.g. `<domain:create>` , `<domain:transfer>`, etc).
    However, for premium domains, the client is required to acknowledge the
    price of an EPP transform operation through the use of the extension. If the
    fee extension is not included in transform commands on premium names, the
    server will respond with an error.

**8.4 Do you return different "premium" tiers?**

We do not have different premium tiers. If a domain name is premium, we
communicate "premium" as a single class in the EPP response, regardless of the
domain’s price range.

**8.5 What do you do with the "grace-period", "applied" and "refundable"
attributes as added in draft 0.6 of the Fee Extension?**

Registry does not return any of those fields, and will reject any transform
commands that attempt to pass in those fields.

**8.6 What currency code do I use for the Fee Extension?**

The value for `<fee:currency>` depends on the currency we use for billing the
specific TLD.

**8.7 Do you support balance check in the Fee Extension?**

Currently we do not support it.

**8.8 How can I try the Fee Extension in the sandbox?**

*[ We recommend that you set up a dummy premium list in the sandbox environment
with special names that you can give out to registrars for testing purposes. For
instance, we use the following SLD names, with different associated premium
prices: "rich", "richer", "silver", "iridium", "gold", "platinum", "rhodium",
"diamond", "palladium", "aluminum", "copper" and "brass". ]*

Trying to create premium names without using the fee extension will return an
error. The accepted value for `<fee:currency>` in sandbox is USD.

**8.9 What if I don’t want to carry premium domains?**

The fee extension is required to execute transform operations on premium
domains. If you do not declare the fee extension during login, you will not be
able to Create, Delete, Transfer, or Restore premium domains. All operations on
non-premium domains, as well as Check and Info on premiums, will still be
allowed.

To summarise, here is the expected EPP response if you do not declare the fee
extension during login:

*   `<domain:check>` on a non-premium domain will return "*avail=true*" or
    "*avail=false*" depending on the availability of the domain.
*   `<domain:check>` on a premium domain will always return "*avail=false*". If
    the domain is already registered, the reason code will be "*In Use*",
    otherwise it will be "*Premium names require EPP ext.*"
*   `<fee:check>` and other fee extension commands should result in a "*2002 -
    Command use error*" response code since the fee extension is not declared
    during login.

**8.10 Why do I get an error when using `<fee:command phase="sunrise">`?**

Prices are the same across all phases, so it is not necessary to specify any
command phases or subphases.

**8.11 Why do I get a "2103: Unimplemented Extension" error when trying to
include fee and claim extensions in the same `<domain:check>` command?**

Fee and claim checks are not allowed in the same `<domain:check>` command.The
[Launch Phase](http://tools.ietf.org/html/draft-tan-epp-launchphase-12#section-3.1)
extension defines two types of domain checks: availability checks and claim
checks. The `<fee:check>` command is only allowed in availability check
commands.

**8.12 What are your Grace and Pending Period durations?**

*   Add - 5 days
*   Renew - 5 days
*   Transfer - 5 days
*   Pending Delete - 5 days
*   Sunrise Add/Drop - 30 days
*   Redemption - 30 days
*   Auto-Renew - 45 days

**8.13 How do I use the fee extension during the Early Access Period?**

The use of the fee extension is mandatory in EAP for all registrations. You will
need to specify two items in <domain:create> requests, one for the normal price,
one for the Early Access Fee. These should be specified as in the following
example:

```
<fee:create xmlns:fee="urn:ietf:params:xml:ns:fee-0.6">
  <fee:currency>USD</fee:currency>
  <fee:fee description="create">70</fee:fee>
  <fee:fee description="Early Access Period">80.00</fee:fee>
</fee:create>
```

## Security

*[ The answers in this section depend on your EPP proxy implementation. These
are the answers that we give, because our EPP proxy has IP allow lists, and
requires SSL certificates and SNI. We recommend that other proxy implementations
do likewise. ]*

**9.1 How do I specify the IP addresses that can access your EPP system?**

You will be asked to submit your allow-listed IPs (in CIDR notation) during the
onboarding process. After completion of the onboarding process, you can use the
support console to manage the IP allow list for your production account.

**9.2 What SSL certificates will you accept for EPP connections?**

You must connect via TLS v1.2 or higher. All TLS v1.3 cipher suites are
supported. For TLS v1.2, only the following cipher suites are supported:

*   TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256
*   TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
*   TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256
*   TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256
*   TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384
*   TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384

In addition, only RSA certificates with a public key length of 2048 bits or
above, and ECDSA certificates using secp256r1 and secp384r1 curves are
supported. Certificates must be valid at the time of the connection.
Certificates issued on or after 2020-09-01 must have a validity period of 398 or
fewer days. Certificates issued before 2020-09-01 must have a validity period of
825 or fewer days.

You will be asked to submit your certificate for allowlisting during the
onboarding process. After completion of the onboarding process, you can use the
support console to manage the certificate for your production account.

**9.3 Is the use of SNI (Server Name Indication) required when connecting to the
EPP endpoint?**

Yes, the use of SNI is required.

**9.4 Is SNI supported in Java?**

The default SSL implementation in the current OpenJDK is the Java Secure Socket
Extension (JSSE) from Sun, which does support SNI. However, it can be somewhat
tricky to enable, as it may depend on which method you use to connect. If you
call `SSLSocketFactory.createSocket(...)` and pass in the host directly, it will
use SNI; however, if you call `SSLSocketFactory.createSocket()` and then
subsequently call `connect(...)` on the resultant `Socket` object, then it may
not use SNI. In that case, you may have to cast the socket to its actual
implementation and call `setHost(...)` on it directly. Please refer to the
following code snippet for illustration:

```java
SSLContext sslContext = SSLContext.getInstance("TLSv1");
SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();
SSLSocket sslSocket = (SSLSocket) sslSocketFactory.createSocket();
if(sslSocket instanceof sun.security.ssl.SSLSocketImpl){
    ((sun.security.ssl.SSLSocketImpl)sslSocket).

setHost(server);
}
    socket.

connect(new InetSocketAddress(server, port),timeout);
```

We recommend that registrars use Java7 or later, as they have native SNI
support, unlike previous versions of the JDK.

**9.5 When do I get a username/password for the production EPP system?**

You will receive a username and password upon passing OT&E and creation of your
registrar account.

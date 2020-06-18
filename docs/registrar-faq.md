# FAQ for talking to registrars

This document contains some of the questions that registrars are likely to ask
about the system. In most cases, the question can be answered generally for
Nomulus systems. In some cases, the details depend on things outside the scope
of Nomulus, such as EPP and WHOIS proxies and DNS systems.

## Technical onboarding

**1.1 Do you provide a web based interface for registrars to manage domain names
(URL)?**

There is no web based interface to manage domains at this time; registrars must
use EPP commands.

**1.2 Will a Registrar Tool Kit be made available? If yes, what is included in
the Tool Kit?**

Registry has provided this technical FAQ documentation on how to use the core
EPP protocols and supported extensions. An EPP SDK is not provided. The
registrar can develop EPP client using any existing EPP client libraries (e.g.
Verisign, Afilias, or any Open Source variants).

**1.3 Does the registry provide a secure certificate? Are multiple
certifications required across gTLDs?**

*[ The answer to this question depends on the details of your EPP proxy
implementation. Here is how we answer it: ]*

The registry does not provide a secure certificate. Registrars must provide
their own certificate during onboarding, which will be allow-listed for the
connection. A single certificate can be used for multiple TLDs.

**1.4 Locks and statuses: do lock and status rules follow RFC specifications?**

Yes, lock and status rules follow RFC specifications.

**1.5 How can we receive the registry zone file (SFTP, web-based download)?**

The zone file is available by requesting through ICANN’s [Centralized Zone Data
Service](https://czds.icann.org).

## Domain names

**2.1 Are second level IDNs supported?**

For open TLDs launched by Registry, Latin script (which includes all Spanish
alphabet characters) and the Kanji, Hiragana, and Katakana scripts are supported
at the second level. Please refer to the IDN tables on [IANA
website](http://www.iana.org/domains/idn-tables) for the list of IDNs supported
for each specific TLD.

**2.2 Are variants of the domain name allowed?**

For IDNs that contain variants, we will block them. However, the two tables that
we currently support (Japanese and Latin) do not have variants.

**2.3 Will the life cycle for IDNs v. ASCII differ?**

No, the restrictions for an IDN domain are the same as those for an ASCII
domain.

**2.4 Is NDN (for example, 1234.tld) allowed for registration?**

Yes. Numeric Domain Names are allowed.

**2.5 What are the restrictions on the length of a domain?**

A second level domain can be any length up to 15 characters if it contains any
Japanese codepoints, or 63 characters if it only contains ASCII or Latin script
codepoints.

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

We do not validate DS records, so any value is accepted.

**3.5 Is the maxSigLife attribute enabled?**

No, the maxSigLife attribute is not supported.

**3.6 What is the maximum number of DNSSEC records allowed per domain?**

A maximum of eight DNSSEC records are allowed per domain.

## Nameservers

**4.1 Do you support host objects or host attributes for the domain:ns tag?**

Registry supports host objects. Host attributes are not supported.

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

**4.12 How many nameservers can be set on a domain?**

Thirteen nameservers can be set on a domain.

**4.13 Do you allow the usage of glue nameservers?**

We require IP address information for nameservers subordinate to a TLD, and do
not allow it otherwise.

**4.14 What is your procedure for the handling of Orphan Glue Records?**

We do not have a procedure for the handling of Orphan Glue records because we
will not have Orphan Glue Records. When a host object is no longer referred to
by any domain, its glue is removed.

**4.15 What are the authoritative domain name servers for Registry TLDs?**

*[ The answer to this question will depend on your DNS implementation. ]*

## Contacts

**5.1 What contacts are required to register a domain?**

A registrant, administrative, and technical contact are required to register a
domain. A billing contact is not required. An abuse contact will be required
beginning in 2017, as per ICANN directive. All contacts may point at the same
contact ID. Note that multiple contacts of the same type are not allowed.

**5.2 Can contact information be updated at anytime without a fee?**

Yes, contact information can be updated at any time without a fee.

**5.3 Are proxy contacts allowed on the domain?**

Yes, proxy contacts are allowed on the domain.

**5.4 Do contact attributes follow the RFC standard?**

Yes, contact attributes follow the RFC standard.

**5.5 Are ISO country codes used?**

Yes, ISO country codes are used.

**5.6 Is a transfer of Contacts possible?**

Yes, contacts may be transferred.

## WHOIS

**6.1 What is the URL and port number for WHOIS requests?**

*[ The answer to this question will depend on your WHOIS proxy implementation.
]*

**6.2 Does your policy allow WHOIS Privacy?**

Whois Privacy is allowed.

**6.3 Is a Bulk WHOIS available?**

No, Bulk WHOIS is not available.

**6.4 What are the WHOIS rate limitations?**

*[ The answer to this question will depend on your WHOIS proxy implementation.
]*

**6.5 How often is WHOIS information updated?**

WHOIS is updated in near real time.

## EPP - General

**7.1 What is your EPP server address?**

*[ The answer to this question will depend on your EPP proxy implementation. ]*

**7.2 Will you provide a standard Pool and Batch account (e.g. as VeriSign)?**

No, we will not provide a standard Pool and Batch account.

**7.3 Does the registry support thick domain registrations?**

Yes, Registry supports thick domain registrations.

**7.4 What EPP commands exist to retrieve the account balance of the
registrar?**

None at the current time.

**7.5 Are there any restrictions on the volume of commands allowed during a
session?**

No, there are no restriction on the volume of commands allowed during a session.

**7.6 Are you using special extensions for your EPP API?**

[Launch Phase
Mapping](http://tools.ietf.org/html/draft-ietf-eppext-launchphase-01) and
Registry Fee Extension (versions 0.6 and 0.11, as described below) are
supported.

**7.7 Which domain status are allowed for the domains?**

Standard EPP statuses are allowed.

**7.8 Do you use a real-time or non real-time registration system?**

Registry uses a real-time registration system.

**7.9 What are your connection policies to the registry system (e.g., how many
connections are allowed)?**

*[ The answer to this question will depend on your App Engine configuration. ]*

**7.10 Are you using a Shared Registry System or a Dedicated Registry System
(separate EPP servers for each TLD)?**

We have a shared registry system for EPP, with a shared namespace across all
supported TLDs. Contacts and hosts are shared across all TLDs; for instance, the
same contact can be used for all of a registrar's domains in the system.

**7.11 If using a DRS, are login credentials, IP allow listing, etc. configured
separately or will these be the same for all TLDs in your system?**

These will be the same for all TLDs, because we are a shared registry system.
There is a single login regardless of TLD, and the same login session can be
used to send EPP commands for all TLDs.

**7.12 What types of signed marks do you accept for sunrise applications?**

We only accept encoded signed marks that are signed by the TMCH. Non-encoded
signed marks are not supported.

**7.13 Where do I get encoded signed marks to use during the OT&E process?**

You can use any of the active test SMDs provided by ICANN, which can be found by
following the link in [this ICANN PDF
document](http://newgtlds.icann.org/en/about/trademark-clearinghouse/smd-test-repository-02oct13-en.pdf).

**7.14 How do I specify the EPP launch phases (i.e. Sunrise and Landrush)?**

For applications during the Sunrise phase, the launch phase should be specified
as follows:

`<launch:phase>sunrise</launch:phase>`

For applications during the Landrush phase, the launch phase should be specified
as follows:

`<launch:phase>landrush</launch:phase>`

**7.15 Do you accept domain labels with upper-case characters?**

No, the registry will reject domain labels with any uppercase characters. While
DNS labels are inherently case-insensitive
([RFC4343](http://tools.ietf.org/html/rfc4343)), we ask registrars to
canonicalize to lowercase to avoid confusion matching up labels on EPP responses
and poll messages.

**7.16 When should I run a claims check?**

Claims checks are required during the General Availability and Landrush periods
for domains matching a label existing in the TMCH claims database. Otherwise,
the registration/application will be rejected. Claims checks are not allowed
during Sunrise.

**7.17 How can I get the claims notice from TMDB using the TCNID?**

Section 3.1 of [this ICANN PDF
document](http://newgtlds.icann.org/en/about/trademark-clearinghouse/scsvcs/db-registrar-06dec13-en.pdf)
provides an overview of how you can access the TMDB system.

**7.18 What happens if I send an explicit renew command during the auto-renew
grace period?**

If an explicit renewal command is sent through during the auto-renew grace
period, it will be in addition to the auto-renew and further extend the
expiration date (not overrule the auto-renew).

Note however that this is not the case for transfers; a transfer during the
auto-renew grace period overrules the auto-renew.

**7.19 What testing environments are available to registrars on sandbox?**

In addition to the existing registrar-specific OTE EPP accounts and TLDs, we
provide a production-like development environment. All registrars are able to
use their OTE GA accounts to access the development versions of the TLDs they
have been onboarded for.

These development TLDs can be accessed at:

*   EPP hostname: *[ this depends on your EPP proxy implementation ]*
*   Using OTE EPP accounts: `<registrarid>-3` and `<registrarid>-4`

**7.20 When using the Verisign EPP Tool to test access to the registry, I see
an "Error Reading Server Greeting" message.**

This is usually the result of the connection being terminated by the server
because no security certificate is present. The EPP Tool's Getting Started Guide
describes how to configure the connection profile to include a certificate using
the ssl.\* parameters. A certificate is required in order to connect to
the registry.

## EPP - Fee extension

**8.1 Which version of the Fee Extension draft do you support?**

We currently support two versions of the Registry Fee Extension:

[urn:ietf:params:xml:ns:fee-0.6](https://tools.ietf.org/html/draft-brown-epp-fees-03)

[urn:ietf:params:xml:ns:fee-0.11](https://tools.ietf.org/html/draft-ietf-regext-epp-fees-00)

Note that the version of the specification document is not the same as the
version of the fee extension URI itself. Also, version 0.11 is still in the
process of being finalized, so for the moment, version 0.6 is to be preferred.

**8.2 Where is the Fee Extension supported?**

Fee extension is supported in the OT&E sandbox and in the production
environment.

**8.3 How do I use the Fee Extension?**

*   Declare the fee extension in the `<login>` command:

    `<extURI>urn:ietf:params:xml:ns:fee-0.6</extURI>`

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
[Launch
Phase](http://tools.ietf.org/html/draft-tan-epp-launchphase-12#section-3.1)
extension defines two types of domain checks: availability checks and claim
checks. The `<fee:check>` command is only allowed in availability check
commands.

**8.12 What are your Grace and Pending Period durations?**

*   Add - 5 days
*   Renew - 5 days
*   Transfer - 5 days
*   Pending Delete - 5 days
*   Sunrise and Landrush Add/Drop - 30 days
*   Redemption - 30 days
*   Auto-Renew - 45 days

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

We will accept any SSL certificate. You will be asked to submit your certificate
for allow-listing during the onboarding process. After completion of the
onboarding process, you can use the support console to manage the certificate
for your production account.

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
if (sslSocket instanceof sun.security.ssl.SSLSocketImpl) {
    ((sun.security.ssl.SSLSocketImpl) sslSocket).setHost(server);
}
socket.connect(new InetSocketAddress(server, port), timeout);
```

We recommend that registrars use Java7 or later, as they have native SNI
support, unlike previous versions of the JDK.

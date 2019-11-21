# Managing reserved lists

Reserved lists are static lists of labels that are blocked from being registered
for various reasons, usually because of potential abuse.

## Reserved list file format

Reserved lists are handled in a similar way to [premium
lists](./premium-list-management.md), except that instead of each label having
a price, it has a reservation type. The valid values for reservation types are:

*   **`NAMESERVER_RESTRICTED`** - Only nameservers included here can be set on a
    domain with this label. If the a label in this type exists on multiple
    reserved lists that are applied to the same TLD. The set of allowed
    nameservers for that label in that TLD is the intersection of all applicable
    nameservers. Note that this restriction is orthogonal to the TLD-wide
    nameserver restrictions that may be otherwise imposed. The ultimate set of
    allowed nameservers for a certain domain is the intersection of per-domain
    and TLD-wide allowed nameservers set. Furthermore, a TLD can be set in a
    domain create restricted mode, in which case **only** domains that are
    reserved with this type can be registered.
*   **`ALLOWED_IN_SUNRISE`** - The label can be registered during the sunrise
    period by a registrant with a valid claim but it is reserved thereafter.
*   **`RESERVED_FOR_SPECIFIC_USE`** - The label is reserved for the use of a
    specific registrant, and can only be registered by someone sending along the
    allocation token at time of registration. This token is configured on an
    `AllocationToken` entity with a matching `domainName`, and is sent by the
    registrar using the [allocation token EPP
    extension](https://tools.ietf.org/id/draft-ietf-regext-allocation-token-07.html).
*   **`RESERVED_FOR_ANCHOR_TENANT`** - Like `RESERVED_FOR_SPECIFIC_USE`, except
    for an anchor tenant (i.e. a registrant participating in a [Qualified Launch
    Program](https://newgtlds.icann.org/en/announcements-and-media/announcement-10apr14-en)),
    meaning that registrations can occur during sunrise ahead of GA, and must be
    for a two year term.
*   **`NAME_COLLISION`** - The label is reserved because it is on an [ICANN
    collision
    list](https://www.icann.org/resources/pages/name-collision-2013-12-06-en).
    It may be registered during sunrise by a registrant with a valid claim but
    is reserved thereafter. The `SERVER_HOLD` status is automatically applied
    upon registration, which will prevent the domain name from ever resolving in
    DNS.
*   **`FULLY_BLOCKED`** - The label is fully reserved, no further reason
    specified.

The reservation types are listed in order of increasing precedence, but if a
label is included in different lists that are applied to a single TLD, all
reservation types of the label are returned when queried. The order of the
reservation types only affects the message a domain check EPP request receives,
which is the one with the highest precedence. E.g. a label with name collision
reservation type in one list and allowed in sunrise reservation type in another
list will have both reservation types, but domain check will report that the
label is reserved due to name collision (with message "Cannot be delegated"). In
general `FULLY_BLOCKED` is by far the most widely used reservation type for
typical TLD use cases.

Here's an example of a small reserved list. Note that the
`NAMESERVER_RESTRICTED` label has a third entry, a colon separated list of
nameservers that the label can be delegated to:

```
reserveddomain,FULLY_BLOCKED
availableinga,ALLOWED_IN_SUNRISE
fourletterword,FULLY_BLOCKED
acmecorp,RESERVED_FOR_ANCHOR_TENANT
internaldomain,NAMESERVER_RESTRICTED,ns1.internal.tld:ns1.internal.tld
```

# Reserved list file name format

There are two types of reserved lists: Those that are intended to apply to a
specific TLD, and are thus prefixed with the name of the TLD followed by an
underscore, and those that can be applied to any TLD, and are prefixed with
`common_`. For example, a list of reserved labels on the TLD `exampletld` might
be named `exampletld_blocked-names.txt`, whereas a similar list intended to
apply to multiple TLDs might be named `common_blocked-names.txt`. Note that
these naming conventions are enforced by the tooling used to create and apply
reserved lists (see subsequent sections). The two naming patterns are thus:

*   `common_list-name.txt`
*   `tldname_list-name.txt`

## Creating a reserved list

Once the file containing the list of reserved terms is created, run the
`create_reserved_list` command to load it into Datastore as follows. For the
purposes of this example, we are creating a common reserved list named
"common_bad-words".

```shell
$ nomulus -e {ENVIRONMENT} create_reserved_list -i common_bad-words.txt
[ ... snip long confirmation prompt ... ]
Perform this command? (y/N): y
Updated 1 entities.
```

Note that `-i` is the input file containing the list. You can optionally specify
the name of the reserved list using `-n`, but when it's omitted as above the
list name is inferred from the name of the filename (minus the file extension).
For ease of tracking track of things, it is recommended to store all lists such
that the filename and list name are identical.

You're not done yet! After creating the reserved list you must the apply it to
one or more TLDs (see below) for it to actually be used.

## Updating a reserved list

To update the contents of an existing reserved list, make changes to the .txt
file containing the reserved list entries, then pass it as input to the
`update_reserved_list` command as follows:

```shell
$ nomulus -e {ENVIRONMENT} update_reserved_list -i common_bad-words.txt
[ ... snip diff of changes to list entries ... ]
Perform this command? (y/N): y
Updated 1 entities.
```

Note that, like the create command, the name of the list is inferred from the
filename unless you specify the `-n` parameter (not generally recommended).

## Applying a reserved list to a TLD

Separate from the management of the contents of individual reserved lists,
reserved lists must also be applied to a TLD. Unlike premium lists, for which
each TLD may only have a single list applied, a TLD can have any number of
reserved lists applied. The list of reserved labels for a TLD is the union of
all applied reserved lists, using the precedence rules described earlier when a
label appears in more than one list.

To add a reserved list to a TLD, run the `update_tld` command with the following
parameter:

```shell
$ nomulus -e {ENVIRONMENT} update_tld exampletld \
    --add_reserved_lists common_bad-words
Update Registry@exampletld
reservedLists: null -> [Key<?>(EntityGroupRoot("cross-tld")/ReservedList("common_bad-words"))]
Perform this command? (y/N): y
Updated 1 entities.
```

The `--add_reserved_lists` parameter can take a comma-delimited list of reserved
list names if you are applying multiple reserved lists to a TLD. There is also a
`--remove_reserved_lists` parameter that functions as you might expect.

Naming rules are enforced: reserved lists that start with `common_` can be
applied to any TLD (though they don't automatically apply to all TLDs), whereas
reserved lists that start with the name of a TLD can only be applied to the TLD
with that name.

## Checking which reserved lists are applied to a TLD

The `get_tld` command shows which reserved lists (if any) are applied to a TLD,
along with lots of other information about that TLD which is not relevant to our
purposes here. It is used as follows:

```shell
$ nomulus -e {ENVIRONMENT} get_tld exampletld
[ ... snip output ... ]
reservedLists=[Key<?>(EntityGroupRoot("cross-tld")/ReservedList("common_bad-words"))]
[ ... snip output ... ]
```

## Listing all available reserved lists

The `list_reserved_lists` command is used to list all reserved lists in
Datastore. It takes no arguments and displays a simple list of reserved lists in
newline-delimited format as follows:

```shell
$ nomulus -e {ENVIRONMENT} list_reserved_lists
common_bad-words
exampletld_some-other-list
```

## Verifying reserved list updates

To verify that the changes have actually been applied, you can run a domain
check on a modified entry using the `nomulus check_domain` command and verify
that the domain now has the correct reservation status.

```shell
$ nomulus -e production check_domain {domain_name}
[ ... snip output ... ]
```

 **Note that the list can be cached for up to 60 minutes, so changes may not
take place immediately**. If it is urgent that the new changes be applied, and
it's OK to potentially interrupt client connections, then you can use the App
Engine web console to kill instances of the `default` service, as the cache is
per-instance. Once you've killed all the existing instances (don't kill them all
at once!), all of the newly spun up instances will now be using the new values
you've configured.

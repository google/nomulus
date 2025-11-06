# Managing static premium price lists

## Premium list file format

Nomulus comes with a `StaticPremiumListPricingEngine` that determines premium
prices of domain labels (i.e. the part of the domain name without the TLD) by
checking for their presence on a list of prices in the database. `nomulus` is
used to load and update these lists from flat text files. The format of this
list is simple: It is a newline-delimited CSV text file with each line
containing the label and its price (including currency specifier in ISO-4217
format). Any individual label may not appear more than once in the file. Here's
an example of the formatting:

```
premium,USD 100
superpremium,USD 500
```

By convention, premium lists are named after the TLD they apply to (with one
premium list per TLD). If the above example applied to the `exampletld` example,
then the file would be saved as `exampletld.txt` and the domain name
`premium.exampletld` would cost $100. It is also possible to have a single
premium list that is used on multiple TLDs, for which a different naming
convention would be used.

It is recommended that you store your premium list .txt files in a backed-up
location that is accessible to all members of your team (ideally in a source
control system for revision tracking). These files should be thought of as the
canonical versions of your premium lists. Note that there is no provided way to
reconstruct a premium list .txt file from the premium list that is loaded into
the database (though in principle it would be easy to do by writing a tool to do
so), so don't lose those .txt files.

The nomulus repository contains an
[example premium list file](https://github.com/google/nomulus/blob/master/core/src/main/java/google/registry/config/files/premium/example.txt).

## Creating a premium list

Once the file containing the premium prices is ready, run the
`create_premium_list` command to load it into the database as follows:

```shell
$ nomulus -e {ENVIRONMENT} create_premium_list -n exampletld -i exampletld.txt

You are about to save the premium list exampletld with 2 items:
Perform this command? (y/N): y
Successfully saved premium list exampletld
```

`-n` is the name of the list to be created, and `-i` is the input filename. Note
that the convention of naming premium lists after the TLD they are intended to
be used for is enforced unless the override parameter `-o` is passed, which
allows premium lists to be created with any name.

You're not done yet! After creating the premium list you must the apply it to
one or more TLDs (see below) for it to actually be used.

## Updating a premium list

If the premium list already exists and you want to update it with new prices
from a text file, the procedure is exactly the same, except using the
`update_premium_list` command as follows:

```shell
$ nomulus -e {ENVIRONMENT} update_premium_list -n exampletld -i exampletld.txt

You are about to save the premium list exampletld with 2 items:
Perform this command? (y/N): y
Successfully saved premium list exampletld
```

### Note:

We recommend only updating premium lists manually in the case of emergencies.
Instead, we run the `update_premium_list` command (as well as `configure_tld`
and `update_reserved_list` commands) as part of the build process after a pull
request has been merged into the private source code repository that contains
the files. The `--build_environment` flag is used to signal that the command is
being run in one of those automated environments, and thus allowed to modify
production. Without that flag, commands against production will fail.

This is similar to the process for [updating TLDs](modifying-tlds.md).

If this premium list is already applied to a TLD, then changes will take up to
60 minutes to take effect (depending on how you've configured the relevant
caching interval; 60 minutes is the default).

## Applying a premium list to a TLD

Separate from the management of the contents of individual premium lists, a
premium list must first be applied to a TLD before it will take effect. You will
only need to do this when first creating a premium list; once it has been
applied, it stays applied, and updates to the list are effective automatically.
Note that each TLD can have no more than one premium list applied to it. To
apply a premium list to a TLD,
[update the TLD to set the premium list](modifying-tlds.md):

```shell
...
pendingDeleteLength: "PT432000S"
premiumListName: "test"
pricingEngineClassName: "google.registry.model.pricing.StaticPremiumListPricingEngine"
...
```

## Checking which premium list is applied to a TLD

The `get_tld` command shows which premium list is applied to a TLD (along with
all other information about a TLD). It is used as follows:

```shell
$ nomulus -e {ENVIRONMENT} get_tld exampletld
[ ... snip output ... ]
premiumListName: "test"
[ ... snip output ... ]
```

## Listing all available premium lists

The `list_premium_lists` command is used to list all premium lists in the
database. It takes no arguments and displays a simple list of premium lists as
follows:

```shell
$ nomulus -e {ENVIRONMENT} list_premium_lists
exampletld
someotherlist
```

## Verifying premium list updates

To verify that the changes have actually been applied, you can run a domain
check on a modified entry using the `nomulus check_domain` command and verify
that the domain now has the correct price.

```shell
$ nomulus -e production check_domain {domain_name}
[ ... snip output ... ]
```

**Note that the list can be cached for up to 60 minutes, so the old value may
still be returned for a little while**. If it is urgent that the new pricing
changes be applied, and it's OK to potentially interrupt client connections,
then you can use the GCP web console to kill instances of the `frontend`
service, as the cache is per-instance. Once you've killed all the existing
instances (don't kill them all at once!), all the newly spun up instances will
now be using the new values you've configured.

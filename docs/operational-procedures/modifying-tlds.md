# Creating or Modifying TLDs

Nomulus stores YAML representations of TLDs, in an effort to make sure that any
(potentially significant) modifications to TLDs go through source control and
code review. We recommend storing these TLD YAML representations in a separate
private repository so that changes can be verified by multiple people before
being merged
([here is an example TLD](https://github.com/google/nomulus/blob/master/core/src/test/resources/google/registry/tools/tld.yaml))

Creating and updating a TLD use the same process -- the only difference is
whether you're creating a TLD YAML file from scratch or modifying an existing
one.

Similar to [premium lists](premium-list-management.md) and
[reserved lists](reserved-list-management.md), we recommend modifying TLDs as a
part of an automated build process after the desired changes have been merged
into the TLD YAML files. The automated process should run:

```shell
nomulus -e {ENVIRONMENT} configure_tld --build_environment --input=path/to/my/file/tld.yaml
```

The `build_environment` flag signals that this is being run as part of an
automated build process and should ideally not be used manually. There is an
additional `--break_glass` argument that can be used in emergencies to modify
TLDs outside a normal build process.

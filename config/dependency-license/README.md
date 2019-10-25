## Summary

This folder contains configuration files for the gradle-license-report plugin:

*   allowed_licenses.json declares the acceptable licenses. A license may have
    multiple entries in this file, since the 'moduleLicense' property value must
    match exactly the phrases found in pom or manifest files.
*   license_normalizer_bundle.json configures normalization rules for license
    reporting.

## Notes About Adding New Licenses

*   The WTFPL license is not allowed.

*   Each 'Public Domain' license entry must include a specific 'moduleName'. Do
    not omit moduleName or use wildcards.

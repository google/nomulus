// Copyright 2017 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.util;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.base.Ascii;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.net.InternetDomainName;

/** Utility methods related to domain names. */
public final class DomainNameUtils {

  /** Prefix for unicode domain name parts. */
  public static final String ACE_PREFIX = "xn--";
  public static final String ACE_PREFIX_REGEX = "^xn--";

  /** Checks whether "name" is a strict subdomain of "potentialParent". */
  public static boolean isUnder(InternetDomainName name, InternetDomainName potentialParent) {
    int numNameParts = name.parts().size();
    int numParentParts = potentialParent.parts().size();
    return numNameParts > numParentParts
        && name.parts().subList(numNameParts - numParentParts, numNameParts)
            .equals(potentialParent.parts());
  }

  /** Canonicalizes a domain name by lowercasing and converting unicode to punycode. */
  public static String canonicalizeDomainName(String label) {
    String labelLowercased = Ascii.toLowerCase(label);
    try {
      return Idn.toASCII(labelLowercased);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(String.format("Error ASCIIfying label '%s'", label), e);
    }
  }

  /**
   * Returns the canonicalized TLD part of a valid fully-qualified domain name by stripping off the
   * leftmost part.
   *
   * <p>This method should not be called for subdomains.
   *
   * <p>This function is compatible with multi-part tlds, e.g. {@code co.uk}. This function will
   * also work on domains for which the registry is not authoritative. If you are certain that the
   * input will be under a TLD this registry controls, then it is preferable to use {@link
   * google.registry.model.tld.Registries#findTldForName(InternetDomainName)
   * Registries#findTldForName}, which will work on hostnames in addition to domains.
   *
   * @param fullyQualifiedDomainName must be a punycode SLD (not a host or unicode)
   * @throws IllegalArgumentException if there is no TLD
   */
  public static String getTldFromDomainName(String fullyQualifiedDomainName) {
    checkArgument(
        !Strings.isNullOrEmpty(fullyQualifiedDomainName),
        "fullyQualifiedDomainName cannot be null or empty");
    return getTldFromDomainName(InternetDomainName.from(fullyQualifiedDomainName));
  }

  /**
   * Returns the canonicalized TLD part of a valid fully-qualified domain name by stripping off the
   * leftmost part.
   *
   * <p>This function is compatible with multi-part TLDs and should not be called with subdomains.
   *
   * @throws IllegalArgumentException if there is no TLD
   */
  public static String getTldFromDomainName(InternetDomainName domainName) {
    checkArgumentNotNull(domainName);
    checkArgument(domainName.hasParent(), "domainName does not have a TLD");
    return domainName.parent().toString();
  }

  /**
   * Returns the second level domain name for a fully qualified host name under a given tld.
   *
   * <p>This function is merely a string parsing utility, and does not verify if the tld is operated
   * by the registry.
   *
   * @throws IllegalArgumentException if either argument is null or empty, or the domain name is not
   *     under the tld
   */
  public static String getSecondLevelDomain(String hostName, String tld) {
    checkArgument(
        !Strings.isNullOrEmpty(hostName),
        "hostName cannot be null or empty");
    checkArgument(!Strings.isNullOrEmpty(tld), "tld cannot be null or empty");
    ImmutableList<String> domainParts = InternetDomainName.from(hostName).parts();
    ImmutableList<String> tldParts = InternetDomainName.from(tld).parts();
    checkArgument(
        domainParts.size() > tldParts.size(),
        "hostName must be at least one level below the tld");
    checkArgument(
        domainParts
            .subList(domainParts.size() - tldParts.size(), domainParts.size())
            .equals(tldParts),
        "hostName must be under the tld");
    ImmutableList<String> sldParts =
        domainParts.subList(domainParts.size() - tldParts.size() - 1, domainParts.size());
    return Joiner.on(".").join(sldParts);
  }

  private DomainNameUtils() {}
}

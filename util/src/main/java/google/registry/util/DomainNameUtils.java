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
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.net.InternetDomainName;
import java.util.List;

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

  /**
   * Canonicalizes a hostname/domain name by lowercasing and converting Unicode to punycode.
   *
   * <p>This applies slightly stricter rules to all labels other than the TLD part (all other
   * labels are not allowed to have hyphens in the third and fourth position except when using
   * ACE-formatted Punycode). This restriction is not enforced on the last label (so multi-part
   * TLDs still cannot have said characters except on the last part).
   */
  public static String canonicalizeHostname(String label) {
    String labelLowercased = Ascii.toLowerCase(label);
    List<String> parts = Splitter.on('.').splitToList(labelLowercased);
    // If the hostname only has one part, just canonicalize that.
    if (parts.size() == 1) {
      return Idn.toASCII(parts.getFirst());
    }
    // If the hostname has multiple parts, apply stricter validation to all labels but the last
    // one (which relaxes the hyphens in third and fourth positions rule).
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < parts.size() - 1; i++) {
      sb.append(Idn.toASCII(parts.get(i))).append('.');
    }
    sb.append(Idn.tldToASCII(parts.getLast()));
    return sb.toString();
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
   * google.registry.model.tld.Tlds#findTldForName(InternetDomainName) Registries#findTldForName},
   * which will work on hostnames in addition to domains.
   *
   * @param domainName must be a punycode SLD (not a host or unicode)
   * @throws IllegalArgumentException if there is no TLD
   */
  public static String getTldFromDomainName(String domainName) {
    checkArgument(!Strings.isNullOrEmpty(domainName), "domainName cannot be null or empty");
    return getTldFromDomainName(InternetDomainName.from(domainName));
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

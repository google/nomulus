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

package google.registry.rdap;


import com.google.common.base.Strings;
import google.registry.request.HttpException.BadRequestException;
import google.registry.request.HttpException.UnprocessableEntityException;
import google.registry.util.Idn;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * Object containing the results of parsing an RDAP partial match search pattern. Search patterns
 * are of the form XXXX, XXXX* or XXXX*.YYYY. There can be at most one wildcard character, and it
 * must be at the end, except for a possible suffix string on the end to restrict the search to a
 * particular TLD (for domains) or domain (for nameservers).
 *
 * @see <a href="http://www.ietf.org/rfc/rfc9082.txt">RFC 9082: Registration Data Access Protocol
 *     (RDAP) Query Format</a>
 */
public final class RdapSearchPattern {

  static final int MIN_INITIAL_STRING_LENGTH = 2;

  /**
   * Pattern for allowed LDH searches.
   *
   * <p>Based on RFC 9082 4.1. Must contains only alphanumeric plus dots and hyphens. A single
   * whildcard asterix is allowed - but if exists must be the last character of a domain name label
   * (so exam* and exam*.com are allowed, but exam*le.com isn't allowd)
   *
   * <p>The prefix is in group(1), and the suffix without the dot (if it exists) is in group(4). If
   * there's no wildcard, group(2) is empty.
   */
  static final Pattern LDH_PATTERN =
      Pattern.compile("([-.a-zA-Z0-9]*)([*]([.]([-.a-zA-Z0-9]+))?)?");

  /** String before the wildcard character. */
  private final String initialString;

  /** If false, initialString contains the entire search string. */
  private final boolean hasWildcard;

  /**
   * Terminating suffix after the wildcard, or null if none was specified; for domains, it should be
   * a TLD, for nameservers, a domain. RFC 9082 requires only that it be a sequence of domain
   * labels, but this definition is stricter for efficiency purposes.
   */
  @Nullable private final String suffix;

  private RdapSearchPattern(
      final String initialString, final boolean hasWildcard, @Nullable final String suffix) {
    this.initialString = initialString;
    this.hasWildcard = hasWildcard;
    this.suffix = suffix;
  }

  public String getInitialString() {
    return initialString;
  }

  public boolean getHasWildcard() {
    return hasWildcard;
  }

  @Nullable
  public String getSuffix() {
    return suffix;
  }

  /**
   * Attempts to return the next string in sort order after {@link #getInitialString()
   * initialString}. This can be used to convert a wildcard query into a range query, by looking for
   * strings greater than or equal to {@link #getInitialString() initialString} and less than {@link
   * #getNextInitialString() nextInitialString}.
   */
  public String getNextInitialString() {
    return initialString.substring(0, initialString.length() - 1)
        + (char) (initialString.charAt(initialString.length() - 1) + 1);
  }

  /**
   * Creates a SearchPattern using the provided search pattern string in Unicode.
   *
   * <p>The search query might end in an asterix, in which case that asterix is considered a
   * wildcard and can match 0 or more characters. Without that asterix - the match will be exact.
   *
   * @param searchQuery the string containing the partial match pattern, optionally ending in a
   *     wildcard asterix
   * @throws UnprocessableEntityException if {@code pattern} has a wildcard not at the end of the
   *     query
   */
  public static RdapSearchPattern createFromUnicodeString(String searchQuery) {
    int wildcardLocation = searchQuery.indexOf('*');
    if (wildcardLocation < 0) {
      return new RdapSearchPattern(searchQuery, false, null);
    }
    if (wildcardLocation == searchQuery.length() - 1) {
      return new RdapSearchPattern(searchQuery.substring(0, wildcardLocation), true, null);
    }
    throw new UnprocessableEntityException(
        String.format(
            "Query can only have a single wildcard, and it must be at the end of the query, but"
                + " was: '%s'",
            searchQuery));
  }

  /**
   * Creates a SearchPattern using the provided domain search pattern in LDH format.
   *
   * <p>The domain search pattern can have a single wildcard asterix that can match 0 or more
   * charecters. If such an asterix exists - it must be at the end of a domain label.
   *
   * @param searchQuery the string containing the partial match pattern
   * @throws UnprocessableEntityException if {@code pattern} does not meet the requirements of RFC
   *     7482
   */
  public static RdapSearchPattern createFromLdhDomainName(String searchQuery) {
    Matcher matcher = LDH_PATTERN.matcher(searchQuery);
    if (!matcher.matches()) {
      throw new UnprocessableEntityException(
          String.format(
              "Query can only have a single wildcard, and it must be at the end of a label,"
                  + " but was: '%s'",
              searchQuery));
    }

    String initialString = matcher.group(1);
    boolean hasWildcard = !Strings.isNullOrEmpty(matcher.group(2));
    String suffix = Strings.emptyToNull(matcher.group(4));

    return new RdapSearchPattern(initialString, hasWildcard, suffix);
  }

  /**
   * Creates a SearchPattern using the provided domain search pattern in LDH or Unicode format.
   *
   * <p>The domain search pattern can have a single wildcard asterix that can match 0 or more
   * charecters. If such an asterix exists - it must be at the end of a domain label.
   *
   * <p>In theory, according to RFC 9082 4.1 - we should make some checks about partial matching in
   * unicode queries. We don't, but we might want to just disable partial matches for unicode inputs
   * (meaning if it doesn't match LDH_PATTERN, then don't allow wildcard at all).
   *
   * @param searchQuery the string containing the partial match pattern
   * @throws UnprocessableEntityException if {@code pattern} does not meet the requirements of RFC
   *     7482
   */
  public static RdapSearchPattern createFromLdhOrUnicodeDomainName(String searchQuery) {
    String ldhSearchQuery;
    try {
      ldhSearchQuery = Idn.toASCII(searchQuery);
    } catch (Exception e) {
      throw new BadRequestException(
          String.format("Invalid value of searchQuery: '%s'", searchQuery), e);
    }
    return RdapSearchPattern.createFromLdhDomainName(ldhSearchQuery);
  }

  /**
   * Checks a string to make sure that it matches the search pattern.
   *
   * @param string the string to be matched
   * @return true if the pattern matches the string
   */
  public boolean matches(@Nullable String string) {
    if (string == null) {
      return false;
    }
    int lengthAccountedFor = 0;
    if (initialString != null) {
      if (!string.startsWith(initialString)) {
        return false;
      }
      lengthAccountedFor += initialString.length();
    }
    if (suffix != null) {
      if (!string.endsWith(suffix)) {
        return false;
      }
      lengthAccountedFor += suffix.length();
    }
    return hasWildcard
        ? (lengthAccountedFor <= string.length())
        : (lengthAccountedFor == string.length());
  }
}

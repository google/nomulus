// Copyright 2016 The Nomulus Authors. All Rights Reserved.
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

package google.registry.flows.host;

import static google.registry.model.EppResourceUtils.isActive;
import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.model.registry.Registries.findTldForName;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.base.Ascii;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.Iterables;
import com.google.common.net.InternetDomainName;
import google.registry.flows.EppException;
import google.registry.flows.EppException.AuthorizationErrorException;
import google.registry.flows.EppException.ObjectDoesNotExistException;
import google.registry.flows.EppException.ParameterValuePolicyErrorException;
import google.registry.flows.EppException.ParameterValueRangeErrorException;
import google.registry.flows.EppException.ParameterValueSyntaxErrorException;
import google.registry.model.domain.DomainResource;
import google.registry.util.Idn;
import org.joda.time.DateTime;

/** Static utility functions for host flows. */
public class HostFlowUtils {

  /** Checks that a host name is valid. */
  static InternetDomainName validateHostName(String name) throws EppException {
    checkArgumentNotNull(name, "Must specify host name to validate");
    if (name.length() > 253) {
      throw new HostNameTooLongException();
    }
    String hostNameLowerCase = Ascii.toLowerCase(name);
    if (!name.equals(hostNameLowerCase)) {
      throw new HostNameNotLowerCaseException(hostNameLowerCase);
    }
    try {
      String hostNamePunyCoded = Idn.toASCII(name);
      if (!name.equals(hostNamePunyCoded)) {
        throw new HostNameNotPunyCodedException(hostNamePunyCoded);
      }
      InternetDomainName hostName = InternetDomainName.from(name);
      if (!name.equals(hostName.toString())) {
        throw new HostNameNotNormalizedException(hostName.toString());
      }
      // Checks whether a hostname is deep enough. Technically a host can be just one under a
      // public suffix (e.g. example.com) but we require by policy that it has to be at least one
      // part beyond that (e.g. ns1.example.com). The public suffix list includes all current
      // ccTlds, so this check requires 4+ parts if it's a ccTld that doesn't delegate second
      // level domains, such as .co.uk. But the list does not include new tlds, so in that case
      // we just ensure 3+ parts. In the particular case where our own tld has a '.' in it, we know
      // that there need to be 4 parts as well.
      if (hostName.isUnderPublicSuffix()) {
        if (hostName.parent().isUnderPublicSuffix()) {
          return hostName;
        }
      } else {
        // We need to know how many parts the hostname has beyond the public suffix, but we don't
        // know what the public suffix is. If the host is in bailiwick and we are hosting a
        // multipart "tld" like .co.uk the publix suffix might be 2 parts. Otherwise it's an
        // unrecognized tld that's not on the public suffix list, so assume the tld alone is the
        // public suffix.
        Optional<InternetDomainName> tldParsed = findTldForName(hostName);
        int suffixSize = tldParsed.isPresent() ? tldParsed.get().parts().size() : 1;
        if (hostName.parts().size() >= suffixSize + 2) {
          return hostName;
        }
      }
      throw new HostNameTooShallowException();
    } catch (IllegalArgumentException e) {
      throw new InvalidHostNameException();
    }
  }

  /** Return the {@link DomainResource} this host is subordinate to, or null for external hosts. */
  static DomainResource lookupSuperordinateDomain(
      InternetDomainName hostName, DateTime now) throws EppException {
    Optional<InternetDomainName> tld = findTldForName(hostName);
    if (!tld.isPresent()) {
      // This is an host on a TLD we don't run, therefore obviously external, so we are done.
      return null;
    }
    // This is a subordinate host
    String domainName = Joiner.on('.').join(Iterables.skip(
        hostName.parts(), hostName.parts().size() - (tld.get().parts().size() + 1)));
    DomainResource superordinateDomain = loadByForeignKey(DomainResource.class, domainName, now);
    if (superordinateDomain == null || !isActive(superordinateDomain, now)) {
      throw new SuperordinateDomainDoesNotExistException(domainName);
    }
    return superordinateDomain;
  }

  /** Superordinate domain for this hostname does not exist. */
  static class SuperordinateDomainDoesNotExistException extends ObjectDoesNotExistException {
    public SuperordinateDomainDoesNotExistException(String domainName) {
      super(DomainResource.class, domainName);
    }
  }

  /** Ensure that the superordinate domain is sponsored by the provided clientId. */
  static void verifyDomainIsSameRegistrar(
      DomainResource superordinateDomain,
      String clientId) throws EppException {
    if (superordinateDomain != null
        && !clientId.equals(superordinateDomain.getCurrentSponsorClientId())) {
      throw new HostDomainNotOwnedException();
    }
  }

  /** Domain for host is sponsored by another registrar. */
  static class HostDomainNotOwnedException extends AuthorizationErrorException {
    public HostDomainNotOwnedException() {
      super("Domain for host is sponsored by another registrar");
    }
  }

  /** Host names are limited to 253 characters. */
  static class HostNameTooLongException extends ParameterValueRangeErrorException {
    public HostNameTooLongException() {
      super("Host names are limited to 253 characters");
    }
  }

  /** Host names must be at least two levels below the public suffix. */
  static class HostNameTooShallowException extends ParameterValuePolicyErrorException {
    public HostNameTooShallowException() {
      super("Host names must be at least two levels below the public suffix");
    }
  }

  /** Invalid host name. */
  static class InvalidHostNameException extends ParameterValueSyntaxErrorException {
    public InvalidHostNameException() {
      super("Invalid host name");
    }
  }

  /** Host names must be in lower-case. */
  static class HostNameNotLowerCaseException extends ParameterValueSyntaxErrorException {
    public HostNameNotLowerCaseException(String expectedHostName) {
      super(String.format("Host names must be in lower-case; expected %s", expectedHostName));
    }
  }

  /** Host names must be puny-coded. */
  static class HostNameNotPunyCodedException extends ParameterValueSyntaxErrorException {
    public HostNameNotPunyCodedException(String expectedHostName) {
      super(String.format("Host names must be puny-coded; expected %s", expectedHostName));
    }
  }

  /** Host names must be in normalized format. */
  static class HostNameNotNormalizedException extends ParameterValueSyntaxErrorException {
    public HostNameNotNormalizedException(String expectedHostName) {
      super(
          String.format("Host names must be in normalized format; expected %s", expectedHostName));
    }
  }
}

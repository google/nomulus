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

package google.registry.flows.host;

import static google.registry.model.EppResourceUtils.isActive;
import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.model.tld.Registries.findTldForName;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;
import static java.util.stream.Collectors.joining;

import com.google.common.base.Ascii;
import com.google.common.net.InternetDomainName;
import google.registry.flows.EppException;
import google.registry.flows.EppException.AuthorizationErrorException;
import google.registry.flows.EppException.ObjectDoesNotExistException;
import google.registry.flows.EppException.ParameterValuePolicyErrorException;
import google.registry.flows.EppException.ParameterValueRangeErrorException;
import google.registry.flows.EppException.ParameterValueSyntaxErrorException;
import google.registry.flows.EppException.StatusProhibitsOperationException;
import google.registry.model.domain.DomainBase;
import google.registry.model.eppcommon.StatusValue;
import google.registry.util.Idn;
import java.util.Optional;
import org.joda.time.DateTime;

/** Static utility functions for host flows. */
public class HostFlowUtils {

  /** Checks that a host name is valid. */
  public static InternetDomainName validateHostName(String name) throws EppException {
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
      // The effective TLD is, in order of preference, the registry suffix, if the TLD is a real TLD
      // published in the public suffix list (https://publicsuffix.org/, note that a registry suffix
      // is in the "ICANN DOMAINS" in that list); or a TLD managed by Nomulus (in-bailiwick), found
      // by #findTldForName; or just the last part of a domain name.
      InternetDomainName effectiveTld =
          hostName.isUnderRegistrySuffix()
              ? hostName.registrySuffix()
              : findTldForName(hostName).orElse(InternetDomainName.from("invalid"));
      // Checks whether a hostname is deep enough. Technically a host can be just one level beneath
      // the effective TLD (e.g. example.com) but we require by policy that it has to be at least
      // one part beyond that (e.g. ns1.example.com).
      if (hostName.parts().size() < effectiveTld.parts().size() + 2) {
        throw new HostNameTooShallowException();
      }
      return hostName;
    } catch (IllegalArgumentException e) {
      throw new InvalidHostNameException();
    }
  }

  /** Return the {@link DomainBase} this host is subordinate to, or null for external hosts. */
  public static Optional<DomainBase> lookupSuperordinateDomain(
      InternetDomainName hostName, DateTime now) throws EppException {
    Optional<InternetDomainName> tld = findTldForName(hostName);
    if (!tld.isPresent()) {
      // This is an host on a TLD we don't run, therefore obviously external, so we are done.
      return Optional.empty();
    }
    // This is a subordinate host
    String domainName =
        hostName.parts().stream()
            .skip(hostName.parts().size() - (tld.get().parts().size() + 1))
            .collect(joining("."));
    Optional<DomainBase> superordinateDomain =
        loadByForeignKey(DomainBase.class, domainName, now);
    if (!superordinateDomain.isPresent() || !isActive(superordinateDomain.get(), now)) {
      throw new SuperordinateDomainDoesNotExistException(domainName);
    }
    return superordinateDomain;
  }

  /** Superordinate domain for this hostname does not exist. */
  static class SuperordinateDomainDoesNotExistException extends ObjectDoesNotExistException {
    public SuperordinateDomainDoesNotExistException(String domainName) {
      super(DomainBase.class, domainName);
    }
  }

  /** Ensure that the superordinate domain is sponsored by the provided clientId. */
  static void verifySuperordinateDomainOwnership(
      String clientId, DomainBase superordinateDomain) throws EppException {
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

  /** Ensure that the superordinate domain is not in pending delete. */
  static void verifySuperordinateDomainNotInPendingDelete(DomainBase superordinateDomain)
      throws EppException {
    if ((superordinateDomain != null)
        && superordinateDomain.getStatusValues().contains(StatusValue.PENDING_DELETE)) {
      throw new SuperordinateDomainInPendingDeleteException();
    }
  }

  /** Superordinate domain for this hostname is in pending delete. */
  static class SuperordinateDomainInPendingDeleteException
      extends StatusProhibitsOperationException {
    public SuperordinateDomainInPendingDeleteException() {
      super("Superordinate domain for this hostname is in pending delete");
    }
  }

  /** Host names are limited to 253 characters. */
  static class HostNameTooLongException extends ParameterValueRangeErrorException {
    public HostNameTooLongException() {
      super("Host names are limited to 253 characters");
    }
  }

  /** Host names must be at least two levels below the registry suffix. */
  static class HostNameTooShallowException extends ParameterValuePolicyErrorException {
    public HostNameTooShallowException() {
      super("Host names must be at least two levels below the registry suffix");
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

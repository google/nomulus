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

package google.registry.flows;

import static com.google.common.base.MoreObjects.toStringHelper;
import static google.registry.request.RequestParameters.extractOptionalHeader;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.HostAndPort;
import com.google.common.net.InetAddresses;
import dagger.Module;
import dagger.Provides;
import google.registry.config.RegistryConfig.Config;
import google.registry.flows.EppException.AuthenticationErrorException;
import google.registry.flows.certs.CertificateChecker;
import google.registry.flows.certs.CertificateChecker.InsecureCertificateException;
import google.registry.model.registrar.Registrar;
import google.registry.request.Header;
import google.registry.util.CidrAddressBlock;
import google.registry.util.ProxyHttpHeaders;
import java.net.InetAddress;
import java.util.Optional;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;

/**
 * Container and validation for TLS certificate and IP-allow-listing.
 *
 * <p>Credentials are based on the following headers:
 *
 * <dl>
 *   <dt>X-SSL-Certificate
 *   <dd>This field should contain a base64 encoded digest of the client's TLS certificate. It is
 *       used only if the validation of the full certificate fails.
 *   <dt>X-Forwarded-For
 *   <dd>This field should contain the host and port of the connecting client. It is validated
 *       during an EPP login command against an IP allow list that is transmitted out of band.
 * </dl>
 */
public class TlsCredentials implements TransportCredentials {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final boolean requireSslCertificates;
  private final Optional<String> clientCertificateHash;
  private final Optional<InetAddress> clientInetAddr;
  private final CertificateChecker certificateChecker;

  @Inject
  public TlsCredentials(
      @Config("requireSslCertificates") boolean requireSslCertificates,
      @Header(ProxyHttpHeaders.CERTIFICATE_HASH) Optional<String> clientCertificateHash,
      @Header(ProxyHttpHeaders.IP_ADDRESS) Optional<String> clientAddress,
      CertificateChecker certificateChecker) {
    this.requireSslCertificates = requireSslCertificates;
    this.clientCertificateHash = clientCertificateHash;
    this.clientInetAddr = clientAddress.map(TlsCredentials::parseInetAddress);
    this.certificateChecker = certificateChecker;
  }

  static InetAddress parseInetAddress(String asciiAddr) {
    try {
      return InetAddresses.forString(HostAndPort.fromString(asciiAddr).getHost());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  @Override
  public void validate(Registrar registrar, String password) throws AuthenticationErrorException {
    validateIp(registrar);
    validateCertificateHash(registrar);
    validatePassword(registrar, password);
  }

  /**
   * Verifies {@link #clientInetAddr} is in CIDR allow list associated with {@code registrar}.
   *
   * @throws BadRegistrarIpAddressException If IP address is not in the allow list provided
   */
  private void validateIp(Registrar registrar) throws AuthenticationErrorException {
    ImmutableList<CidrAddressBlock> ipAddressAllowList = registrar.getIpAddressAllowList();
    if (ipAddressAllowList.isEmpty()) {
      logger.atInfo().log(
          "Skipping IP allow list check because %s doesn't have an IP allow list.",
          registrar.getClientId());
      return;
    }
    // In the rare unexpected case that the client inet address wasn't passed along at all, then
    // by default deny access.
    if (clientInetAddr.isPresent()) {
      for (CidrAddressBlock cidrAddressBlock : ipAddressAllowList) {
        if (cidrAddressBlock.contains(clientInetAddr.get())) {
          // IP address is in allow list; return early.
          return;
        }
      }
    }
    logger.atInfo().log(
        "Authentication error: IP address %s is not allow-listed for registrar %s; allow list is:"
            + " %s",
        clientInetAddr, registrar.getClientId(), ipAddressAllowList);
    throw new BadRegistrarIpAddressException();
  }

  @VisibleForTesting
  void validateCertificateHash(Registrar registrar) throws AuthenticationErrorException {
    if (!registrar.getClientCertificateHash().isPresent()
        && !registrar.getFailoverClientCertificateHash().isPresent()) {
      if (requireSslCertificates) {
        throw new RegistrarCertificateNotConfiguredException();
      } else {
        // If the environment is configured to allow missing SSL certificate hashes and this hash is
        // missing, then bypass the certificate hash checks.
        return;
      }
    }
    // Check that the request included the certificate hash
    if (!clientCertificateHash.isPresent()) {
      logger.atInfo().log(
          "Request from registrar %s did not include X-SSL-Certificate.", registrar.getClientId());
      throw new MissingRegistrarCertificateException();
    }
    // Check if the certificate hash is equal to the one on file for the registrar.
    if (!clientCertificateHash.equals(registrar.getClientCertificateHash())
        && !clientCertificateHash.equals(registrar.getFailoverClientCertificateHash())) {
      logger.atWarning().log(
          "Non-matching certificate hash (%s) for %s, wanted either %s or %s.",
          clientCertificateHash,
          registrar.getClientId(),
          registrar.getClientCertificateHash(),
          registrar.getFailoverClientCertificateHash());
      throw new BadRegistrarCertificateException();
    }
    if (requireSslCertificates) {
      String passedCert =
          clientCertificateHash.equals(registrar.getClientCertificateHash())
              ? registrar.getClientCertificate().get()
              : registrar.getFailoverClientCertificate().get();
      try {
        certificateChecker.validateCertificate(passedCert);
      } catch (InsecureCertificateException e) {
        logger.atWarning().log(
            "Registrar certificate used for %s does not meet certificate requirements: %s",
            registrar.getClientId(), e.getMessage());
        throw new CertificateContainsSecurityViolationsException(e);
      }
    }
  }

  private void validatePassword(Registrar registrar, String password)
      throws BadRegistrarPasswordException {
    if (!registrar.verifyPassword(password)) {
      throw new BadRegistrarPasswordException();
    }
  }

  @Override
  public String toString() {
    return toStringHelper(getClass())
        .add("clientCertificateHash", clientCertificateHash.orElse(null))
        .add("clientAddress", clientInetAddr.orElse(null))
        .toString();
  }

  /** Registrar certificate does not match stored certificate. */
  public static class BadRegistrarCertificateException extends AuthenticationErrorException {
    BadRegistrarCertificateException() {
      super("Registrar certificate does not match stored certificate");
    }
  }

  /** Registrar certificate contains the following security violations: ... */
  public static class CertificateContainsSecurityViolationsException
      extends AuthenticationErrorException {
    InsecureCertificateException exception;

    CertificateContainsSecurityViolationsException(InsecureCertificateException exception) {
      super(
          String.format(
              "Registrar certificate contains the following security violations:\n%s",
              exception.getMessage()));
      this.exception = exception;
    }
  }

  /** Registrar certificate not present. */
  public static class MissingRegistrarCertificateException extends AuthenticationErrorException {
    MissingRegistrarCertificateException() {
      super("Registrar certificate not present");
    }
  }

  /** Registrar certificate is not configured. */
  public static class RegistrarCertificateNotConfiguredException
      extends AuthenticationErrorException {
    RegistrarCertificateNotConfiguredException() {
      super("Registrar certificate is not configured");
    }
  }

  /** Registrar IP address is not in stored allow list. */
  public static class BadRegistrarIpAddressException extends AuthenticationErrorException {
    BadRegistrarIpAddressException() {
      super("Registrar IP address is not in stored allow list");
    }
  }

  /** Dagger module for the EPP TLS endpoint. */
  @Module
  public static final class EppTlsModule {

    @Provides
    @Header(ProxyHttpHeaders.CERTIFICATE_HASH)
    static Optional<String> provideClientCertificateHash(HttpServletRequest req) {
      // Note: This header is actually required, we just want to handle its absence explicitly
      // by throwing an EPP exception rather than a generic Bad Request exception.
      return extractOptionalHeader(req, ProxyHttpHeaders.CERTIFICATE_HASH);
    }

    @Provides
    @Header(ProxyHttpHeaders.IP_ADDRESS)
    static Optional<String> provideIpAddress(HttpServletRequest req) {
      return extractOptionalHeader(req, ProxyHttpHeaders.IP_ADDRESS);
    }
  }
}

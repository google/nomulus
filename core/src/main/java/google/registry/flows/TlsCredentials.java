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
import static com.google.common.base.Strings.isNullOrEmpty;
import static google.registry.request.RequestParameters.extractOptionalHeader;
import static google.registry.request.RequestParameters.extractRequiredHeader;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.HostAndPort;
import com.google.common.net.InetAddresses;
import dagger.Module;
import dagger.Provides;
import google.registry.config.RegistryConfig.Config;
import google.registry.flows.EppException.AuthenticationErrorException;
import google.registry.model.registrar.Registrar;
import google.registry.request.Header;
import google.registry.util.CidrAddressBlock;
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
 *       validated during an EPP login command against a known good value that is transmitted out of
 *       band.
 *   <dt>X-Forwarded-For
 *   <dd>This field should contain the host and port of the connecting client. It is validated
 *       during an EPP login command against an IP allow list that is transmitted out of band.
 * </dl>
 */
public class TlsCredentials implements TransportCredentials {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final boolean requireSslCertificates;
  private final String clientCertificateHash;
  private final InetAddress clientInetAddr;

  @Inject
  public TlsCredentials(
      @Config("requireSslCertificates") boolean requireSslCertificates,
      @Header("X-SSL-Certificate") String clientCertificateHash,
      @Header("X-Forwarded-For") Optional<String> clientAddress) {
    this.requireSslCertificates = requireSslCertificates;
    this.clientCertificateHash = clientCertificateHash;
    this.clientInetAddr = clientAddress.isPresent() ? parseInetAddress(clientAddress.get()) : null;
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
    validateCertificate(registrar);
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
          "Skipping IP allow list check because %s doesn't have an IP allow list",
          registrar.getClientId());
      return;
    }
    for (CidrAddressBlock cidrAddressBlock : ipAddressAllowList) {
      if (cidrAddressBlock.contains(clientInetAddr)) {
        // IP address is in allow list; return early.
        return;
      }
    }
    logger.atInfo().log(
        "Authentication error: IP address %s is not allow-listed for registrar %s; allow list is:"
            + " %s",
        clientInetAddr, registrar.getClientId(), ipAddressAllowList);
    throw new BadRegistrarIpAddressException();
  }

  /**
   * Verifies client SSL certificate is permitted to issue commands as {@code registrar}.
   *
   * @throws MissingRegistrarCertificateException if frontend didn't send certificate hash header
   * @throws BadRegistrarCertificateException if registrar requires certificate and it didn't match
   */
  @VisibleForTesting
  void validateCertificate(Registrar registrar) throws AuthenticationErrorException {
    if (isNullOrEmpty(registrar.getClientCertificateHash())
        && isNullOrEmpty(registrar.getFailoverClientCertificateHash())) {
      if (requireSslCertificates) {
        throw new RegistrarCertificateNotConfiguredException();
      } else {
        // If the environment is configured to allow missing SSL certificate hashes and this hash is
        // missing, then bypass the certificate hash checks.
        return;
      }
    }
    if (isNullOrEmpty(clientCertificateHash)) {
      logger.atInfo().log("Request did not include X-SSL-Certificate");
      throw new MissingRegistrarCertificateException();
    }
    if (!clientCertificateHash.equals(registrar.getClientCertificateHash())
        && !clientCertificateHash.equals(registrar.getFailoverClientCertificateHash())) {
      logger.atWarning().log(
          "bad certificate hash (%s) for %s, wanted either %s or %s",
          clientCertificateHash,
          registrar.getClientId(),
          registrar.getClientCertificateHash(),
          registrar.getFailoverClientCertificateHash());
      throw new BadRegistrarCertificateException();
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
        .add("clientCertificateHash", clientCertificateHash)
        .add("clientAddress", clientInetAddr)
        .toString();
  }

  /** Registrar certificate does not match stored certificate. */
  public static class BadRegistrarCertificateException extends AuthenticationErrorException {
    public BadRegistrarCertificateException() {
      super("Registrar certificate does not match stored certificate");
    }
  }

  /** Registrar certificate not present. */
  public static class MissingRegistrarCertificateException extends AuthenticationErrorException {
    public MissingRegistrarCertificateException() {
      super("Registrar certificate not present");
    }
  }

  /** Registrar certificate is not configured. */
  public static class RegistrarCertificateNotConfiguredException
      extends AuthenticationErrorException {
    public RegistrarCertificateNotConfiguredException() {
      super("Registrar certificate is not configured");
    }
  }

  /** Registrar IP address is not in stored allow list. */
  public static class BadRegistrarIpAddressException extends AuthenticationErrorException {
    public BadRegistrarIpAddressException() {
      super("Registrar IP address is not in stored allow list");
    }
  }

  /** Dagger module for the EPP TLS endpoint. */
  @Module
  public static final class EppTlsModule {
    @Provides
    @Header("X-SSL-Certificate")
    static String provideClientCertificateHash(HttpServletRequest req) {
      return extractRequiredHeader(req, "X-SSL-Certificate");
    }

    @Provides
    @Header("X-Forwarded-For")
    static Optional<String> provideForwardedFor(HttpServletRequest req) {
      return extractOptionalHeader(req, "X-Forwarded-For");
    }
  }
}

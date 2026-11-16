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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Throwables.throwIfInstanceOf;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.io.BaseEncoding.base64;
import static google.registry.util.DomainNameUtils.canonicalizeAndValidateHostname;
import static java.nio.charset.StandardCharsets.US_ASCII;

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CRLException;
import java.security.cert.CRLReason;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateParsingException;
import java.security.cert.CertificateRevokedException;
import java.security.cert.X509CRL;
import java.security.cert.X509CRLEntry;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import javax.annotation.Tainted;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;
import org.bouncycastle.asn1.ASN1String;
import org.bouncycastle.asn1.x500.AttributeTypeAndValue;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;

/** X.509 Public Key Infrastructure (PKI) helper functions. */
public final class X509Utils {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /**
   * Maximum byte length of the {@link ProxyHttpHeaders#CERTIFICATE_HOSTNAMES} HTTP header value
   * (leaving headroom below Jetty's 8 KB request header limit for cookies, OIDC bearer tokens, and
   * other headers).
   */
  public static final int MAX_HOSTNAMES_HEADER_BYTES = 4096;

  /**
   * Maximum certificate chain length accepted for PKIX validation, matching OpenJDK's {@code
   * jdk.tls.maxCertificateChainLength} default. OpenJDK already enforces this limit during the TLS
   * handshake under {@code SslProvider.JDK}, while BoringSSL ({@code SslProvider.OPENSSL}) only
   * bounds raw byte size ({@code max_cert_list}), so this guards against unbounded PKIX CPU work on
   * untrusted chains across both providers.
   */
  public static final int MAX_CERTIFICATE_CHAIN_LENGTH = 10;

  private static final String UNKNOWN_AUTH_TYPE = "UNKNOWN";

  private static final Supplier<X509TrustManager> DEFAULT_TRUST_MANAGER =
      Suppliers.memoize(X509Utils::loadDefaultTrustManager);

  /** Result of validating a client TLS certificate chain against trusted root CAs. */
  public record CertificateChainValidationResult(Optional<String> failureReason) {

    private static final CertificateChainValidationResult VALID =
        new CertificateChainValidationResult(Optional.empty());

    public CertificateChainValidationResult {
      checkNotNull(failureReason, "failureReason");
      failureReason.ifPresent(
          reason -> checkArgument(!reason.isBlank(), "failureReason must not be blank"));
    }

    public boolean valid() {
      return failureReason.isEmpty();
    }

    public static CertificateChainValidationResult ok() {
      return VALID;
    }

    public static CertificateChainValidationResult failed(String failureReason) {
      return new CertificateChainValidationResult(Optional.of(failureReason));
    }
  }

  /**
   * Parse the encoded certificate and return a base64 encoded string (without padding) of the
   * SHA-256 digest of the certificate.
   *
   * <p>Note that this must match the method used by the GFE to generate the client certificate hash
   * so that the two will match when we check against the allow list.
   */
  public static String getCertificateHash(X509Certificate cert) {
    try {
      MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
      messageDigest.update(cert.getEncoded());
      return base64().omitPadding().encode(messageDigest.digest());
    } catch (CertificateException | NoSuchAlgorithmException e) {
      throw new IllegalArgumentException(e);
    }
  }

  /**
   * Loads an ASCII-armored public X.509 certificate.
   *
   * @throws CertificateParsingException on parsing errors.
   */
  public static X509Certificate loadCertificate(InputStream input)
      throws CertificateParsingException {
    try {
      return CertificateFactory.getInstance("X.509")
          .generateCertificates(input)
          .stream()
          .filter(X509Certificate.class::isInstance)
          .map(X509Certificate.class::cast)
          .collect(onlyElement());
    } catch (CertificateException e) {  // CertificateParsingException by specification.
      throwIfInstanceOf(e, CertificateParsingException.class);
      throw new CertificateParsingException(e);
    } catch (NoSuchElementException e) {
      throw new CertificateParsingException("No X509Certificate found.");
    } catch (IllegalArgumentException e) {
      throw new CertificateParsingException("Multiple X509Certificate found.");
    }
  }

  /**
   * Loads an ASCII-armored public X.509 certificate.
   *
   * @throws CertificateParsingException on parsing errors
   */
  public static X509Certificate loadCertificate(String asciiCrt)
      throws CertificateParsingException {
    return loadCertificate(new ByteArrayInputStream(asciiCrt.getBytes(US_ASCII)));
  }

  /**
   * Loads an ASCII-armored public X.509 certificate.
   *
   * @throws CertificateParsingException on parsing errors
   * @throws IOException on file system errors
   */
  public static X509Certificate loadCertificate(Path certPath)
      throws CertificateParsingException, IOException {
    return loadCertificate(Files.newInputStream(certPath));
  }

  /**
   * Loads an ASCII-armored X.509 certificate revocation list (CRL).
   *
   * @throws CRLException on parsing errors.
   */
  public static X509CRL loadCrl(String asciiCrl) throws GeneralSecurityException {
    ByteArrayInputStream input = new ByteArrayInputStream(asciiCrl.getBytes(US_ASCII));
    try {
      return CertificateFactory.getInstance("X.509")
          .generateCRLs(input)
          .stream()
          .filter(X509CRL.class::isInstance)
          .map(X509CRL.class::cast)
          .collect(onlyElement());
    } catch (NoSuchElementException e) {
      throw new CRLException("No X509CRL found.");
    } catch (IllegalArgumentException e) {
      throw new CRLException("Multiple X509CRL found.");
    }
  }

  /**
   * Check that {@code cert} is signed by the {@code ca} and not revoked.
   *
   * <p>Support for certificate chains has not been implemented.
   *
   * @throws GeneralSecurityException for unsupported protocols, certs not signed by the TMCH,
   *     parsing errors, encoding errors, if the CRL is expired, or if the CRL is older than the one
   *     currently in memory.
   */
  public static void verifyCertificate(
      X509Certificate rootCert, X509CRL crl, @Tainted X509Certificate cert, Instant now)
      throws GeneralSecurityException {
    cert.checkValidity(Date.from(checkNotNull(now, "now")));
    cert.verify(rootCert.getPublicKey());
    if (crl.isRevoked(cert)) {
      X509CRLEntry entry = crl.getRevokedCertificate(cert);
      throw new CertificateRevokedException(
          checkNotNull(entry.getRevocationDate(), "revocationDate"),
          Optional.ofNullable(entry.getRevocationReason()).orElse(CRLReason.UNSPECIFIED),
          firstNonNull(entry.getCertificateIssuer(), crl.getIssuerX500Principal()),
          ImmutableMap.of());
    }
  }

  /**
   * Checks if an X.509 CRL you downloaded can safely replace your current CRL.
   *
   * <p>This routine makes sure {@code newCrl} is signed by {@code rootCert} and that its timestamps
   * are correct with respect to {@code now}.
   *
   * @throws GeneralSecurityException for unsupported protocols, certs not signed by the TMCH,
   *     incorrect keys, and for invalid, old, not-yet-valid or revoked certificates.
   */
  public static void verifyCrl(
      X509Certificate rootCert, @Nullable X509CRL oldCrl, @Tainted X509CRL newCrl, Instant now)
      throws GeneralSecurityException {
    if (oldCrl != null
        && newCrl.getThisUpdate().toInstant().isBefore(oldCrl.getThisUpdate().toInstant())) {
      throw new CRLException(
          String.format(
              "New CRL is more out of date than our current CRL. %s < %s\n%s",
              newCrl.getThisUpdate(), oldCrl.getThisUpdate(), newCrl));
    }
    if (newCrl.getNextUpdate().toInstant().isBefore(now)) {
      throw new CRLException("CRL has expired.\n" + newCrl);
    }
    newCrl.verify(rootCert.getPublicKey());
  }

  /** Constructs an X.509 certificate from a PEM string and encodes it. */
  public static String encodeX509CertificateFromPemString(String certificateString)
      throws CertificateException {
    return encodeX509Certificate(loadCertificate(certificateString));
  }

  /**
   * Encodes an X.509 certificate in the same form that the proxy encodes a certificate before
   * passing it via an HTTP header.
   */
  public static String encodeX509Certificate(X509Certificate certificate)
      throws CertificateEncodingException {
    return Base64.getEncoder().encodeToString(certificate.getEncoded());
  }

  /**
   * Returns a PKIX {@link X509TrustManager} initialized with the JVM's default truststore (loaded
   * once and memoized).
   */
  public static X509TrustManager getDefaultTrustManager() {
    return DEFAULT_TRUST_MANAGER.get();
  }

  private static X509TrustManager loadDefaultTrustManager() {
    try {
      // OpenJDK PKIX disables online CRL/OCSP revocation checking by default when
      // com.sun.net.ssl.checkRevocation is unset/false; relying on this default avoids blocking
      // network I/O on the Netty EventLoop while keeping the code simple and avoiding unnecessary
      // complexity (rather than manually building CertPathTrustManagerParameters).
      TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance("PKIX");
      trustManagerFactory.init((KeyStore) null);
      return Arrays.stream(trustManagerFactory.getTrustManagers())
          .filter(X509TrustManager.class::isInstance)
          .map(X509TrustManager.class::cast)
          .findFirst()
          .orElseThrow(() -> new IllegalStateException("No X509TrustManager found"));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Failed to initialize default PKIX X509TrustManager", e);
    }
  }

  /**
   * Validates the client TLS certificate chain against the given {@link X509TrustManager}.
   *
   * <p>Validates all chains via {@link X509TrustManager#checkServerTrusted} (with authType {@code
   * "UNKNOWN"}) so publicly trusted TLS certificates (including {@code serverAuth}-only leaves such
   * as Let's Encrypt post-2026-07-08, dual-EKU, {@code anyExtendedKeyUsage}, and no-EKU leaves) are
   * accepted under CA/B Forum TLS Baseline Requirements while enforcing OpenJDK's {@code TLSServer}
   * CA-distrust policies and SHA-1 constraints and rejecting {@code clientAuth}-only and non-TLS
   * EKUs. Throws {@link NullPointerException} if {@code trustManager} is null; otherwise never
   * throws.
   */
  public static CertificateChainValidationResult validateCertificateChain(
      @Nullable Certificate[] peerCertificates, X509TrustManager trustManager) {
    checkNotNull(trustManager, "trustManager");
    if (peerCertificates == null || peerCertificates.length == 0) {
      return CertificateChainValidationResult.failed("No peer certificates presented");
    }
    if (peerCertificates.length > MAX_CERTIFICATE_CHAIN_LENGTH) {
      return CertificateChainValidationResult.failed(
          String.format(
              "Certificate chain length %d exceeds maximum of %d",
              peerCertificates.length, MAX_CERTIFICATE_CHAIN_LENGTH));
    }
    if (!(peerCertificates[0] instanceof X509Certificate leafCert)) {
      return CertificateChainValidationResult.failed("Leaf certificate is not an X509Certificate");
    }
    X509Certificate[] x509Chain =
        Arrays.stream(peerCertificates)
            .filter(X509Certificate.class::isInstance)
            .map(X509Certificate.class::cast)
            .toArray(X509Certificate[]::new);
    try {
      if (leafCert.getBasicConstraints() != -1) {
        return CertificateChainValidationResult.failed("Leaf certificate must not be a CA");
      }
      // Explicitly check leaf dates (PKIX builds a 0-length path if the leaf is a trust anchor).
      leafCert.checkValidity();
      trustManager.checkServerTrusted(x509Chain, UNKNOWN_AUTH_TYPE);
      return CertificateChainValidationResult.ok();
    } catch (CertificateException | RuntimeException e) {
      logger.atFine().withCause(e).log("Certificate chain validation did not succeed.");
      String reason = e.toString();
      return CertificateChainValidationResult.failed(
          reason != null && !reason.isBlank() ? reason : e.getClass().getName());
    }
  }

  /**
   * Extracts canonicalized, deduplicated DNS hostnames (excluding wildcard names) from the
   * certificate's Subject Alternative Names, or falls back to the Subject Common Name (CN) only
   * when no SAN extension is present.
   */
  public static ImmutableList<String> extractHostnames(X509Certificate certificate) {
    checkNotNull(certificate, "certificate");
    Collection<List<?>> sanEntries;
    boolean hasSanExtension;
    try {
      sanEntries = certificate.getSubjectAlternativeNames();
      // Per RFC 9525 section 6.2, never fall back to CN when a SAN extension is present (including
      // a degenerate empty-SEQUENCE SAN extension).
      hasSanExtension =
          certificate.getExtensionValue(Extension.subjectAlternativeName.getId()) != null
              || (sanEntries != null && !sanEntries.isEmpty());
    } catch (CertificateParsingException | RuntimeException e) {
      logger.atWarning().withCause(e).log(
          "Failed to parse Subject Alternative Names from certificate.");
      return ImmutableList.of();
    }
    ImmutableList<String> rawNames =
        hasSanExtension ? extractDnsSans(sanEntries) : extractCommonNames(certificate);
    return rawNames.stream()
        .map(X509Utils::normalizeCertificateHostname)
        .flatMap(Optional::stream)
        .distinct()
        .collect(toImmutableList());
  }

  private static ImmutableList<String> extractDnsSans(@Nullable Collection<List<?>> sanEntries) {
    if (sanEntries == null) {
      return ImmutableList.of();
    }
    return sanEntries.stream()
        .filter(
            entry ->
                entry != null
                    && entry.size() >= 2
                    && Integer.valueOf(GeneralName.dNSName).equals(entry.get(0))
                    && entry.get(1) instanceof String)
        .map(entry -> (String) entry.get(1))
        .collect(toImmutableList());
  }

  private static ImmutableList<String> extractCommonNames(X509Certificate certificate) {
    try {
      X500Principal principal = certificate.getSubjectX500Principal();
      if (principal == null) {
        return ImmutableList.of();
      }
      X500Name subject = X500Name.getInstance(principal.getEncoded());
      if (subject == null) {
        return ImmutableList.of();
      }
      return Arrays.stream(subject.getRDNs(BCStyle.CN))
          .flatMap(rdn -> Arrays.stream(rdn.getTypesAndValues()))
          .filter(atv -> BCStyle.CN.equals(atv.getType()))
          .map(AttributeTypeAndValue::getValue)
          .filter(ASN1String.class::isInstance)
          .map(value -> ((ASN1String) value).getString())
          .collect(toImmutableList());
    } catch (RuntimeException e) {
      logger.atWarning().withCause(e).log("Failed to parse Subject Common Name from certificate.");
      return ImmutableList.of();
    }
  }

  /**
   * Formats extracted hostnames as a comma-separated HTTP header value bounded to {@link
   * #MAX_HOSTNAMES_HEADER_BYTES} bytes, truncating only on whole-hostname boundaries. Callers must
   * pass pre-validated, canonicalized (ASCII) hostnames from {@link
   * #extractHostnames(X509Certificate)}. Returns {@link Optional#empty()} if {@code hostnames} is
   * null, empty, or no hostname fits within the limit.
   */
  public static Optional<String> formatHostnamesHeader(
      @Nullable ImmutableList<String> hostnames, @Nullable String certificateHash) {
    if (hostnames == null || hostnames.isEmpty()) {
      return Optional.empty();
    }
    StringBuilder header = new StringBuilder();
    int includedCount = 0;
    boolean truncated = false;
    for (String hostname : hostnames) {
      if (hostname.isEmpty()) {
        continue;
      }
      int separatorLength = header.isEmpty() ? 0 : 1;
      if (header.length() + separatorLength + hostname.length() > MAX_HOSTNAMES_HEADER_BYTES) {
        truncated = true;
        break;
      }
      if (separatorLength > 0) {
        header.append(',');
      }
      header.append(hostname);
      includedCount++;
    }
    if (truncated) {
      String hash = firstNonNull(certificateHash, "unknown");
      if (header.isEmpty()) {
        logger.atWarning().log(
            "Client certificate (hash=%s) omitted %s header: 0 of %d hostname(s) fit within %d"
                + " bytes.",
            hash,
            ProxyHttpHeaders.CERTIFICATE_HOSTNAMES,
            hostnames.size(),
            MAX_HOSTNAMES_HEADER_BYTES);
        return Optional.empty();
      }
      logger.atWarning().log(
          "Client certificate (hash=%s) truncated %s header: kept %d of %d hostnames (%d bytes,"
              + " limit %d bytes).",
          hash,
          ProxyHttpHeaders.CERTIFICATE_HOSTNAMES,
          includedCount,
          hostnames.size(),
          header.length(),
          MAX_HOSTNAMES_HEADER_BYTES);
    }
    return header.isEmpty() ? Optional.empty() : Optional.of(header.toString());
  }

  private static Optional<String> normalizeCertificateHostname(String raw) {
    if (raw == null || raw.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(canonicalizeAndValidateHostname(raw));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private X509Utils() {}
}

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
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Throwables.throwIfInstanceOf;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.io.BaseEncoding.base64;
import static java.nio.charset.StandardCharsets.US_ASCII;

import com.google.common.collect.ImmutableMap;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CRLException;
import java.security.cert.CRLReason;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateParsingException;
import java.security.cert.CertificateRevokedException;
import java.security.cert.X509CRL;
import java.security.cert.X509CRLEntry;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.NoSuchElementException;
import java.util.Optional;
import javax.annotation.Tainted;

/** X.509 Public Key Infrastructure (PKI) helper functions. */
public final class X509Utils {

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
   *         parsing errors, encoding errors, if the CRL is expired, or if the CRL is older than the
   *         one currently in memory.
   */
  public static void verifyCertificate(
      X509Certificate rootCert, X509CRL crl, @Tainted X509Certificate cert, Date now)
          throws GeneralSecurityException {
    cert.checkValidity(checkNotNull(now, "now"));
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
   *         incorrect keys, and for invalid, old, not-yet-valid or revoked certificates.
   */
  public static void verifyCrl(
      X509Certificate rootCert, X509CRL oldCrl, @Tainted X509CRL newCrl, Date now)
      throws GeneralSecurityException {
    if (newCrl.getThisUpdate().before(oldCrl.getThisUpdate())) {
      throw new CRLException(String.format(
          "New CRL is more out of date than our current CRL. %s < %s\n%s",
          newCrl.getThisUpdate(), oldCrl.getThisUpdate(), newCrl));
    }
    if (newCrl.getNextUpdate().before(now)) {
      throw new CRLException("CRL has expired.\n" + newCrl);
    }
    newCrl.verify(rootCert.getPublicKey());
  }

  private X509Utils() {}
}

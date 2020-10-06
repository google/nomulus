// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableSet;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import org.joda.time.DateTime;
import org.joda.time.Days;

/** An utility to check that a given certificate meets our requirements */
public class CertificateChecker {

  private final int maxValidityDays;
  private final int daysToExpiration;
  private final int minimumRsaKeyLength;
  public final CertificateViolation certificateExpiredViolation;
  public final CertificateViolation certificateNotYetValidViolation;
  public final CertificateViolation certificateValidityLengthViolation;
  public final CertificateViolation certificateOldValidityLengthValidViolation;
  public final CertificateViolation certificateRsaKeyLengthViolation;
  public final CertificateViolation certificateAlgorithmViolation;

  public CertificateChecker(int maxValidityDays, int daysToExpiration, int minimumRsaKeyLength) {
    this.maxValidityDays = maxValidityDays;
    this.daysToExpiration = daysToExpiration;
    this.minimumRsaKeyLength = minimumRsaKeyLength;
    this.certificateExpiredViolation =
        CertificateViolation.create("Expired Certificate", "This certificate is expired.");
    this.certificateNotYetValidViolation =
        CertificateViolation.create(
            "Not Yet Valid", "This certificate's start date has not yet passed.");
    this.certificateOldValidityLengthValidViolation =
        CertificateViolation.create(
            "Validity Period Too Long",
            String.format(
                "The certificate's validity length must be less than or equal to %d days, or %d"
                    + " days if issued prior to 2020-09-01.",
                maxValidityDays, 825));
    this.certificateValidityLengthViolation =
        CertificateViolation.create(
            "Validity Period Too Long",
            String.format(
                "The certificate must have a validity length of less than %d days.",
                maxValidityDays));
    this.certificateRsaKeyLengthViolation =
        CertificateViolation.create(
            "RSA Key Length Too Long",
            String.format("The minimum RSA key length is %d.", minimumRsaKeyLength));
    this.certificateAlgorithmViolation =
        CertificateViolation.create(
            "Certificate Algorithm Not Allowed", "Only RSA and ECDSA keys are accepted.");
  }

  /**
   * Checks a certificate for violations and returns a list of all the violations the certificate
   * has.
   */
  public ImmutableSet<CertificateViolation> checkCertificate(
      X509Certificate certificate, Date now) {
    ImmutableSet.Builder<CertificateViolation> violations = new ImmutableSet.Builder<>();

    // Check Expiration
    if (certificate.getNotAfter().before(now)) {
      violations.add(certificateExpiredViolation);
    } else if (certificate.getNotBefore().after(now)) {
      violations.add(certificateNotYetValidViolation);
    }
    int validityLength = getValidityLengthInDays(certificate);
    if (validityLength > maxValidityDays) {
      if (new DateTime(certificate.getNotBefore())
          .isBefore(DateTime.parse("2020-09-01T00:00:00Z"))) {
        if (validityLength > 825) {
          violations.add(certificateOldValidityLengthValidViolation);
        }
      } else {
        violations.add(certificateValidityLengthViolation);
      }
    }

    // Check Key Strengths
    PublicKey key = certificate.getPublicKey();
    if (key.getAlgorithm().equals("RSA")) {
      RSAPublicKey rsaPublicKey = (RSAPublicKey) key;
      if (rsaPublicKey.getModulus().bitLength() < minimumRsaKeyLength) {
        violations.add(certificateRsaKeyLengthViolation);
      }
    } else if (key.getAlgorithm().equals("EC")) {
      // TODO(sarahbot): Add verification of ECDSA curves
    } else {
      violations.add(certificateAlgorithmViolation);
    }
    return violations.build();
  }

  /** Returns true if the certificate is nearing expiration. */
  public boolean isNearingExpiration(X509Certificate certificate, Date now) {
    Date nearingExpirationDate =
        DateTime.parse(certificate.getNotAfter().toInstant().toString())
            .minusDays(daysToExpiration)
            .toDate();
    return now.after(nearingExpirationDate);
  }

  private static int getValidityLengthInDays(X509Certificate certificate) {
    DateTime start = DateTime.parse(certificate.getNotBefore().toInstant().toString());
    DateTime end = DateTime.parse(certificate.getNotAfter().toInstant().toString());
    return Days.daysBetween(start.withTimeAtStartOfDay(), end.withTimeAtStartOfDay()).getDays();
  }
}

/**
 * The type of violation a certificate has based on the certificate requirements
 * (go/registry-proxy-security).
 */
@AutoValue
abstract class CertificateViolation {

  public abstract String name();

  public abstract String displayMessage();

  public static CertificateViolation create(String name, String displayMessage) {
    return new AutoValue_CertificateViolation(name, displayMessage);
  }
}

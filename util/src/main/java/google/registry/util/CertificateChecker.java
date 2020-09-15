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

import com.google.common.collect.ImmutableSet;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECFieldFp;
import java.util.Date;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.spec.ECParameterSpec;
import org.joda.time.DateTime;
import org.joda.time.Days;

/** An utility to check that a given certificate meets our requirements */
public class CertificateChecker {

  private final int maxValidityDays;
  private final int daysToExpiration;
  private final int minimumRsaKeyLength;

  public CertificateChecker(int maxValidityDays, int daysToExpiration, int minimumRsaKeyLength) {
    this.maxValidityDays = maxValidityDays;
    this.daysToExpiration = daysToExpiration;
    this.minimumRsaKeyLength = minimumRsaKeyLength;
  }

  /**
   * The type of violation a certificate has based on the certificate requirements
   * (go/registry-proxy-security).
   */
  public enum CertificateViolation {
    EXPIRED,
    NEARING_EXPIRATION, // less than 30 days to expiration
    RSA_KEY_LENGTH_TOO_SMALL, // key length is too low
    ELLIPTIC_CURVE_NOT_ALLOWED, // uses a curve other than P-256
    NOT_YET_VALID, // certificate start date has not passed yet
    VALIDITY_PERIOD_TOO_LONG, // validity length is too long
    DISALLOWED_CERTIFICATE_ALGORITHM // certificate is not RSA or ECDSA
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
      violations.add(CertificateViolation.EXPIRED);
    } else {
      if (isNearingExpiration(certificate, now, daysToExpiration)) {
        violations.add(CertificateViolation.NEARING_EXPIRATION);
      }
      if (certificate.getNotBefore().after(now)) {
        violations.add(CertificateViolation.NOT_YET_VALID);
      }
    }
    if (!isValidityLengthValid(certificate, maxValidityDays)) {
      violations.add(CertificateViolation.VALIDITY_PERIOD_TOO_LONG);
    }

    // Check Key Strengths
    PublicKey key = certificate.getPublicKey();
    switch (key.getAlgorithm()) {
      case "RSA":
        RSAPublicKey rsaPublicKey = (RSAPublicKey) key;
        if (rsaPublicKey.getModulus().bitLength() < minimumRsaKeyLength) {
          violations.add(CertificateViolation.RSA_KEY_LENGTH_TOO_SMALL);
        }
        break;
      case "EC":
        if (!isEcCurveTypeValid((ECPublicKey) key)) {
          violations.add(CertificateViolation.ELLIPTIC_CURVE_NOT_ALLOWED);
        }
        break;
      default:
        violations.add(CertificateViolation.DISALLOWED_CERTIFICATE_ALGORITHM);
        break;
    }
    return violations.build();
  }

  /** Returns true if the validity period is 825 days or less. */
  private static boolean isValidityLengthValid(X509Certificate certificate, int maxValidityDays) {
    DateTime start = DateTime.parse(certificate.getNotBefore().toInstant().toString());
    DateTime end = DateTime.parse(certificate.getNotAfter().toInstant().toString());
    return Days.daysBetween(start.withTimeAtStartOfDay(), end.withTimeAtStartOfDay())
        .isLessThan(Days.days(maxValidityDays));
  }

  /** Returns true if the certificate is less than 30 days from expiration. */
  private static boolean isNearingExpiration(
      X509Certificate certificate, Date now, int daysToExpiration) {
    Date nearingExpirationDate =
        DateTime.parse(certificate.getNotAfter().toInstant().toString())
            .minusDays(daysToExpiration)
            .toDate();
    return now.after(nearingExpirationDate);
  }

  /** Returns true if a P-256 curve is used. */
  private static boolean isEcCurveTypeValid(ECPublicKey key) {
    ECParameterSpec spec = key.getParameters();
    if (spec != null) {
      ECFieldFp field = new ECFieldFp(spec.getCurve().getField().getCharacteristic());
      return spec.getCurve().getOrder().bitLength() == 256 && field.getP().isProbablePrime(1);
    }
    return false; // Return false if we were unable to determine the curve
  }
}

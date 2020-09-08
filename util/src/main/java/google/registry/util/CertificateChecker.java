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
import java.security.interfaces.DSAParams;
import java.security.interfaces.DSAPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.spec.ECParameterSpec;
import org.joda.time.DateTime;
import org.joda.time.Days;

/** An utility to check that a given certificate meets our requirements */
public class CertificateChecker {

  /**
   * The type of violation a certificate has based on the certificate requirements
   * (go/registry-proxy-security).
   */
  public enum CertificateViolation {
    EXPIRED,
    NEARING_EXPIRATION, // less than 30 days to expiration
    RSA_KEY_LENGTH, // key length is too low
    EC_KEY_LENGTH, // key length is too low
    DSA_KEY_LENGTH, // key length is too low
    NOT_YET_VALID, // certificate start date has not passed yet
    VALIDITY_LENGTH // validity length is too long
  }

  /**
   * Checks a certificate for violations and returns a list of all the violations the certificate
   * has.
   */
  public static ImmutableSet<CertificateViolation> checkCertificate(
      X509Certificate certificate, Date now) {
    ImmutableSet.Builder<CertificateViolation> violations = ImmutableSet.builder();

    // Check Expiration
    if (isExpired(certificate, now)) {
      violations.add(CertificateViolation.EXPIRED);
    } else {
      if (!isValidNow(certificate, now)) {
        violations.add(CertificateViolation.NOT_YET_VALID);
      }
      if (!checkValidityLength(certificate)) {
        violations.add(CertificateViolation.VALIDITY_LENGTH);
      }
      if (isNearingExpiration(certificate, now)) {
        violations.add(CertificateViolation.NEARING_EXPIRATION);
      }
    }

    // Check Key Lengths
    PublicKey key = certificate.getPublicKey();
    switch (key.getAlgorithm()) {
      case "RSA":
        if (!checkRsaKeyLength((RSAPublicKey) key)) {
          violations.add(CertificateViolation.RSA_KEY_LENGTH);
        }
        break;
      case "EC":
        if (!checkEcKeyLength((ECPublicKey) key)) {
          violations.add(CertificateViolation.EC_KEY_LENGTH);
        }
        break;
      case "DSA":
        if (!checkDsaKeyLength((DSAPublicKey) key)) {
          violations.add(CertificateViolation.DSA_KEY_LENGTH);
        }
        break;
      default:
        break;
    }

    return violations.build();
  }

  private static boolean isExpired(X509Certificate certificate, Date now) {
    return certificate.getNotAfter().before(now);
  }

  private static boolean isValidNow(X509Certificate certificate, Date now) {
    return certificate.getNotBefore().before(now);
  }

  /** Returns true if the validity period is 825 days or less. */
  private static boolean checkValidityLength(X509Certificate certificate) {
    DateTime start = DateTime.parse(certificate.getNotBefore().toInstant().toString());
    DateTime end = DateTime.parse(certificate.getNotAfter().toInstant().toString());
    return Days.daysBetween(start.withTimeAtStartOfDay(), end.withTimeAtStartOfDay())
        .isLessThan(Days.days(826));
  }

  /** Returns true if the certificate is less than 30 days from expiration. */
  private static boolean isNearingExpiration(X509Certificate certificate, Date now) {
    Date nearingExpirationDate =
        DateTime.parse(certificate.getNotAfter().toInstant().toString()).minusDays(30).toDate();
    return now.after(nearingExpirationDate);
  }

  /** Returns true if the key length is greater than or equal to 2048. */
  private static boolean checkRsaKeyLength(RSAPublicKey key) {
    return key.getModulus().bitLength() >= 2048;
  }

  /** Returns true if the key length is greater than or equal to 256. */
  private static boolean checkEcKeyLength(ECPublicKey key) {
    ECParameterSpec spec = key.getParameters();
    if (spec != null) {
      return spec.getCurve().getOrder().bitLength() >= 256;
    }
    return false; // Return false if we were unable to determine the key length
  }

  /** Returns true if the key length is greater than or equal to 2048. */
  private static boolean checkDsaKeyLength(DSAPublicKey key) {
    DSAParams spec = key.getParams();
    if (spec != null) {
      return spec.getP().bitLength() >= 2048;
    }
    return key.getY().bitLength() >= 2048;
  }
}

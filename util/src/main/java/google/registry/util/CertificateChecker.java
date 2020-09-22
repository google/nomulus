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
import java.util.Date;
import java.util.Objects;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.spec.ECParameterSpec;
import org.joda.time.DateTime;
import org.joda.time.Days;

/** An utility to check that a given certificate meets our requirements */
public class CertificateChecker {

  private final int maxValidityDays;
  private final int daysToExpiration;
  private final int minimumRsaKeyLength;
  private final ImmutableSet<Integer> curves;

  public CertificateChecker(
      int maxValidityDays,
      int daysToExpiration,
      int minimumRsaKeyLength,
      ImmutableSet<Integer> curves) {
    this.maxValidityDays = maxValidityDays;
    this.daysToExpiration = daysToExpiration;
    this.minimumRsaKeyLength = minimumRsaKeyLength;
    this.curves = curves;
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
      violations.add(new ExpiredCertificateViolation());
    } else if (certificate.getNotBefore().after(now)) {
      violations.add(new NotYetValidViolation());
    }
    int validityLength = getValidityLength(certificate);
    if (validityLength > maxValidityDays) {
      violations.add(new ValidityPeriodViolation(maxValidityDays, validityLength));
    }

    // Check Key Strengths
    PublicKey key = certificate.getPublicKey();
    if (key.getAlgorithm().equals("RSA")) {
      RSAPublicKey rsaPublicKey = (RSAPublicKey) key;
      if (rsaPublicKey.getModulus().bitLength() < minimumRsaKeyLength) {
        violations.add(
            new RsaKeyLengthViolation(minimumRsaKeyLength, rsaPublicKey.getModulus().bitLength()));
      }
    } else if (key.getAlgorithm().equals("EC")) {
      if (!isEcCurveTypeValid((ECPublicKey) key, curves)) {
        violations.add(new EllipticCurveViolation());
      }
    } else {
      violations.add(new CertificateAlgorithmViolation());
    }
    return violations.build();
  }

  /** Returns true if the certificate is nearing expiration. */
  public boolean checkNearingExpiration(X509Certificate certificate, Date now) {
    Date nearingExpirationDate =
        DateTime.parse(certificate.getNotAfter().toInstant().toString())
            .minusDays(daysToExpiration)
            .toDate();
    return now.after(nearingExpirationDate);
  }

  private static int getValidityLength(X509Certificate certificate) {
    DateTime start = DateTime.parse(certificate.getNotBefore().toInstant().toString());
    DateTime end = DateTime.parse(certificate.getNotAfter().toInstant().toString());
    return Days.daysBetween(start.withTimeAtStartOfDay(), end.withTimeAtStartOfDay()).getDays();
  }

  /** Returns true if a supported curve is used. */
  private static boolean isEcCurveTypeValid(ECPublicKey key, ImmutableSet<Integer> curves) {
    ECParameterSpec spec = key.getParameters();
    if (spec != null) {
      // a dimension value of 1 indicates the curve is over a prime field
      if (spec.getCurve().getField().getDimension() != 1) {
        return false;
      }
      return curves.contains(spec.getCurve().getOrder().bitLength());
    }
    return false; // Return false if we were unable to determine the curve
  }

  /**
   * The type of violation a certificate has based on the certificate requirements
   * (go/registry-proxy-security).
   */
  public abstract static class CertificateViolation {

    private final String name;
    private final String displayMessage;

    public String getName() {
      return name;
    }

    public String getDisplayMessage() {
      return displayMessage;
    }

    CertificateViolation(String name, String displayMessage) {
      this.name = name;
      this.displayMessage = displayMessage;
    }

    @Override
    public boolean equals(Object violation) {
      if (this == violation) {
        return true;
      }
      if (violation == null || !getClass().isInstance(violation)) {
        return false;
      }
      CertificateViolation certificateViolation = (CertificateViolation) violation;
      return name.equals(certificateViolation.name)
          && displayMessage.equals(certificateViolation.displayMessage);
    }

    @Override
    public int hashCode() {
      return Objects.hash(name, displayMessage);
    }
  }

  public static class ExpiredCertificateViolation extends CertificateViolation {
    ExpiredCertificateViolation() {
      super("Expired Certificate", "This certificate is expired.");
    }
  }

  public static class ValidityPeriodViolation extends CertificateViolation {
    int validityLength;

    ValidityPeriodViolation(int maxValidityDays, int validityLength) {
      super(
          "Validity Period Too Long",
          String.format(
              "The certificate must have a validity length of less than %d days. This certificate"
                  + " has a validity length of %d days.",
              maxValidityDays, validityLength));
      this.validityLength = validityLength;
    }

    int getValidityLength() {
      return validityLength;
    }
  }

  public static class NotYetValidViolation extends CertificateViolation {
    NotYetValidViolation() {
      super("Not Yet Valid", String.format("This certificate's start date has not yet passed."));
    }
  }

  public static class RsaKeyLengthViolation extends CertificateViolation {
    int keyLength;

    RsaKeyLengthViolation(int minimumRsaKeyLength, int keyLength) {
      super(
          "RSA Key Length Too Long",
          String.format(
              "The minimum RSA key length is %d. This certificate has a key length of %d.",
              minimumRsaKeyLength, keyLength));
      this.keyLength = keyLength;
    }

    int getKeyLength() {
      return keyLength;
    }
  }

  public static class EllipticCurveViolation extends CertificateViolation {
    EllipticCurveViolation() {
      super(
          "Elliptic Curve Not Allowed",
          String.format("This certificate uses an unsupported elliptic curve."));
    }
  }

  public static class CertificateAlgorithmViolation extends CertificateViolation {
    CertificateAlgorithmViolation() {
      super(
          "Certificate Algorithm Not Allowed",
          String.format("Only RSA and ECDSA keys are accepted."));
    }
  }
}

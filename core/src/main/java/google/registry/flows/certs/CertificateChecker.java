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

package google.registry.flows.certs;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.config.RegistryConfig.Config;
import google.registry.util.Clock;
import google.registry.util.DateTimeUtils;
import java.io.ByteArrayInputStream;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.bouncycastle.jcajce.provider.asymmetric.util.EC5Util;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.joda.time.DateTime;
import org.joda.time.Days;

/** An utility to check that a given certificate meets our requirements */
public class CertificateChecker {

  private final ImmutableSortedMap<DateTime, Integer> maxValidityLengthSchedule;
  private final int daysToExpiration;
  private final int minimumRsaKeyLength;
  private final Clock clock;
  private final ImmutableSet<String> allowedEcdsaCurves;

  /**
   * Constructs a CertificateChecker instance with the specified configuration parameters.
   *
   * <p>The max validity length schedule is a sorted map of {@link DateTime} to {@link Integer}
   * entries representing a maximum validity period for certificates issued on or after that date.
   * The first entry must have a key of {@link DateTimeUtils#START_OF_TIME}, such that every
   * possible date has an applicable max validity period. Since security requirements tighten over
   * time, the max validity periods will be decreasing as the date increases.
   *
   * <p>The validity length schedule used by all major Web browsers as of 2020Q4 would be
   * represented as:
   *
   * <pre>
   *   ImmutableSortedMap.of(
   *     START_OF_TIME, 825,
   *     DateTime.parse("2020-09-01T00:00:00Z"), 398
   *   );
   * </pre>
   */
  @Inject
  public CertificateChecker(
      @Config("maxValidityDaysSchedule")
          ImmutableSortedMap<DateTime, Integer> maxValidityDaysSchedule,
      @Config("expirationWarningDays") int expirationWarningDays,
      @Config("minimumRsaKeyLength") int minimumRsaKeyLength,
      @Config("allowedEcdsaCurves") ImmutableSet<String> allowedEcdsaCurves,
      Clock clock) {
    checkArgument(
        maxValidityDaysSchedule.containsKey(START_OF_TIME),
        "Max validity length schedule must contain an entry for START_OF_TIME");
    this.maxValidityLengthSchedule = maxValidityDaysSchedule;
    this.daysToExpiration = expirationWarningDays;
    this.minimumRsaKeyLength = minimumRsaKeyLength;
    this.allowedEcdsaCurves = allowedEcdsaCurves;
    this.clock = clock;
  }

  /**
   * Checks the given certificate string for violations and throws an exception if any violations
   * exist.
   */
  public void validateCertificate(String certificateString) throws InsecureCertificateException {
    handleCertViolations(checkCertificate(certificateString));
  }

  /**
   * Checks the given certificate string for violations and throws an exception if any violations
   * exist.
   */
  public void validateCertificate(X509Certificate certificate) throws InsecureCertificateException {
    handleCertViolations(checkCertificate(certificate));
  }

  private void handleCertViolations(ImmutableSet<CertificateViolation> violations)
      throws InsecureCertificateException {
    if (!violations.isEmpty()) {
      String displayMessages =
          violations.stream()
              .map(violation -> getViolationDisplayMessage(violation))
              .collect(Collectors.joining("\n"));
      throw new InsecureCertificateException(violations, displayMessages);
    }
  }

  /**
   * Checks a given certificate for violations and returns a list of all the violations the
   * certificate has.
   */
  public ImmutableSet<CertificateViolation> checkCertificate(X509Certificate certificate) {
    ImmutableSet.Builder<CertificateViolation> violations = new ImmutableSet.Builder<>();

    // Check if currently in validity period
    Date now = clock.nowUtc().toDate();
    if (certificate.getNotAfter().before(now)) {
      violations.add(CertificateViolation.EXPIRED);
    } else if (certificate.getNotBefore().after(now)) {
      violations.add(CertificateViolation.NOT_YET_VALID);
    }

    // Check validity period length
    int maxValidityDays =
        maxValidityLengthSchedule.floorEntry(new DateTime(certificate.getNotBefore())).getValue();
    if (getValidityLengthInDays(certificate) > maxValidityDays) {
      violations.add(CertificateViolation.VALIDITY_LENGTH_TOO_LONG);
    }

    // Check key strengths
    PublicKey key = certificate.getPublicKey();
    if (key.getAlgorithm().equals("RSA")) {
      RSAPublicKey rsaPublicKey = (RSAPublicKey) key;
      if (rsaPublicKey.getModulus().bitLength() < minimumRsaKeyLength) {
        violations.add(CertificateViolation.RSA_KEY_LENGTH_TOO_SHORT);
      }
    } else if (key.getAlgorithm().equals("EC")) {
      if (!checkCurveName(key, allowedEcdsaCurves)) {
        violations.add(CertificateViolation.INVALID_ECDSA_CURVE);
      }
    } else {
      violations.add(CertificateViolation.ALGORITHM_CONSTRAINED);
    }
    return violations.build();
  }

  /**
   * Converts a given string to a certificate and checks it for violations, returning a list of all
   * the violations the certificate has.
   */
  public ImmutableSet<CertificateViolation> checkCertificate(String certificateString) {
    X509Certificate certificate;

    try {
      certificate =
          (X509Certificate)
              CertificateFactory.getInstance("X509")
                  .generateCertificate(new ByteArrayInputStream(certificateString.getBytes(UTF_8)));
    } catch (CertificateException e) {
      throw new IllegalArgumentException("Unable to read given certificate.");
    }

    return checkCertificate(certificate);
  }

  /**
   * Returns whether the certificate is nearing expiration.
   *
   * <p>Note that this is <i>all</i> that it checks. The certificate itself may well be expired or
   * not yet valid and this message will still return false. So you definitely want to pair a call
   * to this method with a call to {@link #checkCertificate} to determine other issues with the
   * certificate that may be occurring.
   */
  public boolean isNearingExpiration(X509Certificate certificate) {
    Date nearingExpirationDate =
        DateTime.parse(certificate.getNotAfter().toInstant().toString())
            .minusDays(daysToExpiration)
            .toDate();
    return clock.nowUtc().toDate().after(nearingExpirationDate);
  }

  private static int getValidityLengthInDays(X509Certificate certificate) {
    DateTime start = DateTime.parse(certificate.getNotBefore().toInstant().toString());
    DateTime end = DateTime.parse(certificate.getNotAfter().toInstant().toString());
    return Days.daysBetween(start.withTimeAtStartOfDay(), end.withTimeAtStartOfDay()).getDays();
  }

  /** Checks if the curve used for a public key is in the list of acceptable curves. */
  private static boolean checkCurveName(PublicKey key, ImmutableSet<String> allowedEcdsaCurves) {
    org.bouncycastle.jce.spec.ECParameterSpec params;
    // These 2 different instances of PublicKey need to be handled separately since their OIDs are
    // encoded differently. More details on this can be found at
    // https://stackoverflow.com/questions/49895713/how-to-find-the-matching-curve-name-from-an-ecpublickey.
    if (key instanceof ECPublicKey) {
      ECPublicKey ecKey = (ECPublicKey) key;
      params = EC5Util.convertSpec(ecKey.getParams(), false);
    } else if (key instanceof org.bouncycastle.jce.interfaces.ECPublicKey) {
      org.bouncycastle.jce.interfaces.ECPublicKey ecKey =
          (org.bouncycastle.jce.interfaces.ECPublicKey) key;
      params = ecKey.getParameters();
    } else {
      throw new IllegalArgumentException("Unrecognized instance of PublicKey.");
    }
    return allowedEcdsaCurves.stream()
        .anyMatch(
            curve -> {
              ECNamedCurveParameterSpec cParams = ECNamedCurveTable.getParameterSpec(curve);
              return cParams.getN().equals(params.getN())
                  && cParams.getH().equals(params.getH())
                  && cParams.getCurve().equals(params.getCurve())
                  && cParams.getG().equals(params.getG());
            });
  }

  private String getViolationDisplayMessage(CertificateViolation certificateViolation) {
    // Yes, we'd rather do this as an instance method on the CertificateViolation enum itself, but
    // we can't because we need access to configuration (injected as instance variables) which you
    // can't get in a static enum context.
    switch (certificateViolation) {
      case EXPIRED:
        return "Certificate is expired.";
      case NOT_YET_VALID:
        return "Certificate start date is in the future.";
      case ALGORITHM_CONSTRAINED:
        return "Certificate key algorithm must be RSA or ECDSA.";
      case RSA_KEY_LENGTH_TOO_SHORT:
        return String.format(
            "RSA key length is too short; the minimum allowed length is %d bits.",
            this.minimumRsaKeyLength);
      case VALIDITY_LENGTH_TOO_LONG:
        return String.format(
            "Certificate validity period is too long; it must be less than or equal to %d days.",
            this.maxValidityLengthSchedule.lastEntry().getValue());
      case INVALID_ECDSA_CURVE:
        return String.format(
            "The ECDSA key must use one of these algorithms: %s", allowedEcdsaCurves);
      default:
        throw new IllegalArgumentException(
            String.format(
                "Unknown CertificateViolation enum value: %s", certificateViolation.name()));
    }
  }

  /**
   * The type of violation a certificate has based on the certificate requirements
   * (go/registry-proxy-security).
   */
  public enum CertificateViolation {
    EXPIRED,
    NOT_YET_VALID,
    VALIDITY_LENGTH_TOO_LONG,
    RSA_KEY_LENGTH_TOO_SHORT,
    ALGORITHM_CONSTRAINED,
    INVALID_ECDSA_CURVE;

    /**
     * Gets a suitable end-user-facing display message for this particular certificate violation.
     *
     * <p>Note that the {@link CertificateChecker} instance must be passed in because it contains
     * configuration values (e.g. minimum RSA key length) that go into the error message text.
     */
    public String getDisplayMessage(CertificateChecker certificateChecker) {
      return certificateChecker.getViolationDisplayMessage(this);
    }
  }

  /** Exception to throw when a certificate has security violations. */
  public static class InsecureCertificateException extends Exception {
    ImmutableSet<CertificateViolation> violations;

    InsecureCertificateException(ImmutableSet<CertificateViolation> violations, String message) {
      super(message);
      this.violations = violations;
    }
  }
}

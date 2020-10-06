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

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.collect.ImmutableMap;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Random;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/** A self-signed certificate authority (CA) cert for use in tests. */
// TODO(weiminyu): make this class test-only. Requires refactor in proxy and prober.
public class SelfSignedCaCertificate {

  private static final String DEFAULT_ISSUER_FQDN = "registry-test";
  private static final Date DEFAULT_NOT_BEFORE =
      Date.from(Instant.now().minus(Duration.ofHours(1)));
  private static final Date DEFAULT_NOT_AFTER = Date.from(Instant.now().plus(Duration.ofDays(1)));

  private static final Random RANDOM = new Random();
  private static final BouncyCastleProvider PROVIDER = new BouncyCastleProvider();
  private static final KeyPairGenerator keyGen = createKeyPairGenerator();
  private static final ImmutableMap<String, String> KEY_SIGNATURE_ALGS =
      ImmutableMap.of(
          "EC", "SHA256WithECDSA", "DSA", "SHA256WithDSA", "RSA", "SHA256WithRSAEncryption");

  private final PrivateKey privateKey;
  private final X509Certificate cert;

  public SelfSignedCaCertificate(PrivateKey privateKey, X509Certificate cert) {
    this.privateKey = privateKey;
    this.cert = cert;
  }

  public PrivateKey key() {
    return privateKey;
  }

  public X509Certificate cert() {
    return cert;
  }

  public static SelfSignedCaCertificate create() throws Exception {
    return create(
        keyGen.generateKeyPair(), DEFAULT_ISSUER_FQDN, DEFAULT_NOT_BEFORE, DEFAULT_NOT_AFTER);
  }

  public static SelfSignedCaCertificate create(String fqdn) throws Exception {
    return create(fqdn, DEFAULT_NOT_BEFORE, DEFAULT_NOT_AFTER);
  }

  public static SelfSignedCaCertificate create(String fqdn, Date from, Date to) throws Exception {
    return create(keyGen.generateKeyPair(), fqdn, from, to);
  }

  public static SelfSignedCaCertificate create(KeyPair keyPair, String fqdn, Date from, Date to)
      throws Exception {
    return new SelfSignedCaCertificate(keyPair.getPrivate(), createCaCert(keyPair, fqdn, from, to));
  }

  static KeyPairGenerator createKeyPairGenerator() {
    try {
      KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA", PROVIDER);
      keyGen.initialize(2048, new SecureRandom());
      return keyGen;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /** Returns a self-signed Certificate Authority (CA) certificate. */
  static X509Certificate createCaCert(KeyPair keyPair, String fqdn, Date from, Date to)
      throws Exception {
    X500Name owner = new X500Name("CN=" + fqdn);
    String publicKeyAlg = keyPair.getPublic().getAlgorithm();
    checkArgument(KEY_SIGNATURE_ALGS.containsKey(publicKeyAlg), "Unexpected public key algorithm");
    String signatureAlgorithm = KEY_SIGNATURE_ALGS.get(publicKeyAlg);
    ContentSigner signer =
        new JcaContentSignerBuilder(signatureAlgorithm).build(keyPair.getPrivate());
    X509v3CertificateBuilder builder =
        new JcaX509v3CertificateBuilder(
            owner, new BigInteger(64, RANDOM), from, to, owner, keyPair.getPublic());

    // Mark cert as CA by adding basicConstraint with cA=true to the builder
    BasicConstraints basicConstraints = new BasicConstraints(true);
    builder.addExtension(new ASN1ObjectIdentifier("2.5.29.19"), true, basicConstraints);

    X509CertificateHolder certHolder = builder.build(signer);
    return new JcaX509CertificateConverter().setProvider(PROVIDER).getCertificate(certHolder);
  }
}

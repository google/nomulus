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

package google.registry.tmch;

import static google.registry.config.RegistryConfig.ConfigModule.TmchCaMode.PILOT;
import static google.registry.config.RegistryConfig.ConfigModule.TmchCaMode.PRODUCTION;
import static google.registry.config.RegistryConfig.getSingletonCacheRefreshDuration;
import static google.registry.util.ResourceUtils.readResourceUtf8;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import google.registry.config.RegistryConfig.Config;
import google.registry.config.RegistryConfig.ConfigModule.TmchCaMode;
import google.registry.model.tmch.TmchCrl;
import google.registry.util.Clock;
import google.registry.util.X509Utils;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

/**
 * Helper methods for accessing ICANN's TMCH root certificate and revocation list.
 *
 * <p>There are two CRLs, a real one for the production environment and a pilot one for
 * non-production environments. The Datastore singleton {@link TmchCrl} entity is used to cache this
 * CRL once loaded and will always contain the proper one corresponding to the environment.
 *
 * <p>The CRTs do not change and are included as files in the codebase that are not refreshed. They
 * were downloaded from https://ca.icann.org/tmch.crt and https://ca.icann.org/tmch_pilot.crt
 */
@Immutable
@ThreadSafe
public final class TmchCertificateAuthority {

  private static final String ROOT_CRT_FILE = "icann-tmch.crt";
  private static final String ROOT_CRT_PILOT_FILE = "icann-tmch-pilot.crt";
  private static final String CRL_FILE = "icann-tmch.crl";
  private static final String CRL_PILOT_FILE = "icann-tmch-pilot.crl";

  private final TmchCaMode tmchCaMode;
  private final Clock clock;

  @Inject
  public TmchCertificateAuthority(@Config("tmchCaMode") TmchCaMode tmchCaMode, Clock clock) {
    this.tmchCaMode = tmchCaMode;
    this.clock = clock;
  }

  /**
   * A cached supplier that loads the CRL from Datastore or chooses a default value.
   *
   * <p>We keep the cache here rather than caching TmchCrl in the model, because loading the CRL
   * string into an X509CRL instance is expensive and should itself be cached.
   *
   * <p>Note that the stored CRL won't exist for tests, and on deployed environments will always
   * correspond to the correct CRL for the given TMCH CA mode because {@link TmchCrlAction} can only
   * persist the correct one for this given environment.
   */
  private static final LoadingCache<TmchCaMode, X509CRL> CRL_CACHE =
      CacheBuilder.newBuilder()
          .expireAfterWrite(
              java.time.Duration.ofMillis(getSingletonCacheRefreshDuration().getMillis()))
          .build(
              new CacheLoader<TmchCaMode, X509CRL>() {
                @Override
                public X509CRL load(final TmchCaMode tmchCaMode) throws GeneralSecurityException {
                  TmchCrl storedCrl = TmchCrl.get();
                  String crlContents;
                  if (storedCrl == null) {
                    String file = (tmchCaMode == PILOT) ? CRL_PILOT_FILE : CRL_FILE;
                    crlContents = readResourceUtf8(TmchCertificateAuthority.class, file);
                  } else {
                    crlContents = storedCrl.getCrl();
                  }
                  X509CRL crl = X509Utils.loadCrl(crlContents);
                  crl.verify(ROOT_CERTS.get(tmchCaMode).getPublicKey());
                  return crl;
                }
              });

  /** CRTs from a jar resource. */
  private static final ImmutableMap<TmchCaMode, X509Certificate> ROOT_CERTS =
      loadRootCertificates();

  private static ImmutableMap<TmchCaMode, X509Certificate> loadRootCertificates() {
    try {
      return ImmutableMap.of(
          PILOT,
          X509Utils.loadCertificate(
              readResourceUtf8(TmchCertificateAuthority.class, ROOT_CRT_PILOT_FILE)),
          PRODUCTION,
          X509Utils.loadCertificate(
              readResourceUtf8(TmchCertificateAuthority.class, ROOT_CRT_FILE)));
    } catch (CertificateParsingException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Check that {@code cert} is signed by the ICANN TMCH CA root and not revoked.
   *
   * <p>Support for certificate chains has not been implemented.
   *
   * @throws GeneralSecurityException for unsupported protocols, certs not signed by the TMCH,
   *         incorrect keys, and for invalid, old, not-yet-valid or revoked certificates.
   * @see X509Utils#verifyCertificate
   */
  public void verify(X509Certificate cert) throws GeneralSecurityException {
    synchronized (TmchCertificateAuthority.class) {
      X509Utils.verifyCertificate(getAndValidateRoot(), getCrl(), cert, clock.nowUtc().toDate());
    }
  }

  /**
   * Update to the latest TMCH X.509 certificate revocation list and save it to Datastore.
   *
   * <p>Your ASCII-armored CRL must be signed by the current ICANN root certificate.
   *
   * <p>This will not take effect (either on this instance or on others) until the CRL_CACHE next
   * refreshes itself.
   *
   * @throws GeneralSecurityException for unsupported protocols, certs not signed by the TMCH,
   *     incorrect keys, and for invalid, old, not-yet-valid or revoked certificates.
   * @see X509Utils#verifyCrl
   */
  public void updateCrl(String asciiCrl, String url) throws GeneralSecurityException {
    X509CRL crl = X509Utils.loadCrl(asciiCrl);
    X509Utils.verifyCrl(getAndValidateRoot(), getCrl(), crl, clock.nowUtc().toDate());
    TmchCrl.set(asciiCrl, url);
  }

  public X509Certificate getAndValidateRoot() throws GeneralSecurityException {
    try {
      X509Certificate root = ROOT_CERTS.get(tmchCaMode);
      // The current production certificate expires on 2023-07-23. Future code monkey be reminded,
      // if you are looking at this code because the next line throws an exception, ask ICANN for a
      // new root certificate! (preferably before the current one expires...)
      root.checkValidity(clock.nowUtc().toDate());
      return root;
    } catch (Exception e) {
      if (e instanceof GeneralSecurityException) {
        throw (GeneralSecurityException) e;
      } else if (e instanceof RuntimeException) {
        throw (RuntimeException) e;
      }
      throw new RuntimeException(e);
    }
  }

  public X509CRL getCrl() throws GeneralSecurityException {
    try {
      return CRL_CACHE.get(tmchCaMode);
    } catch (Exception e) {
      if (e.getCause() instanceof GeneralSecurityException) {
        throw (GeneralSecurityException) e.getCause();
      } else if (e instanceof RuntimeException) {
        throw (RuntimeException) e;
      }
      throw new RuntimeException(e);
    }
  }
}

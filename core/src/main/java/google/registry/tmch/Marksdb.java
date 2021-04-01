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

import static com.google.appengine.api.urlfetch.FetchOptions.Builder.validateCertificate;
import static com.google.appengine.api.urlfetch.HTTPMethod.GET;
import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.util.HexDumper.dumpHex;
import static google.registry.util.UrlFetchUtils.setAuthorizationHeader;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.appengine.api.urlfetch.HTTPRequest;
import com.google.appengine.api.urlfetch.HTTPResponse;
import com.google.appengine.api.urlfetch.URLFetchService;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import com.google.common.io.ByteSource;
import google.registry.config.RegistryConfig.Config;
import google.registry.keyring.api.KeyModule.Key;
import google.registry.util.UrlFetchException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.security.Security;
import java.security.SignatureException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import javax.annotation.Tainted;
import javax.inject.Inject;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider;

/** Shared code for tasks that download stuff from MarksDB. */
public final class Marksdb {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final int MAX_DNL_LOGGING_LENGTH = 500;

  @Inject URLFetchService fetchService;
  @Inject @Config("tmchMarksdbUrl") String tmchMarksdbUrl;
  @Inject @Key("marksdbPublicKey") PGPPublicKey marksdbPublicKey;
  @Inject Marksdb() {}

  /**
   * Extracts a {@link PGPSignature} object from a blob of {@code .sig} data.
   *
   * @throws SignatureException if a signature object couldn't be extracted for any reason.
   */
  private static PGPSignature pgpExtractSignature(@Tainted byte[] signature)
      throws SignatureException {
    try {
      ByteArrayInputStream input = new ByteArrayInputStream(signature);
      PGPObjectFactory decoder = new BcPGPObjectFactory(PGPUtil.getDecoderStream(input));
      Object object = decoder.nextObject();
      if (object == null) {
        throw new SignatureException(String.format(
            "No OpenPGP packets found in signature.\n%s",
            dumpHex(signature)));
      }
      if (!(object instanceof PGPSignatureList)) {
        throw new SignatureException(String.format(
            "Expected PGPSignatureList packet but got %s\n%s",
            object.getClass().getSimpleName(),
            dumpHex(signature)));
      }
      PGPSignatureList sigs = (PGPSignatureList) object;
      if (sigs.isEmpty()) {
        throw new SignatureException(String.format(
            "PGPSignatureList doesn't have a PGPSignature.\n%s",
            dumpHex(signature)));
      }
      return sigs.get(0);
    } catch (IOException e) {
      throw new SignatureException(String.format(
          "Failed to extract PGPSignature object from .sig blob.\n%s",
          dumpHex(signature)), e);
    }
  }

  private static void pgpVerifySignature(byte[] data, byte[] signature, PGPPublicKey publicKey)
      throws PGPException, SignatureException {
    Security.addProvider(new BouncyCastleProvider());
    PGPSignature sig = pgpExtractSignature(signature);
    sig.init(new BcPGPContentVerifierBuilderProvider(), publicKey);
    sig.update(data);
    if (!sig.verify()) {
      throw new SignatureException(String.format(
          "MarksDB PGP signature verification failed.\n%s",
          dumpHex(signature)));
    }
  }

  byte[] fetch(URL url, Optional<String> loginAndPassword) throws IOException {
    HTTPRequest req = new HTTPRequest(url, GET, validateCertificate().setDeadline(60d));
    setAuthorizationHeader(req, loginAndPassword);
    HTTPResponse rsp;
    try {
      rsp = fetchService.fetch(req);
    } catch (IOException e) {
      throw new IOException(
          String.format("Error connecting to MarksDB at URL %s", url), e);
    }
    if (rsp.getResponseCode() != SC_OK) {
      throw new UrlFetchException("Failed to fetch from MarksDB", req, rsp);
    }
    return rsp.getContent();
  }

  List<String> fetchSignedCsv(Optional<String> loginAndPassword, String csvPath, String sigPath)
      throws IOException, SignatureException, PGPException {
    checkArgument(
        loginAndPassword.isPresent(), "Cannot fetch from MarksDB without login credentials");

    String csvUrl = tmchMarksdbUrl + csvPath;
    byte[] csv = fetch(new URL(csvUrl), loginAndPassword);
    logFetchedBytes(csvUrl, csv);

    String sigUrl = tmchMarksdbUrl + sigPath;
    byte[] sig = fetch(new URL(sigUrl), loginAndPassword);
    logFetchedBytes(sigUrl, sig);

    pgpVerifySignature(csv, sig, marksdbPublicKey);
    ImmutableList<String> lines = ByteSource.wrap(csv).asCharSource(US_ASCII).readLines();
    logger.atInfo().log("Parsed %d lines.", lines.size());
    return lines;
  }

  /**
   * Logs the length and first 500 characters of a byte array of the given name.
   *
   * <p>Note that the DNL is long, hence truncating it instead of logging the whole thing.
   */
  private static void logFetchedBytes(String sourceUrl, byte[] bytes) {
    logger.atInfo().log(
        "Fetched contents of %s -- Size: %d bytes; first %d chars:\n\n%s%s",
        sourceUrl,
        bytes.length,
        MAX_DNL_LOGGING_LENGTH,
        new String(Arrays.copyOf(bytes, Math.min(bytes.length, MAX_DNL_LOGGING_LENGTH)), US_ASCII),
        (bytes.length > MAX_DNL_LOGGING_LENGTH) ? "<truncated>" : "");
  }
}

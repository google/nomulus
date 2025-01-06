// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.rde;

import static google.registry.request.UrlConnectionUtils.getResponseBytes;
import static google.registry.request.UrlConnectionUtils.setBasicAuth;
import static google.registry.request.UrlConnectionUtils.setPayload;
import static google.registry.util.DomainNameUtils.canonicalizeHostname;
import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.api.client.http.HttpMethods;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import google.registry.config.RegistryConfig.Config;
import google.registry.keyring.api.KeyModule.Key;
import google.registry.request.HttpException.InternalServerErrorException;
import google.registry.request.UrlConnectionService;
import google.registry.util.UrlConnectionException;
import google.registry.xjc.XjcXmlTransformer;
import google.registry.xjc.iirdea.XjcIirdeaResponseElement;
import google.registry.xjc.iirdea.XjcIirdeaResult;
import google.registry.xjc.rdeheader.XjcRdeHeader;
import google.registry.xjc.rdereport.XjcRdeReportReport;
import google.registry.xml.XmlException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.GeneralSecurityException;
import javax.inject.Inject;

/**
 * Class that uploads a decrypted XML deposit report to ICANN's webserver.
 *
 * @see RdeReportAction
 */
public class RdeReporter {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /**
   * @see <a href="http://tools.ietf.org/html/draft-lozano-icann-registry-interfaces-05#section-4">
   *     ICANN Registry Interfaces - Interface details</a>
   */
  private static final MediaType MEDIA_TYPE = MediaType.XML_UTF_8;

  @Inject UrlConnectionService urlConnectionService;

  @Inject @Config("rdeReportUrlPrefix") String reportUrlPrefix;
  @Inject @Key("icannReportingPassword") String password;
  @Inject RdeReporter() {}

  /** Uploads {@code reportBytes} to ICANN. */
  public void send(byte[] reportBytes) throws XmlException, GeneralSecurityException, IOException {
    XjcRdeReportReport report =
        XjcXmlTransformer.unmarshal(
            XjcRdeReportReport.class, new ByteArrayInputStream(reportBytes));
    XjcRdeHeader header = report.getHeader().getValue();

    // Send a PUT request to ICANN's HTTPS server.
    URL url = makeReportUrl(header.getTld(), report.getId());
    String username = header.getTld() + "_ry";
    logger.atInfo().log("Sending report:\n%s", new String(reportBytes, UTF_8));
    HttpURLConnection connection = urlConnectionService.createConnection(url);
    connection.setRequestMethod(HttpMethods.PUT);
    setBasicAuth(connection, username, password);
    setPayload(connection, reportBytes, MEDIA_TYPE.toString());
    int responseCode;
    byte[] responseBytes;

    try {
      responseCode = connection.getResponseCode();
      if (responseCode != SC_OK && responseCode != SC_BAD_REQUEST) {
        logger.atWarning().log("Connection to RDE report server failed: %d", responseCode);
        throw new UrlConnectionException("PUT failed", connection);
      }
      responseBytes = getResponseBytes(connection);
    } finally {
      connection.disconnect();
    }

    // We know that an HTTP 200 response can only contain a result code of
    // 1000 (i. e. success), there is no need to parse it.
    if (responseCode != SC_OK) {
      XjcIirdeaResult result = parseResult(responseBytes);
      logger.atWarning().log(
          "Rejected when trying to PUT RDE report to ICANN server: %d %s\n%s",
          result.getCode().getValue(), result.getMsg(), result.getDescription());
      throw new InternalServerErrorException(result.getMsg());
    }
  }

  /**
   * Unmarshall IIRDEA XML result object from HTTP response payload.
   *
   * @see <a
   *     href="http://tools.ietf.org/html/draft-lozano-icann-registry-interfaces-05#section-4.1">
   *     ICANN Registry Interfaces - IIRDEA Result Object</a>
   */
  private static XjcIirdeaResult parseResult(byte[] responseBytes) throws XmlException {
    logger.atInfo().log("Received response:\n%s", new String(responseBytes, UTF_8));
    XjcIirdeaResponseElement response =
        XjcXmlTransformer.unmarshal(
            XjcIirdeaResponseElement.class, new ByteArrayInputStream(responseBytes));
    return response.getResult();
  }

  private URL makeReportUrl(String tld, String id) {
    try {
      return new URL(String.format("%s/%s/%s", reportUrlPrefix, canonicalizeHostname(tld), id));
    } catch (MalformedURLException e) {
      throw new RuntimeException(e);
    }
  }
}

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

package google.registry.rde;

import static google.registry.request.UrlConnectionUtils.getResponseBytes;
import static google.registry.request.UrlConnectionUtils.setBasicAuth;
import static google.registry.request.UrlConnectionUtils.setPayload;
import static google.registry.util.DomainNameUtils.canonicalizeDomainName;
import static java.nio.charset.StandardCharsets.UTF_8;
import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.api.client.http.HttpMethods;
import com.google.appengine.api.urlfetch.HTTPResponse;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import google.registry.config.RegistryConfig.Config;
import google.registry.keyring.api.KeyModule.Key;
import google.registry.request.HttpException.InternalServerErrorException;
import google.registry.request.UrlConnectionService;
import google.registry.util.Retrier;
import google.registry.util.UrlConnectionException;
import google.registry.xjc.XjcXmlTransformer;
import google.registry.xjc.iirdea.XjcIirdeaResponseElement;
import google.registry.xjc.iirdea.XjcIirdeaResult;
import google.registry.xjc.rdeheader.XjcRdeHeader;
import google.registry.xjc.rdereport.XjcRdeReportReport;
import google.registry.xml.XmlException;
import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
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

  @Inject Retrier retrier;
  @Inject UrlConnectionService urlConnectionService;

  @Inject @Config("rdeReportUrlPrefix") String reportUrlPrefix;
  @Inject @Key("icannReportingPassword") String password;
  @Inject RdeReporter() {}

  /** Uploads {@code reportBytes} to ICANN. */
  public void send(byte[] reportBytes) throws XmlException {
    XjcRdeReportReport report = XjcXmlTransformer.unmarshal(
        XjcRdeReportReport.class, new ByteArrayInputStream(reportBytes));
    XjcRdeHeader header = report.getHeader().getValue();

    // Send a PUT request to ICANN's HTTPS server.
    URL url = makeReportUrl(header.getTld(), report.getId());
    String username = header.getTld() + "_ry";
    logger.atInfo().log("Sending report:\n%s", new String(reportBytes, UTF_8));
    byte[] responseBytes =
        retrier.callWithRetry(
            () -> {
              HttpURLConnection connection = urlConnectionService.createConnection(url);
              connection.setRequestMethod(HttpMethods.PUT);
              setBasicAuth(connection, username, password);
              setPayload(connection, reportBytes, MEDIA_TYPE.toString());
              int responseCode = connection.getResponseCode();
              if (responseCode == SC_OK || responseCode == SC_BAD_REQUEST) {
                return getResponseBytes(connection);
              }
              throw new UrlConnectionException("PUT failed", connection);
            },
            SocketTimeoutException.class);

    // Ensure the XML response is valid.
    XjcIirdeaResult result = parseResult(responseBytes);
    if (result.getCode().getValue() != 1000) {
      logger.atWarning().log(
          "PUT rejected: %d %s\n%s",
          result.getCode().getValue(), result.getMsg(), result.getDescription());
      throw new InternalServerErrorException(result.getMsg());
    }
  }

  /**
   * Unmarshals IIRDEA XML result object from {@link HTTPResponse} payload.
   *
   * @see <a
   *     href="http://tools.ietf.org/html/draft-lozano-icann-registry-interfaces-05#section-4.1">
   *     ICANN Registry Interfaces - IIRDEA Result Object</a>
   */
  private XjcIirdeaResult parseResult(byte[] responseBytes) throws XmlException {
    logger.atInfo().log("Received response:\n%s", new String(responseBytes, UTF_8));
    XjcIirdeaResponseElement response = XjcXmlTransformer.unmarshal(
        XjcIirdeaResponseElement.class, new ByteArrayInputStream(responseBytes));
    return response.getResult();
  }

  private URL makeReportUrl(String tld, String id) {
    try {
      return new URL(String.format("%s/%s/%s", reportUrlPrefix, canonicalizeDomainName(tld), id));
    } catch (MalformedURLException e) {
      throw new RuntimeException(e);
    }
  }
}

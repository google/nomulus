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

import static com.google.appengine.api.urlfetch.FetchOptions.Builder.validateCertificate;
import static com.google.appengine.api.urlfetch.HTTPMethod.PUT;
import static com.google.common.io.BaseEncoding.base64;
import static com.google.common.net.HttpHeaders.AUTHORIZATION;
import static com.google.common.net.HttpHeaders.CONTENT_TYPE;
import static google.registry.util.DomainNameUtils.canonicalizeDomainName;
import static java.nio.charset.StandardCharsets.UTF_8;
import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.appengine.api.urlfetch.HTTPHeader;
import com.google.appengine.api.urlfetch.HTTPRequest;
import com.google.appengine.api.urlfetch.HTTPResponse;
import com.google.appengine.api.urlfetch.URLFetchService;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig.Config;
import google.registry.keyring.api.KeyModule.Key;
import google.registry.request.HttpException.InternalServerErrorException;
import google.registry.util.Retrier;
import google.registry.util.UrlFetchException;
import google.registry.xjc.XjcXmlTransformer;
import google.registry.xjc.iirdea.XjcIirdeaResponseElement;
import google.registry.xjc.iirdea.XjcIirdeaResult;
import google.registry.xjc.rdeheader.XjcRdeHeader;
import google.registry.xjc.rdereport.XjcRdeReportReport;
import google.registry.xml.XmlException;
import java.io.ByteArrayInputStream;
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

  /** @see <a href="http://tools.ietf.org/html/draft-lozano-icann-registry-interfaces-05#section-4">
   *     ICANN Registry Interfaces - Interface details</a>*/
  private static final String REPORT_MIME = "text/xml";

  @Inject Retrier retrier;
  @Inject URLFetchService urlFetchService;
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
    String token = base64().encode(String.format("%s:%s", username, password).getBytes(UTF_8));
    final HTTPRequest req = new HTTPRequest(url, PUT, validateCertificate().setDeadline(60d));
    req.addHeader(new HTTPHeader(CONTENT_TYPE, REPORT_MIME));
    req.addHeader(new HTTPHeader(AUTHORIZATION, "Basic " + token));
    req.setPayload(reportBytes);
    logger.atInfo().log("Sending report:\n%s", new String(reportBytes, UTF_8));
    HTTPResponse rsp =
        retrier.callWithRetry(
            () -> {
              HTTPResponse rsp1 = urlFetchService.fetch(req);
              switch (rsp1.getResponseCode()) {
                case SC_OK:
                case SC_BAD_REQUEST:
                  break;
                default:
                  throw new UrlFetchException("PUT failed", req, rsp1);
              }
              return rsp1;
            },
            SocketTimeoutException.class);

    // Ensure the XML response is valid.
    XjcIirdeaResult result = parseResult(rsp);
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
   * @see <a href="http://tools.ietf.org/html/draft-lozano-icann-registry-interfaces-05#section-4.1">
   *     ICANN Registry Interfaces - IIRDEA Result Object</a>
   */
  private XjcIirdeaResult parseResult(HTTPResponse rsp) throws XmlException {
    byte[] responseBytes = rsp.getContent();
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

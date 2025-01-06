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

package google.registry.reporting.icann;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.net.MediaType.CSV_UTF_8;
import static google.registry.model.tld.Tlds.assertTldExists;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;

import com.google.api.client.http.HttpMethods;
import com.google.common.base.Ascii;
import com.google.common.base.Splitter;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig.Config;
import google.registry.keyring.api.KeyModule.Key;
import google.registry.reporting.icann.IcannReportingModule.ReportType;
import google.registry.request.UrlConnectionService;
import google.registry.request.UrlConnectionUtils;
import google.registry.xjc.XjcXmlTransformer;
import google.registry.xjc.iirdea.XjcIirdeaResponseElement;
import google.registry.xjc.iirdea.XjcIirdeaResult;
import google.registry.xml.XmlException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.List;
import javax.inject.Inject;
import org.joda.time.YearMonth;
import org.joda.time.format.DateTimeFormat;

/**
 * Class that uploads a CSV file to ICANN's endpoint via an HTTP PUT call.
 *
 * <p>It uses basic authorization credentials as specified in the "Registry Interfaces" draft.
 *
 * <p>Note that there's a lot of hard-coded logic extracting parameters from the report filenames.
 * These are safe, as long as they follow the tld-reportType-yearMonth.csv filename format.
 *
 * @see IcannReportingUploadAction
 * @see <a href=https://tools.ietf.org/html/draft-lozano-icann-registry-interfaces-07#page-9>ICANN
 *     Reporting Specification</a>
 */
public class IcannHttpReporter {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Inject UrlConnectionService urlConnectionService;

  @Inject
  @Key("icannReportingPassword")
  String password;

  @Inject
  @Config("icannTransactionsReportingUploadUrl")
  String icannTransactionsUrl;

  @Inject
  @Config("icannActivityReportingUploadUrl")
  String icannActivityUrl;

  @Inject
  IcannHttpReporter() {}

  /** Uploads {@code reportBytes} to ICANN, returning whether or not it succeeded. */
  public boolean send(byte[] reportBytes, String reportFilename)
      throws GeneralSecurityException, IOException {
    validateReportFilename(reportFilename);
    URL uploadUrl = makeUrl(reportFilename);
    logger.atInfo().log(
        "Sending report to %s with content length %d.", uploadUrl, reportBytes.length);
    HttpURLConnection connection = urlConnectionService.createConnection(uploadUrl);
    connection.setRequestMethod(HttpMethods.PUT);
    UrlConnectionUtils.setBasicAuth(connection, getTld(reportFilename) + "_ry", password);
    UrlConnectionUtils.setPayload(connection, reportBytes, CSV_UTF_8.toString());
    connection.setInstanceFollowRedirects(false);

    int responseCode = 0;
    byte[] content = null;
    try {
      responseCode = connection.getResponseCode();
      content = UrlConnectionUtils.getResponseBytes(connection);
      if (responseCode != SC_OK) {
        XjcIirdeaResult result = parseResult(content);
        logger.atWarning().log(
            "PUT rejected, status code %s:\n%s\n%s",
            result.getCode().getValue(), result.getMsg(), result.getDescription());
        return false;
      }
    } catch (IOException e) {
      logger.atWarning().withCause(e).log(
          "Connection to ICANN server failed with responseCode %s and connection %s",
          responseCode == 0 ? "not available" : responseCode, connection);
      return false;
    } catch (XmlException e) {
      logger.atWarning().withCause(e).log(
          "Failed to parse ICANN response with responseCode %s and content %s",
          responseCode, new String(content, StandardCharsets.UTF_8));
      return false;
    } finally {
      connection.disconnect();
    }
    return true;
  }

  private static XjcIirdeaResult parseResult(byte[] content) throws XmlException {
    XjcIirdeaResponseElement response =
        XjcXmlTransformer.unmarshal(
            XjcIirdeaResponseElement.class, new ByteArrayInputStream(content));
    return response.getResult();
  }

  /** Verifies a given report filename matches the pattern tld-reportType-yyyyMM.csv. */
  private static void validateReportFilename(String filename) {
    checkArgument(
        filename.matches("[a-z0-9.\\-]+-((activity)|(transactions))-[0-9]{6}\\.csv"),
        "Expected file format: tld-reportType-yyyyMM.csv, got %s instead",
        filename);
    assertTldExists(getTld(filename));
  }

  private static String getTld(String filename) {
    // Extract the TLD, up to second-to-last hyphen in the filename (works with international TLDs)
    return filename.substring(0, filename.lastIndexOf('-', filename.lastIndexOf('-') - 1));
  }

  private URL makeUrl(String filename) throws MalformedURLException {
    // Filename is in the format tld-reportType-yearMonth.csv
    String tld = getTld(filename);
    // Remove the tld- prefix and csv suffix
    String remainder = filename.substring(tld.length() + 1, filename.length() - 4);
    List<String> elements = Splitter.on('-').splitToList(remainder);
    ReportType reportType = ReportType.valueOf(Ascii.toUpperCase(elements.get(0)));
    // Re-add hyphen between year and month, because ICANN is inconsistent between filename and URL
    String yearMonth =
        YearMonth.parse(elements.get(1), DateTimeFormat.forPattern("yyyyMM")).toString("yyyy-MM");
    return new URL(String.format("%s/%s/%s", getUrlPrefix(reportType), tld, yearMonth));
  }

  private String getUrlPrefix(ReportType reportType) {
    return switch (reportType) {
      case TRANSACTIONS -> icannTransactionsUrl;
      case ACTIVITY -> icannActivityUrl;
      default ->
          throw new IllegalStateException(
              String.format(
                  "Received invalid reportTypes! Expected ACTIVITY or TRANSACTIONS, got %s.",
                  reportType));
    };
  }

}

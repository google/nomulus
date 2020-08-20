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

package google.registry.reporting.icann;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.net.MediaType.CSV_UTF_8;
import static google.registry.model.registry.Registries.assertTldExists;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpTransport;
import com.google.common.base.Ascii;
import com.google.common.base.Splitter;
import com.google.common.flogger.FluentLogger;
import com.google.common.io.BaseEncoding;
import com.google.common.io.ByteStreams;
import google.registry.config.RegistryConfig.Config;
import google.registry.keyring.api.KeyModule.Key;
import google.registry.reporting.icann.IcannReportingModule.ReportType;
import google.registry.xjc.XjcXmlTransformer;
import google.registry.xjc.iirdea.XjcIirdeaResponseElement;
import google.registry.xjc.iirdea.XjcIirdeaResult;
import google.registry.xml.XmlException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
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

  @Inject HttpTransport httpTransport;
  @Inject @Key("icannReportingPassword") String password;
  @Inject @Config("icannTransactionsReportingUploadUrl") String icannTransactionsUrl;
  @Inject @Config("icannActivityReportingUploadUrl") String icannActivityUrl;
  @Inject IcannHttpReporter() {}

  /** Uploads {@code reportBytes} to ICANN, returning whether or not it succeeded. */
  public boolean send(byte[] reportBytes, String reportFilename) throws XmlException, IOException {
    validateReportFilename(reportFilename);
    GenericUrl uploadUrl = new GenericUrl(makeUrl(reportFilename));
    HttpRequest request =
        httpTransport
            .createRequestFactory()
            .buildPutRequest(uploadUrl, new ByteArrayContent(CSV_UTF_8.toString(), reportBytes));

    HttpHeaders headers = request.getHeaders();
    headers.setBasicAuthentication(getTld(reportFilename) + "_ry", password);
    headers.setContentType(CSV_UTF_8.toString());
    request.setHeaders(headers);
    request.setFollowRedirects(false);

    HttpResponse response = null;
    logger.atInfo().log(
        "Sending report to %s with content length %d", uploadUrl, request.getContent().getLength());
    boolean success = true;
    try {
      response = request.execute();
      byte[] content;
      try {
        content = ByteStreams.toByteArray(response.getContent());
      } finally {
        response.getContent().close();
      }
      logger.atInfo().log(
          "Received response code %d with content: %s\n\nResponse content in hex: %s",
          response.getStatusCode(),
          new String(content, UTF_8),
          BaseEncoding.base16().encode(content));
      XjcIirdeaResult result = parseResult(content);
      if (result.getCode().getValue() != 1000) {
        success = false;
        logger.atWarning().log(
            "PUT rejected, status code %s:\n%s\n%s",
            result.getCode(), result.getMsg(), result.getDescription());
      }
    } finally {
      if (response != null) {
        response.disconnect();
      } else {
        success = false;
        logger.atWarning().log("Received null response from ICANN server at %s", uploadUrl);
      }
    }
    return success;
  }

  private XjcIirdeaResult parseResult(byte[] content) throws XmlException {
    XjcIirdeaResponseElement response =
        XjcXmlTransformer.unmarshal(
            XjcIirdeaResponseElement.class, new ByteArrayInputStream(content));
    return response.getResult();
  }

  /** Verifies a given report filename matches the pattern tld-reportType-yyyyMM.csv. */
  private void validateReportFilename(String filename) {
    checkArgument(
        filename.matches("[a-z0-9.\\-]+-((activity)|(transactions))-[0-9]{6}\\.csv"),
        "Expected file format: tld-reportType-yyyyMM.csv, got %s instead",
        filename);
    assertTldExists(getTld(filename));
  }

  private String getTld(String filename) {
    // Extract the TLD, up to second-to-last hyphen in the filename (works with international TLDs)
    return filename.substring(0, filename.lastIndexOf('-', filename.lastIndexOf('-') - 1));
  }

  private String makeUrl(String filename) {
    // Filename is in the format tld-reportType-yearMonth.csv
    String tld = getTld(filename);
    // Remove the tld- prefix and csv suffix
    String remainder = filename.substring(tld.length() + 1, filename.length() - 4);
    List<String> elements = Splitter.on('-').splitToList(remainder);
    ReportType reportType = ReportType.valueOf(Ascii.toUpperCase(elements.get(0)));
    // Re-add hyphen between year and month, because ICANN is inconsistent between filename and URL
    String yearMonth =
        YearMonth.parse(elements.get(1), DateTimeFormat.forPattern("yyyyMM")).toString("yyyy-MM");
    return String.format("%s/%s/%s", getUrlPrefix(reportType), tld, yearMonth);
  }

  private String getUrlPrefix(ReportType reportType) {
    switch (reportType) {
      case TRANSACTIONS:
        return icannTransactionsUrl;
      case ACTIVITY:
        return icannActivityUrl;
      default:
        throw new IllegalStateException(
            String.format(
                "Received invalid reportTypes! Expected ACTIVITY or TRANSACTIONS, got %s.",
                reportType));
    }
  }
}

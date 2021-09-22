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
import static java.nio.charset.StandardCharsets.UTF_8;
import static javax.servlet.http.HttpServletResponse.SC_NO_CONTENT;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.appengine.api.urlfetch.HTTPRequest;
import com.google.appengine.api.urlfetch.HTTPResponse;
import com.google.appengine.api.urlfetch.URLFetchService;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.flogger.FluentLogger;
import com.google.common.io.ByteSource;
import google.registry.request.Action;
import google.registry.request.Header;
import google.registry.request.HttpException.ConflictException;
import google.registry.request.Parameter;
import google.registry.request.RequestParameters;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.util.UrlFetchException;
import java.io.IOException;
import java.net.URL;
import java.util.Map.Entry;
import javax.inject.Inject;

/**
 * NORDN CSV uploading system, verify operation.
 *
 * <p>Every three hours (max twenty-six hours) we generate CSV files for each TLD which we need to
 * upload to MarksDB. The upload is a two-phase process. We send the CSV data as a POST request and
 * get back a 202 Accepted. This response will give us a URL in the Location header, where we'll
 * check back later for the actual result.
 *
 * @see NordnUploadAction
 * @see <a href="http://tools.ietf.org/html/draft-lozano-tmch-func-spec-08#section-5.2.3.3">
 *     http://tools.ietf.org/html/draft-lozano-tmch-func-spec-08#section-5.2.3.3</a>
 */
@Action(
    service = Action.Service.BACKEND,
    path = NordnVerifyAction.PATH,
    method = Action.Method.POST,
    automaticallyPrintOk = true,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public final class NordnVerifyAction implements Runnable {

  static final String PATH = "/_dr/task/nordnVerify";
  static final String QUEUE = "marksdb";
  static final String URL_HEADER = "X-DomainRegistry-Nordn-Url";
  static final String HEADER_ACTION_LOG_ID = "X-DomainRegistry-ActionLogId";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Inject LordnRequestInitializer lordnRequestInitializer;
  @Inject Response response;
  @Inject URLFetchService fetchService;
  @Inject @Header(URL_HEADER) URL url;
  @Inject @Header(HEADER_ACTION_LOG_ID) String actionLogId;
  @Inject @Parameter(RequestParameters.PARAM_TLD) String tld;
  @Inject NordnVerifyAction() {}

  @Override
  public void run() {
    try {
      verify();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Fetch LORDN log file from MarksDB to confirm successful upload.
   *
   * <p>Idempotency: The confirmation URL will always return the same result once it becomes
   * available.
   *
   * @throws ConflictException if MarksDB has not yet finished processing the LORDN upload
   * @see <a href="http://tools.ietf.org/html/draft-lozano-tmch-func-spec-08#section-6.3.1">
   *     TMCH functional specifications LORDN Log File</a>
   */
  @VisibleForTesting
  LordnLog verify() throws IOException {
    logger.atInfo().log("LORDN verify task %s: Sending request to URL %s", actionLogId, url);
    HTTPRequest req = new HTTPRequest(url, GET, validateCertificate().setDeadline(60d));
    lordnRequestInitializer.initialize(req, tld);
    HTTPResponse rsp;
    try {
      rsp = fetchService.fetch(req);
    } catch (IOException e) {
      throw new IOException(
          String.format("Error connecting to MarksDB at URL %s", url), e);
    }
    logger.atInfo().log(
        "LORDN verify task %s response: HTTP response code %d, response data: %s",
        actionLogId, rsp.getResponseCode(), rsp.getContent());
    if (rsp.getResponseCode() == SC_NO_CONTENT) {
      // Send a 400+ status code so App Engine will retry the task.
      throw new ConflictException("Not ready");
    }
    if (rsp.getResponseCode() != SC_OK) {
      throw new UrlFetchException(
          String.format("LORDN verify task %s: Failed to verify LORDN upload to MarksDB.",
              actionLogId),
          req, rsp);
    }
    LordnLog log =
        LordnLog.parse(ByteSource.wrap(rsp.getContent()).asCharSource(UTF_8).readLines());
    if (log.getStatus() == LordnLog.Status.ACCEPTED) {
      logger.atInfo().log("LORDN verify task %s: Upload accepted.", actionLogId);
    } else {
      logger.atSevere().log(
          "LORDN verify task %s: Upload rejected with reason: %s", actionLogId, log);
    }
    for (Entry<String, LordnLog.Result> result : log) {
      switch (result.getValue().getOutcome()) {
        case OK:
          break;
        case WARNING:
          // fall through
        case ERROR:
          logger.atWarning().log(result.toString());
          break;
        default:
          logger.atWarning().log(
              "LORDN verify task %s: Unexpected outcome: %s", actionLogId, result);
          break;
      }
    }
    return log;
  }
}

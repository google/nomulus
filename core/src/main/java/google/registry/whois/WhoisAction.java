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

package google.registry.whois;

import static google.registry.request.Action.Method.POST;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.appengine.api.datastore.DatastoreFailureException;
import com.google.appengine.api.datastore.DatastoreTimeoutException;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import google.registry.config.RegistryConfig.Config;
import google.registry.request.Action;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import google.registry.util.Retrier;
import google.registry.whois.WhoisException.UncheckedWhoisException;
import google.registry.whois.WhoisMetrics.WhoisMetric;
import google.registry.whois.WhoisResponse.WhoisResponseResults;
import java.io.Reader;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * HTTP request handler for WHOIS protocol requests sent to us by a proxy.
 *
 * <p>All commands and responses conform to the WHOIS spec as defined in RFC 3912. Commands must be
 * sent via an HTTP POST in the request body.
 *
 * <p>This action is meant to serve as a low level interface for the proxy app which forwards us
 * requests received on port 43. However this interface is technically higher level because it sends
 * back proper HTTP error codes such as 200, 400, 500, etc. These are discarded by the proxy because
 * WHOIS specifies no manner for differentiating successful and erroneous requests.
 *
 * @see WhoisHttpAction
 * @see <a href="http://www.ietf.org/rfc/rfc3912.txt">RFC 3912: WHOIS Protocol Specification</a>
 */
@Action(
    service = Action.Service.PUBAPI,
    path = "/_dr/whois",
    method = POST,
    auth = Auth.AUTH_PUBLIC_OR_INTERNAL)
public class WhoisAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /** WHOIS doesn't define an encoding, nor any way to specify an encoding in the protocol. */
  static final MediaType CONTENT_TYPE = MediaType.PLAIN_TEXT_UTF_8;

  /**
   * As stated above, this is the low level interface intended for port 43, and as such, it
   * always prefers ASCII.
   */
  static final boolean PREFER_UNICODE = false;

  @Inject Clock clock;
  @Inject Reader input;
  @Inject Response response;
  @Inject Retrier retrier;
  @Inject WhoisReader whoisReader;
  @Inject @Config("whoisDisclaimer") String disclaimer;
  @Inject WhoisMetric.Builder metricBuilder;
  @Inject WhoisMetrics whoisMetrics;

  @Inject
  WhoisAction() {}

  @Override
  public void run() {
    String responseText;
    final DateTime now = clock.nowUtc();
    try {
      final WhoisCommand command = whoisReader.readCommand(input, false, now);
      metricBuilder.setCommand(command);
      WhoisResponseResults results =
          retrier.callWithRetry(
              () -> {
                WhoisResponseResults results1;
                try {
                  results1 = command.executeQuery(now).getResponse(PREFER_UNICODE, disclaimer);
                } catch (WhoisException e) {
                  throw new UncheckedWhoisException(e);
                }
                return results1;
              },
              DatastoreTimeoutException.class,
              DatastoreFailureException.class);
      responseText = results.plainTextOutput();
      setWhoisMetrics(metricBuilder, results.numResults(), SC_OK);
    } catch (UncheckedWhoisException u) {
      WhoisException e = (WhoisException) u.getCause();
      WhoisResponseResults results = e.getResponse(PREFER_UNICODE, disclaimer);
      responseText = results.plainTextOutput();
      setWhoisMetrics(metricBuilder, 0, e.getStatus());
    } catch (WhoisException e) {
      WhoisResponseResults results = e.getResponse(PREFER_UNICODE, disclaimer);
      responseText = results.plainTextOutput();
      setWhoisMetrics(metricBuilder, 0, e.getStatus());
    } catch (Throwable t) {
      logger.atSevere().withCause(t).log("WHOIS request crashed.");
      responseText = "Internal Server Error";
      setWhoisMetrics(metricBuilder, 0, SC_INTERNAL_SERVER_ERROR);
    }
    // Note that we always return 200 (OK) even if an error was hit. This is because returning an
    // non-OK HTTP status code will cause the proxy server to silently close the connection. Since
    // WHOIS has no way to return errors, it's better to convert any such errors into strings and
    // return them directly.
    response.setStatus(SC_OK);
    response.setContentType(CONTENT_TYPE);
    response.setPayload(responseText);
    whoisMetrics.recordWhoisMetric(metricBuilder.build());
  }

  private static void setWhoisMetrics(
      WhoisMetric.Builder metricBuilder, int numResults, int status) {
    metricBuilder.setNumResults(numResults);
    metricBuilder.setStatus(status);
  }
}

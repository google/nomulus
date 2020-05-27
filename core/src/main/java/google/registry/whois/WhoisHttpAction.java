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

import static com.google.common.base.Strings.nullToEmpty;
import static com.google.common.base.Verify.verify;
import static com.google.common.net.HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN;
import static com.google.common.net.HttpHeaders.CACHE_CONTROL;
import static com.google.common.net.HttpHeaders.EXPIRES;
import static com.google.common.net.HttpHeaders.LAST_MODIFIED;
import static com.google.common.net.HttpHeaders.X_CONTENT_TYPE_OPTIONS;
import static com.google.common.net.MediaType.PLAIN_TEXT_UTF_8;
import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.Throwables;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig.Config;
import google.registry.request.Action;
import google.registry.request.RequestPath;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import google.registry.whois.WhoisMetrics.WhoisMetric;
import google.registry.whois.WhoisResponse.WhoisResponseResults;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import javax.inject.Inject;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 * Human-Friendly HTTP WHOIS API
 *
 * <p>This API uses easy to understand paths rather than {@link WhoisAction} which requires a POST
 * request containing a WHOIS command. Because the typical WHOIS command is along the lines of
 * {@code "domain google.lol"} or the equivalent {@code "google.lol}, this action is just going to
 * replace the slashes with spaces and let {@link WhoisReader} figure out what to do.
 *
 * <p>This action accepts requests from any origin.
 *
 * <p>You can send AJAX requests to our WHOIS API from your <em>very own</em> website using the
 * following embed code:
 *
 * <pre>{@code
 * <input id="query-input" placeholder="Domain, Nameserver, IP, etc." autofocus>
 * <button id="search-button">Lookup</button>
 * <pre id="whois-results"></pre>
 * <script>
 *  (function() {
 *    var WHOIS_API_URL = 'https://domain-registry-alpha.appspot.com/whois/';
 *    function OnKeyPressQueryInput(ev) {
 *      if (typeof ev == 'undefined' && window.event) {
 *        ev = window.event;
 *      }
 *      if (ev.keyCode == 13) {
 *        document.getElementById('search-button').click();
 *      }
 *    }
 *    function OnClickSearchButton() {
 *      var query = document.getElementById('query-input').value;
 *      var req = new XMLHttpRequest();
 *      req.onreadystatechange = function() {
 *        if (req.readyState == 4) {
 *          var results = document.getElementById('whois-results');
 *          results.textContent = req.responseText;
 *        }
 *      };
 *      req.open('GET', WHOIS_API_URL + escape(query), true);
 *      req.send();
 *    }
 *    document.getElementById('search-button').onclick = OnClickSearchButton;
 *    document.getElementById('query-input').onkeypress = OnKeyPressQueryInput;
 *  })();
 * </script>
 * }</pre>
 *
 * @see WhoisAction
 */
@Action(
    service = Action.Service.PUBAPI,
    path = WhoisHttpAction.PATH,
    isPrefix = true,
    auth = Auth.AUTH_PUBLIC_ANONYMOUS)
public final class WhoisHttpAction implements Runnable {

  public static final String PATH = "/whois/";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /**
   * Cross-origin resource sharing (CORS) allowed origins policy.
   *
   * <p>This field specifies the value of the {@code Access-Control-Allow-Origin} response header.
   * Without this header, other domains such as charlestonroadregistry.com would not be able to
   * send requests to our WHOIS interface.
   *
   * <p>Our policy shall be to allow requests from pretty much anywhere using a wildcard policy.
   * The reason this is safe is because our WHOIS interface doesn't allow clients to modify data,
   * nor does it allow them to fetch user data. Only publicly available information is returned.
   *
   * @see <a href="http://www.w3.org/TR/cors/#access-control-allow-origin-response-header">
   *        W3C CORS § 5.1 Access-Control-Allow-Origin Response Header</a>
   */
  private static final String CORS_ALLOW_ORIGIN = "*";

  /** We're going to let any HTTP proxy in the world cache our responses. */
  private static final String CACHE_CONTROL_VALUE = "public";

  /** Responses may be cached for up to a day. */
  private static final String X_CONTENT_NO_SNIFF = "nosniff";

  /** Splitter that turns information on HTTP into a list of tokens. */
  private static final Splitter SLASHER = Splitter.on('/').trimResults().omitEmptyStrings();

  /** Joiner that turns {@link #SLASHER} tokens into a normal WHOIS query. */
  private static final Joiner JOINER = Joiner.on(' ');

  @Inject Clock clock;
  @Inject Response response;
  @Inject @Config("whoisDisclaimer") String disclaimer;
  @Inject @Config("whoisHttpExpires") Duration expires;
  @Inject WhoisReader whoisReader;
  @Inject @RequestPath String requestPath;
  @Inject WhoisMetric.Builder metricBuilder;
  @Inject WhoisMetrics whoisMetrics;

  @Inject
  WhoisHttpAction() {}

  @Override
  public void run() {
    verify(requestPath.startsWith(PATH));
    String path = nullToEmpty(requestPath);
    try {
      // Extremely permissive parsing that turns stuff like "/hello/world/" into "hello world".
      String commandText =
          decode(JOINER.join(SLASHER.split(path.substring(PATH.length())))) + "\r\n";
      DateTime now = clock.nowUtc();
      WhoisCommand command = whoisReader.readCommand(new StringReader(commandText), false, now);
      metricBuilder.setCommand(command);
      sendResponse(SC_OK, command.executeQuery(now));
    } catch (WhoisException e) {
      metricBuilder.setStatus(e.getStatus());
      metricBuilder.setNumResults(0);
      sendResponse(e.getStatus(), e);
    } catch (Throwable e) {
      metricBuilder.setStatus(SC_INTERNAL_SERVER_ERROR);
      metricBuilder.setNumResults(0);
      Throwables.throwIfUnchecked(e);
      throw new RuntimeException(e);
    } finally {
      whoisMetrics.recordWhoisMetric(metricBuilder.build());
    }
  }

  private void sendResponse(int status, WhoisResponse whoisResponse) {
    response.setStatus(status);
    metricBuilder.setStatus(status);
    response.setDateHeader(LAST_MODIFIED, whoisResponse.getTimestamp());
    response.setDateHeader(EXPIRES, whoisResponse.getTimestamp().plus(expires));
    response.setHeader(CACHE_CONTROL, CACHE_CONTROL_VALUE);
    response.setHeader(ACCESS_CONTROL_ALLOW_ORIGIN, CORS_ALLOW_ORIGIN);
    response.setHeader(X_CONTENT_TYPE_OPTIONS, X_CONTENT_NO_SNIFF);
    response.setContentType(PLAIN_TEXT_UTF_8);
    WhoisResponseResults results = whoisResponse.getResponse(true, disclaimer);
    metricBuilder.setNumResults(results.numResults());
    response.setPayload(results.plainTextOutput());
  }

  /** Removes {@code %xx} escape codes from request path components. */
  private String decode(String pathData)
      throws UnsupportedEncodingException, WhoisException {
    try {
      return URLDecoder.decode(pathData, "UTF-8");
    } catch (IllegalArgumentException e) {
      logger.atInfo().log("Malformed WHOIS request path: %s (%s)", requestPath, pathData);
      throw new WhoisException(clock.nowUtc(), SC_BAD_REQUEST, "Malformed path query.");
    }
  }
}

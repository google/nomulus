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

package google.registry.flows;

import static java.nio.charset.StandardCharsets.US_ASCII;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.BaseEncoding;
import google.registry.request.Response;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * A metadata class that saves the data directly in cookies.
 *
 * <p>Unlike {@link HttpSessionMetadata}, this class does not rely on a session manager to translate
 * an opaque session cookie into the metadata. This means that the locality of the session manager
 * is irrelevant and as long as the client (the proxy) respects the {@code Set-Cookie} headers and
 * sets the respective cookies in subsequent requests in a session, the metadata will be available
 * to all servers, not just the one that created the session.
 *
 * <p>The string representation of the metadata is saved in Base64 URL-safe format in a cookie named
 * {@code SESSION_INFO}.
 */
public class CookieSessionMetadata extends SessionMetadata {

  protected static final String COOKIE_NAME = "SESSION_INFO";
  protected static final String REGISTRAR_ID = "clientId";
  protected static final String SERVICE_EXTENSIONS = "serviceExtensionUris";
  protected static final String FAILED_LOGIN_ATTEMPTS = "failedLoginAttempts";

  private static final Pattern COOKIE_PATTERN = Pattern.compile("SESSION_INFO=([^;\\s]+)?");
  private static final Pattern REGISTRAR_ID_PATTERN = Pattern.compile("clientId=([^,\\s]+)?");
  private static final Pattern SERVICE_EXTENSIONS_PATTERN =
      Pattern.compile("serviceExtensionUris=([^,\\s}]+)?");
  private static final Pattern FAILED_LOGIN_ATTEMPTS_PATTERN =
      Pattern.compile("failedLoginAttempts=([^,\\s]+)?");

  private final Map<String, String> data = new HashMap<>();

  @Nullable private String registrarId = null;
  @Nullable private String serviceExtensions = null;
  @Nullable private String failedLoginAttempts = null;

  public CookieSessionMetadata(HttpServletRequest request) {
    Optional.ofNullable(request.getHeader("Cookie"))
        .ifPresent(
            cookie -> {
              Matcher matcher = COOKIE_PATTERN.matcher(cookie);
              if (matcher.find()) {
                String sessionInfo = decode(matcher.group(1));
                matcher = REGISTRAR_ID_PATTERN.matcher(sessionInfo);
                if (matcher.find()) {
                  registrarId = matcher.group(1);
                  if (!registrarId.equals("null")) {
                    data.put(REGISTRAR_ID, registrarId);
                  }
                }
                matcher = SERVICE_EXTENSIONS_PATTERN.matcher(sessionInfo);
                if (matcher.find()) {
                  serviceExtensions = matcher.group(1);
                  if (serviceExtensions != null) {
                    data.put(SERVICE_EXTENSIONS, serviceExtensions);
                  }
                }
                matcher = FAILED_LOGIN_ATTEMPTS_PATTERN.matcher(sessionInfo);
                if (matcher.find()) {
                  failedLoginAttempts = matcher.group(1);
                  data.put(FAILED_LOGIN_ATTEMPTS, failedLoginAttempts);
                }
              }
            });
  }

  @Override
  public void invalidate() {
    data.clear();
  }

  @Override
  public String getRegistrarId() {
    return data.getOrDefault(REGISTRAR_ID, null);
  }

  @Override
  public Set<String> getServiceExtensionUris() {
    return Optional.ofNullable(data.getOrDefault(SERVICE_EXTENSIONS, null))
        .map(s -> Splitter.on('.').splitToList(s))
        .map(ImmutableSet::copyOf)
        .orElse(ImmutableSet.of());
  }

  @Override
  public int getFailedLoginAttempts() {
    return Optional.ofNullable(data.getOrDefault(FAILED_LOGIN_ATTEMPTS, null))
        .map(Integer::parseInt)
        .orElse(0);
  }

  @Override
  public void setRegistrarId(String registrarId) {
    data.put(REGISTRAR_ID, registrarId);
  }

  @Override
  public void setServiceExtensionUris(Set<String> serviceExtensionUris) {
    data.put(SERVICE_EXTENSIONS, Joiner.on('.').join(serviceExtensionUris));
  }

  @Override
  public void incrementFailedLoginAttempts() {
    data.put(FAILED_LOGIN_ATTEMPTS, String.valueOf(getFailedLoginAttempts() + 1));
  }

  @Override
  public void resetFailedLoginAttempts() {
    data.remove(FAILED_LOGIN_ATTEMPTS);
  }

  @Override
  public void save(Response response) {
    String value = encode(toString());
    response.setHeader("Set-Cookie", COOKIE_NAME + "=" + value);
  }

  protected static String encode(String plainText) {
    return BaseEncoding.base64Url().encode(plainText.getBytes(US_ASCII));
  }

  protected static String decode(String cipherText) {
    return new String(BaseEncoding.base64Url().decode(cipherText), US_ASCII);
  }
}

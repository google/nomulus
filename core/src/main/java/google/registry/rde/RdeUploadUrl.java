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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.isNullOrEmpty;

import com.google.common.collect.ImmutableMap;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

/**
 * Class representing the remote destination for a deposit upload (like an SFTP server).
 *
 * <p>This class provides validity contracts, default values, and syntactic sugar which make it
 * preferable to using a plain {@link URI}.
 *
 * @see java.net.URI
 * @see RdeUploadAction
 */
@Immutable
final class RdeUploadUrl implements Comparable<RdeUploadUrl> {

  public static final Protocol SFTP = new Protocol("sftp", 22);
  private static final ImmutableMap<String, Protocol> ALLOWED_PROTOCOLS =
      ImmutableMap.of("sftp", SFTP);

  private final Protocol protocol;
  private final URI uri;

  /**
   * Constructs and validates a new {@link RdeUploadUrl} instance.
   *
   * @see java.net.URI#create(String)
   */
  public static RdeUploadUrl create(URI uri) {
    checkArgument(!isNullOrEmpty(uri.getScheme()) && !isNullOrEmpty(uri.getHost()),
        "Incomplete url: %s", uri);
    Protocol protocol = ALLOWED_PROTOCOLS.get(uri.getScheme());
    checkArgument(protocol != null, "Unsupported scheme: %s", uri);
    return new RdeUploadUrl(protocol, uri);
  }

  /** @see #create(URI) */
  private RdeUploadUrl(Protocol protocol, URI uri) {
    this.protocol = checkNotNull(protocol, "protocol");
    this.uri = checkNotNull(uri, "uri");
  }

  /** Returns username from URL userinfo. */
  public Optional<String> getUser() {
    String userInfo = uri.getUserInfo();
    if (isNullOrEmpty(userInfo)) {
      return Optional.empty();
    }
    int idx = userInfo.indexOf(':');
    if (idx != -1) {
      return Optional.of(userInfo.substring(0, idx));
    } else {
      return Optional.of(userInfo);
    }
  }

  /** Returns password from URL userinfo (if specified). */
  public Optional<String> getPass() {
    String userInfo = uri.getUserInfo();
    if (isNullOrEmpty(userInfo)) {
      return Optional.empty();
    }
    int idx = userInfo.indexOf(':');
    if (idx != -1) {
      return Optional.of(userInfo.substring(idx + 1));
    } else {
      return Optional.empty();
    }
  }

  /** Returns hostname or IP without port. */
  public String getHost() {
    return uri.getHost();
  }

  /** Returns network port or default for protocol if not specified. */
  public int getPort() {
    return uri.getPort() != -1 ? uri.getPort() : getProtocol().getPort();
  }

  /** Returns the protocol of this URL. */
  public Protocol getProtocol() {
    return protocol;
  }

  /** Returns path element of URL (if present). */
  public Optional<String> getPath() {
    String path = uri.getPath();
    if (isNullOrEmpty(path) || path.equals("/")) {
      return Optional.empty();
    } else {
      return Optional.of(path.substring(1));
    }
  }

  /** Returns URL as ASCII text with password concealed (if any). */
  @Override
  public String toString() {
    String result = getProtocol().getName() + "://";
    if (getUser().isPresent()) {
      result += urlencode(getUser().get());
      if (getPass().isPresent()) {
        result += ":****";
      }
      result += "@";
    }
    result += getHost();
    if (getPort() != getProtocol().getPort()) {
      result += String.format(":%d", getPort());
    }
    result += "/";
    result += getPath().orElse("");
    return result;
  }

  /**
   * Simplified wrapper around Java's daft URL encoding API.
   *
   * @return an ASCII string that's escaped in a conservative manner for safe storage within any
   *         component of a URL. Non-ASCII characters are converted to UTF-8 bytes before being
   *         encoded. No choice of charset is provided because the W3C says we should use UTF-8.
   * @see URLEncoder#encode(String, String)
   */
  private static String urlencode(String str) {
    try {
      return URLEncoder.encode(str, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      throw new RuntimeException(e);
    }
  }

  /** @see java.net.URI#compareTo(java.net.URI) */
  @Override
  public int compareTo(RdeUploadUrl rhs) {
    return uri.compareTo(checkNotNull(rhs).uri);
  }

  /** @see java.net.URI#equals(Object) */
  @Override
  public boolean equals(@Nullable Object object) {
    return object == this
        || (object instanceof RdeUploadUrl && Objects.equals(uri, ((RdeUploadUrl) object).uri));
  }

  /** @see java.net.URI#hashCode() */
  @Override
  public int hashCode() {
    return Objects.hashCode(uri);
  }

  /** Used to store default settings for {@link #ALLOWED_PROTOCOLS}. */
  @Immutable
  public static final class Protocol {
    private final String name;
    private final int port;

    public Protocol(String name, int port) {
      checkArgument(0 < port && port < 65536, "bad port: %s", port);
      this.name = checkNotNull(name, "name");
      this.port = port;
    }

    /** Returns lowercase name of protocol. */
    public String getName() {
      return name;
    }

    /** Returns the standardized port number assigned to this protocol. */
    public int getPort() {
      return port;
    }

    /** @see Object#equals(Object) */
    @Override
    public boolean equals(@Nullable Object object) {
      return object == this
          || (object instanceof Protocol
              && port == ((Protocol) object).port
              && Objects.equals(name, ((Protocol) object).name));
    }

    /** @see Object#hashCode() */
    @Override
    public int hashCode() {
      return Objects.hash(name, port);
    }
  }
}

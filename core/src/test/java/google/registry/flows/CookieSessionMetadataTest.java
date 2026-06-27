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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.flows.CookieSessionMetadata.COOKIE_NAME;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableSet;
import com.google.common.io.BaseEncoding;
import google.registry.testing.FakeResponse;
import jakarta.servlet.http.HttpServletRequest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CookieSessionMetadata}. */
public class CookieSessionMetadataTest {

  private static final String TEST_SECRET = "my-test-unsigned-session-secret-key-32-chars-long";

  private HttpServletRequest request = mock(HttpServletRequest.class);
  private FakeResponse response = new FakeResponse();
  private CookieSessionMetadata cookieSessionMetadata =
      new CookieSessionMetadata(request, TEST_SECRET);

  private String createSignedCookie(String plainText) {
    try {
      byte[] payloadBytes = plainText.getBytes(US_ASCII);
      byte[] signatureBytes = calculateHmac(payloadBytes, TEST_SECRET);
      String encodedPayload = BaseEncoding.base64Url().encode(payloadBytes);
      String encodedSignature = BaseEncoding.base64Url().encode(signatureBytes);
      return encodedPayload + "." + encodedSignature;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static byte[] calculateHmac(byte[] data, String secret) throws Exception {
    SecretKeySpec signingKey = new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256");
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(signingKey);
    return mac.doFinal(data);
  }

  @Test
  void testNoCookie() {
    assertThat(cookieSessionMetadata.getRegistrarId()).isNull();
    assertThat(cookieSessionMetadata.getFailedLoginAttempts()).isEqualTo(0);
    assertThat(cookieSessionMetadata.getServiceExtensionUris()).isEmpty();
  }

  @Test
  void testCookieWithAllFields() {
    when(request.getHeader("Cookie"))
        .thenReturn(
            "THIS_COOKIE=foo; SESSION_INFO="
                + createSignedCookie(
                    "CookieSessionMetadata{clientId=test_registrar, failedLoginAttempts=5, "
                        + " serviceExtensionUris=A|B|C}")
                + "; THAT_COOKIE=bar");
    cookieSessionMetadata = new CookieSessionMetadata(request, TEST_SECRET);
    assertThat(cookieSessionMetadata.getRegistrarId()).isEqualTo("test_registrar");
    assertThat(cookieSessionMetadata.getFailedLoginAttempts()).isEqualTo(5);
    assertThat(cookieSessionMetadata.getServiceExtensionUris()).containsExactly("A", "B", "C");
  }

  @Test
  void testCookieWithNullRegistrar() {
    when(request.getHeader("Cookie"))
        .thenReturn(
            "SESSION_INFO="
                + createSignedCookie(
                    "CookieSessionMetadata{clientId=null, failedLoginAttempts=5, "
                        + " serviceExtensionUris=A|B|C}"));
    cookieSessionMetadata = new CookieSessionMetadata(request, TEST_SECRET);
    assertThat(cookieSessionMetadata.getRegistrarId()).isNull();
    assertThat(cookieSessionMetadata.getFailedLoginAttempts()).isEqualTo(5);
    assertThat(cookieSessionMetadata.getServiceExtensionUris()).containsExactly("A", "B", "C");
  }

  @Test
  void testCookieWithEmptyExtension() {
    when(request.getHeader("Cookie"))
        .thenReturn(
            "SESSION_INFO="
                + createSignedCookie(
                    "CookieSessionMetadata{clientId=test_registrar, failedLoginAttempts=5, "
                        + " serviceExtensionUris=}"));
    cookieSessionMetadata = new CookieSessionMetadata(request, TEST_SECRET);
    assertThat(cookieSessionMetadata.getRegistrarId()).isEqualTo("test_registrar");
    assertThat(cookieSessionMetadata.getFailedLoginAttempts()).isEqualTo(5);
    assertThat(cookieSessionMetadata.getServiceExtensionUris()).isEmpty();
  }

  @Test
  void testCookieWithSingleExtension() {
    when(request.getHeader("Cookie"))
        .thenReturn(
            "SESSION_INFO="
                + createSignedCookie(
                    "CookieSessionMetadata{clientId=test_registrar, failedLoginAttempts=5, "
                        + " serviceExtensionUris=Foo}"));
    cookieSessionMetadata = new CookieSessionMetadata(request, TEST_SECRET);
    assertThat(cookieSessionMetadata.getRegistrarId()).isEqualTo("test_registrar");
    assertThat(cookieSessionMetadata.getFailedLoginAttempts()).isEqualTo(5);
    assertThat(cookieSessionMetadata.getServiceExtensionUris()).containsExactly("Foo");
  }

  @Test
  void testIncrementFailedLoginAttempts() {
    when(request.getHeader("Cookie"))
        .thenReturn(
            "SESSION_INFO="
                + createSignedCookie(
                    "CookieSessionMetadata{clientId=test_registrar, failedLoginAttempts=5, "
                        + " serviceExtensionUris=Foo}"));
    cookieSessionMetadata = new CookieSessionMetadata(request, TEST_SECRET);
    cookieSessionMetadata.incrementFailedLoginAttempts();
    assertThat(cookieSessionMetadata.getRegistrarId()).isEqualTo("test_registrar");
    assertThat(cookieSessionMetadata.getFailedLoginAttempts()).isEqualTo(6);
    assertThat(cookieSessionMetadata.getServiceExtensionUris()).containsExactly("Foo");
  }

  @Test
  void testResetFailedLoginAttempts() {
    when(request.getHeader("Cookie"))
        .thenReturn(
            "SESSION_INFO="
                + createSignedCookie(
                    "CookieSessionMetadata{clientId=test_registrar, failedLoginAttempts=5, "
                        + " serviceExtensionUris=Foo}"));
    cookieSessionMetadata = new CookieSessionMetadata(request, TEST_SECRET);
    cookieSessionMetadata.resetFailedLoginAttempts();
    assertThat(cookieSessionMetadata.getRegistrarId()).isEqualTo("test_registrar");
    assertThat(cookieSessionMetadata.getFailedLoginAttempts()).isEqualTo(0);
    assertThat(cookieSessionMetadata.getServiceExtensionUris()).containsExactly("Foo");
  }

  @Test
  void testSetRegistrarId() {
    when(request.getHeader("Cookie"))
        .thenReturn(
            "SESSION_INFO="
                + createSignedCookie(
                    "CookieSessionMetadata{clientId=test_registrar, failedLoginAttempts=5, "
                        + " serviceExtensionUris=Foo}"));
    cookieSessionMetadata = new CookieSessionMetadata(request, TEST_SECRET);
    cookieSessionMetadata.setRegistrarId("new_registrar");
    assertThat(cookieSessionMetadata.getRegistrarId()).isEqualTo("new_registrar");
    assertThat(cookieSessionMetadata.getFailedLoginAttempts()).isEqualTo(5);
    assertThat(cookieSessionMetadata.getServiceExtensionUris()).containsExactly("Foo");
  }

  @Test
  void testSetExtensions() {
    when(request.getHeader("Cookie"))
        .thenReturn(
            "SESSION_INFO="
                + createSignedCookie(
                    "CookieSessionMetadata{clientId=test_registrar, failedLoginAttempts=5, "
                        + " serviceExtensionUris=Foo}"));
    cookieSessionMetadata = new CookieSessionMetadata(request, TEST_SECRET);
    cookieSessionMetadata.setServiceExtensionUris(ImmutableSet.of("Bar", "Baz", "foo:bar:baz-1.3"));
    assertThat(cookieSessionMetadata.getRegistrarId()).isEqualTo("test_registrar");
    assertThat(cookieSessionMetadata.getFailedLoginAttempts()).isEqualTo(5);
    assertThat(cookieSessionMetadata.getServiceExtensionUris())
        .containsExactly("Bar", "Baz", "foo:bar:baz-1.3");
  }

  @Test
  void testSetEmptyExtensions() {
    when(request.getHeader("Cookie"))
        .thenReturn(
            "SESSION_INFO="
                + createSignedCookie(
                    "CookieSessionMetadata{clientId=test_registrar, failedLoginAttempts=5, "
                        + " serviceExtensionUris=Foo}"));
    cookieSessionMetadata = new CookieSessionMetadata(request, TEST_SECRET);
    cookieSessionMetadata.setServiceExtensionUris(ImmutableSet.of());
    assertThat(cookieSessionMetadata.getRegistrarId()).isEqualTo("test_registrar");
    assertThat(cookieSessionMetadata.getFailedLoginAttempts()).isEqualTo(5);
    assertThat(cookieSessionMetadata.getServiceExtensionUris()).isEmpty();
  }

  @Test
  void testInvalidate() {
    when(request.getHeader("Cookie"))
        .thenReturn(
            "SESSION_INFO="
                + createSignedCookie(
                    "CookieSessionMetadata{clientId=test_registrar, failedLoginAttempts=5, "
                        + " serviceExtensionUris=Foo}"));
    cookieSessionMetadata = new CookieSessionMetadata(request, TEST_SECRET);
    cookieSessionMetadata.invalidate();
    assertThat(cookieSessionMetadata.getRegistrarId()).isNull();
    assertThat(cookieSessionMetadata.getFailedLoginAttempts()).isEqualTo(0);
    assertThat(cookieSessionMetadata.getServiceExtensionUris()).isEmpty();
  }

  @Test
  void testSave() {
    cookieSessionMetadata.save(response);
    String cookieHeader = response.getHeaders().get("Set-Cookie").toString();
    String cookieValue = cookieHeader.substring(COOKIE_NAME.length() + 1);

    // Verify the saved cookie is signed correctly and parses successfully
    CookieSessionMetadata verifyMetadata =
        new CookieSessionMetadata(mockRequestWithCookie(cookieValue), TEST_SECRET);
    assertThat(verifyMetadata.getRegistrarId()).isNull();
    assertThat(verifyMetadata.getFailedLoginAttempts()).isEqualTo(0);
    assertThat(verifyMetadata.getServiceExtensionUris()).isEmpty();

    cookieSessionMetadata.setRegistrarId("new_registrar");
    cookieSessionMetadata.setServiceExtensionUris(ImmutableSet.of("Bar", "Baz"));
    cookieSessionMetadata.incrementFailedLoginAttempts();
    cookieSessionMetadata.save(response);

    cookieHeader = response.getHeaders().get("Set-Cookie").toString();
    cookieValue = cookieHeader.substring(COOKIE_NAME.length() + 1);
    verifyMetadata = new CookieSessionMetadata(mockRequestWithCookie(cookieValue), TEST_SECRET);
    assertThat(verifyMetadata.getRegistrarId()).isEqualTo("new_registrar");
    assertThat(verifyMetadata.getFailedLoginAttempts()).isEqualTo(1);
    assertThat(verifyMetadata.getServiceExtensionUris()).containsExactly("Bar", "Baz");
  }

  @Test
  void testSignatureMismatch_rejected() {
    // Session cookie signed with a different key
    String invalidSignedCookie =
        "SESSION_INFO="
            + createSignedCookie(
                "CookieSessionMetadata{clientId=test_registrar, failedLoginAttempts=5, "
                    + " serviceExtensionUris=Foo}")
            + "; THAT_COOKIE=bar";

    // Re-verify using a different secret key
    HttpServletRequest badRequest = mock(HttpServletRequest.class);
    when(badRequest.getHeader("Cookie")).thenReturn(invalidSignedCookie);
    CookieSessionMetadata badMetadata =
        new CookieSessionMetadata(badRequest, "different-secret-key-32-chars-long");

    // The metadata should be parsed as empty/no session
    assertThat(badMetadata.getRegistrarId()).isNull();
    assertThat(badMetadata.getFailedLoginAttempts()).isEqualTo(0);
    assertThat(badMetadata.getServiceExtensionUris()).isEmpty();
  }

  private static HttpServletRequest mockRequestWithCookie(String cookieValue) {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getHeader("Cookie")).thenReturn("SESSION_INFO=" + cookieValue);
    return req;
  }
}

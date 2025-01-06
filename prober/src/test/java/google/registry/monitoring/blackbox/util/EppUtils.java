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

package google.registry.monitoring.blackbox.util;

import static google.registry.monitoring.blackbox.message.EppMessage.CLIENT_ID_KEY;
import static google.registry.monitoring.blackbox.message.EppMessage.CLIENT_PASSWORD_KEY;
import static google.registry.monitoring.blackbox.message.EppMessage.CLIENT_TRID_KEY;
import static google.registry.monitoring.blackbox.message.EppMessage.DOMAIN_KEY;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import google.registry.monitoring.blackbox.exception.EppClientException;
import google.registry.monitoring.blackbox.message.EppMessage;
import google.registry.monitoring.blackbox.message.EppRequestMessage;
import google.registry.monitoring.blackbox.message.EppResponseMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import org.w3c.dom.Document;

/** Houses static utility functions for testing EPP components of Prober. */
public class EppUtils {

  /** Return a simple default greeting as a {@link Document}. */
  public static Document getGreeting() throws IOException, EppClientException {
    return EppMessage.getEppDocFromTemplate("greeting.xml", ImmutableMap.of());
  }

  /**
   * Return a basic response as a {@link Document}.
   *
   * @param success specifies if response shows a success or failure
   * @param clTrid the client transaction ID
   * @param svTrid the server transaction ID
   * @return the EPP basic response as a {@link Document}.
   */
  public static Document getBasicResponse(boolean success, String clTrid, String svTrid)
      throws IOException, EppClientException {

    String template = success ? "success_response.xml" : "failed_response.xml";

    return EppMessage.getEppDocFromTemplate(
        template,
        ImmutableMap.of(
            EppRequestMessage.CLIENT_TRID_KEY, clTrid, EppMessage.SERVER_TRID_KEY, svTrid));
  }

  /**
   * Return a domain check response as a {@link Document}.
   *
   * @param exists specifies if response shows that domain name exists on server or doesn't
   * @param clTrid the client transaction ID
   * @param svTrid the server transaction ID
   * @param domain the domain the check success is for
   * @return the EPP check response as a {@link Document}.
   */
  public static Document getDomainCheck(boolean exists, String clTrid, String svTrid, String domain)
      throws IOException, EppClientException {

    String template = exists ? "domain_exists.xml" : "domain_not_exists.xml";

    return EppMessage.getEppDocFromTemplate(
        template,
        ImmutableMap.of(
            EppRequestMessage.CLIENT_TRID_KEY, clTrid,
            EppMessage.SERVER_TRID_KEY, svTrid,
            EppMessage.DOMAIN_KEY, domain));
  }

  /** Converts {@link Document} to {@link ByteBuf}. */
  public static ByteBuf docToByteBuf(Document message) throws EppClientException {
    byte[] bytestream = EppMessage.xmlDocToByteArray(message);

    ByteBuf buf = Unpooled.buffer(bytestream.length);

    buf.writeBytes(bytestream);

    return buf;
  }

  /** Returns standard hello request with supplied response. */
  public static EppRequestMessage getHelloMessage(EppResponseMessage greetingResponse) {
    return new EppRequestMessage("hello", greetingResponse, null, (a, b) -> ImmutableMap.of());
  }

  /** Returns standard login request with supplied userId, userPassword, and response. */
  public static EppRequestMessage getLoginMessage(
      EppResponseMessage response, String userId, String userPassword) {
    return new EppRequestMessage(
        "login",
        response,
        "login.xml",
        (clTrid, domain) ->
            ImmutableMap.of(
                CLIENT_TRID_KEY, clTrid,
                CLIENT_ID_KEY, userId,
                CLIENT_PASSWORD_KEY, userPassword));
  }

  /** Returns standard create request with supplied response. */
  public static EppRequestMessage getCreateMessage(EppResponseMessage response) {
    return new EppRequestMessage(
        "create",
        response,
        "create.xml",
        (clTrid, domain) ->
            ImmutableMap.of(
                CLIENT_TRID_KEY, clTrid,
                DOMAIN_KEY, domain));
  }

  /** Returns standard delete request with supplied response. */
  public static EppRequestMessage getDeleteMessage(EppResponseMessage response) {
    return new EppRequestMessage(
        "delete",
        response,
        "delete.xml",
        (clTrid, domain) ->
            ImmutableMap.of(
                CLIENT_TRID_KEY, clTrid,
                DOMAIN_KEY, domain));
  }

  /** Returns standard logout request with supplied response. */
  public static EppRequestMessage getLogoutMessage(EppResponseMessage successResponse) {
    return new EppRequestMessage(
        "logout",
        successResponse,
        "logout.xml",
        (clTrid, domain) -> ImmutableMap.of(CLIENT_TRID_KEY, clTrid));
  }

  /** Returns standard check request with supplied response. */
  public static EppRequestMessage getCheckMessage(EppResponseMessage response) {
    return new EppRequestMessage(
        "check",
        response,
        "check.xml",
        (clTrid, domain) ->
            ImmutableMap.of(
                CLIENT_TRID_KEY, clTrid,
                DOMAIN_KEY, domain));
  }

  /** Returns standard success response. */
  public static EppResponseMessage getSuccessResponse() {
    return new EppResponseMessage(
        "success",
        (clTrid, domain) ->
            ImmutableList.of(
                String.format("//eppns:clTRID[.='%s']", clTrid), EppMessage.XPASS_EXPRESSION));
  }

  /** Returns standard failure response. */
  public static EppResponseMessage getFailureResponse() {
    return new EppResponseMessage(
        "failure",
        (clTrid, domain) ->
            ImmutableList.of(
                String.format("//eppns:clTRID[.='%s']", clTrid), EppMessage.XFAIL_EXPRESSION));
  }

  /** Returns standard domainExists response. */
  public static EppResponseMessage getDomainExistsResponse() {
    return new EppResponseMessage(
        "domainExists",
        (clTrid, domain) ->
            ImmutableList.of(
                String.format("//eppns:clTRID[.='%s']", clTrid),
                String.format("//domainns:name[@avail='false'][.='%s']", domain),
                EppMessage.XPASS_EXPRESSION));
  }

  /** Returns standard domainNotExists response. */
  public static EppResponseMessage getDomainNotExistsResponse() {
    return new EppResponseMessage(
        "domainNotExists",
        (clTrid, domain) ->
            ImmutableList.of(
                String.format("//eppns:clTRID[.='%s']", clTrid),
                String.format("//domainns:name[@avail='true'][.='%s']", domain),
                EppMessage.XPASS_EXPRESSION));
  }

  /** Returns standard greeting response. */
  public static EppResponseMessage getGreetingResponse() {
    return new EppResponseMessage(
        "greeting", (clTrid, domain) -> ImmutableList.of("//eppns:greeting"));
  }
}

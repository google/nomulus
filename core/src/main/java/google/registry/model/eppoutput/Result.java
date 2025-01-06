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

package google.registry.model.eppoutput;

import static google.registry.util.XmlEnumUtils.enumToXml;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import google.registry.model.ImmutableObject;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlEnumValue;

/**
 * "If the command was processed successfully, only one {@code Result} element MUST be returned. If
 * the command was not processed successfully, multiple {@code Result} elements MAY be returned to
 * document failure conditions."
 */
public class Result extends ImmutableObject {

  /**
   * "EPP result codes are based on the theory of reply codes described in section 4.2.1 of
   * [RFC5321]. EPP uses four decimal digits to describe the success or failure of each EPP command.
   * Each of the digits of the reply have special significance."
   *
   * <p>"The first digit denotes command success or failure. The second digit denotes the response
   * category, such as command syntax or security. The third and fourth digits provide explicit
   * response detail within each response category."
   */
  public enum Code {
    @XmlEnumValue("1000")
    SUCCESS("Command completed successfully"),

    @XmlEnumValue("1001")
    SUCCESS_WITH_ACTION_PENDING("Command completed successfully; action pending"),

    @XmlEnumValue("1300")
    SUCCESS_WITH_NO_MESSAGES("Command completed successfully; no messages"),

    @XmlEnumValue("1301")
    SUCCESS_WITH_ACK_MESSAGE("Command completed successfully; ack to dequeue"),

    @XmlEnumValue("1500")
    SUCCESS_AND_CLOSE("Command completed successfully; ending session"),

    @XmlEnumValue("2000")
    UNKNOWN_COMMAND("Unknown command"),

    @XmlEnumValue("2001")
    SYNTAX_ERROR("Command syntax error"),

    @XmlEnumValue("2002")
    COMMAND_USE_ERROR("Command use error"),

    @XmlEnumValue("2003")
    REQUIRED_PARAMETER_MISSING("Required parameter missing"),

    @XmlEnumValue("2004")
    PARAMETER_VALUE_RANGE_ERROR("Parameter value range error"),

    @XmlEnumValue("2005")
    PARAMETER_VALUE_SYNTAX_ERROR("Parameter value syntax error"),

    @XmlEnumValue("2100")
    UNIMPLEMENTED_PROTOCOL_VERSION("Unimplemented protocol version"),

    @XmlEnumValue("2101")
    UNIMPLEMENTED_COMMAND("Unimplemented command"),

    @XmlEnumValue("2102")
    UNIMPLEMENTED_OPTION("Unimplemented option"),

    @XmlEnumValue("2103")
    UNIMPLEMENTED_EXTENSION("Unimplemented extension"),

    @XmlEnumValue("2200")
    AUTHENTICATION_ERROR("Authentication error"),

    @XmlEnumValue("2201")
    AUTHORIZATION_ERROR("Authorization error"),

    @XmlEnumValue("2202")
    INVALID_AUTHORIZATION_INFORMATION_ERROR("Invalid authorization information"),

    @XmlEnumValue("2300")
    OBJECT_PENDING_TRANSFER("Object pending transfer"),

    @XmlEnumValue("2301")
    OBJECT_NOT_PENDING_TRANSFER("Object not pending transfer"),

    @XmlEnumValue("2302")
    OBJECT_EXISTS("Object exists"),

    @XmlEnumValue("2303")
    OBJECT_DOES_NOT_EXIST("Object does not exist"),

    @XmlEnumValue("2304")
    STATUS_PROHIBITS_OPERATION("Object status prohibits operation"),

    @XmlEnumValue("2305")
    ASSOCIATION_PROHIBITS_OPERATION("Object association prohibits operation"),

    @XmlEnumValue("2306")
    PARAMETER_VALUE_POLICY_ERROR("Parameter value policy error"),

    @XmlEnumValue("2307")
    UNIMPLEMENTED_OBJECT_SERVICE("Unimplemented object service"),

    @XmlEnumValue("2400")
    COMMAND_FAILED("Command failed"),

    @XmlEnumValue("2501")
    AUTHENTICATION_ERROR_CLOSING_CONNECTION("Authentication error; server closing connection");

    /** A four-digit (positive) number that describes the success or failure of the command. */
    public final int code;

    /** A human-readable description of the response code. */
    public final String msg;

    /**
     * An RFC 4646 language code.
     *
     * @see <a href="http://tools.ietf.org/html/rfc4646">
     *     RFC 4646 - Tags for Identifying Languages</a>
     */
    public final String msgLang;

    /** @param msg a human-readable description of the response code; required.  */
    Code(String msg) {
      this.code = Integer.parseInt(enumToXml(this));
      Preconditions.checkArgument(
          (int) Math.log10(code) == 3,
          "Response code must be a four-digit (positive) number.");
      this.msg = Preconditions.checkNotNull(msg, "A message must be specified.");
      this.msgLang = "en";  // All of our messages are English.
    }

    /** Returns true iff the response code is in the 1xxx category, representing success. */
    public boolean isSuccess() {
      return code < 2000;
    }
  }

  /** The result code for this result. This is always present. */
  @XmlAttribute
  Code code;

  /** An explanation of the result code. */
  String msg;

  public Code getCode() {
    return code;
  }

  public String getMsg() {
    return msg;
  }

  public static Result create(Code code, String msg) {
    Result instance = new Result();
    instance.code = code;
    // If no message was set, pick up a default message from the Code enum.
    Preconditions.checkState(
        !code.isSuccess() || msg == null,
        "Only error result codes may have a message");
    instance.msg = MoreObjects.firstNonNull(msg, code.msg);
    return instance;
  }

  public static Result create(Code code) {
    return create(code, null);
  }
}

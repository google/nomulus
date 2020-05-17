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

package google.registry.model.domain.launch;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.hash.Hashing.crc32;
import static com.google.common.io.BaseEncoding.base16;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.google.common.base.Ascii;
import com.google.common.base.CharMatcher;
import com.google.common.primitives.Ints;
import com.googlecode.objectify.annotation.Embed;
import com.googlecode.objectify.annotation.IgnoreSave;
import com.googlecode.objectify.condition.IfNull;
import google.registry.model.ImmutableObject;
import java.util.Optional;
import javax.persistence.Embedded;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlType;
import javax.xml.bind.annotation.XmlValue;
import org.joda.time.DateTime;

/** The claims notice id from the claims phase. */
@Embed
@XmlType(propOrder = {"noticeId", "expirationTime", "acceptedTime"})
@javax.persistence.Embeddable
public class LaunchNotice extends ImmutableObject {

  /** An empty instance to use in place of null. */
  private static final NoticeIdType EMPTY_NOTICE_ID = new NoticeIdType();

  /** An id with a validator-id attribute. */
  @Embed
  @javax.persistence.Embeddable
  public static class NoticeIdType extends ImmutableObject {

    /**
     * The Trademark Claims Notice ID from
     * {@link "http://tools.ietf.org/html/draft-lozano-tmch-func-spec-08#section-6.3"}.
     */
    @XmlValue
    String tcnId;

    /** The identifier of the TMDB provider to use, defaulting to the TMCH. */
    @IgnoreSave(IfNull.class)
    @XmlAttribute(name = "validatorID")
    String validatorId;

    public String getTcnId() {
      return tcnId;
    }

    public String getValidatorId() {
      // The default value is "tmch".
      return Optional.ofNullable(validatorId).orElse("tmch");
    }
  }

  @XmlElement(name = "noticeID")
  @Embedded
  NoticeIdType noticeId;

  @XmlElement(name = "notAfter")
  DateTime expirationTime;

  @XmlElement(name = "acceptedDate")
  DateTime acceptedTime;

  public NoticeIdType getNoticeId() {
    return Optional.ofNullable(noticeId).orElse(EMPTY_NOTICE_ID);
  }

  public DateTime getExpirationTime() {
    return expirationTime;
  }

  public DateTime getAcceptedTime() {
    return acceptedTime;
  }

  /**
   * Validate the checksum of the notice against the domain label.
   */
  public void validate(String domainLabel) throws InvalidChecksumException {
    // According to http://tools.ietf.org/html/draft-lozano-tmch-func-spec-08#section-6.3, a TCNID
    // is always 8 chars of checksum + 19 chars of a decimal notice id. Check the length before
    // taking substrings to avoid an IndexOutOfBoundsException.
    String tcnId = getNoticeId().getTcnId();
    checkArgument(tcnId.length() == 27);

    int checksum = Ints.fromByteArray(base16().decode(Ascii.toUpperCase(tcnId.substring(0, 8))));
    String noticeId = tcnId.substring(8);
    checkArgument(CharMatcher.inRange('0', '9').matchesAllOf(noticeId));

    // The checksum in the first 8 chars must match the crc32 of label + expiration + notice id.
    String stringToHash =
        domainLabel + MILLISECONDS.toSeconds(getExpirationTime().getMillis()) + noticeId;
    int computedChecksum = crc32().hashString(stringToHash, UTF_8).asInt();
    if (checksum != computedChecksum) {
      throw new InvalidChecksumException();
    }
  }

  /** Thrown from validate() if the checksum is invalid. */
  public static class InvalidChecksumException extends Exception {}

  public static LaunchNotice create(
      String tcnId, String validatorId, DateTime expirationTime, DateTime acceptedTime) {
    LaunchNotice instance = new LaunchNotice();
    instance.noticeId = new NoticeIdType();
    instance.noticeId.tcnId = tcnId;
    instance.noticeId.validatorId = "tmch".equals(validatorId) ? null : validatorId;
    instance.expirationTime = expirationTime;
    instance.acceptedTime = acceptedTime;
    return instance;
  }
}

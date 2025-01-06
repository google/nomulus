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

package google.registry.util;

import com.google.auto.value.AutoBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.MediaType;
import jakarta.mail.internet.InternetAddress;
import java.util.Collection;
import java.util.Optional;

/**
 * Record representing the content and metadata of an email.
 *
 * <p>The sender address and display name are set by the email client and are not customizable by
 * the user.
 *
 * @param replyToEmailAddress Optional return email address that overrides the default.
 */
public record EmailMessage(
    String subject,
    String body,
    ImmutableSet<InternetAddress> recipients,
    Optional<InternetAddress> replyToEmailAddress,
    ImmutableSet<InternetAddress> ccs,
    ImmutableSet<InternetAddress> bccs,
    Optional<MediaType> contentType,
    Optional<Attachment> attachment) {

  public static Builder newBuilder() {
    return new AutoBuilder_EmailMessage_Builder();
  }

  public static EmailMessage create(String subject, String body, InternetAddress recipient) {
    return newBuilder()
        .setSubject(subject)
        .setBody(body)
        .setRecipients(ImmutableList.of(recipient))
        .build();
  }

  /** Builder for {@link EmailMessage}. */
  @AutoBuilder
  public interface Builder {

    Builder setSubject(String subject);

    Builder setBody(String body);

    Builder setRecipients(Collection<InternetAddress> recipients);

    Builder setReplyToEmailAddress(InternetAddress replyToEmailAddress);

    Builder setReplyToEmailAddress(Optional<InternetAddress> replyToEmailAddress);

    Builder setBccs(Collection<InternetAddress> bccs);

    Builder setCcs(Collection<InternetAddress> ccs);

    Builder setContentType(MediaType contentType);

    Builder setAttachment(Attachment attachment);

    ImmutableSet.Builder<InternetAddress> recipientsBuilder();

    ImmutableSet.Builder<InternetAddress> bccsBuilder();

    ImmutableSet.Builder<InternetAddress> ccsBuilder();

    default Builder addRecipient(InternetAddress value) {
      recipientsBuilder().add(value);
      return this;
    }

    default Builder addBcc(InternetAddress bcc) {
      bccsBuilder().add(bcc);
      return this;
    }

    default Builder addCc(InternetAddress cc) {
      ccsBuilder().add(cc);
      return this;
    }

    EmailMessage build();
  }

  /** An attachment to the email, if one exists. */
  public record Attachment(MediaType contentType, String filename, String content) {
    public static Builder newBuilder() {
      return new AutoBuilder_EmailMessage_Attachment_Builder();
    }

    /** Builder for {@link Attachment}. */
    @AutoBuilder
    public interface Builder {
      Builder setContentType(MediaType contentType);

      Builder setFilename(String filename);

      Builder setContent(String content);

      Attachment build();
    }
  }
}

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

package google.registry.model.poll;

import com.google.common.base.Splitter;
import google.registry.persistence.VKey;
import java.util.List;

/**
 * A converter between external key strings for {@link PollMessage}s (i.e. what registrars use to
 * identify and ACK them) and {@link VKey}s to the resource.
 *
 * <p>The format of the key string is A-B as follows:
 *
 * <pre>
 *   A = PollMessage.id (decimal)
 *   B = PollMessage.eventTime (decimal, year only)
 * </pre>
 *
 * <p>A typical poll message ID might thus look like: 10072612074-2018
 */
public final class PollMessageExternalKeyConverter {

  /** An exception thrown when an external key cannot be parsed. */
  public static class PollMessageExternalKeyParseException extends RuntimeException {

    private static final long serialVersionUID = 9026397649048831777L;
  }

  /** Returns an external poll message ID for the given poll message. */
  public static String makePollMessageExternalId(PollMessage pollMessage) {
    return String.format("%d-%d", pollMessage.getId(), pollMessage.getEventTime().getYear());
  }

  /**
   * Returns a {@link VKey} to a {@link PollMessage} corresponding with the external ID.
   *
   * <p>Note that the year field that is included at the end of the poll message isn't actually used
   * for anything; it exists solely to create unique externally visible IDs for autorenews. We thus
   * ignore it (for now) for backwards compatibility reasons, so that registrars can still ACK
   * existing poll message IDs they may have lying around.
   *
   * @throws PollMessageExternalKeyParseException if the external key has an invalid format.
   */
  public static VKey<PollMessage> parsePollMessageExternalId(String externalKey) {
    List<String> idComponents = Splitter.on('-').splitToList(externalKey);
    if (idComponents.size() != 2) {
      throw new PollMessageExternalKeyParseException();
    }
    try {
      Long id = Long.parseLong(idComponents.get(0));
      return VKey.create(PollMessage.class, id);
      // Note that idComponents.get(1) is entirely ignored; we never use the year field internally.
    } catch (NumberFormatException e) {
      throw new PollMessageExternalKeyParseException();
    }
  }

  private PollMessageExternalKeyConverter() {}
}

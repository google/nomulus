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

import static java.util.Objects.hash;

import com.googlecode.objectify.annotation.Embed;
import google.registry.model.ImmutableObject;
import java.util.Objects;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlValue;

/**
 * The launch phase of the TLD being addressed by this command.
 *
 * <p>The launch phase refers to the various stages that a TLD goes through before entering general
 * availability. The various phases are described below (in order that they usually occur).
 *
 * <p>Each phase is connected to the TldState in which it can be used (see DomainFlowUtils). Sending
 * an EPP with the wrong launch phase for the current TldState will result in an error. However, we
 * don't actually check the launch phase *exists*.
 *
 * <p>We usually check for the information *inside* a launch phase (e.g. the signed mark for the
 * domain) and return an error if that is missing - which would also check that the phase exists.
 * But if we bypass the need for that information (e.g., an Anchor Tenant doesn't need a signed
 * mark), then we never actually test the phase exists.
 *
 * <p>This means an Anchor Tenant has some weird peculiarities: It doesn't need to specify the
 * phase. It *can* specify the phase, but not give any of the phase's information (e.g. - signed
 * marks), in which case we accept even a wrong phase. But if it *does* give a signed mark as well -
 * we will return an error if it's the wrong phase (or if the marks are invalid) even though we
 * didn't require them.
 *
 * <p>This is OK (?) because the Anchor Tenants field is set internally and manually. The person who
 * sets it is the one that needs to make sure the domain isn't a trademark and that the fields are
 * correct.
 */
@Embed
public class LaunchPhase extends ImmutableObject {

  /**
   * The phase during which trademark holders can submit domain registrations with trademark
   * information that can be validated by the server.
   */
  public static final LaunchPhase SUNRISE = create("sunrise");

  /**
   * The Trademark Claims phase, as defined in the TMCH Functional Specification, in which a Claims
   * Notice must be displayed to a prospective registrant of a domain name that matches trademarks.
   */
  public static final LaunchPhase CLAIMS = create("claims");

  /** A post-launch phase that is also referred to as "steady state". */
  public static final LaunchPhase OPEN = create("open");

  /** Private create function for the typesafe enum pattern. */
  public static LaunchPhase create(String phase) {
    LaunchPhase instance = new LaunchPhase();
    instance.phase = phase;
    return instance;
  }

  @XmlValue String phase;

  /**
   * Holds the name of a custom phase if the main phase is "custom", or a sub-phase for all other
   * values.
   *
   * <p>This is currently unused, but is retained so that incoming XMLs that include a subphase can
   * have it be reflected back.
   */
  @XmlAttribute(name = "name")
  String subphase;

  public String getPhase() {
    return phase;
  }

  /** A special equals implementation that only considers the string value. */
  @Override
  public boolean equals(Object other) {
    return other instanceof LaunchPhase
        && Objects.equals(phase, ((LaunchPhase) other).phase)
        && Objects.equals(subphase, ((LaunchPhase) other).subphase);
  }

  /** A special hashCode implementation that only considers the string value. */
  @Override
  public int hashCode() {
    return hash(phase, subphase);
  }
}

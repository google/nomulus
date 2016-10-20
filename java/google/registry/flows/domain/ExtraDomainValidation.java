package google.registry.flows.domain;

import org.joda.time.DateTime;

import google.registry.flows.EppException;
import google.registry.model.domain.launch.LaunchCreateExtension;
import google.registry.model.registry.label.ReservationType;

/** Provides an injection point for custom Domain name validation during flows */
public interface ExtraDomainValidation {

  /**
   * An Injection point for domain create logic
   *
   * @param label the second level domain name string
   * @param tld the top level domain name string
   * @param launchCreate an epp create extension
   * @param now the date time this flow was executed
   * @throws EppException if the custom validation fails
   */
  void validateDomainCreate(
      final String label,
      final String tld,
      final LaunchCreateExtension launchCreate,
      final DateTime now)
      throws EppException;

  /**
   * Checks if the provided label is blocked by any extra domain validation
   *
   * @param label the second level domain name string
   * @param tld the top level domain name string
   * @param reservationType enum describing reservation on a label
   * @param now the date time this flow was executed
   * @return the reason the domain is blocked or null if not blocked
   */
  String getExtraValidationBlockMessage(
      final String label,
      final String tld,
      final ReservationType reservationType,
      final DateTime now);
}

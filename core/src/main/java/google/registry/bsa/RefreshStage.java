package google.registry.bsa;

public enum RefreshStage {
  /**
   * Checks for stale unblockable domains. The output is a stream of {@link
   * google.registry.bsa.api.UnblockableDomainChange} objects that describe the stale domains.
   */
  CHECK_FOR_CHANGES,
  /** Fixes the stale domains in the database. */
  APPLY_CHANGES,
  /** Reports the unblockable domains to be removed to BSA. */
  UPLOAD_REMOVALS,
  /** Reports the newly found unblockable domains to BSA. */
  UPLOAD_ADDITIONS,
  DONE;
}

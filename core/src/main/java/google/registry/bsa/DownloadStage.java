// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

package google.registry.bsa;

/** The processing stages of a download. */
public enum DownloadStage {
  /** Downloads BSA block list files. */
  DOWNLOAD,
  /** Generates block list diffs with the previous download. */
  MAKE_DIFF,
  /** Applies the label diffs to the database tables. */
  APPLY_DIFF,
  /**
   * Makes a REST API call to BSA endpoint, declaring that processing starts for new orders in the
   * diffs.
   */
  START_UPLOADING,
  /** Makes a REST API call to BSA endpoint, sending the domains that cannot be blocked. */
  UPLOAD_DOMAINS_IN_USE,
  /** Makes a REST API call to BSA endpoint, declaring the completion of order processing. */
  FINISH_UPLOADING,
  /** The terminal stage after processing succeeds. */
  DONE,
  /**
   * The terminal stage indicating that the downloads are discarded because their checksums are the
   * same as that of the previous download.
   */
  NOP,
  /**
   * The terminal stage indicating that the downloads are not processed because their BSA-generated
   * checksums do not match those calculated by us.
   */
  CHECKSUMS_NOT_MATCH;
}

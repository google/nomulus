#standardSQL
  -- Copyright 2017 The Nomulus Authors. All Rights Reserved.
  --
  -- Licensed under the Apache License, Version 2.0 (the "License");
  -- you may not use this file except in compliance with the License.
  -- You may obtain a copy of the License at
  --
  --     http://www.apache.org/licenses/LICENSE-2.0
  --
  -- Unless required by applicable law or agreed to in writing, software
  -- distributed under the License is distributed on an "AS IS" BASIS,
  -- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  -- See the License for the specific language governing permissions and
  -- limitations under the License.

  -- Retrieve per-TLD DNS query counts.

  -- This is a hack to enable using DNS counts from the internal-only #plx
  -- workflow. See other references to b/67301320 in the codebase to see the
  -- full extent of the hackery.
  -- TODO(b/67301320): Delete this when we can make open-source DNS metrics.

SELECT *
FROM `domain-registry-alpha.icann_reporting.dns_counts_from_plx`

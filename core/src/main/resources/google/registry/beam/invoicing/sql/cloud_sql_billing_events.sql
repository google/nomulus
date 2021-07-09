--Copyright 2021 The Nomulus Authors. All Rights Reserved.
--
--Licensed under the Apache License, Version 2.0 (the "License");
--you may not use this file except in compliance with the License.
--You may obtain a copy of the License at
--
--    http://www.apache.org/licenses/LICENSE-2.0
--
--Unless required by applicable law or agreed to in writing, software
--distributed under the License is distributed on an "AS IS" BASIS,
--WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
--See the License for the specific language governing permissions and
--limitations under the License.

-- This query gathers all non-canceled billing events for a given
-- YEAR_MONTH in yyyy-MM format.

 select b, r
 from BillingEvent b join Registrar r on b.clientId = r.clientIdentifier
 join Domain d on b.domainRepoId = d.repoId
 join Tld t on t.tldStrId = d.tld
 left join BillingCancellation c on b.id = c.refOneTime.billingId
 left join BillingCancellation cr on b.cancellationMatchingBillingEvent = cr.refRecurring.billingId
 where r.billingIdentifier != null
 and r.type = 'REAL'
 and t.invoicingEnabled = true
 and b.billingTime between CAST('%FIRST_TIMESTAMP_OF_MONTH%' AS timestamp) and CAST('%LAST_TIMESTAMP_OF_MONTH%' AS timestamp)
 and c.id = null
 and cr.id = null

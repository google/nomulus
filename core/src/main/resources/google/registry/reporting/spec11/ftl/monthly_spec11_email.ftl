<#ftl output_format="HTML">
<#-- Copyright 2026 The Nomulus Authors. All Rights Reserved. -->

<p>Dear registrar partner,</p>

<p>${registry} previously notified you when the following domains managed by your
  registrar were flagged for potential security concerns.</p>

<p>The following domains that you manage continue to be flagged by our analysis for
  potential security concerns. This may be because the registrants have not completed the
  requisite steps to mitigate the potential security abuse and/or have it reviewed and
  delisted.</p>

<table>
  <tr>
    <th>Domain Name</th>
    <th>Threat Type</th>
  </tr>
  <#list threats as threat>
    <tr>
      <td>${threat.domainName}</td>
      <td>${threat.threatType}</td>
    </tr>
  </#list>
</table>

<p>Please work with the registrant to mitigate any security issues and have the
  domains delisted. If you believe that any of the domains were reported in error, or are
  still receiving reports for issues that have been remediated,
  please <a href="https://safebrowsing.google.com/safebrowsing/report_error/?hl=en">submit a
  request</a> to have the site reviewed.</p>

<#if (resources?size > 0)>
  <p>Some helpful resources for getting off a blocked list include:</p>
  <ul>
    <#list resources as resource>
      <li>${resource}</li>
    </#list>
  </ul>
</#if>

<p>You will continue to receive a monthly summary of all domains managed by your registrar
  that remain on our lists of potential security threats. You will also receive a daily
  notice when any new domains are added to these lists.</p>

<p>If you have any questions regarding this notice, please contact ${replyToEmail}.</p>

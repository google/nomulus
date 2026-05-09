<#ftl output_format="HTML">
<#-- Copyright 2026 The Nomulus Authors. All Rights Reserved. -->

<p>Dear registrar partner,</p>

<p>${registry} conducts a daily analysis of all domains registered in its TLDs to
  identify potential security concerns. On ${date}, the following domains that your
  registrar manages were flagged for potential security concerns:</p>

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

<p><b>Please communicate these findings to the registrant and work with the
  registrant to mitigate any security issues and have the domains delisted.</b></p>

<#if (resources?size > 0)>
  <p>Some helpful resources for getting off a blocked list include:</p>
  <ul>
    <#list resources as resource>
      <li>${resource}</li>
    </#list>
  </ul>
</#if>

<p>If you believe that any of the domains were reported in error, or are still receiving
  reports for issues that have been remediated,
  please <a href="https://safebrowsing.google.com/safebrowsing/report_error/?hl=en">submit
  a request</a> to have the site reviewed.</p>

<p>You will continue to receive daily notices when new domains managed by your registrar
  are flagged for abuse, as well as a monthly summary of all of your domains under management
  that remain flagged for abuse.</p>

<p>If you would like to change the email to which these notices are sent, please update your
  abuse contact using your registrar portal account.</p>

<p>If you have any questions regarding this notice, please contact ${replyToEmail}.</p>

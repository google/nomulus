# RDE Deposits

## Overview

Many registries are required by ICANN to make daily escrow deposits of the
registry data. Note that this applies mainly to gTLDs. ccTLDs do not generally
have this requirement, in which case the following would not apply.

The RDE process takes care of escrow deposit processing. It happens in three
phases:

1.  [Staging](https://github.com/google/nomulus/blob/master/java/google/registry/rde/RdeStagingAction.java):
    Generate XML deposit and XML report files on Google Cloud Storage.
2.  [Upload](https://github.com/google/nomulus/blob/master/java/google/registry/rde/RdeUploadAction.java):
    Transmit XML deposit to the escrow provider via sFTP.
3.  [Report](https://github.com/google/nomulus/blob/master/java/google/registry/rde/RdeReportAction.java):
    Transmit XML *report* file to ICANN via HTTPS.

Each phase happens with an App Engine task queue entry that retries on failure.
When each task succeeds, it automatically enqueues a task for the next phase in
the process. The staging files are stored in Google Cloud Storage indefinitely,
encrypted with the GhostRyDE container format.

Note that in order for the automated RDE processing to work correctly, you will
need to implement a working and secure key store from which RDE can pull the
private key used to transmit the deposits via sFTP.

For each phase and TLD in the process, the system maintains a `Cursor` entity in
the database, which contains a timestamp indicating that everything before the
timestamp is current (except for RDE_UPLOAD_SFTP, which works a little
differently). Only if the current time is after the cursor time do the actions
check to see if they have work to do. For RDE, there are separate cursor types
for each phase, plus the special RDE_UPLOAD_SFTP cursor:

*   `RDE_STAGING`
*   `RDE_UPLOAD`
*   `RDE_UPLOAD_SFTP`
*   `RDE_REPORT`

To view the cursors, you can use the `nomulus list_cursors` command. Here are
some examples:

```shell
$ nomulus -e production list_cursors --type RDE_STAGING
2015-05-22T00:00:00.000Z tld1
2015-05-22T00:00:00.000Z tld2
2015-05-22T00:00:00.000Z tld3
2015-05-22T00:00:00.000Z tld4
[....]
$ nomulus -e production list_cursors --type RDE_UPLOAD
2015-05-22T00:00:00.000Z tld1
2015-05-22T00:00:00.000Z tld2
2015-05-22T00:00:00.000Z tld3
2015-05-22T00:00:00.000Z tld4
[...]
$ nomulus -e production list_cursors --type RDE_REPORT
2015-05-22T00:00:00.000Z tld1
2015-05-22T00:00:00.000Z tld2
2015-05-22T00:00:00.000Z tld3
2015-05-22T00:00:00.000Z tld4
[....]
```

In this example, RDE deposits for all four TLDs are up to date through the day
before May 22, 2015. The first time that the RDE staging action runs after
midnight on 2015-05-22, it will realize that a new day's worth of deposits needs
to be generated, and will kick off the series of phases. As each phase is
completed for a particular TLD, the appropriate cursor will be updated to
2015-05-23. If everything is operating normally, you should see all three
cursors set to tomorrow for all TLDs.

As a special case, if a cursor is not yet defined, it is treated as if it were
set to today. This will happen when a TLD begins operating. The first time the
staging job runs, it will assume that the cursor is set to today, generate a
deposit for the TLD today, and set the cursor to tomorrow. Likewise, the first
time the upload job runs, it will upload today's data, if available, and set the
cursor to tomorrow. One thing to be aware of: If, as sometimes happens with new
TLDs, something is misconfigured, and the RDE staging job is unable to generate
a deposit, the cursors will stay undefined. After the problem is resolved, the
RDE process will begin generating and uploading with the current day, because
undefined cursors are always assumed to be set to today. If ICANN requires that
the deposits be backfilled to some date, all three cursors (`RDE_STAGING`,
`RDE_UPLOAD` and `RDE_REPORT`) should be manually set to the first day to be
backfilled, using the `nomulus` tool. The system will then generate and upload
the missing daily deposits at the rate of one deposit every four hours,
eventually catching up.

The `RDE_UPLOAD_SFTP` cursor is used to avoid uploading multiple files to the
escrow provider within too short a time span, as described below. Therefore, the
values of the cursor are a little different. Rather than pointing to tomorrow,
they point to the last time that an RDE deposit for the specified TLD was
uploaded to the escrow provider. This will usually be some time between midnight
and 2:00 am of the current day. When it runs, the RDE upload action checks that
the current time is after the RDE_UPLOAD_SFTP cursor value plus a cooldown
period set by the `rdeUploadSftpCooldown` config parameter (usually, two hours).
If not, the upload is delayed until the next run of the action (usually, four
hours). If the `RDE_UPLOAD_SFTP` cursor is not yet defined, the system assumes
that no cooldown period is necessary.

## Listing deposits in Cloud Storage

You can list the files in Cloud Storage for a given TLD using the gcloud storage tool.
All files are stored in the {PROJECT-ID}-rde bucket, where {PROJECT-ID} is the
name of the App Engine project for the particular environment you are checking.

```shell
$ gcloud storage ls gs://{PROJECT-ID}-rde/zip_2015-05-16*
gs://{PROJECT-ID}-rde/zip_2015-05-16-report.xml.ghostryde
gs://{PROJECT-ID}-rde/zip_2015-05-16.xml.ghostryde
gs://{PROJECT-ID}-rde/zip_2015-05-16.xml.length
```

## Normal launch

Under normal circumstances, RDE is launched by TldFanoutAction, configured in
cron.xml. If the App Engine's cron executor isn't working, you can spawn it
manually by visiting the following URL:

    https://backend-dot-{PROJECT-ID}.appspot.com/_dr/task/rdeStaging

That will spawn a staging task for each TLD under the backend module in that App
Engine project. You can also run the task from the cron tab of the GAE console.

## Notification of upload problems

If there is a problem with the deposit from the escrow provider's side, they
will usually email a notification. Here is an example of such a notification
from Iron Mountain, one common escrow provider:

```
Greetings,

The Registry Data Escrow deposit for XXX has failed the verification process.
Details can be found below.

------------- Name Check ---------------- 
Name Check Filename : xxx_2015-05-16_full_S1_R0.sig 
Name Check : SUCCESSFUL 
Name Check Filename : xxx_2015-05-16_full_S1_R0.ryde 
Name Check : SUCCESSFUL 
------------- Signature Check ---------------- 
Signature Check Filename : xxx_2015-05-16_full_S1_R0.ryde 
Digital Signature Check : FAILED 

Thank you

Iron Mountain
```

## Iron Mountain-specific caveat: never re-upload between 10 p.m. and 3 a.m. UTC

When Iron Mountain receives two deposits for the same TLD within two hours, it
merges them together. So when resending deposits, don't do it anywhere near
midnight UTC, since that might get merged with the output of the regular cron
job. And if you need to resend deposits for more than one day on a single TLD,
space the uploading at least two hours apart.

Note that this warning only applies when you (re)upload files directly to the
sFTP server. There's an RDE_UPLOAD_SFTP cursor that prevents the production
system from uploading twice in a two hour window, so when you let the production
job upload missing deposits, it will be safe. Therefore, one safe approach is to
reset the cursor, then kick off the production job manually.

## Decrypting GhostRyDE files

Sometimes you'll want to take a peek at the contents of a deposit that's been
staged to cloud storage. Use this command:

```shell
$ gcloud storage cat gs://{PROJECT-ID}-rde/foo.ghostryde | nomulus -e production ghostryde --decrypt | less
```

## Identifying which phase of the process failed

Analyze the GAE logs on the backend module.

If the rdeStaging task failed, then it's likely the files do not exist in cloud
storage.

If the rdeUpload task failed, then it's likely the files were not uploaded to
the escrow provider's sFTP server.

If the rdeReport task failed, then it's likely the report file was not sent to
ICANN.

## Generating RDE files

In general RDE files should be regenerated by updating the cursors to before the
desired date and then re-running the mapreduce (either by waiting until the next
scheduled cron execution, or by manually invoking the RDE staging action).

In very rare cases (and only for small TLDs) you can use `nomulus` to generate
the RDE files on a per-TLD basis. Here's an example:

```shell
# Define the tld/date combination.

$ tld=xxx
$ date=2015-05-16

# 1. Generate the deposit. This drops a bunch of files in the current directory.

$ nomulus -e production generate_escrow_deposit --tld=${tld} --watermark=${date}T00:00:00Z

$ ls -l
total 22252
-rw-r----- 1 tjb eng     2292 May 19 16:49 xxx_2015-05-16_full_S1_R0-report.xml
-rw-r----- 1 tjb eng  1321343 May 19 16:49 xxx_2015-05-16_full_S1_R0.ryde
-rw-r----- 1 tjb eng      361 May 19 16:49 xxx_2015-05-16_full_S1_R0.sig
-rw-r----- 1 tjb eng 21448005 May 19 16:49 xxx_2015-05-16_full_S1_R0.xml
-rw-r----- 1 tjb eng      977 May 19 16:49 xxx.pub

$ nomulus -e production validate_escrow_deposit -i ${tld}_${date}_full_S1_R0.xml
ID: AAAACTK2AU2AA
Previous ID: null
Type: FULL
Watermark: 2015-05-16T00:00:00.000Z
RDE Version: 1.0
RDE Object URIs:
  - urn:ietf:params:xml:ns:rdeContact-1.0
  - urn:ietf:params:xml:ns:rdeDomain-1.0
  - urn:ietf:params:xml:ns:rdeHeader-1.0
  - urn:ietf:params:xml:ns:rdeHost-1.0
  - urn:ietf:params:xml:ns:rdeRegistrar-1.0
Contents:
  - XjcRdeContact: 4,224 entries
  - XjcRdeDomain: 2,667 entries
  - XjcRdeHeader: 1 entry
  - XjcRdeHost: 35,932 entries
  - XjcRdeRegistrar: 146 entries
RDE deposit is XML schema valid

# 2. GhostRyDE it!

$ nomulus -e production ghostryde --encrypt \
    --input=${tld}_${date}_full_S1_R0-report.xml \
    --output=${tld}_${date}_full_S1_R0-report.xml.ghostryde

$ nomulus -e production ghostryde --encrypt \
    --input=${tld}_${date}_full_S1_R0.xml \
    --output=${tld}_${date}_full_S1_R0.xml.ghostryde

# 3. Copy to Cloud Storage so RdeUploadTask can find them.

$ gcloud storage cp ${tld}_${date}_full_S1_R0{,-report}.xml.ghostryde gs://{PROJECT-ID}-rde/
```

## Updating an RDE cursor

If the rdeStaging or rdeUpload tasks are failing and you find yourself needing
to perform this process manually, you must make sure to update the appropriate
cursors. Again, you can use `nomulus` to perform this operation.

```shell
$ nomulus -e production update_cursors --timestamp=2015-05-21T00:00:00Z --type=RDE_STAGING $tld
$ nomulus -e production update_cursors --timestamp=2015-05-21T00:00:00Z --type=RDE_UPLOAD $tld
$ nomulus -e production update_cursors --timestamp=2015-05-21T00:00:00Z --type=RDE_REPORT $tld
```

## Manually uploading files to the escrow provider

These instructions work for Iron Mountain, and should be applicable to other
escrow providers as well. We upload the RDE deposits to an sFTP server (see the
[ConfigModule](https://github.com/google/nomulus/blob/master/java/google/registry/config/ConfigModule.java)
for specific URLs). First, you need a generated deposit .xml file (see above for
how to generate such a file locally, or how to decrypt a .ghostryde file into
the .xml original).

### Encrypting the RDE deposits for sending to the escrow provider

```shell
$ nomulus -e production encrypt_escrow_deposit --tld=$tld --input=${tld}_${date}_full_S1_R0-report.xml
$ ls *.ryde *.sig
  ${tld}_${date}_full_S1_R0-report.ryde
  ${tld}_${date}_full_S1_R0-report.sig
```

### Verifying the deposit signature (optional)

To verify the deposit signature, you will need a file containing the public key.

```shell
$ (umask 0077; mkdir gpgtemp)
$ GNUPGHOME=gpgtemp gpg --import ./rde-signing-public
$ GNUPGHOME=gpgtemp gpg --verify ${tld}_${date}_full_S1_R0-report.{sig,ryde}
...
gpg: Good signature from ...
...
$ rm -rf gpgtemp
```

### Uploading the encrypted deposit and signature files

NOTE: If you need to manually upload files directly to the escrow provider, only
upload the .ryde and .sig files. DO NOT upload any other files. You will need a
file containing the private key.

```shell
# Next, sftp to the server
$ sftp -i ./rde-ssh-client-private ${user}@${host}:Outbox
Connected to ${host}.
sftp> ls
# Once in the Outbox/ directory, you can change your local directory to where you have the escrow files
sftp> lcd /path/to/escrow/files/on/local/machine
sftp> put ${tld}_2015-05-16_full_S1_R0.ryde
sftp> put ${tld}_2015-05-16_full_S1_R0.sig
```

It would be convenient to have the following in your `~/.ssh/config` file and
store the SSH private key that you stored in `rde-ssh-client-private` as
`~/.ssh/id_rsa_rde` so that you can simply run `$ sftp rde` to connect to
the sFTP server.

```
Host rde
  Hostname $host
  User $user
  IdentityFile ~/.ssh/id_rsa_rde
```

## Resending the ICANN notification report

As with everything else related to RDE, you can use the `nomulus` tool to send a
notification report to ICANN:

```shell
# This command assumes the report XML file is in your current working directory
$ nomulus -e production send_escrow_report_to_icann xxx_2015-05-16_full_S1_R0-report.xml
```

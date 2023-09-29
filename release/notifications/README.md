## About

We have deployed a Cloud Build notifier on Cloud Run that sends build failure
notifications to a Google Chat space. This folder contains the configuration and
helper scripts.

## Details

The instructions for notifier setup can be found on the
[GCP documentation site](https://cloud.google.com/build/docs/configuring-notifications/configure-googlechat).
For the **initial** setup, Cloud Build provides a
[script](https://cloud.google.com/build/docs/configuring-notifications/automate)
that automates a lot of the work.

With the automated procedure, the notifier is deployed as a Cloud Run service
named `googlechat-notifier` in a single region of user's choice. The notifier
subscribes to the `cloud-builds` Pub/sub topic for build statuses, applies our
custom filter, and sends matching notifications to our Google Chat space.

Our custom configuration for the notifier is in the `googlechat.yaml` file. It
defines:

*   The build status filter, currently matching for all triggered builds that
    have failed or timed out. The filter semantics are explained
    [here](https://cloud.google.com/build/docs/configuring-notifications/configure-googlechat#using_cel_to_filter_build_events).
*   The secret name of the Chat Webhook token stored in the Secret Manager. This
    token allows the notifier to send notifications to our Chat space. The
    webhook token can be managed in the Google Chat client.

The `googlechat.yaml` configuration file should be uploaded to the GCS bucket
`{PROJECT_ID}-notifiers-config`. The `upload_config.sh` script can be used for
uploading. The new configuration will take effect eventually after all currently
running notifier instances shutdown due to inactivity, which is bound to happen
with our workload (Note: this depends on the scale-to-zero policy, which is the
default).

To make sure the new configurations take effect immediately, the
`update_notifier.sh`. It deploys a new revision of the notifier and shuts down
old instances.

Note that if the notifier is moved to another region, the full setup must be
repeated.

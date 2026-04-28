# Cloud Deploy Configuration

This directory contains the Google Cloud Deploy configuration files for the Nomulus project.

## Files

### `delivery-pipeline.yaml`
Defines the `DeliveryPipeline` resource named `deploy-nomulus`. It sets up the serial pipeline for rolling out changes to different targets.

### Target Configurations (e.g., `crash-target.yaml`)
Files matching this format define the `Target` resources for Cloud Deploy. They specify the GKE cluster and other environment-specific settings for deployment.

### `skaffold.yaml`
Defines the Skaffold configuration used by Cloud Deploy to render and deploy the application manifests.

## Usage

You can apply or modify these configurations in Google Cloud by using the `gcloud` CLI. For example:

```bash
gcloud deploy apply --file=<config-file>.yaml --project=<project-id> --region=<region>
```

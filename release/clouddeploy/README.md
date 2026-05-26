# Cloud Deploy Configuration

This directory contains the Google Cloud Deploy configuration files for the Nomulus project.

## Files

### `delivery-pipeline.yaml`
Defines the `DeliveryPipeline` resource named `deploy-nomulus`. It sets up the serial pipeline for rolling out changes to different targets.

### Target Configurations (e.g., `crash-target.yaml`)
Files matching this format define the `Target` resources for Cloud Deploy. They specify the GKE cluster and other environment-specific settings for deployment.

### Environment Configurations (e.g., `crash-config.yaml`)
Configuration files containing environment-specific parameters and SLA-based alert policy checks (such as EPP and RDAP success metrics) used for automated analysis and target population.

### `skaffold.yaml`
Defines the Skaffold configuration used by Cloud Deploy to render and deploy the application manifests.

## Automated Configuration and Deployment Process

The preparation and application of Cloud Deploy configurations is automated via Cloud Build using `release/cloudbuild-clouddeploy.yaml`.

When executed, the Cloud Build job performs the following workflow:
1. **Repository Merge**: Clones the internal repository (`nomulus-internal`) and merges internal configurations into the workspace.
2. **Dynamic Configuration Population**: Reads variables and alert policy checks specified in the configuration file for the environment from the internal repository, populating them into `delivery-pipeline.yaml` and the corresponding target files.
3. **Apply Configurations**: Runs `gcloud deploy apply` to register the updated targets and delivery pipeline in Google Cloud Deploy.

### Manual Execution on Cloud Build
To manually trigger this configuration pipeline on Google Cloud Build, run:
```bash
gcloud builds submit --config release/cloudbuild-clouddeploy.yaml --substitutions _INTERNAL_REPO_URL=[URL],PROJECT_ID=[PROJECT_ID]
```

## Manual Local Usage

You can also apply or modify rendered configurations directly using the `gcloud` CLI:

```bash
gcloud deploy apply --file=<config-file>.yaml --project=<project-id> --region=<region>
```

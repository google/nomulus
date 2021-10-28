# Proxy Setup Instructions

This doc covers procedures to configure, build and deploy the
[Netty](https://netty.io)-based proxy onto [Kubernetes](https://kubernetes.io)
clusters. [Google Kubernetes
Engine](https://cloud.google.com/kubernetes-engine/) is used as deployment
target. Any kubernetes cluster should in theory work, but the user needs to
change some dependencies on other GCP features such as Cloud KMS for key
management and Stackdriver for monitoring.

## Overview

Nomulus runs on Google App Engine, which only supports HTTP(S) traffic. In order
to work with [EPP](https://tools.ietf.org/html/rfc5730.html) (TCP port 700) and
[WHOIS](https://tools.ietf.org/html/rfc3912) (TCP port 43), a proxy is needed to
relay traffic between clients and Nomulus and do protocol translation.

We provide a Netty-based proxy that runs as a standalone service (separate from
Nomulus) either on a VM or Kubernetes clusters. Deploying to kubernetes is
recommended as it provides automatic scaling and management for docker
containers that alleviates much of the pain of running a production service.

The procedure described here can be used to set up a production environment, as
most of the steps only needs to be configured once for each environment.
However, proper release management (cutting a release, rolling updates, canary
analysis, reliable rollback, etc) is not covered. The user is advised to use a
service like [Spinnaker](https://www.spinnaker.io/) for release management.

## Detailed Instruction

We use [`gcloud`](https://cloud.google.com/sdk/gcloud/) and
[`terraform`](https://terraform.io) to configure the proxy project on GCP. We
use [`kubectl`](https://kubernetes.io/docs/tasks/tools/install-kubectl/) to
deploy the proxy to the project. Additionally,
[`gsutil`](https://cloud.google.com/storage/docs/gsutil) is used to create GCS
bucket for storing the terraform state file. These instructions assume that all
four tools are installed.

### Setup GCP project

There are three projects involved:

-   Nomulus project: the project that hosts Nomulus.
-   Proxy project: the project that hosts this proxy.
-   GCR ([Google Container
    Registry](https://cloud.google.com/container-registry/)) project: the
    project from which the proxy pulls its Docker image.

We recommend using the same project for Nomulus and the proxy, so that logs for
both are collected in the same place and easily accessible. If there are
multiple Nomulus projects (environments), such as production, sandbox, alpha,
etc, it is recommended to use just one as the GCR project. This way the same
proxy images are deployed to each environment, and what is running in production
is the same image tested in sandbox before.

The following document outlines the procedure to setup the proxy for one
environment.

In the proxy project, create a GCS bucket to store the terraform state file:

```bash
$ gsutil config # only if you haven't run gsutil before.
$ gsutil mb -p <proxy-project> gs://<bucket-name>/
```

### Obtain a domain and SSL certificate

The proxy exposes two endpoints, whois.\<yourdomain.tld\> and
epp.\<yourdomain.tld\>. The base domain \<yourdomain.tld\> needs to be obtained
from a registrar ([Google Domains](https://domains.google) for example). Nomulus
operators can also self-allocate a domain in the TLDs under management.

[EPP protocol over TCP](https://tools.ietf.org/html/rfc5734) requires a
client-authenticated SSL connection. The operator of the proxy needs to obtain
an SSL certificate for domain epp.\<yourdomain.tld\>. [Let's
Encrypt](https://letsencrypt.org) offers SSL certificate free of charge, but any
other CA can fill the role.

Concatenate the certificate and its private key into one file:

```bash
$ cat <certificate.pem> <private.key> > <combined_secret.pem>
```

The order between the certificate and the private key inside the combined file
does not matter. However, if the certificate file is chained, i. e. it contains
not only the certificate for your domain, but also certificates from
intermediate CAs, these certificates must appear in order. The previous
certificate's issuer must be the next certificate's subject.

The certificate will be encrypted by KMS and uploaded to a GCS bucket. The
bucket will be created automatically by terraform.

### Setup proxy project

First setup the [Application Default
Credential](https://cloud.google.com/docs/authentication/production) locally:

```bash
$ gcloud auth application-default login
```

Login with the account that has "Project Owner" role of all three projects
mentioned above.

Navigate to `proxy/terraform`, create a folder called
`envs`, and inside it, create a folder for the environment that proxy is
deployed to ("alpha" for example). Copy `example_config.tf` and `outputs.tf`
to the environment folder.

```bash
$ cd proxy/terraform
$ mkdir -p envs/alpha
$ cp example_config.tf envs/alpha/config.tf
$ cp outputs.tf envs/alpha
```

Now go to the environment folder, edit the `config.tf` file and replace
placeholders with actual project and domain names.

Run terraform:

```bash
$ cd envs/alpha
(edit config.tf)
$ terraform init -upgrade
$ terraform apply
```

Go over the proposed changes, and answer "yes". Terraform will start configuring
the projects, including setting up clusters, keyrings, load balancer, etc. This
takes a couple of minutes.

### Setup Nomulus

After terraform completes, it outputs some information, among which is the
client id of the service account created for the proxy. This needs to be added
to the Nomulus configuration file so that Nomulus accepts traffic from the
proxy. Edit the following section in
`java/google/registry/config/files/nomulus-config-<env>.yaml` and redeploy
Nomulus:

```yaml
oAuth:
  allowedOauthClientIds:
    - <client_id>
```

### Setup nameservers

The terraform output (run `terraform output` in the environment folder to show
it again) also shows the nameservers of the proxy domain (\<yourdomain.tld\>).
Delegate this domain to these nameservers (through your registrar). If the
domain is self-allocated by Nomulus, run:

```bash
$ nomulus -e production update_domain <yourdomain.tld> \
-c <registrar_client_name> -n <nameserver1>,<nameserver2>,...
```

### Setup named ports

Unfortunately, terraform currently cannot add named ports on the instance groups
of the GKE clusters it manages. [Named
ports](https://cloud.google.com/compute/docs/load-balancing/http/backend-service#named_ports)
are needed for the load balancer it sets up to route traffic to the proxy. To
set named ports, in the environment folder, do:

```bash
$ bash ../../update_named_ports.sh
```

### Encrypt the certificate to Cloud KMS

With the newly set up Cloud KMS key, encrypt the certificate/key combo file
created earlier:

```bash
$ gcloud kms encrypt --plaintext-file <combined_secret.pem> \
--ciphertext-file - --key <key-name> --keyring <keyring-name> --location \
global | base64 > <combined_secret.pem.enc>
```

This encrypted file is then uploaded to a GCS bucket specified in the
`config.tf` file.

```bash
$ gsutil cp <combined_secret.pem.enc> gs://<your-certificate-bucket>
```

### Edit proxy config file

Proxy configuration files are at `java/google/registry/proxy/config/`. There is
a default config that provides most values needed to run the proxy, and several
environment-specific configs for proxy instances that communicate to different
Nomulus environments. The values specified in the environment-specific file
override those in the default file.

The values that need to be changed include the project name, the Nomulus
endpoint, encrypted certificate/key combo filename and the GCS bucket it is
stored in, Cloud KMS keyring and key names, etc. Refer to the default file for
detailed descriptions on each field.

### Upload proxy docker image to GCR

Edit the `proxy_push` rule in `java/google/registry/proxy/BUILD` to add the GCR
project name and the image name to save to. Note that as currently set up, all
images pushed to GCR will be tagged `bazel` and the GKE deployment object loads
the image tagged as `bazel`. This is fine for testing, but for production one
should give images unique tags (also configured in the `proxy_push` rule).

To push to GCR, run:

```bash
$ bazel run java/google/registry/proxy:proxy_push
```

### Deploy proxy

Terraform by default creates three clusters, in the Americas, EMEA, and APAC,
respectively. We will have to deploy to each cluster separately. The cluster
information is shown by `terraform output` as well.

Deployment is defined in two files, `proxy-deployment-<env>.yaml` and
`proxy-service.yaml`. Edit `proxy-deployment-<env>.yaml` for your environment,
fill in the GCR project name and image name. You can also change the arguments
in the file to turn on logging, for example. To deploy to a cluster:

```bash
# Get credentials to deploy to a cluster.
$ gcloud container clusters get-credentials --project <proxy-project> \
--zone <cluster-zone> <cluster-name>

# Deploys environment specific kubernetes objects.
$ kubectl create -f \
proxy/kubernetes/proxy-deployment-<env>.yaml

# Deploys shared kubernetes objects.
$ kubectl create -f \
proxy/kubernetes/proxy-service.yaml
```

Repeat this for all three clusters.

### Afterwork

Remember to turn on [Stackdriver
Monitoring](https://cloud.google.com/monitoring/docs/) for the proxy project as
we use it to collect metrics from the proxy.

You are done! The proxy should be running now. You should store the private key
safely, or delete it as you now have the encrypted file shipped with the proxy.
See "Additional Steps" in the appendix for other things to check.

## Appendix

Here we give detailed instructions on how to configure a GCP project to host the
proxy manually. We strongly recommend against doing so because it is tedious and
error-prone. Using Terraform is much easier. The following instructions are for
educational purpose for readers to understand why we set up the infrastructure
this way. The Terraform config is essentially a translation of the following
procedure.

### Set default project

The proxy can run on its own GCP project, or use the existing project that also
hosts Nomulus. We recommend initializing the
[`gcloud`](https://cloud.google.com/sdk/gcloud/) config to use that project as
default, as it avoids having to provide the `--project` flag for every `gcloud`
command:

```bash
$ gcloud init
```

Follow the prompt and choose the project you want to deploy the proxy to. You
can skip picking default region and zones, as we will explicitly create clusters
in multiple zones to provide geographical redundancy.

### Create service account

The proxy will run with the credential of a [service
account](https://cloud.google.com/compute/docs/access/service-accounts). In
theory it can take advantage of [Application Default
Credentials](https://cloud.google.com/docs/authentication/production) and use
the service account that the GCE instance underpinning the GKE cluster uses, but
we recommend creating a separate service account. With a dedicated service
account, one can grant permissions only necessary to the proxy. To create a
service account:

```bash
$ gcloud iam service-accounts create proxy-service-account \
--display-name "Service account for Nomulus proxy"
```

Generate a `.json` key file for the newly created service account. The key file
contains the secret necessary to construct credentials of the service account
and needs to be stored safely (it should be deleted later).

```bash
$  gcloud iam service-accounts keys create proxy-key.json --iam-account \
<service-account-email>
```

A `proxy-key.json` file will be created inside the current working directory.

The `client_id` inside the key file needs to be added to the Nomulus
configuration file so that Nomulus accepts the OAuth tokens generated for this
service account. Add its value to
`java/google/registry/config/files/nomulus-config-<env>.yaml`:

```yaml
oAuth:
  allowedOauthClientIds:
    - <client_id>
```

Redeploy Nomulus for the change to take effect.

Also bind the "Logs Writer" and role to the proxy service account so that it can
write logs to [Stackdriver Logging](https://cloud.google.com/logging/).

```bash
$ gcloud projects add-iam-policy-binding <project-id> \
--member serviceAccount:<service-accounte-email> \
--role roles/logging.logWriter
```

### Obtain a domain and SSL certificate

A domain is needed (if you do not want to rely on IP addresses) for clients to
communicate to the proxy. Domains can be purchased from a domain registrar
([Google Domains](https://domains.google) for example). A Nomulus operator could
also consider self-allocating a domain under an owned TLD insteadl.

An SSL certificate is needed as [EPP over
TCP](https://tools.ietf.org/html/rfc5734) requires SSL. You can apply for an SSL
certificate for the domain name you intended to serve as EPP endpoint
(epp.nic.tld for example) for free from [Let's
Encrypt](https://letsencrypt.org). For now, you will need to manually renew your
certificate before it expires.

### Create keyring and encrypt the certificate/private key

The proxy needs access to both the private key and the certificate. Do *not*
package them directly with the proxy. Instead, use [Cloud
KMS](https://cloud.google.com/kms/) to encrypt them, ship the encrypted file
with the proxy, and call Cloud KMS to decrypt them on the fly. (If you want to
use another keyring solution, you will have to modify the proxy and implement
yours)

Concatenate the private key file with the certificate. It does not matter which
file is appended to which. However, if the certificate file is a chained `.pem`
file, make sure that the certificates appear in order, i. e. the issuer of one
certificate is the subject of the next certificate:

```bash
$ cat <private-key.key> <chained-certificates.pem> >> ssl-cert-key.pem
```

Create a keyring and a key in Cloud KMS, and use the key to encrypt the combined
file:

```bash
# create keyring
$ gcloud kms keyrings create <keyring-name> --location global

# create key
$ gcloud kms keys create <key-name> --purpose encryption --location global \
--keyring <keyring-name>

# encryption using the key
$ gcloud kms encrypt --plaintext-file ssl-cert-key.pem \
--ciphertext-file ssl-cert-key.pem.enc \
--key <key-name> --keyring <keyring-name> --location global
```

A file named `ssl-cert-key.pem.enc` will be created. Upload it to a GCS bucket
in the proxy project. To create a bucket and upload the file:

```bash
$ gsutil mb -p <proxy-project> gs://<bucket-name>
$ gustil cp ssl-cert-key.pem.enc gs://<bucket-name>
```

The proxy service account needs the "Cloud KMS CryptoKey Decrypter" role to
decrypt the file using Cloud KMS:

```bash
$ gcloud projects add-iam-policy-binding <project-id> \
--member serviceAccount:<service-accounte-email> \
--role roles/cloudkms.cryptoKeyDecrypter
```

The service account also needs the "Storage Object Viewer" role to retrieve the
encrypted file from GCS:

```bash
$ gsutil iam ch \
serviceAccount:<service-account-email>:roles/storage.objectViewer \
gs://<bucket-name>
```

### Proxy configuration

Proxy configuration files are at `java/google/registry/proxy/config/`. There is
a default config that provides most values needed to run the proxy, and several
environment-specific configs for proxy instances that communicate to different
Nomulus environments. The values specified in the environment-specific file
override those in the default file.

The values that need to be changed include the project name, the Nomulus
endpoint, encrypted certificate/key combo filename (`ssl-cert-key.pem` in the
above example), Cloud KMS keyring and key names, etc. Refer to the default file
for detailed descriptions on each field.

### Setup Stackdriver for the project

The proxy streams metrics to
[Stackdriver](https://cloud.google.com/stackdriver/). Refer to [Stackdriver
Monitoring](https://cloud.google.com/monitoring/docs/) documentation on how to
enable monitoring on the GCP project.

The proxy service account needs to have ["Monitoring Metric
Writer"](https://cloud.google.com/monitoring/access-control#predefined_roles)
role in order to stream metrics to Stackdriver:

```bash
$ gcloud projects add-iam-policy-binding <project-id> \
--member serviceAccount:<service-account-email> --role roles/monitoring.metricWriter
```

### Create GKE clusters

We recommend creating several clusters in different zones for better
geographical redundancy and better network performance. For example to have
clusters in the Americas, EMEA and APAC. It is also a good idea to enable
[autorepair](https://cloud.google.com/kubernetes-engine/docs/concepts/node-auto-repair),
[autoupgrade](https://cloud.google.com/kubernetes-engine/docs/concepts/node-auto-upgrades),
and
[autoscaling](https://cloud.google.com/kubernetes-engine/docs/concepts/cluster-autoscaler)
on the clusters.

The default Kubernetes version on GKE is usually old, consider specifying a
newer version when creating the cluster, to save time upgrading the nodes
immediately after.

```bash
$ gcloud container clusters create proxy-americas-cluster --enable-autorepair \
--enable-autoupgrade --enable-autoscaling --max-nodes=3 --min-nodes=1 \
--zone=us-east1-c --cluster-version=1.9.4-gke.1 --tags=proxy-cluster \
--service-account=<service-account-email>
```

We give the GCE instances inside the cluster the same credential as the proxy
service account, which makes it easier to limit permissions granted to service
accounts. If we use the default GCE service account, we'd have to grant the
default GCE service account permission to read from GCR in order to download
images of the proxy to create pods, which gives *any* GCE instance with the
default service account that permission.

Note the `--tags` flag: it will apply the tag to all GCE instances running in
the cluster, making it easier to set up firewall rules later on. Use the same
tag for all clusters.

Repeat this for all the zones you want to create clusters in.

### Upload proxy docker image to GCR

The GKE deployment manifest is set up to pull the proxy docker image from
[Google Container Registry](https://cloud.google.com/container-registry/) (GCR).
Instead of using `docker` and `gcloud` to build and push images, respectively,
we provide `bazel` rules for the same tasks. To push an image, first use
[`docker-credential-gcr`](https://github.com/GoogleCloudPlatform/docker-credential-gcr)
to obtain necessary credentials. It is used by the [bazel container_push
rules](https://github.com/bazelbuild/rules_docker#authentication) to push the
image.

After credentials are configured, edit the `proxy_push` rule in
`java/google/registry/proxy/BUILD` to add the GCP project name and the image
name to save to. We recommend using the same project and image for proxies
intended for different Nomulus environments, this way one can deploy the same
proxy image first to sandbox for testing, and then to production.

Also note that as currently set up, all images pushed to GCR will be tagged
`bazel` and the GKE deployment object loads the image tagged as `bazel`. This is
fine for testing, but for production one should give images unique tags (also
configured in the `proxy_push` rule).

To push to GCR, run:

```bash
$ bazel run java/google/registry/proxy:proxy_push
```

If the GCP project to host images (gcr project) is different from the project
that the proxy runs in (proxy project), give the service account "Storage Object
Viewer" role of the gcr project.

```bash
$ gcloud projects add-iam-policy-binding <image-project> \
--member serviceAccount:<service-account-email> \
--role roles/storage.objectViewer
```

### Upload proxy service account key to GKE cluster

The kubernetes pods (containers) are configured to read the proxy service
account key file from a secret resource stored in the cluster.

First set the cluster credential in `gcloud` so that `kubectl` knows which
cluster to manage:

```bash
$ gcloud container clusters get-credentials proxy-americas-cluster \
--zone us-east1-c
```

To upload the key file as `service-account-key.json` as a secret named
`service-account`:

```bash
$ kubectl create secret generic service-account \
--from-file=service-account-key.json=<service-account-key.json>
```

More details on using service account on GKE can be found
[here](https://cloud.google.com/kubernetes-engine/docs/tutorials/authenticating-to-cloud-platform).

Repeat the same step for all clusters you want to deploy to. Use `gcloud` to
switch context, and then `kubectl` to upload the key.

### Deploy proxy to GKE clusters

Use `kubectl` to create the deployment and autoscale objects:

```bash
$ kubectl create -f \
proxy/kubernetes/proxy-deployment-alpha.yaml
```

The kubernetes
[deployment](https://kubernetes.io/docs/concepts/workloads/controllers/deployment/)
object specifies the images to run, along with its parameters. The
[autoscale](https://kubernetes.io/docs/tasks/run-application/horizontal-pod-autoscale/)
object changes the number of pods running based on CPU load. This is different
from GKE cluster autoscaling, which changes the number of nodes (VMs) running
based on pod resource requests. Ideally if there's no load, just one pod will be
running in one cluster, resulting only one node running as well, saving
resources.

Repeat the same step for all clusters you want to deploy to.

### Expose the proxy service

The proxies running on GKE clusters need to be exposed to the outside. Do not
use Kubernetes
[`LoadBalancer`](https://kubernetes.io/docs/concepts/services-networking/service/#type-loadbalancer).
It will create a GCP [Network Load
Balancer](https://cloud.google.com/compute/docs/load-balancing/network/), which
has several problems:

-   This load balancer does not terminate TCP connections. It simply acts as an
    edge router that forwards IP packets to a "healthy" node in the cluster. As
    such, it does not support IPv6, because GCE instances themselves are
    currently IPv4 only.
-   IP packets that arrived on the node may be routed to another node for
    reasons of capacity and availability. In doing so it will
    [SNAT](https://en.wikipedia.org/wiki/Network_address_translation#SNAT) the
    packet, therefore losing the source IP information that the proxy needs. The
    proxy uses WHOIS source IP address to cap QPS and passes EPP source IP to
    Nomulus for validation. Note that a TCP terminating load balancer also has
    this problem as the source IP becomes that of the load balancer, but it can
    be addressed in other ways (explained later). See
    [here](https://kubernetes.io/docs/tutorials/services/source-ip/) for more
    details on how Kubernetes route traffic and translate source IPs inside the
    cluster.
-   Acting as an edge router, this type of load balancer can only work with a
    given region as each GCP region forms its own subnet. Therefore multiple
    load balancers, and IP addresses are needed if the proxy were to run in
    multiple regional clusters.

Instead, we split the task of exposing the proxy to the Internet into two tasks,
first to expose it within the cluster, then to expose the cluster to the outside
through a [TCP Proxy Load
Balancer](https://cloud.google.com/compute/docs/load-balancing/tcp-ssl/tcp-proxy).
This load balancer terminates TCP connections and allows for the use of a single
anycast IP address (IPv4 and IPv6) to reach any clusters connected to its
backend (it chooses a particular cluster based on geographical proximity). From
this point forward we will refer to this type of load balancer simply as the
load balancer.

#### Set up proxy NodePort service

Kubernetes pods and nodes are
[ephemeral](https://kubernetes.io/docs/concepts/services-networking/service/). A
pod may crash and be killed, and a new pod will be spun up by the master node to
fill its role. Similarly a node may be shut down due to under-utilization
(thanks to GKE autoscaling). In order to reliably route incoming traffic to the
proxy, a
[NodePort](https://kubernetes.io/docs/concepts/services-networking/service/#type-nodeport)
service is used to expose the proxy on specificed port(s) on every running node
in the cluster, even if the proxy does not run on a VM (in which case the
traffic is routed to a VM that has the proxy running). With a [NodePort]
service, the load balancer can alway route traffic to any healthy node, and
kubernetes takes care of delivering that traffic to a servicing proxy pod.

To deploy the NodePort service:

```bash
$ kubectl create -f \
proxy/kubernetes/proxy-service.yaml
```

This service object will open up port 30000 (health check), 30001 (WHOIS) and
30002 (EPP) on the nodes, routing to the same ports inside a pod.

Repeat this for all clusters.

#### Map named ports in GCE instance groups

GKE uses GCE as its underlying infrastructure. A GKE cluster (or more precisely,
a node pool) corresponds to a GKE instance group. In order to receive traffic
from a load balancer backend, an instance group needs to designate the ports
that are to receive traffic, by giving them names (i. e. making them "named
ports").

As mentioned above, the Kubernetes `NodePort` service object sets up three ports
to receive traffic (30000, 30001 and 30002). Port 30000 is used by the health
check protocol (discussed later) and does not need to be explicitly named.

First obtain the instance group names for the clusters:

```bash
$ gcloud compute instance-groups list
```

They start with `gke` and have the cluster names in them, should be easy to
spot.

Then set the named ports:

```bash
$ gcloud compute instance-groups set-named-ports <instance-group> \
--named-ports whois:30001,epp:30002 --zone <zone>
```

Repeat this for each instance group (cluster).

#### Set up firewall rules to allow traffic from the load balancer

By default inbound traffic from the load balancer are dropped by the GCE
firewall. A new firewall rule needs to be added to explicitly allow TCP packets
originating from the load balancer to the three ports opened in the `NodePort`
service on the nodes.

```bash
$ gcloud compute firewall-rules create proxy-loadbalancer \
--source-ranges 130.211.0.0/22,35.191.0.0/16 \
--target-tags proxy-cluster \
--allow tcp:30000,tcp:30001,tcp:30002
```

The target tag controls what GCE VMs can receive traffic allowed in this rule.
It is the same tag used during cluster creation. Since we use the same tag for
all clusters, this rule applies to all VMs running the proxy. The load balancer
source IP is taken from
[here](https://cloud.google.com/compute/docs/load-balancing/tcp-ssl/tcp-proxy#config-hc-firewall)

#### Create health check

The load balancer sends TCP requests to a designated port on each backend VM to
probe if the VM is healthy to serve traffic. The proxy by default uses port
30000 (which is exposed as the same port on the node) for health check and
returns a pre-configured response (`HEALTH_CHECK_RESPONSE`) when an expected
request (`HEALTH_CHECK_REQUEST`) is received. To add health check:

```bash
$ gcloud compute health-checks create tcp proxy-health \
--description "Health check on port 30000 for Nomulus proxy" \
--port 30000 --request "HEALTH_CHECK_REQUEST" --response "HEALTH_CHECK_RESPONSE"
```

#### Create load balancer backend

The load balancer backend configures what instance groups the load balancer
sends packets to. We have already setup `NodePort` service on each node in all
the clusters to ensure that traffic to any of the exposed node ports will be
routed to the corresponding port on a proxy pod. The backend service codifies
which ports on the node's clusters should receive traffic from the load
balancer.

Create one backend service for EPP and one for WHOIS:

```bash
# EPP backend
$ gcloud compute backend-services create proxy-epp-loadbalancer \
--global --protocol TCP --health-checks proxy-health --timeout 1h \
--port-name epp

# WHOIS backend
$ gcloud compute backend-services create proxy-whois-loadbalancer \
--global --protocol TCP --health-checks proxy-health --timeout 1h \
--port-name whois
```

These two backend services route packets to the epp named port and whois named
port on any instance group attached to them, respectively.

Then add (attach) instance groups that the proxies run on to each backend
service:

```bash
# EPP backend
$ gcloud compute backend-services add-backend proxy-epp-loadbalancer \
--global --instance-group <instance-group> --instance-group-zone <zone> \
--balancing-mode UTILIZATION --max-utilization 0.8

# WHOIS backend
$ gcloud compute backend-services add-backend proxy-whois-loadbalancer \
--global --instance-group <instance-group> --instance-group-zone <zone> \
--balancing-mode UTILIZATION --max-utilization 0.8
```

Repeat this for each instance group.

#### Reserve static IP addresses for the load balancer frontend

These are the public IP addresses that receive all outside traffic. We need one
address for IPv4 and one for IPv6:

```bash
# IPv4
$ gcloud compute addresses create proxy-ipv4 \
--description "Global static anycast IPv4 address for Nomulus proxy" \
--ip-version IPV4 --global

# IPv6
$ gcloud compute addresses create proxy-ipv6 \
--description "Global static anycast IPv6 address for Nomulus proxy" \
--ip-version IPV6 --global
```

To check the IP addresses obtained:

```bash
$ gcloud compute addresses describe proxy-ipv4 --global
$ gcloud compute addresses describe proxy-ipv6 --global
```

Set these IP addresses as the A/AAAA records for both epp.<nic.tld> and
whois.<nic.tld> where <nic.tld> is the domain that was obtained earlier. (If you
use [Cloud DNS](https://cloud.google.com/dns/) as your DNS provider, this step
can also be performed by `gcloud`)

#### Create load balancer frontend

The frontend receives traffic from the Internet and routes it to the backend
service.

First create a TCP proxy (yes, it is confusing, this GCP resource is called
"proxy" as well) which is a TCP termination point. Outside connections terminate
on a TCP proxy, which establishes its own connection to the backend services
defined above. As such, the source IP address from the outside is lost. But the
TCP proxy can add the [PROXY protocol
header](https://www.haproxy.org/download/1.8/doc/proxy-protocol.txt) at the
beginning of the connection to the backend. The proxy running on the backend can
parse the header and obtain the original source IP address of a request.

Make one for each protocol (EPP and WHOIS).

```bash
# EPP
$ gcloud compute target-tcp-proxies create proxy-epp-proxy \
--backend-service proxy-epp-loadbalancer --proxy-header PROXY_V1

# WHOIS
$ gcloud compute target-tcp-proxies create proxy-whois-proxy \
--backend-service proxy-whois-loadbalancer --proxy-header PROXY_V1
```

Note the use of the `--proxy-header` flag, which turns on the PROXY protocol
header.

Next, create the forwarding rule that route outside traffic to a given IP to the
TCP proxy just created:

```bash
$ gcloud compute forwarding-rules create proxy-whois-ipv4 \
--global --target-tcp-proxy proxy-whois-proxy \
--address proxy-ipv4  --ports 43
```

The above command sets up a forwarding rule that routes traffic destined to the
static IPv4 address reserved earlier, on port 43 (actual port for WHOIS), to the
TCP proxy that connects to the whois backend service.

Repeat the above command another three times, set up IPv6 forwarding for WHOIS,
and IPv4/IPv6 forwarding for EPP.

## Additional steps

### Check if it all works

At this point the proxy should be working and reachable from the Internet. Try
if a whois request to it is successful:

```bash
whois -h whois.<nic.tld> something
```

One can also try to contact the EPP endpoint with an EPP client.

### Check logs and metrics

The proxy saves logs to [Stackdriver
Logging](https://cloud.google.com/logging/), which is the same place that
Nomulus saves it logs to. On GCP console, navigate to Logging - Logs - GKE
Container - <cluster name> - default. Do not choose "All namespace_id" as it
includes logs from the Kubernetes system itself and can be quite overwhelming.

Metrics are stored in [Stackdriver
Monitoring](https://cloud.google.com/monitoring/docs/). To view the metrics, go
to Stackdriver [console](https://app.google.stackdriver.com) (also accessible
from GCE console under Monitoring), navigate to Resources - Metrics Explorer.
Choose resource type "GKE Container" and search for metrics with name "/proxy/"
in it. Currently available metrics include total connection counts, active
connection count, request/response count, request/response size, round-trip
latency and quota rejection count.

### Cleanup sensitive files

Delete the service account key file and the SSL certificate private key, or
store them in some secure location.

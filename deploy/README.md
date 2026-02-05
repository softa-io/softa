
# 1. Build multi-platform images 

## 1.1 Build & Push by GitHub Actions
You can use the GitHub Actions workflow or any other CI tools, to build and push multi-platform images to Docker container registry.
Refer to the `.github/workflows/build-and-push.yml` file for more details.
Trigger build action on push of tags starting with "v", e.g., v1.0.0

`./deploy/Dockerfile` is a common multi-stage Dockerfile, which can be used to build the app image.
The `APP_PATH`, `APP_NAME` parameters are passed as build arguments in the GitHub Actions workflow.
The image `version` is extracted from the tag name e.g., `v1.0.0` -> `1.0.0`.

## 1.2 Build & Push by Local Script Manually
Build images and push them to Docker container registry
Specify the `APP_PATH`, `APP_VERSION` to build the image for the Java application.
```
./deploy/build.sh <APP_PATH> <APP_VERSION> [<REGISTRY_NAMESPACE>]
```
The `APP_PATH` is the relative path to the application source code directory, such as `apps/demo-app`.
And the last name in `APP_PATH` is the application name, such as `demo-app`.

The `REGISTRY_NAMESPACE` is optional, and the default value is `softa`.
Your can specify the `REGISTRY_NAMESPACE` to push the image to your own docker image repository.

Example to build the demo application image:
```bash
./deploy/build.sh apps/demo-app 1.0.3
```

# 2. Start EFK by Docker Compose (Optional)
```bash
docker-compose -f deploy/efk/docker-compose.yml up -d
```
Access the Kibana console at http://localhost:5601

Or you can specify the `spring.elasticsearch.uris` property to connect to your own Elasticsearch cluster.

# 3. Start Pulsar by Docker Compose (Optional)
```bash
docker-compose -f deploy/pulsar/docker-compose.yml up -d
```
Access the Pulsar console at http://localhost:8080

Or you can specify the `spring.pulsar.service-url` property to connect to your own Pulsar cluster.

The most crucial information is you need to configure the `mq.topics.xxx.topic` properties to enable the corresponding Listeners.

On the other hand, if you are not ready to set up the pulsar service, you can also choose not to configure 
or comment out the `mq.topics.xxx` topics to avoid the issue of being unable to start.

# 4. Start Minio by Docker Compose (Optional)
```bash
docker-compose -f deploy/minio/docker-compose.yml up -d
```
### Minio API Endpoints
http://localhost:9000

### Minio Web UI Dashboard
http://localhost:9001
Username: minioadmin
Password: minioadmin

# 5. Start the demo application by Docker Compose
```bash
docker-compose -f ./deploy/demo-app/docker-compose.yml up -d
```
Create a database instance and execute the SQL scripts in `deploy/demo-app/init_mysql`.

# 6. Production Environment
It is highly recommended that the production environment be deployed using a pipeline and Kubernetes for containerization.
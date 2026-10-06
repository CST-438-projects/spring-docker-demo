# Docker demo: from `./gradlew bootRun` to a public HTTPS URL

CST 438, Week 7. This walks one tiny Spring Boot API through everything Project 02 asks of
Docker: a multi-stage `Dockerfile` plus `.dockerignore` (Sprint 2), `docker compose up`
(Sprint 2), a published image (Sprint 3), and the API live on a public HTTPS host (Sprint 3).

Every command and every output block below was run on 2026-10-06 on an Apple Silicon Mac with
Docker 29.8 (linux/arm64), JDK 25 on the PATH, Spring Boot 3.5.16, Gradle 9.7 wrapper.
Anything that could **not** be run here (the Render dashboard, pushing to a registry) is
marked **UNVERIFIED** and describes Render's and GitHub's docs, not a click-through.

```
docker_demo/
├── DOCKER_DEMO.md               <- this file
└── app/
    ├── Dockerfile               <- finished multi-stage reference
    ├── Dockerfile.single-stage  <- the wrong way, kept for the size comparison
    ├── .dockerignore
    ├── compose.yaml
    ├── build.gradle.kts, settings.gradle.kts, gradlew, gradle/
    └── src/main/java/edu/csumb/cst438/hello/   (3 classes + 1 record)
        src/main/resources/application.properties
        src/test/java/edu/csumb/cst438/hello/HelloControllerTest.java
```

The app has two endpoints and no database:

| Endpoint | Returns | Why it exists |
|---|---|---|
| `GET /api/v1/hello` | `{"message","hostname","version"}` | `hostname` proves you are inside a container; `version` proves env vars reach the app |
| `GET /actuator/health` | `{"status":"UP"}` | What Docker's `HEALTHCHECK` and the host's health check poll |

---

## 1. Quick start / cheat sheet

Run from the `app/` folder. Replace `USER` with your GitHub username (lowercase).

```bash
cd app

# 1. Run it WITHOUT Docker (prove the app works first)
./gradlew build                         # compiles + runs the 3 tests
java -jar build/libs/app.jar &          # or: ./gradlew bootRun
sleep 3                                 # wait for "Started HelloApiApplication" (about 1-3 s)
curl localhost:8080/api/v1/hello ; curl localhost:8080/actuator/health
kill %1

# 2. Write the Dockerfile and .dockerignore (see section 2.3 / 2.4)

# 3. Build the image
docker build -t hello-api .
docker images hello-api

# 4. Run the container
docker run -d --name hello -p 8080:8080 -e APP_VERSION=1.0.0 hello-api
curl localhost:8080/api/v1/hello        # hostname is now the container ID
docker ps ; docker logs hello
docker stop hello && docker rm hello

# 5. Compose
docker compose up -d --build
curl localhost:8080/api/v1/hello        # version is "compose"
docker compose down

# 6. Publish the image to GitHub Container Registry (amd64, because the host is amd64)
echo $CR_PAT | docker login ghcr.io -u USER --password-stdin
docker buildx build --platform linux/amd64 -t ghcr.io/USER/hello-api:0.1.0 --push .
#   then on github.com: your profile -> Packages -> hello-api -> Package settings -> Change visibility -> Public

# 7. Deploy on Render: New -> Web Service -> "Existing Image" -> ghcr.io/USER/hello-api:0.1.0
#    (or connect the repo and set Language = Docker). Set Health Check Path = /actuator/health.

# 8. Verify
curl https://<your-service>.onrender.com/api/v1/hello
curl https://<your-service>.onrender.com/actuator/health
```

---

## 2. Step by step, with explanations

### 2.1 Run it without Docker

Never debug the app and the Dockerfile at the same time. First prove the jar works on its own.

```
$ ./gradlew build
> Task :compileJava
> Task :bootJar
> Task :test
BUILD SUCCESSFUL in 25s
7 actionable tasks: 7 executed

$ ls build/libs/
app.jar                           <- the runnable "fat" jar (23.8 MB), named in build.gradle.kts
hello-api-0.0.1-SNAPSHOT-plain.jar  <- just your classes, not runnable; ignore it

$ java -jar build/libs/app.jar
... Tomcat started on port 8080 (http) with context path '/'
... Started HelloApiApplication in 1.183 seconds

$ curl localhost:8080/api/v1/hello
{"message":"Hello from CST 438","hostname":"chimera","version":"dev"}
$ curl localhost:8080/actuator/health
{"status":"UP"}
```

`hostname` is my laptop's name and `version` is the default `dev`. Remember both; they change
once we are in a container.

Two build settings matter for Docker, both in `build.gradle.kts`:

```kotlin
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }  // compile for Java 21 even on a JDK 25 machine
tasks.bootJar { archiveFileName = "app.jar" }                         // a stable name the Dockerfile can COPY
```

The toolchain line is why `./gradlew build` worked on JDK 25 but produced Java 21 bytecode
(class file major version 65). The runtime image below ships a Java 21 JRE, so the two must
match. If your machine has no JDK 21 at all, the `foojay-resolver-convention` plugin in
`settings.gradle.kts` downloads one on the first build (same plugin start.spring.io gives you).

### 2.2 The two application.properties lines that make this cloud-ready

```properties
server.port=${PORT:8080}
app.version=${APP_VERSION:dev}
```

`${PORT:8080}` means "use the `PORT` environment variable, or 8080 if it is not set."

**Why `PORT` matters.** Hosts like Render do not ask what port you listen on; they *tell* you by
injecting `PORT` (Render's default is `10000`) and then route HTTPS traffic to that port. Render's
docs: "We recommend binding your HTTP server to the port defined by the `PORT` environment
variable" and "If Render fails to detect a bound port, your web service's deploy fails." Hard-code
`server.port=8080` and your deploy may still work on Render (it usually detects the port) but
will fail on hosts that do not scan, so make it configurable from day one.

**Why 0.0.0.0 matters.** A container has its own network namespace. If the app bound to
`127.0.0.1` inside the container, `-p 8080:8080` would forward traffic to a port nobody is
listening on from the outside. Spring Boot binds to `0.0.0.0` by default, so there is nothing to
do; just do not add `server.address=127.0.0.1`.

### 2.3 Write the Dockerfile

The finished file is `app/Dockerfile`. Line by line:

```dockerfile
# syntax=docker/dockerfile:1
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon --quiet
COPY src/ src/
RUN ./gradlew bootJar --no-daemon --quiet

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring
COPY --from=build --chown=spring:spring /app/build/libs/app.jar app.jar
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
  CMD wget -qO- http://localhost:${PORT:-8080}/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
```

| Line | What | Why |
|---|---|---|
| `FROM eclipse-temurin:21-jdk AS build` | Stage 1 starts from a full JDK 21 | You need `javac` and Gradle to build. You do **not** need them to run. |
| `--platform=$BUILDPLATFORM` | Run stage 1 on the builder's native CPU | When you cross-build for amd64 on an M-series Mac, Gradle still runs natively instead of under QEMU emulation. The jar is architecture-neutral; only the runtime stage must match the host. (Measured: the amd64 build took 56 s, same as the native one.) |
| `COPY gradlew settings.gradle.kts build.gradle.kts ./` then `COPY gradle/ gradle/` | Copy only the build definition | **Layer caching.** Docker reuses a layer if its inputs have not changed. Build files change rarely, so the slow dependency download below stays cached. |
| `RUN ./gradlew dependencies` | Download Gradle and all dependencies | Produces a cached layer. Edit a `.java` file and this step is skipped (see 2.5). |
| `COPY src/ src/` | Now the source | Everything from here down is rebuilt when source changes, and only this. |
| `RUN ./gradlew bootJar` | Produce `build/libs/app.jar` | Tests already ran in CI; `bootJar` skips them so the image build is fast. |
| `FROM eclipse-temurin:21-jre-alpine AS runtime` | Stage 2 starts over from a slim JRE | **Multi-stage** = the final image is stage 2 only. No JDK, no Gradle, no source. Final image 232 MB vs 781 MB for the single-stage version (2.7). |
| `addgroup/adduser` + `USER spring:spring` | Run as a non-root user | If the app is compromised, the attacker is not root in the container. Verified: `id` inside the container prints `uid=100(spring)`. |
| `COPY --from=build --chown=spring:spring .../app.jar app.jar` | Pull one file out of stage 1 | This is the only thing that crosses from build to runtime. |
| `EXPOSE 8080` | Documentation | It does **not** open or publish anything. `docker run -p 8080:8080` does. Tools and teammates read it. |
| `HEALTHCHECK ... wget -qO- http://localhost:${PORT:-8080}/actuator/health` | Docker polls the app | Shell form so `${PORT:-8080}` expands. `wget` is present in both `21-jre-alpine` (BusyBox) and the Ubuntu `21-jre` image (verified), so nothing extra is installed. `--start-period=20s` gives the JVM time before failures count. `docker ps` then shows `(healthy)`. Render's docs describe their own Health Check Path setting (2.10); do not count on a host honoring the Dockerfile `HEALTHCHECK`. |
| `ENTRYPOINT ["java", "-jar", "app.jar"]` | Exec form (JSON array) | Java becomes PID 1 and receives `SIGTERM` directly, so `docker stop` is instant (measured 0.16 s). Shell form (`ENTRYPOINT java -jar app.jar`) wraps it in `/bin/sh`, the signal never reaches Java, and `docker stop` waits 10 s then kills it. |

The project prompt says: "Stage one builds with Gradle, stage two copies only the resulting jar
into a slim runtime image. Your final image must not ship the Gradle toolchain or your source
tree." Proof from inside the running container:

```
$ docker exec hello sh -c 'id; ls /app; which gradle || echo no gradle; ls /app/src'
uid=100(spring) gid=101(spring) groups=101(spring)
app.jar
no gradle
ls: cannot access '/app/src': No such file or directory
```

### 2.4 `.dockerignore`

`docker build .` first tars up the whole folder (the "build context") and sends it to the daemon.
`.dockerignore` keeps `build/`, `.gradle/`, `.idea/`, `.git/` and `.env` out of it. Three reasons:

1. Speed: the context stays kilobytes instead of hundreds of megabytes.
2. Cache correctness: if `build/` were in the context, every local build would change the
   `COPY src/` inputs and bust the cache for no reason.
3. Safety: a stray `.env` with a database password cannot be `COPY`'d into a layer by accident.
   The rubric says a credential baked into the image loses the Dockerfile points outright.

### 2.5 Build the image

```
$ docker build -t hello-api .
#11 [build 1/7] FROM docker.io/library/eclipse-temurin:21-jdk@sha256:3e3c...   13.9s
#15 [build 3/7] COPY gradlew settings.gradle.kts build.gradle.kts ./            0.0s
#16 [build 4/7] COPY gradle/ gradle/                                            0.0s
#17 [build 5/7] RUN chmod +x gradlew && ./gradlew dependencies --no-daemon      27.4s
#18 [build 6/7] COPY src/ src/                                                  0.0s
#19 [build 7/7] RUN ./gradlew bootJar --no-daemon --quiet                        8.3s
#20 [runtime 4/4] COPY --from=build --chown=spring:spring /app/build/libs/app.jar app.jar
#21 naming to docker.io/library/hello-api  DONE
real 54.90        <- first build, cold cache
```

Now edit one source file (I added a blank line to `HelloController.java`) and rebuild:

```
$ docker build -t hello-api .
#13 [build 5/7] RUN chmod +x gradlew && ./gradlew dependencies   CACHED
#14 [build 6/7] COPY src/ src/                                   DONE 0.0s
#15 [build 7/7] RUN ./gradlew bootJar --no-daemon --quiet         DONE 7.8s
real 9.08         <- 55 s down to 9 s. That is the layer order paying off.
```

If you had written `COPY . .` before the dependency step, every source edit would redo the
27-second download.

```
$ docker images hello-api
IMAGE              ID             DISK USAGE   CONTENT SIZE
hello-api:latest   10132a4811c9        232MB             0B
```

(Docker 29 shows `DISK USAGE` by default; `docker images --format 'table {{.Repository}}:{{.Tag}}\t{{.Size}}'` gives the classic column.)

### 2.6 Run the container

```
$ docker run -d --name hello -p 8080:8080 -e APP_VERSION=1.0.0 hello-api
cba1b78a478f863b6e2214ec44035d845598d45fa607fd964ebd5dd0f6449c37

$ curl localhost:8080/api/v1/hello
{"message":"Hello from CST 438","hostname":"cba1b78a478f","version":"1.0.0"}
$ curl localhost:8080/actuator/health
{"status":"UP"}
```

Compare with 2.1: `hostname` is now the container ID (first 12 chars of the hash above) and
`version` is the env var we passed with `-e`. That is the whole "it works on my machine" story
in one line: same jar, different environment, and the environment is supplied from outside.

| Flag | Meaning |
|---|---|
| `-d` | detached; get your terminal back |
| `--name hello` | so you can say `docker logs hello` instead of pasting an ID |
| `-p 8080:8080` | `host:container`. Publishes the port. `EXPOSE` alone does nothing. |
| `-e APP_VERSION=1.0.0` | sets an environment variable inside the container; this is how secrets arrive at runtime too |

Watch the health check go from `starting` to `healthy`:

```
$ docker ps
NAMES   IMAGE       STATUS                            PORTS
hello   hello-api   Up 2 seconds (health: starting)   0.0.0.0:8080->8080/tcp, [::]:8080->8080/tcp
$ docker ps          # ~4 s later
hello   hello-api   Up 5 seconds (healthy)            0.0.0.0:8080->8080/tcp, [::]:8080->8080/tcp

$ docker logs hello
... Starting HelloApiApplication v0.0.1-SNAPSHOT using Java 21.0.12.1 with PID 1 (/app/app.jar started by spring in /app)
... Tomcat started on port 8080 (http) with context path '/'
... Started HelloApiApplication in 1.341 seconds
```

Note `PID 1` and `started by spring`: exec-form ENTRYPOINT and the non-root user, visible in the log.

Prove `PORT` works the way a host will use it:

```
$ docker run -d --name hello9090 -p 9090:9090 -e PORT=9090 hello-api
$ curl localhost:9090/api/v1/hello
{"message":"Hello from CST 438","hostname":"89db3d98a49e","version":"dev"}
$ docker logs hello9090 | grep Tomcat
Tomcat started on port 9090
```

Nothing in the image changed. The host set `PORT`, Spring listened there. That is exactly what
Render does with `PORT=10000`. The `HEALTHCHECK` follows along because `${PORT:-8080}` expands
at check time: `docker inspect --format '{{.State.Health.Status}}' hello9090` reported
`healthy` after ~6 s with output `{"status":"UP"}`.

Clean up:

```
$ docker stop hello      # 0.16 s thanks to exec-form ENTRYPOINT
$ docker rm hello hello9090
```

### 2.7 Image size: single-stage vs multi-stage

`Dockerfile.single-stage` does the obvious thing: one `FROM eclipse-temurin:21-jdk`, `COPY . .`,
`./gradlew bootJar`, `java -jar`. It works. It is also 3.4x bigger and ships your source code
and Gradle cache to production.

```
$ docker build -f Dockerfile.single-stage -t hello-api:single .
$ docker images --format 'table {{.Repository}}:{{.Tag}}\t{{.Size}}'
hello-api:single                 781MB    <- JDK + Gradle distribution + dependency cache + source + jar
hello-api:latest                 232MB    <- multi-stage: alpine JRE + jar
eclipse-temurin:21-jre           348MB    (Ubuntu-based JRE, the other common choice)
eclipse-temurin:21-jre-alpine    208MB    (what the runtime stage starts from)
```

`docker history hello-api:latest` shows the arithmetic: 162 MB of JRE from the base image, 23.8 MB
for `app.jar`, 3 KB for the user, zero bytes for everything else.

### 2.8 `docker compose up`

`compose.yaml` describes the same thing as the `docker run` line in 2.6, as a file your whole
team shares:

```yaml
services:
  api:
    build: .
    image: hello-api:compose
    ports:
      - "8080:8080"
    environment:
      APP_VERSION: compose
```

```
$ docker compose up -d --build
 Container app-api-1 Created
 Container app-api-1 Started

$ curl localhost:8080/api/v1/hello
{"message":"Hello from CST 438","hostname":"2c4570d03fd9","version":"compose"}

$ docker compose ps
NAME        IMAGE               STATUS                    PORTS
app-api-1   hello-api:compose   Up 27 seconds (healthy)   0.0.0.0:8080->8080/tcp, [::]:8080->8080/tcp

$ docker compose logs | grep Tomcat
Tomcat started on port 8080

$ docker compose down
 Container app-api-1 Removed
 Network app_default Removed
```

The container is named `app-api-1` because the project name defaults to the folder name (`app`)
and the service is `api`. `--build` rebuilds the image if the Dockerfile or context changed;
without it, compose reuses the last `hello-api:compose`.

**File name.** `docker compose` looks for `compose.yaml` first, then `docker-compose.yml`, and
accepts both. The Project 02 checklist and rubric literally say `docker-compose.yml`; naming
yours that way is the zero-friction choice for grading.

**Project 02: adding the local Postgres.** The requirement is "One `docker compose up` should
start your API plus a local Postgres." The commented block at the bottom of `compose.yaml` shows
the shape: a `db` service from `postgres:17-alpine`, a named volume so data survives restarts, a
`pg_isready` healthcheck, and `depends_on: db: condition: service_healthy` on the API so Spring
does not start before Postgres accepts connections. The API reaches the database at hostname
`db` (the service name) on compose's private network: `jdbc:postgresql://db:5432/appdb`. This
Postgres is for development only; the deployed API still points at your remote hosted database,
and that URL arrives as an environment variable on the host, never in the image.

### 2.9 Publish the image to GitHub Container Registry

Sprint 3 says "deployed from a published image." The prompt wants CI to publish on merge to
`main` (see the GitHub Actions links in the prompt); doing it by hand once first shows you what
CI is automating. **UNVERIFIED here** (no push was made from this machine); commands are from
GitHub's container-registry docs.

**The Apple Silicon gotcha, first.** `docker build` on an M-series Mac produces a `linux/arm64`
image. Render runs `linux/amd64` and its docs say outright: "The Docker image must be built for
the `linux/amd64` platform ... ARM64 is not supported." Push an arm64 image and the deploy dies
with `exec format error`. Two fixes:

* Build for amd64 explicitly: `docker buildx build --platform linux/amd64 ...`. Because our
  build stage uses `--platform=$BUILDPLATFORM`, Gradle still runs natively; only the tiny
  runtime stage is emulated. Verified:

  ```
  $ docker buildx build --platform linux/amd64 -t hello-api:amd64 --load .
  real 56.21
  $ docker image inspect --format '{{.Os}}/{{.Architecture}}' hello-api:amd64 hello-api:latest
  linux/amd64
  linux/arm64
  $ docker run -d --platform linux/amd64 -p 8181:8080 hello-api:amd64   # runs under Rosetta on the Mac
  $ curl localhost:8181/api/v1/hello
  {"message":"Hello from CST 438","hostname":"7a053b85dd54","version":"dev"}
  $ docker exec <id> uname -m
  x86_64
  ```

* Or do not push from a Mac at all: let Render build from your Dockerfile (2.10 option A), or
  let GitHub Actions build and push. `ubuntu-latest` runners are amd64, so CI-built images are
  correct by default. This is the Sprint 3 end state anyway.

Steps:

```bash
# 1. A GitHub personal access token (classic) with the write:packages scope, in $CR_PAT
echo $CR_PAT | docker login ghcr.io -u USER --password-stdin

# 2. Build for amd64, tag with the registry path, push in one go. Names must be lowercase.
docker buildx build --platform linux/amd64 -t ghcr.io/USER/hello-api:0.1.0 --push .

#    Or in two steps from an existing image:
#    docker tag hello-api:amd64 ghcr.io/USER/hello-api:0.1.0
#    docker push ghcr.io/USER/hello-api:0.1.0
```

3. Make the package public so Render can pull it without credentials (GitHub docs click-path):
   your profile -> **Packages** tab -> click the package -> **Package settings** (right side) ->
   bottom, **Danger Zone** -> **Change visibility** -> **Public** -> type the package name ->
   **I understand the consequences, change package visibility**. Public cannot be undone.
   (Alternatively keep it private and give Render a credential in 2.10.)

Use a real version tag (`0.1.0`, or the git SHA) rather than only `latest`. Render's docs
note that mutable tags like `latest` can be served from cache; immutable tags redeploy
predictably.

### 2.10 Deploy on Render

**UNVERIFIED UI**: labels below are quoted from Render's docs (`render.com/docs/web-services`,
`/docs/docker`, `/docs/deploying-an-image`, `/docs/health-checks`, `/docs/free`) as of today.
Where the docs are vague I say "look for". Render is in the prompt's hosting links; its free
tier works with a GitHub account and no card. (Render's free-tier page lists web services
generally; that Docker/Existing-Image services are offered on the Free plan was not confirmed
from the docs text. Check the compute-plan picker when you create the service.)

**Option A: Render builds from your repo's Dockerfile** (simplest; sidesteps the arm64 problem)

1. Dashboard -> **New** -> **Web Service**.
2. Source: **Git Provider**, connect GitHub, pick the repo and **Branch** (`main`).
3. **Language**: **Docker**. If your Dockerfile is not at the repo root, look for
   **Dockerfile Path** (e.g. `api/Dockerfile`) and a root/context directory setting.
4. **Name** and **Region** as you like. Choose the **Free** compute plan.
5. Under **Advanced**: add **Environment Variables** (this is where `DATABASE_URL`, OAuth
   secrets, and `APP_VERSION` go; never in the Dockerfile). Set the health check path (also
   available later under the service's **Settings** page, **Health Checks** section) to
   `/actuator/health`.
6. **Create Web Service**. Render runs your multi-stage build with BuildKit and layer caching,
   then starts the container.

**Option B: Render pulls your published image** (what "deployed from a published image" means)

1. Dashboard -> **+ New** -> **Web Service**.
2. Under **Source Code** choose **Existing Image**.
3. **Image URL**: the full path, `ghcr.io/USER/hello-api:0.1.0`. (Render defaults the host to
   `docker.io` if you omit it, so include `ghcr.io/`.) Public image: no credential. Private:
   **Add credential** with your GitHub username and a PAT that has `read:packages`.
4. **Connect**, then fill in name/region/plan/env vars and health check as above, then **Deploy**.
5. New pushes do **not** auto-deploy. To roll a new tag: **Manual Deploy** -> **Deploy latest
   reference**, or use the service's deploy hook URL with an `imgURL` query parameter (that is
   the line your CI workflow curls after it pushes).

**Settings that matter on Render, whichever option**

| Setting | Value | Why |
|---|---|---|
| `PORT` | leave Render's default (`10000`); do not set it yourself | Render injects it; our `server.port=${PORT:8080}` picks it up. `EXPOSE 8080` in the Dockerfile is irrelevant here. Render: "Every Render web service must bind to a port on host `0.0.0.0`." Spring does. |
| Health Check Path | `/actuator/health` | Render polls it "every few seconds"; any `2xx`/`3xx` is healthy. A new deploy that never goes healthy within 15 minutes is cancelled and the old version keeps serving. This is the "Spring Actuator health endpoint your host can poll" from the prompt. |
| Environment variables | DB URL, OAuth client secret, etc. | Supplied at runtime by the host. Nothing in the image. |
| Free tier cold start | expect it | "Render spins down a Free web service that goes 15 minutes without receiving any inbound traffic." Waking "takes about one minute." Your Android client's first request after a quiet spell will be slow or time out; set a generous timeout and warn your graders in the README. 750 free instance hours per workspace per month. |

### 2.11 Verify the live deployment

```bash
curl -i https://<your-service>.onrender.com/actuator/health     # expect HTTP/2 200 {"status":"UP"}
curl    https://<your-service>.onrender.com/api/v1/hello         # hostname is Render's container, version whatever you set
```

If the first call hangs for ~60 s on the free tier, that is the cold start, not a bug. Put the
live URL in the README (rubric: "The live URL is in the README").

Commit what you built (new branch, and use `git switch`, not `checkout`):

```bash
git switch -c docker
git add Dockerfile .dockerignore compose.yaml
git update-index --chmod=+x gradlew      # keeps the executable bit in git; see troubleshooting
git commit -m "Add multi-stage Dockerfile and compose file"
```

---

## 3. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `Bind for 0.0.0.0:8080 failed: port is already allocated` (verified output from `docker run` a second container) or Spring's `Port 8080 was already in use` | Something is already listening on the host port | `docker ps` to find a container using it, or `lsof -nP -iTCP:8080 -sTCP:LISTEN` for a non-Docker process. Stop it, or map a different host port: `-p 8081:8080` (the container side stays 8080). |
| Container runs, `docker logs` shows Tomcat started, but `curl localhost:8080` gets "connection reset"/empty | Either you forgot `-p`, or the app bound to `127.0.0.1` inside the container | Check `docker ps` PORTS column shows `0.0.0.0:8080->8080/tcp`. Remove any `server.address=localhost` setting. Spring's default is `0.0.0.0`, which is what you want. |
| `exec /usr/bin/java: exec format error` on Render, or in logs after pushing from a Mac | The image is `linux/arm64`; the host is `linux/amd64` | `docker image inspect --format '{{.Architecture}}' IMAGE` to confirm. Rebuild with `docker buildx build --platform linux/amd64 ... --push`, or let Render/GitHub Actions build it. |
| `./gradlew: Permission denied` inside the Docker build (or on a teammate's clone) | The executable bit was lost when the file went through git on Windows, or a zip | Locally: `chmod +x gradlew`. In git, so it sticks for everyone: `git update-index --chmod=+x gradlew && git commit`. The reference Dockerfile also runs `chmod +x gradlew` as a belt-and-braces step. |
| You changed code but the container still serves the old behavior | Stale image or stale container | `docker build` again (check the step output actually says `DONE`, not `CACHED`, for `COPY src/`), then `docker rm -f` the old container and `docker run` again. With compose: `docker compose up -d --build`. Nuclear option: `docker build --no-cache --pull .` |
| Render: "No open ports detected" / deploy fails after the build succeeds | The app never bound a port Render could see, or bound to localhost | Confirm `server.port=${PORT:8080}` and no `server.address`. Check the Render logs for `Tomcat started on port 10000`. Render: "Bind your host to `0.0.0.0` and optionally set the `PORT` environment variable to use a custom port (the default port is `10000`)." |
| Render: health check keeps failing, deploy cancelled after 15 min | Wrong path, or actuator not on the classpath, or the app crashes on a missing env var | `curl` the path locally in a container first. Confirm `spring-boot-starter-actuator` is a dependency. Read the Render logs for the Spring stack trace; a missing `DATABASE_URL` is the usual culprit. |
| `Unable to create tempDir ... No space left on device` as the first `Caused by` in the container log (hit for real during this demo) | The Docker Desktop VM disk is full; root still works because of reserved blocks, the non-root `spring` user does not | `docker system df` to see what is reclaimable. `docker builder prune` (build cache) and `docker image prune` (dangling layers) are safe; `docker system prune -a --volumes` deletes everything not running, including data volumes, so read what it says before confirming. |
| `docker ps` shows `(unhealthy)` but curl works | The HEALTHCHECK command fails inside the container | `docker inspect --format '{{json .State.Health}}' NAME` shows the last output. Common causes: `wget`/`curl` missing in your base image (both exist in `eclipse-temurin:21-jre` and `21-jre-alpine`), wrong port, or `--start-period` too short for the JVM. |
| Build is slow every time, even for a one-line change | `COPY . .` before the dependency step, or `build/` not in `.dockerignore` | Order the Dockerfile as in 2.3; confirm `.dockerignore` lists `build/` and `.gradle/`. The second build in 2.5 should be ~10 s. |
| Gradle toolchain error: `No matching toolchains found for requested specification: {languageVersion=21}` | No JDK 21 installed and no auto-download | Make sure `settings.gradle.kts` has the `org.gradle.toolchains.foojay-resolver-convention` plugin (it does here), or install a JDK 21. Inside the Docker build stage this never happens because the image *is* JDK 21. |

---

## 4. Mapping to the Project 02 checklist

| Prompt requirement | Where it is satisfied in this demo |
|---|---|
| Multi-stage `Dockerfile` plus `.dockerignore`, image builds and runs | `app/Dockerfile`, `app/.dockerignore`, sections 2.3 to 2.6 |
| Final image must not ship Gradle or the source tree | `docker exec` proof in 2.3; 232 MB vs 781 MB in 2.7 |
| `docker-compose.yml` brings up the API and a local database | `app/compose.yaml` (rename to `docker-compose.yml` to match the rubric) + the Postgres sidebar in 2.8 |
| `HEALTHCHECK` in the Dockerfile, or an Actuator health endpoint the host can poll | Both: `HEALTHCHECK` line in 2.3 and `/actuator/health` wired to Render in 2.10 |
| No secrets baked into the image | Env vars via `-e`, compose `environment:`, and Render's Environment Variables; `.env` is in `.dockerignore` |
| Image published to GHCR; host deploys that tag | 2.9 and 2.10 option B (by hand here; CI on merge to `main` per the prompt's GitHub Actions links) |
| API live at a public HTTPS URL, URL in the README | 2.10, 2.11 |
| Database runs outside the container | Compose Postgres is dev-only; deployed API uses the remote DB through an env var |

## 5. What was and was not verified

Verified by running on 2026-10-06 (outputs above are real, trimmed):
`./gradlew build` (3 tests pass, Java 21 bytecode); `java -jar` locally; `docker build` cold
(54.9 s) and warm after a source edit (9.1 s, deps layer `CACHED`); `docker run` with curl of
both endpoints; non-root user; `(healthy)` status, including with `PORT=9090` set; `PORT=9090`
override; port-conflict error;
`docker stop` timing; `docker compose up -d --build` / `ps` / `logs` / `down`;
`docker buildx build --platform linux/amd64` (56.2 s, image reports `linux/amd64`, runs under
emulation on the Mac); single-stage image build and size comparison; `wget` present in both
Temurin 21 JRE images; multi-arch manifests for all three base images.

Not verified: every Render dashboard step (labels quoted from Render docs), whether the Free
plan offers Docker/Existing Image services, any `docker push`/package-visibility step on
GitHub (no registry push was made and no accounts were created), and the foojay automatic
JDK 21 download (this machine already had a JDK 21, which the toolchain found and used).

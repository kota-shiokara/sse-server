# ビルドコンテキストはリポジトリのルート (shared/ と server/ の両方が必要)。
#   docker build -f server/Dockerfile .
# あるいは compose.yaml 経由で。

# ---- build ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# 1. Gradle ディストリビューション
COPY gradlew ./
COPY gradle gradle
RUN ./gradlew --no-daemon --version

# 2. 依存の解決。ビルド定義だけを先に入れておくと、
#    ソースを変えただけのときにこのレイヤーが再利用される。
#    -Psse.serverOnly=true で CLI (Kotlin/Native) を構成から外し、
#    ネイティブツールチェーンを引き込まないようにする。
COPY gradle.properties settings.gradle.kts build.gradle.kts ./
COPY shared/build.gradle.kts shared/
COPY server/build.gradle.kts server/
RUN ./gradlew --no-daemon -Psse.serverOnly=true \
      :server:dependencies --configuration runtimeClasspath > /dev/null

# 3. ソース
COPY shared/src shared/src
COPY server/src server/src
RUN ./gradlew --no-daemon -Psse.serverOnly=true :server:buildFatJar

# ---- run ----
FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /src/server/build/libs/sse-server.jar app.jar

# 非 root で動かす。JVM が書き込める HOME も与える。
RUN useradd --uid 10001 --create-home --home-dir /home/sse --shell /usr/sbin/nologin sse
USER 10001
ENV HOME=/home/sse

ENV PORT=8080
# CORS で許可するオリジン。* / カンマ区切り / 空文字 (CORS なし) を取る。
ENV SSE_ALLOW_ORIGINS=*
EXPOSE 8080

# jre イメージに curl は無いので、bash の /dev/tcp で /health を叩く。
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=3 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/$PORT \
      && printf "GET /health HTTP/1.0\r\n\r\n" >&3 \
      && grep -q "200 OK" <&3'

# コンテナのメモリ上限に合わせてヒープを決める
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]

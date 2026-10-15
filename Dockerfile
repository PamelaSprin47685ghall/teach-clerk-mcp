FROM clojure:temurin-21-tools-deps AS build
WORKDIR /app
COPY deps.edn ./
RUN clojure -P
COPY src ./src
COPY resources ./resources
RUN clojure -M -e '(require (quote teach-clerk.main))'

FROM clojure:temurin-21-tools-deps
WORKDIR /app
COPY --from=build /root/.m2 /root/.m2
COPY --from=build /app /app
ENV PORT=10000
# Leave enough RAM on Render's 512 MiB free instance for the disposable
# sandboxed evaluator JVM and the JVM's own native memory.
ENV JAVA_TOOL_OPTIONS="-Xms48m -Xmx192m -XX:MaxMetaspaceSize=128m -XX:MaxDirectMemorySize=32m -XX:ReservedCodeCacheSize=48m -XX:+UseSerialGC"
EXPOSE 10000
CMD ["clojure", "-M:run"]

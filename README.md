# aeropress

aeropress is currently a proof-of-concept for analyzing the publicly exposed API dependencies for a java JAR library.

```shell
./gradlew aeropress-cli:installDist
./aeropress-cli/build/install/aeropress-cli/bin/aeropress-cli <path-to-classpath-jar>
```

It is assumed that the full classpath is `<path-to-classpath-jar>/../*`
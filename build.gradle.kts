plugins {
    `java-library`
    `maven-publish`
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
}

group = "org.twostack"
version = "1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}

repositories {
    mavenLocal()
    mavenCentral()
}

val pekkoVersion = "1.1.3"
val pekkoJdbcVersion = "1.1.0"
val pekkoProjectionVersion = "1.1.0"
val scalaVersion = "2.13"

dependencies {
    // Bitcoin4j — API dependency (transitive to consumers)
    api("org.twostack:bitcoin4j:1.7.0")

    // Pekko Actor
    implementation("org.apache.pekko:pekko-actor-typed_$scalaVersion:$pekkoVersion")

    // Pekko Persistence
    implementation("org.apache.pekko:pekko-persistence-typed_$scalaVersion:$pekkoVersion")
    implementation("org.apache.pekko:pekko-persistence-jdbc_$scalaVersion:$pekkoJdbcVersion")

    // Pekko Cluster Sharding
    implementation("org.apache.pekko:pekko-cluster-sharding-typed_$scalaVersion:$pekkoVersion")

    // Pekko Serialization (Jackson CBOR)
    implementation("org.apache.pekko:pekko-serialization-jackson_$scalaVersion:$pekkoVersion")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-cbor:2.17.3")

    // Pekko Projections
    implementation("org.apache.pekko:pekko-projection-core_$scalaVersion:$pekkoProjectionVersion")
    implementation("org.apache.pekko:pekko-projection-jdbc_$scalaVersion:$pekkoProjectionVersion")
    implementation("org.apache.pekko:pekko-projection-eventsourced_$scalaVersion:$pekkoProjectionVersion")

    // Micrometer (optional — compileOnly)
    compileOnly("io.micrometer:micrometer-core:1.13.0")

    // Test
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.apache.pekko:pekko-actor-testkit-typed_$scalaVersion:$pekkoVersion")
    testImplementation("org.apache.pekko:pekko-persistence-testkit_$scalaVersion:$pekkoVersion")
    testImplementation("org.assertj:assertj-core:3.26.0")
    testImplementation("ch.qos.logback:logback-classic:1.4.14")
    testImplementation("org.testcontainers:postgresql:1.19.7")
    testImplementation("org.testcontainers:junit-jupiter:1.19.7")
    testImplementation("org.postgresql:postgresql:42.7.3")
    testImplementation("com.fasterxml.jackson.module:jackson-module-parameter-names:2.17.3")
    testImplementation("org.awaitility:awaitility:4.2.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.test {
    useJUnitPlatform()
}

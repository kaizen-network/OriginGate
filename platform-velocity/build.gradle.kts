plugins {
    `java-library`
    id("com.gradleup.shadow")
}

val velocityApi = "com.velocitypowered:velocity-api:3.4.0-20260121.190037-118"

// Test-only permission plugin for the loopback probe. Never part of the release JAR.
val probe: SourceSet = sourceSets.create("probe")

dependencies {
    implementation(project(":presentation"))
    compileOnly(velocityApi)
    annotationProcessor(velocityApi)
    testImplementation(velocityApi)
    "probeCompileOnly"(velocityApi)
    "probeAnnotationProcessor"(velocityApi)
}

tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveBaseName.set("OriginGate-Velocity")
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    filesMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
    mergeServiceFiles()
    relocate("org.yaml.snakeyaml", "io.github.origingate.internal.snakeyaml")
    relocate("org.mariadb.jdbc", "io.github.origingate.internal.mariadb")
    relocate("com.google.gson", "io.github.origingate.internal.gson")
    // maxmind-db ships a root module descriptor; the plugin is loaded from the class path, so it is not needed.
    exclude("module-info.class")
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.assemble { dependsOn(tasks.shadowJar) }

val probeJar = tasks.register<Jar>("probeJar") {
    description = "Build the test-only permission plugin used by tools/run_velocity_probe.py."
    archiveBaseName.set("OriginGate-ProbePermissions")
    from(probe.output)
}

tasks.test {
    dependsOn(tasks.shadowJar)
    systemProperty("origingate.velocityArtifact", tasks.shadowJar.get().archiveFile.get().asFile.absolutePath)
}

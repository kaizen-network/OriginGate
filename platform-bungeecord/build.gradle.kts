plugins { `java-library`; id("com.gradleup.shadow") }
val mockitoAgent = configurations.create("mockitoAgent")
dependencies {
    implementation(project(":presentation"))
    compileOnly("net.md-5:bungeecord-api:1.21-R0.4") { isTransitive = false }
    compileOnly("net.md-5:bungeecord-event:1.21-R0.4") { isTransitive = false }
    compileOnly("net.md-5:bungeecord-chat:1.21-R0.4") { isTransitive = false }
    runtimeOnly("org.slf4j:slf4j-jdk14:2.0.17")
    testImplementation("net.md-5:bungeecord-api:1.21-R0.4") { isTransitive = false }
    testImplementation("net.md-5:bungeecord-chat:1.21-R0.4") { isTransitive = false }
    testRuntimeOnly("net.md-5:bungeecord-event:1.21-R0.4") { isTransitive = false }
    testRuntimeOnly("net.md-5:bungeecord-dialog:1.21-R0.4") { isTransitive = false }
    testRuntimeOnly("com.google.guava:guava:31.0.1-jre") { isTransitive = false }
    testImplementation("org.mockito:mockito-core:5.20.0")
    mockitoAgent("org.mockito:mockito-core:5.20.0") { isTransitive = false }
}
tasks.compileJava { options.release.set(8) }
tasks.jar { enabled = false }
tasks.shadowJar {
    archiveBaseName.set("OriginGate-BungeeCord")
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    filesMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
    mergeServiceFiles()
    relocate("org.yaml.snakeyaml", "io.github.origingate.internal.snakeyaml")
    relocate("org.mariadb.jdbc", "io.github.origingate.internal.mariadb")
    relocate("com.google.gson", "io.github.origingate.internal.gson")
    relocate("net.kyori", "io.github.origingate.internal.kyori")
    relocate("org.slf4j", "io.github.origingate.internal.slf4j")
    exclude("module-info.class", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
}
tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("bungee.yml") { expand("version" to project.version) }
}
tasks.assemble { dependsOn(tasks.shadowJar) }
tasks.test {
    doFirst { jvmArgs("-javaagent:${mockitoAgent.singleFile}") }
    dependsOn(tasks.shadowJar)
    systemProperty("origingate.artifact", tasks.shadowJar.get().archiveFile.get().asFile.absolutePath)
}

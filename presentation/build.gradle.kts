plugins { `java-library` }
dependencies {
    api(project(":core"))
    api("net.kyori:adventure-text-minimessage:4.26.1")
    implementation("net.kyori:adventure-text-serializer-legacy:4.26.1")
}
tasks.compileJava { options.release.set(8) }

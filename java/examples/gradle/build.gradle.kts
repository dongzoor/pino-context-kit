// Gradle consumer of the published io.github.dongzoor:pino-context-kit artifact.
// Compiles ../ContextExample.java against the artifact resolved from the team
// registry and runs it. Run from the repository root:
//
//   gradle -p java/examples/gradle run
//
// Override the consumed version with -PpinoContextKitVersion=<version>.
// Requires MAVEN_REGISTRY_URL and MAVEN_REGISTRY_TOKEN (supplied privately).

plugins {
    application
}

fun requiredEnv(name: String): String = providers.environmentVariable(name).orNull
    ?: throw GradleException("Environment variable $name must point at the team Maven registry")

val registryUrl = uri(requiredEnv("MAVEN_REGISTRY_URL"))
val registryToken = requiredEnv("MAVEN_REGISTRY_TOKEN")
val pinoContextKitVersion = providers.gradleProperty("pinoContextKitVersion").getOrElse("0.1.0")

repositories {
    mavenCentral()
    maven {
        name = "gitlabMaven"
        url = registryUrl
        // Only this repository may use plain HTTP; every other repository keeps Gradle's default.
        isAllowInsecureProtocol = registryUrl.scheme == "http"
        credentials(HttpHeaderCredentials::class) {
            name = "Deploy-Token"
            value = registryToken
        }
        authentication {
            create<HttpHeaderAuthentication>("header")
        }
        content {
            includeModule("io.github.dongzoor", "pino-context-kit")
        }
    }
}

dependencies {
    implementation("io.github.dongzoor:pino-context-kit:$pinoContextKitVersion")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.38")
}

// Reuse the shared example instead of copying it; only the root-level files are picked up.
sourceSets {
    main {
        java {
            setSrcDirs(listOf(".."))
            include("ContextExample.java")
        }
        resources {
            setSrcDirs(listOf(".."))
            include("logback.xml")
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 11
    options.encoding = "UTF-8"
}

application {
    mainClass = "ContextExample"
}

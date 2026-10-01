/*
 * Copyright 2020 See AUTHORS file
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.libgdx.gradle.gitHubRepositoryForPackr
import com.libgdx.gradle.isSnapshot
import com.libgdx.gradle.packrPublishRepositories
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

group = rootProject.group
version = rootProject.version

plugins {
   `maven-publish`
   application
   id("com.gradleup.shadow") version "9.2.2"
   signing
}

repositories {
   for (repositoryIndex in 0..10) {
      if (project.hasProperty("maven.repository.url.$repositoryIndex") && project.findProperty("maven.repository.isdownload.$repositoryIndex")
            .toString()
            .toBoolean()) {
         maven {
            url = uri(project.findProperty("maven.repository.url.$repositoryIndex") as String)
            if (project.hasProperty("maven.repository.username.$repositoryIndex")) {
               credentials {
                  username = project.findProperty("maven.repository.username.$repositoryIndex") as String
                  password = project.findProperty("maven.repository.password.$repositoryIndex") as String
               }
            }
         }
      }
   }

   mavenCentral()
   maven(uri("https://oss.sonatype.org/content/repositories/snapshots/"))
   gitHubRepositoryForPackr(project)

   // temporary for CI publishing until oss.sonatype.org is available for com.libgdx.packr or com.badlogicgames.packr
   maven("https://artifactory.nimblygames.com/artifactory/ng-public-snapshot/")
   maven("https://artifactory.nimblygames.com/artifactory/ng-public-release/")
}

java {
   sourceCompatibility = JavaVersion.VERSION_17
   targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
   options.release.set(17)
}
tasks.withType<Javadoc>().configureEach {
   (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

/**
 * The configuration for depending on the Packr Launcher executables
 */
val packrLauncherMavenRepositoryExecutables: NamedDomainObjectProvider<Configuration> =
      configurations.register("PackrLauncherExecutables")

/**
 * Configuration for getting the latest build executables from PackrLauncher project
 */
val packrLauncherExecutablesForCurrentOs: NamedDomainObjectProvider<Configuration> =
      configurations.register("currentOsPackrLauncherExecutables")
dependencies {
   implementation("org.apache.commons:commons-compress:1.27.1")
   implementation("com.lexicalscope.jewelcli:jewelcli:0.8.9")
   implementation("com.eclipsesource.minimal-json:minimal-json:0.9.1")

   // test
   testImplementation(platform("org.junit:junit-bom:5.11.4"))
   testImplementation("org.junit.jupiter:junit-jupiter")
   testRuntimeOnly("org.junit.platform:junit-platform-launcher")

   // logging
   val log4jVersion = "2.24.3"
   implementation("org.slf4j:slf4j-api:1.7.30")
   runtimeOnly("org.apache.logging.log4j:log4j-slf4j-impl:$log4jVersion")
   runtimeOnly("org.apache.logging.log4j:log4j-core:$log4jVersion")
}

application {
   mainClass.set("com.badlogicgames.packr.Packr")
}

java {
   @Suppress("UnstableApiUsage") withJavadocJar()
   @Suppress("UnstableApiUsage") withSourcesJar()
}

/**
 * Directory with the latest packr launcher executables
 */
val packrLauncherDirectory: Path = layout.buildDirectory.get().asFile.toPath().resolve("packrLauncher")

val createPackrLauncherConsolidatedDirectory = tasks.register<Sync>("createPackrLauncherConsolidatedDirectory") {
   from(layout.projectDirectory.dir("launchers"))
   into(packrLauncherDirectory.toFile())
   doFirst {
      listOf("packr-linux-x64", "packr-mac", "packr-windows-x64.exe").forEach {
         require(file("launchers/$it").exists()) { "Missing launcher: Packr/launchers/$it" }
      }
   }
}

tasks.named<Jar>(JavaPlugin.JAR_TASK_NAME) {
   dependsOn(createPackrLauncherConsolidatedDirectory)

   @Suppress("UnstableApiUsage") manifest {
      attributes["Main-Class"] = application.mainClass.get()
   }

   from(packrLauncherDirectory.toFile())
}

tasks.withType(Test::class).configureEach {
   useJUnitPlatform()
}

tasks.withType(ShadowJar::class).configureEach {
   dependsOn(createPackrLauncherConsolidatedDirectory)

   @Suppress("UnstableApiUsage") manifest {
      attributes["Main-Class"] = application.mainClass.get()
      // log4j-core is a multi-release jar (Java 9+ classes under META-INF/versions); keep that working after shadowing
      attributes["Multi-Release"] = "true"
   }

   // Merge META-INF/services and log4j's plugin cache so logging works from the fat jar
   mergeServiceFiles()
   transform(com.github.jengelman.gradle.plugins.shadow.transformers.Log4j2PluginsCacheFileTransformer::class.java)

   from(packrLauncherDirectory.toFile())
}

/**
 * Configuration for exporting the packr-all jar to other projects in Gradle
 */
val packrAllConfiguration: NamedDomainObjectProvider<Configuration> = configurations.register("packrAll")
artifacts {
   add(packrAllConfiguration.name, tasks.named<ShadowJar>("shadowJar").get()) {
      classifier = ""
   }
}

publishing {
   repositories {
      packrPublishRepositories(project)
      gitHubRepositoryForPackr(project)

      // Until publishing to oss.sonatype.org is possible, publish to artifactory.nimblygames.com
      val ngToken: String? =
            findProperty("NG_ARTIFACT_REPOSITORY_TOKEN") as String? ?: System.getenv("NG_ARTIFACT_REPOSITORY_TOKEN")
      if (ngToken != null) {
         val ngUsername = findProperty("NG_ARTIFACT_REPOSITORY_USER") as String? ?: System.getenv("NG_ARTIFACT_REPOSITORY_USER")
         if (isSnapshot) {
            maven("https://artifactory.nimblygames.com/artifactory/ng-public-snapshot/") {
               credentials {
                  username = ngUsername
                  password = ngToken
               }
            }
         } else {
            maven("https://artifactory.nimblygames.com/artifactory/ng-public-release/") {
               credentials {
                  username = ngUsername
                  password = ngToken
               }
            }
         }
      }
   }
   publications {
      register<MavenPublication>("${project.name}-all") {
         /*
          * Create a different artifact ID for the all package instead of using a classifier so that it doesn't get the same dependencies as the non uber jar version
          */
         artifact(tasks.named<ShadowJar>("shadowJar").get()) {
            classifier = ""
         }
         artifact(tasks.named("javadocJar").get())
         artifact(tasks.named("sourcesJar").get())

         groupId = project.group as String
         artifactId = project.name.lowercase() + "-all"
         version = project.version as String
         pom {
            name.set("Packr shadow jar")
            description.set("An executable jar for use from the command line. Packages your JAR, assets and a JVM for distribution on Windows, Linux and macOS, adding a native executable file to make it appear like a native app.")
            url.set("https://github.com/libgdx/packr")
            licenses {
               license {
                  name.set("The Apache License, Version 2.0")
                  url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
               }
            }
            developers {
               developer {
                  id.set("KarlSabo")
                  name.set("Karl Sabo")
                  email.set("karl@nimblygames.com")
               }
            }
            scm {
               connection.set("scm:git:https://github.com/libgdx/packr")
               developerConnection.set("scm:git:https://github.com/libgdx/packr")
               url.set("https://github.com/libgdx/packr")
            }
         }
      }
      register<MavenPublication>(project.name) {
         from(components["java"])
         artifactId = project.name.lowercase()
         pom {
            name.set("Packr")
            description.set("A jar with Maven dependencies for use as part of an application or build script. This can be useful for creating Packr configuration JSON files. Packages your JAR, assets and a JVM for distribution on Windows, Linux and macOS, adding a native executable file to make it appear like a native app.")
            url.set("https://github.com/libgdx/packr")
            licenses {
               license {
                  name.set("The Apache License, Version 2.0")
                  url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
               }
            }
            developers {
               developer {
                  id.set("KarlSabo")
                  name.set("Karl Sabo")
                  email.set("karl@nimblygames.com")
               }
            }
            scm {
               connection.set("scm:git:https://github.com/libgdx/packr")
               developerConnection.set("scm:git:https://github.com/libgdx/packr")
               url.set("https://github.com/libgdx/packr")
            }
         }
      }
   }
}

signing.useGpgCmd()

if (isSnapshot) {
   logger.info("Skipping signing")
} else {
   publishing.publications.configureEach {
      logger.info("Should sign publication ${this.name}")
      signing.sign(this)
   }
}

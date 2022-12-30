/*
 * This file was generated by the Gradle 'init' task.
 *
 * This generated file contains a sample Java application project to get you started.
 * For more details take a look at the 'Building Java & JVM projects' chapter in the Gradle
 * User Manual available at https://docs.gradle.org/7.3/userguide/building_java_projects.html
 */

import com.github.jengelman.gradle.plugins.shadow.tasks.ConfigureShadowRelocation
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.nio.file.Paths;

plugins {
    java
    id("com.github.johnrengelman.shadow") version "7.1.2"
    `maven-publish`
}

repositories {
    // Use Maven Central for resolving dependencies.
    mavenCentral()
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = "https://maven.pkg.github.com/jamiejackson/luceedebug"
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

dependencies {
    // Use JUnit Jupiter for testing.
    testImplementation("org.junit.jupiter:junit-jupiter:5.7.2")

    // https://mvnrepository.com/artifact/com.google.guava/guava
    implementation("com.google.guava:guava:31.1-jre")


    implementation("org.ow2.asm:asm:9.3")
    implementation("org.ow2.asm:asm-util:9.3")
    implementation("org.ow2.asm:asm-commons:9.3")
    
    // https://mvnrepository.com/artifact/javax.servlet.jsp/javax.servlet.jsp-api
    compileOnly("javax.servlet.jsp:javax.servlet.jsp-api:2.3.3")
    // https://mvnrepository.com/artifact/javax.servlet/javax.servlet-api
    compileOnly("javax.servlet:javax.servlet-api:3.1.0") // same as lucee deps


    compileOnly(files("extern/lucee-5.3.9.158-SNAPSHOT.jar"))
    compileOnly(files("extern/5.3.9.158-SNAPSHOT.lco"))

    // https://mvnrepository.com/artifact/org.eclipse.lsp4j/org.eclipse.lsp4j.debug
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j.debug:0.15.0")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
}

tasks.jar {
    manifest {
        attributes(
            mapOf(
                "Premain-Class" to "luceedebug.Agent",
                "Can-Redefine-Classes" to "true",
                "Bundle-SymbolicName" to "luceedebug-osgi",
                "Bundle-Version" to "2.0.1.1",
                "Export-Package" to "xlel.*,luceedebug.*"
            )
        )
    }
}

///val shadowjar by tasks.registering(ShadowJar::class)  {
tasks.shadowJar {
    // configurations = listOf(project.configurations.compileClasspath.get())
    
    // dependencies {
    //     //exclude("lucee.runtime.*")
    //     //exclude("5.3.9.158-SNAPSHOT.lco")
    //     //exclude(dependency("org.lucee:lucee:5.3.9.141"))
    // }
    
    // dependencies {
    //     exclude(dependency("x"))
    // }
    dependsOn("makeMockCfSourceFile") // should only be in "dev" mode
    dependsOn("relocateShadowJar")
    dependsOn("javah")
    archiveFileName.set("luceedebug.jar") // overwrites the non-shadowed jar but that's OK
}

// Shadow ALL dependencies:
tasks.create<ConfigureShadowRelocation>("relocateShadowJar") {
    target = tasks["shadowJar"] as ShadowJar
    prefix = "luceedebug_shadow"
}

tasks.register("makeMockCfSourceFile") {
    dependsOn("classes")
    doLast {
        javaexec {
            mainClass.set("luceedebug.test.MakeMockCfFile")
            classpath(sourceSets["main"].runtimeClasspath)
        }
    }
}

tasks.register<Copy>("javah") {
    dependsOn("compileJava")
    from(layout.buildDirectory.file("generated/sources/headers/java/main"))
    include("*.h")
    into(file("../../native-agent/include/luceedebug/native-generated"))
}

tasks.register("build-dev") {
    dependsOn("makeMockCfSourceFile")
    dependsOn("shadowJar")
}

// luceeplugin contains an osgi bundle
tasks.register<Jar>("build-lucee-plugin") {
    dependsOn("shadowJar")
    manifest {
        attributes(
            mapOf(
                "version" to "2.0.1.1",
                "id" to "B2BC813A-D738-4076-B9FBD8BE45A953B7",
                "name" to "luceedebug",
                "start-bundles" to "true",
            )
        )
    }
    archiveFileName.value("luceedebug.lex")
    val mainJarAbsPath = Paths.get(
        (tasks["shadowJar"] as ShadowJar).destinationDirectory.get().toString(),
        (tasks["shadowJar"] as ShadowJar).archiveFileName.get()
    ).toFile();
    from(mainJarAbsPath) {
        into("jar")
    }
}

tasks.register("advice-adapter") {
    dependsOn("classes")
    doLast {
        javaexec {
            mainClass.set("luceedebug.test.AdviceAdapter")
            // classpath = sourceSets["main"].runtimeClasspath
            classpath(sourceSets["main"].runtimeClasspath)
        }
        exec {
            commandLine("javap", "-p", "-c", "-s", "-v", "build/classes/java/main/luceedebug/test/AdviceAdapterReceiver_xform.class")
        }
    }
}

tasks.register("field-visitor") {
    dependsOn("classes")
    doLast {
        javaexec {
            mainClass.set("luceedebug.test.FieldVisitor")
            // classpath = sourceSets["main"].runtimeClasspath
            classpath(sourceSets["main"].runtimeClasspath)
        }
        exec {
            commandLine("javap", "-p", "-c", "-s", "-v", "build/classes/java/main/luceedebug/test/FieldVisitorReceiver_xform.class")
        }
    }
}

// tasks.named<Test>("test") {
//     // Use JUnit Platform for unit tests.
//     useJUnitPlatform()
// }

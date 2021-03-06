import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val ktorVersion = "1.2.2"
val prometheusVersion = "0.6.0"
val kafkaVersion = "2.3.0"
val fuelVersion = "2.0.1"
val arrowVersion = "0.9.0"

val junitJupiterVersion = "5.6.0"
val assertJVersion = "3.12.2"
val mainClass = "no.nav.helse.AppKt"
val jacksonVersion = "2.10.0"
val wireMockVersion = "2.23.2"
val mockkVersion = "1.9.3"
val nimbusVersion = "5.8.0.wso2v1"

plugins {
   kotlin("jvm") version "1.3.70"
}

buildscript {
   dependencies {
      classpath("org.junit.platform:junit-platform-gradle-plugin:1.2.0")
   }
}

dependencies {
   compile(kotlin("stdlib"))

   compile("ch.qos.logback:logback-classic:1.2.3")
   compile("net.logstash.logback:logstash-logback-encoder:5.2")
   compile("com.papertrailapp:logback-syslog4j:1.0.0")

   compile("io.ktor:ktor-server-netty:$ktorVersion")
   compile("io.ktor:ktor-jackson:$ktorVersion")
   compile("io.ktor:ktor-auth-jwt:$ktorVersion") {
      exclude(group = "junit")
   }

   compile("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
   compile("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")

   compile("org.apache.kafka:kafka-streams:$kafkaVersion")

   compile("io.prometheus:simpleclient_common:$prometheusVersion")
   compile("io.prometheus:simpleclient_hotspot:$prometheusVersion")

   compile("com.github.kittinunf.fuel:fuel-coroutines:$fuelVersion")

   compile("io.arrow-kt:arrow-core-data:$arrowVersion")
   compile("io.arrow-kt:arrow-effects-data:$arrowVersion")

   testCompile("io.mockk:mockk:$mockkVersion")
   testCompile ("no.nav:kafka-embedded-env:2.3.0")
   testCompile("org.junit.jupiter:junit-jupiter-api:$junitJupiterVersion")
   testCompile("org.junit.jupiter:junit-jupiter-params:$junitJupiterVersion")
   testCompile("org.assertj:assertj-core:$assertJVersion")
   testCompile("org.wso2.orbit.com.nimbusds:nimbus-jose-jwt:$nimbusVersion")
   testCompile("org.awaitility:awaitility:3.1.6")

   testCompile("com.github.tomakehurst:wiremock:$wireMockVersion") {
      exclude(group = "junit")
   }

   testCompile("io.ktor:ktor-server-test-host:$ktorVersion") {
      exclude(group = "junit")
      exclude(group = "org.eclipse.jetty") // conflicts with WireMock
   }

   testRuntime("org.junit.jupiter:junit-jupiter-engine:$junitJupiterVersion")
}

repositories {
   jcenter()
   mavenCentral()
   maven("http://packages.confluent.io/maven/")
}

java {
   sourceCompatibility = JavaVersion.VERSION_12
   targetCompatibility = JavaVersion.VERSION_12
}

tasks.named<Jar>("jar") {
   archiveBaseName.set("app")

   manifest {
      attributes["Main-Class"] = mainClass
      attributes["Class-Path"] = configurations["compile"].map {
         it.name
      }.joinToString(separator = " ")
   }

   doLast {
      configurations["compile"].forEach {
         val file = File("$buildDir/libs/${it.name}")
         if (!file.exists())
            it.copyTo(file)
      }
   }
}

tasks.withType<KotlinCompile> {
   kotlinOptions.jvmTarget = "12"
}

tasks.named<KotlinCompile>("compileTestKotlin") {
   kotlinOptions.jvmTarget = "12"
}

tasks.withType<Test> {
   useJUnitPlatform()
   testLogging {
      events("passed", "skipped", "failed")
   }
}

tasks.withType<Wrapper> {
   gradleVersion = "5.4.1"
}
